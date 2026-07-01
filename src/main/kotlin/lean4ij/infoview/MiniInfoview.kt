package lean4ij.infoview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.intellij.util.ui.JBUI
import lean4ij.project.LeanProjectService
import java.awt.Dimension

class MiniInfoview(val project: Project) : SimpleToolWindowPanel(true) {
    private val editor : CompletableDeferred<EditorEx> = CompletableDeferred()

    // The created viewer editor, tracked so [release] can hand it back to EditorFactory. createViewer must be
    // matched by EditorFactory.releaseEditor or the EditorView leaks under ROOT_DISPOSABLE (same contract the
    // sibling LeanInfoViewWindow documents). @Volatile so [release] sees the write the init coroutine makes;
    // both run on the EDT, so there is no actual race, but keep the visibility guarantee explicit.
    @Volatile
    private var createdEditor: EditorEx? = null
    @Volatile
    private var released = false

    suspend fun getEditor(): EditorEx {
        return editor.await()
    }

    /**
     * This si for displaying popup expr
     */
    val leanProject = project.service<LeanProjectService>()
    init {
        leanProject.scope.launch(Dispatchers.EDT) {
            try {
                val editor0 = InfoViewEditorFactory(project).createEditor(showScroll = false)
                editor0.setCaretEnabled(false)
                editor0.setCaretVisible(false)
                editor0.colorsScheme.setColor(EditorColors.CARET_ROW_COLOR, null)
                if (released) {
                    // Released before this editor finished creating; hand it back rather than leak it.
                    EditorFactory.getInstance().releaseEditor(editor0)
                    return@launch
                }
                createdEditor = editor0
                editor.complete(editor0)
                setContent(editor0.component)
            } catch (ex: Throwable) {
                editor.completeExceptionally(ex)
            }
        }
    }

    /**
     * Release the underlying viewer editor. [MiniInfoviewService] builds a fresh [MiniInfoview] on every
     * popover (re)show and drops the previous one, so without this the createViewer-backed EditorView leaks
     * under ROOT_DISPOSABLE on every editor switch and every scroll. Must be called on the EDT (releaseEditor's
     * contract); the service's cancel()/createPopover() callers are EDT-confined.
     */
    fun release() {
        released = true
        createdEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        createdEditor = null
    }

    suspend fun measureIntrinsicContentSize(): Dimension {
        val editor = getEditor()

        return withContext(Dispatchers.EDT) {
            val document = editor.document
            val text = document.text
            val lines = text.split("\n")

            val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
            val maxWidth = lines.maxOfOrNull { fontMetrics.stringWidth(it) } ?: 0
            val lineHeight = editor.lineHeight
            val totalHeight = lineHeight * lines.size

            // The width estimate undershoots bold/unicode goal text, so the scroll pane shows its AS_NEEDED
            // horizontal bar. The height must cover that bar plus editor insets or it clips the goal line.
            Dimension(maxWidth + 40, totalHeight + JBUI.scale(18))
        }
    }
}