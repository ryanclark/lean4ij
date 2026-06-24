package lean4ij.language

import com.google.common.base.MoreObjects
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.codeInsight.hints.declarative.*
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.UnmodifiableTextAttributes
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.rd.createLifetime
import com.intellij.openapi.rd.createNestedDisposable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.rd.util.lifetime.intersect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import lean4ij.lsp.data.InteractiveGoalsParams
import lean4ij.lsp.data.InteractiveTermGoalParams
import lean4ij.lsp.data.PlainGoalParams
import lean4ij.lsp.data.Position
import lean4ij.project.LeanFile
import lean4ij.project.LeanProjectService
import lean4ij.setting.Lean4Settings
import lean4ij.util.LeanUtil
import lean4ij.util.LspUtil
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.awt.Color
import java.util.*
import java.util.Collections.synchronizedMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

// offset is a plain document offset, not a RangeMarker. A hint only needs to survive the single pass
// between background computation and rendering, and the cache is keyed by modificationStamp, so there is no
// edit to track. Storing RangeMarkers here leaked them: the document's RangeMarkerTree retains every marker
// until it is disposed, and these never were, so editing degraded as markers accumulated over a session.
class Hint(val offset: Int, val content: String, val collapseSize: Int, val collapsedText: String)

class HintSet {
    companion object {
        const val MIN_EVER_COLLAPSED = 10
    }

    private var hints: ArrayList<Hint> = ArrayList()

    fun add(hint: Hint) {
        this.hints.add(hint)
    }

    private fun PresentationTreeBuilder.addHint(hint: Hint) {
        val defaultState = if (hint.content.length < hint.collapseSize) {
            CollapseState.Expanded
        } else {
            CollapseState.Collapsed
        }

        if (hint.content.length < MIN_EVER_COLLAPSED) {
            text(hint.content)
        }
        else {
            this.collapsibleList(defaultState, {
                toggleButton {
                    for (chunk in hint.content.chunked(30)) {
                        text(chunk)
                    }
                }
            }) {
                toggleButton {
                    text(hint.collapsedText)
                }
            }
        }
    }

    fun dumpHints(sink: InlayTreeSink) {
        this.hints.forEach { hint ->
            sink.addPresentation(InlineInlayPosition(hint.offset, false), hasBackground = true) {
                this.addHint(hint)
            }
        }
    }
}

class HintCache {
    var ongoingCache = ConcurrentHashMap<LeanFile, Pair<Long, CompletableFuture<HintSet>>>()
    // items that may not be valid
    // but they are complete and can be used at the very least
    var dirtyCache = ConcurrentHashMap<LeanFile, HintSet>()

    /* returns (need re-computation, displaySet) */
    fun query(file: LeanFile, time: Long): CompletableFuture<HintSet>? {
        val cur = ongoingCache[file] ?: return null

        // we do not have a run scheduled for this version
        if (cur.first != time) {
            cur.second.cancel(true)

            return null
        }
        else {
            return cur.second
        }
    }

    fun insert(file: LeanFile, time: Long, hints: CompletableFuture<HintSet>) {
        ongoingCache[file]?.second?.cancel(true);
        ongoingCache[file] = Pair(time, hints)
    }

    fun queryDirty(file: LeanFile): HintSet? {
        return dirtyCache.get(file)
    }

    fun insertDirty(file: LeanFile, hints: HintSet) {
        dirtyCache[file] = hints
    }

    /**
     * Drop the in-flight slot for [file] at [time] so the next pass recomputes instead of serving a cached
     * (possibly empty) result for this document version. Only removes if the slot still holds [time], to
     * avoid clobbering a newer in-flight computation.
     */
    fun evict(file: LeanFile, time: Long) {
        ongoingCache.computeIfPresent(file) { _, cur -> if (cur.first == time) null else cur }
    }
}


// Base functionality
abstract class InlayHintBase(protected val editor: Editor, protected val project: Project?) : SharedBypassCollector {
    var hintCache = HintCache()

