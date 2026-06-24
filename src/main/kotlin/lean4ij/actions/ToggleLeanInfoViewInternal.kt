package lean4ij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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
        // Set the flag in both branches: presentations are reused, so only ever setting isVisible=false left
        // the action hidden after switching back to a Lean editor.
        // editor.virtualFile is platform-nullable (null for console/diff/in-memory editors); guard it before
        // isLeanFile, which takes a non-null VirtualFile, matching the sibling ToggleLeanInfoViewJcef.
        val virtualFile = e.dataContext.getData(CommonDataKeys.EDITOR)?.virtualFile
        e.presentation.isVisible = virtualFile != null && LeanUtil.isLeanFile(virtualFile)
    }
}