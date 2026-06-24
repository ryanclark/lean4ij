package lean4ij.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.server.definition.extension.ServerExtensionPointBean
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings
import lean4ij.lsp.LeanPackageServerDefinition
import lean4ij.util.Constants
import lean4ij.util.LspUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

/**
 * Detects Lake packages in the project and routes each Lean file to the language server for its
 * nearest-ancestor Lake package.
 *
 * lean4ij historically ran a single `lake serve` rooted at the project base path, which only serves the
 * root Lake package. In a monorepo with several `lakefile.toml`/`lakefile.lean` files (e.g. a root
 * package plus a nested `e/` package), files in the nested package could not resolve their sibling
 * imports because the root server doesn't know that package's modules.
 *
 * This service auto-detects packages and (lazily, when a file from a non-root package is opened)
 * registers an extra lsp4ij server definition rooted at that package (see [LeanPackageServerDefinition]).
 * The root package keeps the static `lean` definition from plugin.xml, so a single-package project
 * registers nothing extra and behaves exactly as before.
 */
@Service(Service.Level.PROJECT)
class LakePackageService(private val project: Project) {

    /** file path (unquoted) -> owning Lake package root. */
    private val packageRootCache = ConcurrentHashMap<String, Path>()

    /** server ids already registered with lsp4ij (atomic guard against duplicate registration). */
    private val registeredServerIds = ConcurrentHashMap.newKeySet<String>()

