package lean4ij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import lean4ij.infoview.external.JcefInfoviewTooWindowFactory
import lean4ij.project.LeanProjectService
import lean4ij.util.LeanUtil

class ToggleLeanInfoViewJcef : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = JcefInfoviewTooWindowFactory.getToolWindow(project) ?:return
        if (toolWindow.isVisible) {
            toolWindow.hide()
        } else {
            toolWindow.show()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        // Restrict visibility to Lean files in a Lean project, mirroring the other infoview toggles.
        val project = e.project
        val virtualFile = e.dataContext.getData(CommonDataKeys.EDITOR)?.virtualFile
        e.presentation.isVisible = project != null &&
            project.service<LeanProjectService>().isLeanProject() &&
            virtualFile != null && LeanUtil.isLeanFile(virtualFile)
    }
}