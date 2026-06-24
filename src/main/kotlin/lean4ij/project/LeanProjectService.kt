package lean4ij.project

import lean4ij.lsp.InternalLeanLanguageServer
import lean4ij.lsp.LeanLanguageServer
import lean4ij.lsp.data.PlainGoalParams
import lean4ij.lsp.data.RpcCallParamsRaw
import lean4ij.util.Constants
import lean4ij.util.LspUtil
import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.LineColumn
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import lean4ij.lsp.data.DefinitionTarget
import lean4ij.setting.Lean4Settings
import lean4ij.util.LeanUtil
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageServer
import java.awt.Color
import java.lang.AssertionError
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

@Service(Service.Level.PROJECT)
class LeanProjectService(val project: Project, val scope: CoroutineScope)  {

    companion object {
        // Don't paint the hover "current term" background if its range spans more than this many lines: the
        // Lean server can report a whole-declaration / whole-file hover range, which would otherwise turn the
        // entire document blue on cmd+hover.
        private const val MAX_HOVER_HIGHLIGHT_LINE_SPAN = 2
    }

    // TODO the state maybe should move out to a delegated
    // This is default to false, for it maybe not creating by Intellij IDEA
    // in this case we think it's already created by the user using command line
    // tool, etc.
    var isProjectCreating: Boolean = false

    // One server per Lake package: keyed by lsp4ij server id ("lean" for the root package,
    // "lean::<relPath>" for nested packages). Files route to their package's server via [languageServerForFile].
    // A single-package project only ever has the "lean" entry, so this behaves exactly as the old single
    // deferred did.
    private val languageServers = ConcurrentHashMap<String, CompletableDeferred<LeanLanguageServer>>()

    fun languageServerFor(serverId: String): CompletableDeferred<LeanLanguageServer> =
        languageServers.computeIfAbsent(serverId) { CompletableDeferred() }

    // Completed when the FIRST Lean server (any package) initializes. The external/JCEF infoview uses this
    // as its "server ready" gate, so it unblocks even when only a nested package's server is running
    // (per-file RPC for goals/diagnostics still routes to the correct package server).
    private var anyInitializeResult = CompletableDeferred<InitializeResult>()

    /** The root package server's deferred. Backward-compatible accessor for callers that are not per-file. */
    val languageServer : CompletableDeferred<LeanLanguageServer> get() = languageServerFor(Constants.LEAN_LANGUAGE_SERVER_ID)

    /** The lsp4ij server id serving the file at [uri] (its nearest-ancestor Lake package). */
    fun serverIdFor(uri: String): String = project.service<LakePackageService>().serverIdFor(uri)

    /** The language-server deferred that serves the file at [uri]. */
    fun languageServerForFile(uri: String): CompletableDeferred<LeanLanguageServer> = languageServerFor(serverIdFor(uri))

    private val lean4Settings = service<Lean4Settings>()

    /**
     * Setting this to false rather than true, although it makes the language server does not start as the project
     * or ide opens, but it seems improving performance for avoiding peak cpu flush as the opening
     * TODO add this on readme
     * TODO maybe some settings for it
     * TODO it's back to true, inconsistent with readme
     * TODO this is not per project...
     */
    val isEnable : AtomicBoolean = AtomicBoolean(lean4Settings.languageServerStartingStrategy == "Eager")

    /**
     * Guards one-time registration of the LSP lifecycle listener. [lean4ij.lsp.LeanLanguageServerProvider]
     * is recreated by lsp4ij on every server (re)start, and its init registered a new listener proxy each
     * time without ever removing the previous one. After N restarts every status event was handled N times,
     * multiplying side effects (the "languageServer already setup" flood, and N× [resetServer]). Register once.
     */
    val lifecycleListenerRegistered = AtomicBoolean(false)

    private val _caretEvent = MutableSharedFlow<PlainGoalParams>()
    val caretEvent: Flow<PlainGoalParams> get() = _caretEvent.asSharedFlow()
    private val _serverEvent = MutableSharedFlow<NotificationMessage>()
    val serverEvent : Flow<NotificationMessage> get() = _serverEvent.asSharedFlow()

    fun emitServerEvent(message: NotificationMessage) {
        scope.launch {
            _serverEvent.emit(message)
        }
    }

    private val leanFiles = ConcurrentHashMap<String, LeanFile>()