    init {
        // Evict a file's cached package root when it closes, mirroring LeanSymbolColoringService, so
        // packageRootCache (populated on the hot isEnabled/belongsTo path for every file lsp4ij queries,
        // including library sources browsed via go-to-definition) doesn't grow for the whole project session.
        // Parented to a project-scoped disposable so the subscription is removed on project close.
        project.messageBus.connect(project.service<LeanProjectDisposable>())
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    packageRootCache.remove(file.path)
                }
            })
    }

    private fun basePath(): Path? = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }

    fun packageRootOf(file: VirtualFile): Path = packageRootOf(file.path)

    /**
     * The nearest-ancestor Lake package root for [uri] (a quoted file uri or a plain path), i.e. the
     * closest directory containing a `lean-toolchain`, `lakefile.lean`, or `lakefile.toml`. Falls back to
     * the project base path. Cached.
     */
    fun packageRootOf(uri: String): Path {
        val path = LspUtil.unquote(uri)
        return packageRootCache.computeIfAbsent(path) { computePackageRoot(it) }
    }

    private fun computePackageRoot(path: String): Path {
        val p = runCatching { Path.of(path) }.getOrNull() ?: return basePath() ?: Path.of(path)
        // A file under a package's `.lake/` (fetched dependencies, build output) belongs to the package
        // that owns that `.lake` dir, not to the dependency's own lakefile. Start the upward search at the
        // directory that contains `.lake`.
        val names = (0 until p.nameCount).map { p.getName(it).toString() }
        val lakeIdx = names.indexOf(".lake")
        var dir: Path? = if (lakeIdx >= 0) {
            val before = if (lakeIdx == 0) null else p.subpath(0, lakeIdx)
            when {
                before == null -> p.root
                p.root != null -> p.root.resolve(before)
                else -> before
            }
        } else {
            p.parent
        }
        while (dir != null) {
            if (hasLakeMarker(dir)) return dir
            dir = dir.parent
        }
        return basePath() ?: (p.parent ?: p)
    }

    private fun hasLakeMarker(dir: Path): Boolean =
        runCatching {
            dir.resolve("lakefile.toml").exists() ||
                dir.resolve("lakefile.lean").exists() ||
                dir.resolve("lean-toolchain").exists()
        }.getOrDefault(false)

    /** Canonical form of [path], memoized. toRealPath is a blocking filesystem call (stats every segment,
     *  resolves symlinks) and is hit on lsp4ij's hot isEnabled/belongsTo path, so it must not run uncached. */
    private val normalizeCache = ConcurrentHashMap<Path, Path>()

    private fun normalize(path: Path): Path =
        normalizeCache.computeIfAbsent(path) {
            runCatching { it.toRealPath() }.getOrElse { _ -> it.toAbsolutePath().normalize() }
        }

    /** True if [root] is the project's root Lake package (served by the static `lean` definition). */
    fun isRootPackage(root: Path): Boolean {
        val base = basePath() ?: return true
        return normalize(root) == normalize(base)
    }

    /** True if [uri]'s owning package is [packageRoot] (the decisive per-file routing check). */
    fun belongsTo(uri: String, packageRoot: Path): Boolean =
        normalize(packageRootOf(uri)) == normalize(packageRoot)

    fun belongsTo(file: VirtualFile, packageRoot: Path): Boolean = belongsTo(file.path, packageRoot)

    private fun relPath(root: Path): String {
        val base = basePath() ?: return root.toString()
        return runCatching { base.relativize(root).toString().replace('\\', '/') }.getOrDefault(root.toString())
    }

    /** The lsp4ij server id that should serve [uri]: `lean` for the root package, else `lean::<relPath>`. */
    fun serverIdFor(uri: String): String {
        val root = packageRootOf(uri)
        return if (isRootPackage(root)) Constants.LEAN_LANGUAGE_SERVER_ID else Constants.leanServerId(relPath(root))
    }

    /**
     * If [uri] belongs to a non-root Lake package that has not been registered yet, register an lsp4ij
     * server definition for it (one `lake serve` rooted at that package). No-op for the root package and
     * for already-registered packages. Called lazily when a Lean file is opened.
     */
    fun ensurePackageServerRegistered(file: VirtualFile) = ensurePackageServerRegistered(file.path)

    fun ensurePackageServerRegistered(uri: String) {
        val root = packageRootOf(uri)
        if (isRootPackage(root)) return
        val serverId = Constants.leanServerId(relPath(root))
        if (!registeredServerIds.add(serverId)) return
        try {
            val bean = ServerExtensionPointBean.EP_NAME.extensionList
                .firstOrNull { it.id == Constants.LEAN_LANGUAGE_SERVER_ID }
            if (bean == null) {
                registeredServerIds.remove(serverId)
                thisLogger().warn("Cannot register Lean server for package $root: lean server extension not found")
                return
            }
            val definition = LeanPackageServerDefinition(bean, root, serverId, relPath(root))
            val mappings = listOf(
                // Map by filename pattern, not IntelliJ language, so this matches the static `lean`
                // definition's mapping. Per-file routing is enforced by Lean4LSPClientFeatures.isEnabled;
                // these programmatic mappings are match-all.
                ServerMappingSettings.createFileNamePatternsMappingSettings(
                    listOf("*.lean4", "*.lean"), Constants.LEAN_LANGUAGE_ID
                )
            )
            // addServerDefinition synchronously notifies lsp4ij's console-explorer tree (Swing), which must run
            // on the EDT. This method runs on a background coroutine (LeanFileOpenedListener launches it off-EDT
            // to avoid slow-op assertions), so hop to the EDT; otherwise lsp4ij throws "TreeUI should be
            // accessed only from EDT". invokeLater (not invokeAndWait) avoids deadlocking against a read lock the
            // coroutine may hold; registration is idempotent and already guarded above by registeredServerIds.
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                try {
                    LanguageServersRegistry.getInstance().addServerDefinition(project, definition, mappings)
                    thisLogger().info("Registered Lean language server '$serverId' for Lake package $root")
                } catch (e: Throwable) {
                    registeredServerIds.remove(serverId)
                    thisLogger().warn("Failed to register Lean server for package $root", e)
                }
            }, ModalityState.any())
        } catch (e: Throwable) {
            registeredServerIds.remove(serverId)
            thisLogger().warn("Failed to register Lean server for package $root", e)
        }
    }
}
