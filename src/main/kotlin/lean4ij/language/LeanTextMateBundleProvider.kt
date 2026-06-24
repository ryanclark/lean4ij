package lean4ij.language

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Ships the Lean 4 TextMate grammar (vendored under `bundles/`, adapted from leanprover/vscode-lean4,
 * Apache-2.0) so lsp4ij can TextMate-highlight ```lean code fences inside hover popups.
 *
 * This does NOT own `.lean` editing: the native `lean4` language (JFlex lexer + Grammar-Kit parser + PSI)
 * still owns the `.lean` file type. The bundle exists only for the hover-popup code-fence highlighting.
 *
 * IntelliJ's TextMate plugin needs a real on-disk bundle directory laid out like a VS Code extension
 * (`package.json` + grammar json + `language-configuration.json`), but our resources live inside the plugin
 * jar, so we copy them into a temp dir and hand back its path. Pattern follows mallowigi/permify-jetbrains
 * and lsp4ij's own DisassemblyTextMateBundleProvider.
 */
class LeanTextMateBundleProvider : TextMateBundleProvider {
    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> = extractedBundles()

    companion object {
        private val BUNDLE_FILES = listOf(
            "package.json",
            "lean4.json",
            "lean4-markdown.json",
            "language-configuration.json",
        )

        @Volatile
        private var cached: List<TextMateBundleProvider.PluginBundle>? = null

        /**
         * lsp4ij's TextMateServiceImpl re-reads bundle providers on several events (file-types changed,
         * settings reload, plugin reload), so extract the bundle once and reuse it; otherwise every call
         * would leak a fresh temp dir. Only a successful extraction is cached, so a transient failure can
         * still be retried on a later call.
         */
        @Synchronized
        private fun extractedBundles(): List<TextMateBundleProvider.PluginBundle> {
            cached?.let { return it }
            return try {
                val dir = Files.createTempDirectory(Path.of(PathManager.getTempPath()), "lean4ij-textmate")
                // Clean up on JVM exit so a fresh `lean4ij-textmate*` dir does not accumulate every IDE run
                // (PathManager temp is not cleared on shutdown). deleteOnExit removes in reverse registration
                // order, so register the dir first and its files after, so the (now empty) dir is removed last.
                dir.toFile().deleteOnExit()
                for (name in BUNDLE_FILES) {
                    val resource = LeanTextMateBundleProvider::class.java.classLoader.getResourceAsStream("bundles/$name")
                        ?: error("Missing bundled TextMate resource: bundles/$name")
                    val target = dir.resolve(name)
                    resource.use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    target.toFile().deleteOnExit()
                }
                listOf(TextMateBundleProvider.PluginBundle("Lean 4", dir)).also { cached = it }
            } catch (e: Exception) {
                thisLogger().warn("Failed to provide the Lean 4 TextMate bundle; .lean files will fall back to plain text", e)
                emptyList()
            }
        }
    }
}