    fun file(file: VirtualFile): LeanFile {
        val ret = file(LspUtil.quote(file.path))
        ret.virtualFile = file
        return ret
    }

    fun file(file: String) : LeanFile {
        return leanFiles.computeIfAbsent(file) { LeanFile(this, file) }
    }

    fun setInitializedServer(serverId: String, languageServer: LanguageServer) {
        val result = languageServerFor(serverId).complete(LeanLanguageServer(languageServer as InternalLeanLanguageServer))
        if (!result) {
            // TODO there is still multiple event
            // throw IllegalStateException("languageServer already setup")
            thisLogger().warn("languageServer already setup: $serverId")
        }
    }

    fun setInitializedResult(serverId: String, initializeResult: InitializeResult) {
        // First server up (any package) signals the external infoview that a server is ready. There is no
        // per-server init gate (nothing awaits an individual server's InitializeResult), so only the shared
        // anyInitializeResult is completed.
        anyInitializeResult.complete(initializeResult)
    }

    /** Resolves once any Lean server (any package) has initialized. Used by the JCEF/external infoview. */
    suspend fun awaitInitializedResult() : InitializeResult = anyInitializeResult.await()

    fun getRelativePath(file: String): String {
        val unquotedFile = LspUtil.unquote(file)
        var prefix = project.basePath ?: return unquotedFile
        if (!prefix.endsWith("/")) {
            prefix += "/"
        }
        if (unquotedFile.startsWith(prefix)) {
            return unquotedFile.substring(prefix.length)
        }
        return unquotedFile
    }

    /**
     * TODO move to [LeanFile] for session lifecycle handling
     */
    suspend fun getSession(uri: String) : String = file(uri).getSession()

    fun updateCaret(params: PlainGoalParams) {
        scope.launch {
            _caretEvent.emit(params)
        }
    }

    suspend fun rpcCallRaw(params: RpcCallParamsRaw): JsonElement? {
        return file(params.textDocument.uri).rpcCallRaw(params)
    }

    fun restartLsp() {
        // TODO should this be lock?
        // Restart every Lake-package server that has been started (plus the root), recreating its deferreds.
        val mgr = LanguageServerManager.getInstance(project)
        // Re-arm the external infoview's "server ready" gate so a reconnect after restart awaits afresh.
        anyInitializeResult = CompletableDeferred()
        val ids = languageServers.keys + Constants.LEAN_LANGUAGE_SERVER_ID
        for (id in ids) {
            languageServers[id] = CompletableDeferred()
            mgr.stop(id)
            mgr.start(id)
        }
    }

    fun resetServer(serverId: String) {
        // TODO should this be lock?
        languageServers[serverId] = CompletableDeferred()
        // The server has stopped, so any file currently elaborating will never receive its terminating
        // fileProgress notification. Proactively clear the progress bars so a dead server cannot leave a
        // phantom "forever" bar (see LeanFile.clearFileProgress), but only for files served by this
        // package's server, so a sibling package's still-running server keeps its own in-progress bars
        // in a multi-package monorepo.
        for ((uri, leanFile) in leanFiles) {
            if (serverIdFor(uri) == serverId) leanFile.clearFileProgress()
        }
    }

    /**
     * TODO [lean4ij.lsp.LeanLanguageServerProvider.setServerCommand] contains some duplicated logic for this
     */
    fun isLeanProject(): Boolean {
        return ToolchainService.expectedToolchainPath(project)
            .toFile().isFile
    }

