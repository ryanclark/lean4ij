package lean4ij.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import lean4ij.setting.Lean4Settings
import lean4ij.util.LeanUtil
import org.eclipse.lsp4j.Hover
import java.awt.Color

/**
 * Paints the cmd/ctrl-hover "current term" highlight in the selected editor from the Lean server's [Hover]
 * response. Extracted from [LeanProjectService]: it owns editor markup + an EDT mouse listener, which is
 * unrelated to project-level server registry/lifecycle state.
 */
@Service(Service.Level.PROJECT)
class EditorHoverHighlightService(private val project: Project) {

    companion object {
        private const val MAX_HOVER_HIGHLIGHT_LINE_SPAN = 2
    }

    private var hoverListener : EditorMouseMotionListener? = null
    private var hoverRangeHighlighter : RangeHighlighter? = null
    // The editor [hoverListener]/[hoverRangeHighlighter] were attached to. They must be removed from
    // this editor rather than the currently selected one: when the selected editor changes between hover
    // responses, removing from the current editor trips platform assertions (see below).
    private var hoverEditor : Editor? = null

    /**
     * Try to add highlight for current hover content in current selected editor.
     * Since the returned type [Hover] does not contain the concrete hovering file, this is implemented at the
     * project level rather than in [LeanFile].
     * This is invoked from the LSP reader thread on every hover response. The previously attached
     * listener/highlighter are removed from [hoverEditor] (the editor they were actually attached to),
     * not the currently selected editor: when the selected editor changes between hover responses,
     * [com.intellij.openapi.editor.impl.EditorImpl.removeEditorMouseMotionListener] would fail its
     * `LOG.assertTrue(success || isReleased)` and [com.intellij.openapi.editor.markup.MarkupModel.removeHighlighter]
     * its belongs-to-the-tree check. Both report an IDE error at creation time, so catching the throwable cannot
     * suppress them.
     */
    fun highlightCurrentContent(hover: Hover?) {
        if (!service<Lean4Settings>().enableHoverHighlight) {
            return
        }

        // Invoked from the LSP reader thread, but the body reads selectedTextEditor and mutates editor mouse
        // listeners + the markup model, all EDT-only. Hop onto the EDT (guarding a disposed project) so the
        // platform's wrong-thread / belongs-to-the-tree assertions can't fire under selection/editor races.
        ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        // Detach the previous hover listener/highlighter unconditionally, BEFORE looking up the selected
        // editor. Capture and null the fields first so a re-entrant call cannot remove the same artifacts
        // twice, and remove them from the editor they were actually attached to. Done outside the
        // selectedTextEditor?.let below because when a hover response arrives while no editor is selected
        // (selectedTextEditor == null) the let is skipped, which would leave the previous listener attached
        // until the next hover that lands while an editor is selected.
        val previousEditor = hoverEditor
        val previousListener = hoverListener
        val previousHighlighter = hoverRangeHighlighter
        hoverEditor = null
        hoverListener = null
        hoverRangeHighlighter = null
        if (previousEditor != null && !previousEditor.isDisposed) {
            if (previousListener != null) {
                thisLogger().debug("remove hover listener $previousListener for $previousEditor")
                previousEditor.removeEditorMouseMotionListener(previousListener)
            }
            if (previousHighlighter != null) {
                previousEditor.markupModel.removeHighlighter(previousHighlighter)
            }
        }

        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            val document = editor.document
            val markupModel = editor.markupModel

            if (hover == null) {
                return@let
            }

            // TODO DRY DRY, duplicated with
            //      lean4ij.infoview.InfoviewMouseMotionListener.mouseMoved
            // TODO this should add some setting page for it too
            val attr = object : TextAttributes() {
                override fun getBackgroundColor(): Color {
                    val scheme = EditorColorsManager.getInstance().globalScheme
                    var color = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
                    if (color != null) {
                        return color
                    }
                    color = scheme.getColor(EditorColors.CARET_COLOR)
                    if (color != null) {
                        return color
                    }
                    return scheme.defaultBackground
                }
            }
            // The hover range can be stale for the *currently selected* editor's document (selection/edit
            // races): lineColToOffset then returns -1 and addRangeHighlighter(-1, -1, ...) throws
            // IllegalArgumentException ("Incorrect offsets"), crashing the EDT. Skip out-of-bounds ranges.
            val startOffset = StringUtil.lineColToOffset(document.charsSequence, hover.range.start.line, hover.range.start.character)
            val endOffset = StringUtil.lineColToOffset(document.charsSequence, hover.range.end.line, hover.range.end.character)
            if (!LeanUtil.isValidRange(startOffset, endOffset, document.textLength)) {
                return@let
            }
            // Cap to a "current term"-sized span. The Lean server sometimes reports a hover range covering a
            // whole declaration or, while a file is still elaborating, effectively the whole file; painting that
            // with the selection background turns the entire document blue on cmd+hover. A current-term highlight
            // is only useful when small, so skip oversized ranges.
            if (hover.range.end.line - hover.range.start.line > MAX_HOVER_HIGHLIGHT_LINE_SPAN) {
                return@let
            }
            try {
                val highlighter = markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.LAST,
                    attr,
                    HighlighterTargetArea.EXACT_RANGE
                )
                hoverRangeHighlighter = highlighter
                hoverListener = object : EditorMouseMotionListener {
                    override fun mouseMoved(e: EditorMouseEvent) {
                        // Capture the highlighter this listener was created for instead of reading the mutable
                        // hoverRangeHighlighter field: highlightCurrentContent() nulls that field at the top of
                        // every call, so on IDE restart (when selectedTextEditor is briefly null and the stale
                        // listener isn't removed) `hoverRangeHighlighter!!` threw an NPE on mouse-move (EDT
                        // crash). isValid guards against double-removing an already-removed highlighter.
                        if (!e.isOverText && highlighter.isValid) {
                            editor.markupModel.removeHighlighter(highlighter)
                        }
                    }
                }
                thisLogger().debug("add hover listener $hoverListener for $editor")
                editor.addEditorMouseMotionListener(hoverListener!!)
                hoverEditor = editor
            } catch (e: AssertionError) {
                // May happen when the editor content changes concurrently while a hover event is triggered;
                // removeHighlighter then fails its belongs-to-the-tree assertion. Catching here is a stopgap.
            }
        }
        }
    }
}