    companion object {
        const val TIMEOUT_DEBOUNCE_MILLIS: Long = 20

        val lean4Settings = service<Lean4Settings>()
        val scheduledRecomputations = synchronizedMap(IdentityHashMap<PsiFile, Long>());
    }

    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        // if the language server is not start, return directly
        if (!lean4Settings.enableLanguageServer) {
            return
        }
        val inlayHintsSettings = DeclarativeInlayHintsSettings.getInstance()
        val isEnabled = inlayHintsSettings.isProviderEnabled(getId()) != false;
        if (!isEnabled) {
            return
        }
        // since here we check if it's a file, this is in fact collect on the file level rather then
        // psi element
        if (project == null || element !is PsiFile) {
            return
        }
        val file = MoreObjects.firstNonNull(editor.virtualFile, element.containingFile.virtualFile)
        if (!LeanUtil.isLeanFile(file)) {
            return
        }
        val leanProject = project.service<LeanProjectService>()
        val leanFile = leanProject.file(file)

        // if cached, return directly
        val time = element.containingFile.modificationStamp
        val cached = hintCache.query(leanFile, time)
        val hints: CompletableFuture<HintSet>
        if (cached != null) {
            hints = cached
        }
        else {
            // recompute
            hints = CompletableFuture<HintSet>()
            hintCache.insert(leanFile, time, hints)
            leanProject.scope.launch {
                try {
                    // document.text must be read under a read lock; reading it off this background coroutine
                    // without one races concurrent document mutations.
                    val content = ReadAction.compute<String, Throwable> { editor.document.text }
                    val actualHints = computeFor(leanFile, content)
                    hints.complete(actualHints)

                    // request rerender of hints
                    // reference: https://github.com/redhat-developer/lsp4ij/blob/main/src/main/java/com/redhat/devtools/lsp4ij/internal/InlayHintsFactoryBridge.java#L59

                    debounceRecompute(element.containingFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The Lean server restarts often (transient closed-file/RPC errors). Drop this version's
                    // cache slot so the next pass retries rather than permanently serving empty hints for the
                    // current document version, and complete the future so anything already holding it unblocks.
                    // The last-good (dirty) hints stay on screen meanwhile.
                    hintCache.evict(leanFile, time)
                    hints.complete(HintSet())
                    thisLogger().debug("Skip inlay hints for ${leanFile.virtualFile?.path} (language server unavailable/restarting): ${e.message}")
                }
            }
        }

        if (hints.isDone) {
            val hs = hints.get()
            hs.dumpHints(sink)
            hintCache.insertDirty(leanFile, hs)
        }
        else {
            // it will reschedule once its finished
            // but in the meantime, put the old (dirty) hints
            val backup = hintCache.queryDirty(leanFile)
            backup?.dumpHints(sink)
        }
    }

    private suspend fun debounceRecompute(file: PsiFile) {
        // group multiple successive hints coming in together into the same recomputation

        val readTime = System.currentTimeMillis()

        delay(TIMEOUT_DEBOUNCE_MILLIS)

        // only the latest recompute should run
        val scheduleTime = scheduledRecomputations.getOrDefault(file, -1)
        if (scheduleTime < readTime) {
            val currentTime = System.currentTimeMillis()

            scheduledRecomputations.put(file, currentTime)

            DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, file.project)
            DaemonCodeAnalyzer.getInstance(project).restart(file)
        }
    }

    abstract suspend fun computeFor(file: LeanFile, content: String): HintSet

    /**
     * we need the provider id for checking if it's disabled
     */
    abstract fun getId() : String
}

/**
 * Ref: https://github.com/JetBrains/intellij-community/blob/idea/242.21829.142/java/java-impl/src/com/intellij/codeInsight/hints/JavaImplicitTypeDeclarativeInlayHintsProvider.kt
 * This is the inlay hints for omit types like
 *     def a := 1
 * or
 *     set a := 1
 * in a proof
 */
class OmitTypeInlayHintsCollector(editor: Editor, project: Project?) : InlayHintBase(editor, project) {

