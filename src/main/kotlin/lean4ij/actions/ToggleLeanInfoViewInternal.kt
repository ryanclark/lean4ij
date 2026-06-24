package lean4ij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.ToolWindowManager
import lean4ij.util.LeanUtil

class ToggleLeanInfoViewInternal : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Toggle the tool window directly instead of via InfoViewWindowFactory.getLeanInfoview:
        // the latter no longer force-creates the tool window content, so it would return null
        // (and this action would no-op) until the infoview has been opened once. show() creates
        // the content on the EDT when needed.
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LeanInfoViewWindow") ?: return
        if (toolWindow.isVisible) {
            toolWindow.hide()
        } else {
            toolWindow.show()
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.dataContext.getData<Editor>(CommonDataKeys.EDITOR)?:return
        if (!LeanUtil.isLeanFile(editor.virtualFile)) {
            e.presentation.isVisible = false
        }
    }
}