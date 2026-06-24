package lean4ij.lsp

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleManager
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import lean4ij.project.LeanProjectService
import lean4ij.project.ToolchainService
import lean4ij.setting.Lean4Settings
import lean4ij.util.OsUtil
import lean4ij.util.notify
import lean4ij.util.notifyErr
import lean4ij.util.execute
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * [packageRoot] is the Lake package directory this `lake serve` is rooted at. It defaults to the project
 * base path (the root package, started by the static `lean` server definition). Per-package server
 * definitions ([LeanPackageServerDefinition]) pass the nested package's directory so each Lake package in
 * a monorepo gets its own server rooted at its own `lakefile`/`lean-toolchain`.
 */
internal class LeanLanguageServerProvider(
    val project: Project,
    private val packageRoot: Path = Path.of(project.basePath ?: "."),
) : ProcessStreamConnectionProvider() {

    private val lean4Settings = service<Lean4Settings>()

    init {
        setServerCommand()
        addLanguageServerLifecycleListener()
    }

    private fun addLanguageServerLifecycleListener() {
        // This provider is recreated by lsp4ij on every server (re)start. Register the lifecycle
        // listener only once per project; otherwise each restart leaks another proxy that re-handles
        // every status event (see LeanProjectService.lifecycleListenerRegistered).
        if (!project.service<LeanProjectService>().lifecycleListenerRegistered.compareAndSet(false, true)) {
            return
        }
        val instance = LanguageServerLifecycleManager.getInstance(project)

        // Resolve addLanguageServerLifecycleListener reflectively (lsp4ij marks the listener interface
        // internal). Degrade gracefully if a future lsp4ij renames/overloads it rather than NPE-ing on a
        // bare !! at server start, which would break the whole server bring-up.
        val targetMethod = instance::class.java.methods.find { method ->
            method.name == "addLanguageServerLifecycleListener" && method.parameterCount == 1
        }
        if (targetMethod == null) {
            thisLogger().error(
                "lsp4ij API changed: addLanguageServerLifecycleListener(1 arg) not found; " +
                    "Lean server lifecycle features (file-progress bar, hover highlight) are disabled"
            )
            // Roll back the one-time guard so a later attempt can retry.
            project.service<LeanProjectService>().lifecycleListenerRegistered.set(false)
            return
        }
        targetMethod.invoke(instance, LeanLanguageServerLifecycleListenerProxyFactory.create(project))
    }


    /**
     * rather than the fun [setServerCommandFromElan] which using elan to determine the lean toolchain
     * here we determine it manually to avoid automatically download the toolchain when trying to start
     * the lsp
     * TODO check if should adapt the automation or not.
     * TODO almost all these should be extracted to [ToolchainService] and refactor
     */
    private fun setServerCommand() {

        // Resolve the toolchain from THIS package's lean-toolchain (a nested Lake package may pin its
        // own toolchain). For the root package this is <basePath>/lean-toolchain, i.e. identical to the
        // previous ToolchainService.expectedToolchainPath(project).
        val toolchainFile = packageRoot.resolve("lean-toolchain")
        if (!toolchainFile.exists()) {
            // more than likely this means that
            // the user is using an intellij ide
            // so it's annoying to notify the user about this
            // 'error'
            //
            // if ever the user does open a lean file
            // and we're not in a lean project,
            // then we notify the error there instead
            // (see LeanFileOpenedListener)
            return
        }
        if (!toolchainFile.isRegularFile()) {
            val content = "File $toolchainFile lean-toolchain is not a regular file. Please check if the project is setup correctly"
            project.notifyErr(content)
            return
        }
        val toolchain = toolchainFile.toFile().readText().trim()
        // TODO Here rather than using elan or lake to determine the lake executable file
        //      We do it manually for currently trying to avoid automatically setting up the environment during
        //      the lsp client start
        //      But don't know if the format will change or not though
        //      Currently we replace slash to double dash and colon to triple dash...
        val toolchainDir = toolchain.replace("/", "--").replace(":", "---")
        // toolchain path is $HOME/.elan/toolchains/<toolchainDir>
        val toolchainPath = Path.of(System.getProperty("user.home"), ".elan", "toolchains", toolchainDir)
        if (!toolchainPath.exists()) {
            val content = "Path $toolchainPath does not exist. Please try to setup the toolchain outside the IDE."
            project.notifyErr(content)
            return
        }
        if (!toolchainPath.isDirectory()) {
            val content = "Path $toolchainPath is not a directory. Please check if the toolchain setup correctly."
            project.notifyErr(content)
            return
        }
        val lakeName = if (OsUtil.isWindows()) {"lake.exe"} else {"lake"}
        val lake = Path.of(toolchainPath.toString(), "bin", lakeName)
        if (!lake.exists()) {
            val content = "File $lake does not exist in the project root. Please check if this is a lean project."
            project.notifyErr(content)
            return
        }
        if (!lake.isRegularFile()) {
            val content = "File $lake lean-toolchain seems not a regular file. Please check if the project setup correctly"
            project.notifyErr(content)
            return
        }
        // TODO DRY DRY
        val leanName = if (OsUtil.isWindows()) {"lean.exe"} else {"lean"}
        val lean = Path.of(toolchainPath.toString(), "bin", leanName)

        // succesfully created
        val toolchainService = project.service<ToolchainService>()
        toolchainService.toolChainPath = toolchainPath
        toolchainService.lakePath = lake
        toolchainService.leanPath = lean

        // TODO should check if lake exists?
        // Root `lake serve` at this package's directory so it serves this package's modules (and resolves
        // its dependencies, including path-deps on sibling packages).
        commands = listOf(lake.toString(), "serve", "--", packageRoot.toString())
        workingDirectory = packageRoot.toString()
    }

    /**
     * TODO elan which lake may cause download lake
     *     > elan which lake
     *       info: downloading component 'lean'
     *       4.0 KiB / 189.8 MiB (  0 %)   0 B/s ETA: Unknown
     */
    private fun setServerCommandFromElan() {
        val elan = getElan()
        val lake = "$elan which lake".execute(File(project.basePath!!)).trim()
        commands = listOf(lake, "serve", "--", project.basePath)
        workingDirectory = project.basePath
    }

    /**
     * TODO here in macos, after installation the elan command cannot be found
     *      it seems because the path environment is not passed, see
     *      https://youtrack.jetbrains.com/issue/IDEA-347154/The-installed-plugin-doesnt-have-access-to-environment-variables
     *      hence we implement this
     */
    private fun getElan(): String {
        val home = System.getProperty("user.home")
        val elanFile = if (OsUtil.isWindows()) {
            Path.of(home, ".elan", "bin", "elan.exe").toFile()
        } else {
            Path.of(home, ".elan", "bin", "elan").toFile()
        }
        if (!elanFile.exists()) {
            throw IllegalStateException("$elanFile does not exist!")
        }
        if (!elanFile.isFile) {
            throw IllegalStateException("$elanFile is not file!")
        }
        return elanFile.absolutePath
    }

    /**
     * TODO for some fallback of getting system environment from IntelliJ idea,
     *      we adhoc add some path here.
     *      mainly I think it should be $HOME/.elan
     *      add setting for this
     */
    private fun path(): List<Path> {
        val home = System.getProperty("user.home")
        val ret: MutableList<Path> = mutableListOf()
        ret.add(Path.of(home, ".elan", "bin"))
        if (OsUtil.isWindows()) {
            // TODO does elan and lake in windows has another path for executable?
        } else {
            // add /usr/bin and /usr/local/bin
            ret.add(Path.of("/usr", "bin"))
            ret.add(Path.of("/usr", "local", "bin"))
        }
        return ret
    }

    private val tempLogDir = Files.createTempDirectory(Path.of(PathManager.getTempPath()), "lean-lsp").toString()

    override fun getUserEnvironmentVariables(): MutableMap<String, String> {
        if (lean4Settings.enableLeanServerLog) {
            thisLogger().info("lean lsp log dir set to $tempLogDir")
            project.notify("lean lsp log dir set to $tempLogDir")
            return mutableMapOf("LEAN_SERVER_LOG_DIR" to tempLogDir)
        }
        return mutableMapOf()
    }

    override fun getInitializationOptions(rootUri: VirtualFile?): Any {
        // comparing to vscode's trace log found this
        // without hasWidgets the rpc call Lean.Widget.getInteractiveDiagnostics
        // returns only text,
        // see: lean4/src/Lean/Widget/InteractiveDiagnostic.lean 221 lines, the mapToInteractive method
        //
        return mapOf(
            "editDelay" to 200,
            "hasWidgets" to true
        )
    }
}