    companion object {
        // TODO this currently does not support let <w, p> := Exists.intro ... type of statements
        val DEF_REGEX = Regex("""(\b(?:def|set|let|have)\s)([^⟨]*?)(\s*:=[\n\s])""")
    }

    override suspend fun computeFor(file: LeanFile, content: String): HintSet {
        val hints = HintSet()
        for (m in DEF_REGEX.findAll(content)) {
            if (hasTypeNotation(m.groupValues[2])) {
                continue
            }
            val session = file.getSession()
            // This +2 is awkward, there maybe bad case for it
            // TODO weird taht there it can be null
            val lineColumn = StringUtil.offsetToLineColumn(content, m.range.last - m.groupValues[3].length + 1) ?: continue
//            val position = Position(line = lineColumn.line, character = lineColumn.column)
            val position = Position(line = lineColumn.line, character = lineColumn.column)
            val textDocument = TextDocumentIdentifier(LspUtil.quote(file.virtualFile!!.path))
            val params = PlainGoalParams(textDocument, position)

            val interactiveTermGoalParams =
                InteractiveTermGoalParams(session, params, textDocument, position)

            // TODO what if the server not start?
            //      will it hang and leak?
            val termGoal = file.getInteractiveTermGoal(interactiveTermGoalParams) ?: continue
            // TODO can here project be null?, the InfoviewRender mainly keeps nullable for it
            val inlayHintType = ": ${termGoal.type.toInfoObjectModel()}"
            var hintPos = m.range.last - m.groupValues[3].length

            // anonymous have is slightly weird
            if (m.groupValues[1] != "have " || !m.groupValues[2].isEmpty()) {
                hintPos += 1
            }

            hints.add(Hint(hintPos, inlayHintType, 35, ": ..."))
        }

        return hints
    }

    override fun getId(): String {
        return "lean.def.omit.type"
    }

    private fun hasTypeNotation(s: String): Boolean {
        // if there exists a balanced :, return true
        var openBracket = 0
        var openFlower = 0
        var openSquare = 0
        for (c in s) {
            if (c == '(') openBracket++
            else if (c == ')') openBracket--
            else if (c == '{') openFlower++
            else if (c == '}') openFlower--
            else if (c == '[') openSquare++
            else if (c == ']') openSquare--
            else if (c == ':' && openBracket == 0 && openFlower == 0 && openSquare == 0) {
                return true
            }
        }

        return false
    }
}

/**
 * Get-or-create a collector stored in the editor's user data. The collector caches a per-file [HintCache],
 * which must survive across passes, but keying that off a static path-indexed map (the previous approach)
 * never evicted and leaked the Editor + HintCache for the application's lifetime. User data is released when
 * the editor is disposed, so this ties the cache to the editor's lifecycle. createCollector may run off the
 * EDT, so use the atomic putUserDataIfAbsent where the editor supports it.
 */
private fun <T : InlayHintBase> editorCollector(editor: Editor, key: Key<T>, create: () -> T): T {
    (editor as? UserDataHolderEx)?.let { return it.putUserDataIfAbsent(key, create()) }
    return editor.getUserData(key) ?: create().also { editor.putUserData(key, it) }
}

class OmitTypeInlayHintsProvider : InlayHintsProvider {

    companion object {
        private val COLLECTOR_KEY = Key.create<OmitTypeInlayHintsCollector>("lean4ij.omitTypeInlayCollector")
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.virtualFile == null) return null
        return editorCollector(editor, COLLECTOR_KEY) { OmitTypeInlayHintsCollector(editor, editor.project) }
    }
}


class GoalInlayHintsCollector(editor: Editor, project: Project?) : InlayHintBase(editor, project) {

    companion object {
        val lean4Settings = service<Lean4Settings>()
    }

