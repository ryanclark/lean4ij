package lean4ij.lsp

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature
import com.redhat.devtools.lsp4ij.client.features.LSPWorkspaceSymbolFeature
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import lean4ij.project.LeanProjectService
import lean4ij.setting.Lean4Settings
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The language server factory as LSP4IJ describe
 */
class LeanLanguageServerFactory : LanguageServerFactory, LanguageServerEnablementSupport {

    private val lean4Settings = service<Lean4Settings>()

    /**
     * only if Editor is focus, check  assign logic of isEnable
     * TODO maybe require some refactor
     * check also [lean4ij.project.LeanProjectActivity.setupEditorFocusChangeEventListener]
     * TODO this seems making the lsp server start a little late
     * Beware that this might make debug harder for it become disabled if not focusing on the editor
     * set it to always true if no language server return while debugging
     */
    override fun isEnabled(project: Project): Boolean {
        val isEnable = project.service<LeanProjectService>().isEnable.get()
        val enabled = lean4Settings.enableLanguageServer && isEnable
        if (!enabled) {
            // Debug, not warn: isEnabled is polled frequently and is legitimately false until an editor is
            // focused (default lazy strategy) or whenever the user disables the server, so warn-level here
            // floods idea.log during normal operation. If this logs during churn, lsp4ij is stopping the
            // server because the plugin reported it disabled (enableLanguageServer / isEnable).
            thisLogger().debug("LeanLanguageServerFactory.isEnabled(project)=false (enableLanguageServer=${lean4Settings.enableLanguageServer}, isEnable=$isEnable)")
        }
        return enabled
    }

    override fun setEnabled(enabled: Boolean, project: Project) {
        // Just ignore the input
    }

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return LeanLanguageServerProvider(project)
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl {
        return LeanLsp4jClient(project)
    }

    override fun getServerInterface(): Class<out LanguageServer> {
        return InternalLeanLanguageServer::class.java
    }

    override fun createClientFeatures(): LSPClientFeatures {
        // TODO extract this to a standalone class and do some refactor
        return Lean4LSPClientFeatures()
    }
}

