package lean4ij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lean4ij.infoview.LeanInfoviewService
import lean4ij.lsp.data.GetGoToLocationParams
import lean4ij.lsp.data.GetGoToLocationInfo
import lean4ij.project.LeanProjectService
import lean4ij.util.LspUtil
import org.eclipse.lsp4j.TextDocumentIdentifier

class InternalInfoviewGotoDefinition : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project?:return
        val leanProjectService = project.service<LeanProjectService>()
        val toolWindowService = project.service<LeanInfoviewService>()
        val context = toolWindowService.contextInfo?:return
        val leanFile = leanProjectService.file(context.second)
        leanProjectService.scope.launch {
            try {
                val textDocument = TextDocumentIdentifier(LspUtil.quote(context.second.url))
                val params = GetGoToLocationParams(
                    leanFile.getSession(),
                    textDocument,
                    context.third,
                    GetGoToLocationInfo("definition", context.first)
                )
                val targets = leanFile.getGotoLocation(params)
                if (targets != null) {
                    project.service<LeanProjectService>().getGoToLocation(targets)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().debug("Skip go-to-definition (language server unavailable/restarting): ${e.message}")
            }
        }
    }

    /**
     * The update here handles that the "Go to definition" action should be enabled or not
     * The logic here is a little counter-intuitive: the [LeanInfoviewService.caretIsOverText]
     * is assigned in [com.intellij.openapi.editor.ex.EditorEx.installPopupHandler]
     * The order is some kind guaranteed though
     */
    override fun update(e: AnActionEvent) {
        val project = e.project?:return
        val toolWindowService = project.service<LeanInfoviewService>()
        e.presentation.isEnabled = toolWindowService.caretIsOverText == true
    }
}