    override suspend fun computeFor(file: LeanFile, content: String): HintSet {
        val hints = HintSet()

        for (m in lean4Settings.getCommentPrefixForGoalHintRegex().findAll(content)) {
            val session = file.getSession()

            val lineColumn = StringUtil.offsetToLineColumn(content, m.range.last)

            val position = Position(line = lineColumn.line, character = lineColumn.column)
            val textDocument = TextDocumentIdentifier(LspUtil.quote(file.virtualFile!!.path))

            val typeHint: String
            // assume tactic mode call
            val params = PlainGoalParams(textDocument, position)
            val interactiveGoalParams =
                InteractiveGoalsParams(session, params, textDocument, position)

            val termGoals = file.getInteractiveGoals(interactiveGoalParams)
            if (termGoals != null && !termGoals.goals.isEmpty()) {
                // TODO can here project be null?, the InfoviewRender mainly keeps nullable for it
                typeHint = termGoals.goals[0].type.toInfoObjectModel().toString()
            }
            else {
                // non tactic mode
                val params = PlainGoalParams(textDocument, position)
                val interactiveTermGoalParams =
                    InteractiveTermGoalParams(session, params, textDocument, position)
                val termGoal = file.getInteractiveTermGoal(interactiveTermGoalParams)
                // TODO can here project be null?, the InfoviewRender mainly keeps nullable for it
                typeHint = termGoal?.type?.toInfoObjectModel()?.toString() ?: continue
            }

            val hintPos = m.range.first + m.groupValues[1].length
            hints.add(Hint(hintPos, typeHint, 100, "..."))
        }

        return hints
    }

    override fun getId(): String {
        return "lean.goal.hint.value"
    }
}

class GoalInlayHintsProvider : InlayHintsProvider {

    companion object {
        private val COLLECTOR_KEY = Key.create<GoalInlayHintsCollector>("lean4ij.goalInlayCollector")
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.virtualFile == null) {
            return null
        }
        return editorCollector(editor, COLLECTOR_KEY) { GoalInlayHintsCollector(editor, editor.project) }
    }
}

class PlaceHolderInlayHintsCollector(editor: Editor, project: Project?) : InlayHintBase(editor, project) {

    companion object {
        // Hoisted so it is compiled once, not per computeFor on the recompute path.
        private val PLACEHOLDER_REGEX = Regex("""\b_\b""")
    }

    override suspend fun computeFor(file: LeanFile, content: String): HintSet {
        if (project == null) {
            return HintSet()
        }

        val hints = HintSet()
        val leanProject = project.service<LeanProjectService>()
        // Resolve the path, language server and document id once: they're invariant across matches, and
        // re-resolving the server per match re-runs the Lake package lookup. virtualFile can be null, so bail
        // rather than assert with !!.
        val quoted = LspUtil.quote(file.virtualFile?.path ?: return hints)
        val languageServer = leanProject.languageServerForFile(quoted).await().languageServer
        val textDocument = TextDocumentIdentifier(quoted)
        for (m in PLACEHOLDER_REGEX.findAll(content)) {
            val lineColumn = StringUtil.offsetToLineColumn(content, m.range.first)
            // A second Position type (lean4ij.lsp.data.Position) is imported, so qualify the lsp4j one here.
            val position = org.eclipse.lsp4j.Position(lineColumn.line, lineColumn.column)
            val hover = languageServer.hover(HoverParams(textDocument, position)).await()
            // TODO there are cases here that here hover is null
            if (hover != null && hover.contents.isRight) {
                val value = hover.contents.right.value
                if (value.contains("placeholder")) {
                    // TODO assume that only one line..., this maybe wrong
                    val split = value.split("\n")
                    if (split.size < 2) {
                        // it seems to be something like
                        // A placeholder term, to be synthesized by unification.
                        continue
                    }
                    val inlayHint = split[1]
                    val hintPos = m.range.last + 1
                    hints.add(Hint(hintPos, inlayHint, 20, "..."))
                }
            }
        }

        return hints
    }

    override fun getId(): String {
        return "lean.placeholder"
    }
}

class PlaceHolderInlayHintsProvider : InlayHintsProvider {
    companion object {
        private val COLLECTOR_KEY = Key.create<PlaceHolderInlayHintsCollector>("lean4ij.placeholderInlayCollector")
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.virtualFile == null) return null
        return editorCollector(editor, COLLECTOR_KEY) { PlaceHolderInlayHintsCollector(editor, editor.project) }
    }
}


