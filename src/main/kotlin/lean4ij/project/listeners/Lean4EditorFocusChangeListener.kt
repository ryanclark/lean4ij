package lean4ij.project.listeners

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx
import com.intellij.openapi.editor.ex.FocusChangeListener
import lean4ij.project.LeanProjectService
import java.awt.event.FocusEvent

/**
 * ref: https://intellij-support.jetbrains.com/hc/en-us/community/posts/4578776718354-How-do-I-listen-for-editor-focus-events
 */
class Lean4EditorFocusChangeListener : FocusChangeListener {
    override fun focusGained(editor: Editor) {
        val project = editor.project?:return
        project.service<LeanProjectService>().isEnable.set(true)
    }

    override fun focusGained(editor: Editor, event: FocusEvent) {
        val project = editor.project?:return
        project.service<LeanProjectService>().isEnable.set(true)
    }

    override fun focusLost(editor: Editor) {
        val project = editor.project?:return
        // avoiding set it to false for popup goto declaration requires
        // project.service<LeanProjectService>().isEnable.set(false)
    }

    override fun focusLost(editor: Editor, event: FocusEvent) {
        val project = editor.project?:return
        // avoiding set it to false for popup goto declaration requires
        // project.service<LeanProjectService>().isEnable.set(false)
    }

    fun register(editorEventMulticaster: EditorEventMulticasterEx, parentDisposable: Disposable) {
        // Parent to a project-scoped disposable so the listener is removed on project close. Registering it
        // with a no-op disposable left it under ROOT_DISPOSABLE, which the platform reports as a memory leak.
        editorEventMulticaster.addFocusChangeListener(this, parentDisposable)
    }
}
