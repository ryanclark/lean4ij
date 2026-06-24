package lean4ij.infoview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lean4ij.infoview.dsl.InfoObjectModel
import lean4ij.infoview.dsl.info
import lean4ij.lsp.data.InteractiveGoals
import lean4ij.lsp.data.InteractiveTermGoal
import lean4ij.lsp.data.Position
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Renders the floating "mini infoview" popup just below the editor caret (the `⊢ <type>` line).
 *
 * EDT confinement: all mutable state and every Editor/caret read are confined to the EDT. The public entry
 * points ([updateCaret], [toggleVisibility]) and the scroll-debounce job hop to [Dispatchers.EDT] before
 * touching anything; the private helpers ([cancel], [createPopover], [showAtCursor], [setupListeners],
 * [removeListeners], [displayContent], [createOrUpdatePopupPanel]) are therefore only ever invoked on the EDT
 * and run synchronously there (the [VisibleAreaListener] callback also fires on the EDT). Because access is
 * single-threaded on the EDT, the fields need no synchronization, and no caretModel/editor read happens off
 * the EDT (the previous code read editor.caretModel.logicalPosition from background coroutines).
 */
@Service(Service.Level.PROJECT)
class MiniInfoviewService(private val project: Project, val scope: CoroutineScope) {

    companion object {
        const val ALLOW_TERM_GOALS: Boolean = false
    }

    var showing = false
    private var isScrolling = false
    private var scrollJob: Job? = null
    private var currentEditor: Editor? = null
    private var areaListener: VisibleAreaListener? = null

    var lastContent: InfoObjectModel? = null
    var currentPopover: JBPopup? = null
    var miniInfoview: MiniInfoview? = null

    // ---- private helpers: EDT-only (always reached from an EDT entry point below) ----

    private fun cancel() {
        removeListeners()
        currentPopover?.cancel()
        currentPopover = null
        miniInfoview = null
    }

    private fun createPopover(editor: Editor?, position: Position?) {
        if (editor == null || position == null) return

        val factory = JBPopupFactory.getInstance()
        miniInfoview = MiniInfoview(project)
        val jPanel = JPanel(VerticalLayout(1))
        jPanel.add(miniInfoview)

        val popup = JBScrollPane(jPanel)
        popup.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        popup.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED

        currentPopover = factory.createComponentPopupBuilder(popup, null)
            .setResizable(true)
            .setFocusable(false)
            .setMovable(true)
            .createPopup()

        setupListeners(editor)
    }

    private fun showAtCursor(editor: Editor, position: Position) {
        val visualPosition = editor.offsetToVisualPosition(editor.logicalPositionToOffset(
            LogicalPosition(position.line, position.character)
        ))

        val point = editor.visualPositionToXY(visualPosition)

        point.y += editor.lineHeight

        val relativePoint = RelativePoint(editor.contentComponent, point)

        if (currentPopover?.canShow() != false) {
            currentPopover?.show(relativePoint)
        }
        else {
            currentPopover?.setLocation(relativePoint.screenPoint)
        }
    }

    private fun setupListeners(editor: Editor) {
        removeListeners()

        areaListener = object : VisibleAreaListener {
            override fun visibleAreaChanged(e: VisibleAreaEvent) {
                // VisibleAreaListener fires on the EDT.
                if (!showing) return

                if (!isScrolling) {
                    isScrolling = true
                    cancel()
                }

                scrollJob?.cancel()
                // Re-show the popup 500ms after scrolling stops. On Dispatchers.EDT so the caret read and the
                // popup update below run on the EDT (delay is a non-blocking coroutine suspend).
                scrollJob = scope.launch(Dispatchers.EDT) {
                    delay(500)
                    isScrolling = false
                    if (showing && lastContent != null) {
                        val caretPosition = editor.caretModel.logicalPosition
                        val position = Position(caretPosition.line, caretPosition.column)
                        createOrUpdatePopupPanel(lastContent, editor, position)
                    }
                }
            }
        }

        // Register listeners
        editor.scrollingModel.addVisibleAreaListener(areaListener!!)
    }

    private fun removeListeners() {
        currentEditor?.let { editor ->
            areaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
        }
        areaListener = null
    }

    // suspend because MiniInfoview.getEditor()/measureIntrinsicContentSize() suspend; always called on the EDT
    // from a Dispatchers.EDT coroutine.
    private suspend fun displayContent(content: InfoObjectModel, editor: Editor, position: Position) {
        if (currentEditor != editor || currentPopover?.isVisible != true || miniInfoview == null) {
            createPopover(editor, position)
        }

        // Update the existing editor content
        miniInfoview?.let { view ->
            val viewEditor = view.getEditor()
            viewEditor.markupModel.removeAllHighlighters()
            content.output(viewEditor)

            currentPopover?.size = view.measureIntrinsicContentSize()
        }

        showAtCursor(editor, position)
    }

    private suspend fun createOrUpdatePopupPanel(doc: InfoObjectModel?, editor: Editor?, position: Position?) {
        lastContent = doc
        if (showing && lastContent != null && editor != null && position != null) {
            displayContent(lastContent!!, editor, position)
        } else {
            cancel()
        }
    }

    private fun getGoal(interactiveGoals: InteractiveGoals?, interactiveTermGoal: InteractiveTermGoal?): InfoObjectModel? {
        return selectMiniInfoviewGoal(interactiveGoals, interactiveTermGoal, ALLOW_TERM_GOALS)
    }

    // ---- public entry points: hop to the EDT before touching any state / editor ----

    fun updateCaret(
        editor: Editor,
        position: Position,
        interactiveGoals: InteractiveGoals?,
        interactiveTermGoal: InteractiveTermGoal?,
    ) {
        scope.launch(Dispatchers.EDT) {
            lastContent = getGoal(interactiveGoals, interactiveTermGoal)
            createOrUpdatePopupPanel(lastContent, editor, position)
            // sometimes necessary for cacheing so toggle visibility can work
            currentEditor = editor
        }
    }

    fun toggleVisibility() {
        scope.launch(Dispatchers.EDT) {
            showing = !showing
            if (showing && lastContent != null && currentEditor != null) {
                val caretPosition = currentEditor!!.caretModel.logicalPosition
                val position = Position(caretPosition.line, caretPosition.column)
                createOrUpdatePopupPanel(lastContent, currentEditor, position)
            } else {
                cancel()
            }
        }
    }
}

/**
 * Pure decision logic extracted from [MiniInfoviewService.getGoal] for characterization testing.
 *
 * Behavior is identical to the original inlined logic: when there is exactly one interactive goal,
 * its type is rendered (prefixed with the goal symbol); when [allowTermGoals] is enabled and there
 * is not exactly one goal, the term goal's type is rendered (or null when absent); otherwise null.
 */
internal fun selectMiniInfoviewGoal(
    interactiveGoals: InteractiveGoals?,
    interactiveTermGoal: InteractiveTermGoal?,
    allowTermGoals: Boolean,
): InfoObjectModel? {
    val goals = interactiveGoals?.goals
    val prefix = "⊢ "

    val type = if (goals?.size == 1) {
        interactiveGoals.goals[0].type
    } else if (allowTermGoals) {
        interactiveTermGoal?.type ?: return null
    } else {
        return null
    }

    return info {
        p(prefix, Lean4TextAttributesKeys.SwingInfoviewGoalSymbol)
        add(type.toInfoObjectModel())
    }
}