object InlayTextAttributes: UnmodifiableTextAttributes() {
    override fun getBackgroundColor(): Color? {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return scheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT).backgroundColor
    }

    override fun getForegroundColor(): Color? {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return scheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT).foregroundColor
    }
}

class InlayRenderer(info: HighlightInfo): HintRenderer(clipDescription(info.description)) {
    companion object {
        const val MAX_LEN = 140;
        fun clipDescription(desc: String): String {
            return if (desc.length < MAX_LEN) {
                desc
            } else {
                desc.substring(0, MAX_LEN - 3) + "..."
            }
        }
    }
    override fun getTextAttributes(editor: Editor): TextAttributes? {
        return InlayTextAttributes
    }

    override fun useEditorFont(): Boolean {
        return true
    }
}

// https://github.com/chylex/IntelliJ-Inspection-Lens/blob/main/src/main/kotlin/com/chylex/intellij/inspectionlens/editor/LensMarkupModelListener.kt
class DiagInlayManager(var editor: TextEditor) : MarkupModelListener {
    var currentHints: IdentityHashMap<RangeHighlighterEx, Inlay<InlayRenderer>> = IdentityHashMap()

    init {
        val model = model()

        val pluginLifetime = ApplicationManager.getApplication().service<lean4ij.services.MyProjectDisposableService>().createLifetime()
        val editorLifetime = editor.createLifetime()

        model?.addMarkupModelListener(pluginLifetime.intersect (editorLifetime).createNestedDisposable("lean4ijDiagEditorLifetime"), this)

        refresh()
    }

    fun model() : MarkupModelEx? {
        return DocumentMarkupModel.forDocument(editor.editor.document, editor.editor.project, false) as? MarkupModelEx
    }

    override fun afterAdded(highlighter: RangeHighlighterEx) {
        this.showHint(highlighter)
    }

    override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) {
        this.updateHint(highlighter)
    }

    override fun beforeRemoved(highlighter: RangeHighlighterEx) {
        this.hideHint(highlighter)
    }

    fun hideHint(range: RangeHighlighterEx) {
        val inlay = this.currentHints[range] ?: return

        this.currentHints.remove(range)
        ApplicationManager.getApplication().invokeLater {
            inlay.dispose()
        }
    }

    fun updateHint(range: RangeHighlighterEx) {
        val info = HighlightInfo.fromRangeHighlighter(range) ?: return

        val inlay = this.currentHints[range] ?: return

        ApplicationManager.getApplication().invokeLater {
            if (info.description == null) {
                inlay.dispose()
            }
            else {
                inlay.renderer.text = info.description
            }
        }
    }

    fun showHint(range: RangeHighlighterEx) {
        val info = HighlightInfo.fromRangeHighlighter(range) ?: return

        // seems that it's always marked as weak warning
        if (info.description == null || info.severity != HighlightSeverity.WEAK_WARNING) {
            return
        }

        val renderer = InlayRenderer(info)
        val properties = InlayProperties()
            .relatesToPrecedingText(true)
            .disableSoftWrapping(true)

        ApplicationManager.getApplication().invokeLater {
            val hint = editor.editor.inlayModel.addAfterLineEndElement(info.actualEndOffset - 1, properties, renderer) ?: return@invokeLater

            this.currentHints[range] = hint
        }
    }

    fun refresh() {

        // hide old ones
        for (hint in currentHints) {
            hint.value.dispose()
        }
        currentHints.clear()

        // create new ones
        val highlighters = model()?.allHighlighters ?: return
        for (highlighter in highlighters) {
            val highlighter = highlighter as? RangeHighlighterEx ?: continue
            this.showHint(highlighter)
        }
    }

    companion object {

        fun register(editor: TextEditor) {
            val settings = service<Lean4Settings>()
            if (settings.enableDiagnosticsLens) {
                DiagInlayManager(editor)
            }
        }
    }
}