    fun updateInfoviewFor(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document)?:return
        if (!LeanUtil.isLeanFile(file)) return
        val editor = EditorFactory.getInstance().getEditors(document).firstOrNull()?:return
        // charsSequence (no full-document copy) on this per-caret/keystroke path.
        val lineCol : LineColumn = StringUtil.offsetToLineColumn(document.charsSequence, editor.caretModel.offset) ?: return
        val position = LogicalPosition(lineCol.line, lineCol.column)
        // TODO this may be duplicated with caret events some times
        //      but without this there are cases no caret events but document changed events
        //      maybe some debounce
        file(file).updateCaret(editor, position)
    }

    fun updateInfoviewFor(editor: Editor, forceUpdate: Boolean = false) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)?:return
        if (!LeanUtil.isLeanFile(file)) return
        val lineCol : LineColumn = StringUtil.offsetToLineColumn(document.charsSequence, editor.caretModel.offset) ?: return
        val position = LogicalPosition(lineCol.line, lineCol.column)
        // TODO this may be duplicated with caret events some times
        //      but without this there are cases no caret events but document changed events
        //      maybe some debounce
        file(file).updateCaret(editor, position, forceUpdate)
    }

    /**
     * not sure why it's a list
     * case for two results:
     * ```
     * [
     *  {
     *    "targetUri": "file:///home/onriv/.elan/toolchains/leanprover--lean4---v4.11.0-rc2/src/lean/Init/Core.lean",
     *    "targetSelectionRange": {
     *      "start": {
     *        "line": 374,
     *        "character": 2
     *      },
     *      "end": {
     *        "line": 374,
     *        "character": 7
     *      }
     *    },
     *    "targetRange": {
     *      "start": {
     *        "line": 374,
     *        "character": 2
     *      },
     *      "end": {
     *        "line": 374,
     *        "character": 7
     *      }
     *    }
     *  },
     *  {
     *    "targetUri": "file:///home/onriv/.elan/toolchains/leanprover--lean4---v4.11.0-rc2/src/lean/Init/Core.lean",
     *    "targetSelectionRange": {
     *      "start": {
     *        "line": 1248,
     *        "character": 0
     *      },
     *      "end": {
     *        "line": 1248,
     *        "character": 8
     *      }
     *    },
     *    "targetRange": {
     *      "start": {
     *        "line": 1248,
     *        "character": 0
     *      },
     *      "end": {
     *        "line": 1249,
     *        "character": 12
     *      }
     *    }
     *  }
     * ```
     */
    fun getGoToLocation(targets: List<DefinitionTarget>) {
        targets.firstOrNull().let { target ->
            if (target == null) {
                return@let
            }
            // TODO this must be tested if it work in windows
            val file = LocalFileSystem.getInstance().findFileByNioFile(Path(URL(target.targetUri).path))?:return@let
            // TODO UTF_8 might fail for some locale, but no better way currently for it
            val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            // TODO also impl select? currently the caret put at the start pos
            val offset = StringUtil.lineColToOffset(
                content,
                target.targetRange.start.line,
                target.targetRange.start.character)
            project.service<LeanProjectService>().scope.launch(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openTextEditor(
                    OpenFileDescriptor(project, file, offset),
                    true
                )
            }
        }
    }

    private var hoverListener : EditorMouseMotionListener? = null
    private var hoverRangeHighlighter : RangeHighlighter? = null
    // The editor [hoverListener]/[hoverRangeHighlighter] were attached to. They must be removed from
    // this editor rather than the currently selected one: when the selected editor changes between
    // hover responses, removing from the current editor trips platform assertions (see below).
    private var hoverEditor : Editor? = null

    /**
     * Try to add highlight for current hover content in current selected editor
     * Since the returned type [Hover] does not contain the concrete hovering file,
     * we implement it here rather than the [LeanFile] class.
     * Although we can also match the request using the response in [LeanLanguageServerLifecycleListener]
     * we do not do it this way yet though
     * TODO MAYBE this should be refactor out to some editor related service or other stuff
     *      too many things in [LeanProjectService]
     * This is invoked from the LSP reader thread on every hover response. The previously attached
     * listener/highlighter are removed from [hoverEditor] (the editor they were actually attached to),
     * not the currently selected editor: when the selected editor changes between hover responses,
     * [com.intellij.openapi.editor.impl.EditorImpl.removeEditorMouseMotionListener] would fail its
     * `LOG.assertTrue(success || isReleased)` and [com.intellij.openapi.editor.markup.MarkupModel.removeHighlighter]
     * its belongs-to-the-tree check. Both report an IDE error at creation time (via [com.intellij.openapi.diagnostic.Logger.assertTrue]),
     * so catching the throwable cannot suppress them.
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
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            val document = editor.document
            val markupModel = editor.markupModel

            // Capture and null the fields before removing so a re-entrant call cannot remove the same
            // artifacts twice, and remove them from the editor they were actually attached to.
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

            if (hover == null) {
                return@let
            }

            // TODO DRY DRY, duplicated with
            //      lean4ij.infoview.InfoviewMouseMotionListener.mouseMoved
            // TODO this should add some setting page for it too
            val attr = object : TextAttributes() {
                override fun getBackgroundColor(): Color {
                    // TODO document this
                    // TODO should scheme be cache?
                    val scheme = EditorColorsManager.getInstance().globalScheme
                    // TODO customize attr? or would backgroundColor null?
                    //      indeed here it can be null, don't know why Kotlin does not mark it as error
                    // TODO there is cases here the background of identifier under current caret is null
                    // TODO do this better in a way
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
                // There may be the following exception, it may be caused by concurrently changing the content in the editor and the hover event is triggered
                // TODO Nevertheless when editing the content the hover event should not be triggered theoretically, throttled the hover event when editing rather than
                //      simply catching the exception
                /**
                 * java.lang.AssertionError: 446 (class com.intellij.openapi.editor.impl.RangeHighlighterTree); this: 902(class com.intellij.openapi.editor.impl.RangeHighlighterTree)
                 * 	at com.intellij.openapi.editor.impl.IntervalTreeImpl.checkBelongsToTheTree(IntervalTreeImpl.java:938)
                 * 	at com.intellij.openapi.editor.impl.IntervalTreeImpl.removeInterval(IntervalTreeImpl.java:969)
                 * 	at com.intellij.openapi.editor.impl.MarkupModelImpl.removeHighlighter(MarkupModelImpl.java:200)
                 * 	at lean4ij.project.LeanProjectService$highlightCurrentContent$1$1.mouseMoved(LeanProjectService.kt:337)
                 * 	at com.intellij.openapi.editor.impl.EditorImpl$MyMouseMotionListener.mouseMoved(EditorImpl.java:4857)
                 */
            }
        }
        }
    }

    /**
     * A naive check on if current project is depending on mathlib
     * TODO definitely it requires some refactor
     */
    fun isDependingOnMathlib() : Boolean {
        val projectBasePath = project.basePath?:return false
        // TODO maybe some constant for the configuration file
        val projectConfigurationFile = Path(projectBasePath, "lakefile.lean")
        if (projectConfigurationFile.exists() && projectConfigurationFile.isRegularFile()){
            val configurationText = projectConfigurationFile.toFile().readText()
            // require mathlib and the concrete git path can be in different lines
            // check: https://grep.app/search?f.lang=Lean&f.path.pattern=lakefile.lean&regexp=true&q=require+mathlib%5B%5Cs%5CS%5D%2Bleanprover-community%2Fmathlib4
            // This definitely has some bad case, but it should enough for most case
            val mathlibDependenceRegex = Regex("require mathlib[\\s\\S]+leanprover-community/mathlib4")
            if (mathlibDependenceRegex.find(configurationText) != null) {
                return true
            }
        }
        val projectConfigurationFileToml =Path(projectBasePath, "lakefile.toml")
        if (projectConfigurationFileToml.exists() && projectConfigurationFileToml.isRegularFile()) {
            val configurationText = projectConfigurationFileToml.toFile().readText()
            // TODO absolutely we should use some toml library for this
            // This is checked with
            // https://grep.app/search?f.lang=TOML&f.path.pattern=lakefile.toml&regexp=true&q=name%5Cs*%3D%5Cs*%22mathlib%22%5B%5Cs%5CS%2B%5Dgit%5Cs*%3D%5Cs*%22https%3A%2F%2Fgithub.com%2Fleanprover-community%2Fmathlib4.git%22
            // val mathlibDependenceRegex = Regex("name\\s*=\\s*\"mathlib\"[\\s\\S+]git\\s*=\\s*\"https://github.com/leanprover-community/mathlib4.git\"")
            // The above is too much, we check only `name = mathlib` for the configuration since the following
            //     name\s*=\s*"mathlib"[\s\S]+scope = "leanprover-community"
            // i.e., the following raw string is also observed, which seems default generated by `lake new <SomeProject> math.toml`
            //     [[require]]
            //      name = "mathlib"
            //      scope = "leanprover-community"
            //      version = "git#master"
            // check https://grep.app/search?f.lang=TOML&f.path.pattern=lakefile.toml&regexp=true&q=name%5Cs*%3D%5Cs*%22mathlib%22%5B%5Cs%5CS%5D%2Bscope+%3D+%22leanprover-community%22
            val mathlibDependenceRegex = Regex("name\\s*=\\s*\"mathlib\"")
            if (mathlibDependenceRegex.find(configurationText) != null) {
                return true
            }
        }
        return false
    }
}
