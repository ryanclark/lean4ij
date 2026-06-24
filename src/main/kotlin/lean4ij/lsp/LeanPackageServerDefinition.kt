package lean4ij.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.definition.extension.ExtensionLanguageServerDefinition
import com.redhat.devtools.lsp4ij.server.definition.extension.ServerExtensionPointBean
import java.nio.file.Path

/**
 * An lsp4ij language-server definition for a single non-root Lake package.
 *
 * lsp4ij runs exactly one server process per definition (keyed by [getId]), so registering one of these
 * per Lake package yields one `lake serve` per package, enabling a monorepo with multiple
 * `lakefile.toml` files to resolve each package's imports. It is built from the same `lean` `<server>`
 * extension bean as the static root definition (so it reuses [LeanLsp4jClient] and the
 * [InternalLeanLanguageServer] interface), but overrides the connection provider to root `lake serve` at
 * [packageRoot] (with that package's own `lean-toolchain`) and the client features to bind routing to
 * that package.
 *
 * Per-file routing is enforced by [Lean4LSPClientFeatures.isEnabled] (a file is served only by the
 * definition whose [packageRoot] is the file's nearest-ancestor lakefile), because programmatically
 * registered language mappings are match-all.
 */
class LeanPackageServerDefinition(
    bean: ServerExtensionPointBean,
    private val packageRoot: Path,
    private val serverId: String,
    private val relPath: String,
) : ExtensionLanguageServerDefinition(bean) {

    override fun getId(): String = serverId

    override fun getDisplayName(): String = "Lean ($relPath)"

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        LeanLanguageServerProvider(project, packageRoot)

    override fun createClientFeatures(): LSPClientFeatures =
        Lean4LSPClientFeatures(packageRoot)
}
