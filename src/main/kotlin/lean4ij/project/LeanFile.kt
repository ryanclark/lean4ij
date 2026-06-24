package lean4ij.project

import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.DefaultLineMarkerRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.ProgressReporter
import com.intellij.platform.util.progress.reportProgress
import com.intellij.platform.util.progress.withProgressText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import lean4ij.setting.Lean4Settings
import lean4ij.infoview.InfoViewWindowFactory
import lean4ij.infoview.MiniInfoviewService
import lean4ij.infoview.external.data.ApplyEditChange
import lean4ij.lsp.data.FileProgressProcessingInfo
import lean4ij.lsp.data.GetGoToLocationParams
import lean4ij.lsp.data.InteractiveDiagnostics
import lean4ij.lsp.data.InteractiveDiagnosticsParams
import lean4ij.lsp.data.InteractiveGoals
import lean4ij.lsp.data.InteractiveGoalsParams
import lean4ij.lsp.data.InteractiveTermGoal
import lean4ij.lsp.data.InteractiveTermGoalParams
import lean4ij.lsp.data.LazyTraceChildrenToInteractiveParams
import lean4ij.lsp.data.LineRange
import lean4ij.lsp.data.LineRangeParam
import lean4ij.lsp.data.MsgEmbed
import lean4ij.lsp.data.PlainGoalParams
import lean4ij.lsp.data.Position
import lean4ij.lsp.data.RpcCallParams
import lean4ij.lsp.data.RpcCallParamsRaw
import lean4ij.lsp.data.RpcConnectParams
import lean4ij.lsp.data.RpcKeepAliveParams
import lean4ij.lsp.data.TaggedText
import lean4ij.lsp.data.DefinitionTarget
import lean4ij.util.Constants
import lean4ij.util.LspUtil
import lean4ij.util.step
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import kotlin.math.max
import kotlin.math.min


class LeanFile(private val leanProjectService: LeanProjectService, private val file: String) {

    companion object {
        // If no $/lean/fileProgress update arrives within this window while a file is "processing", treat it as
        // finished so the background progress task can't hang on a missed/never-sent completion. This only needs
        // to outlast the gap between progress events, not the whole elaboration, but a large proof or a fresh
        // Mathlib import can sit on one file for minutes, so keep it generous to avoid a premature "finished".
        private const val PROGRESS_WATCHDOG_MILLIS = 300_000L
    }

    private val lean4Settings = service<Lean4Settings>()

    /**
     * TODO this should be better named
     */
    private val unquotedFile = LspUtil.unquote(file)

    var virtualFile : VirtualFile? = null

    // UNLIMITED + ordered trySend (see updateFileProcessingInfo): the server's fileProgress events MUST be
    // delivered in the order they arrive, or a `finished` (empty) event can overtake its preceding `processing`
    // one and the progress loop blocks forever.
    private val processingInfoChannel = Channel<FileProgressProcessingInfo>(Channel.UNLIMITED)
    private val project = leanProjectService.project
    private val buildWindowService: BuildWindowService = project.service()
    private val scope = leanProjectService.scope

    /** The single keep-alive loop's job (see [keepAlive]); a new session cancels the previous loop. */
    private var keepAliveJob: Job? = null

    init {
        scope.launch {
            // TODO is it here also blocking a thread?
            // TODO add a setting for this
            while (true) {
                var info = processingInfoChannel.receive()
                var highlighters = mutableListOf<RangeHighlighter>()
                try {
                    highlighters = tryAddLineMarker(info, highlighters)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Defensive: this call is outside the loop's main try/catch, so any marker failure
                    // (bad offsets from a stale progress range, etc.) would otherwise leak as an unhandled
                    // coroutine exception (SEVERE popup).
                    thisLogger().debug("file progress line marker failed for $file: ${e.message}")
                }
                if (info.isFinished()) {
                    continue
                }
                buildWindowService.startBuild(file)
                try {
                    withBackgroundFileProgress { reporter ->
                        var currentStep = 0
                        do {
                            val newStep = info.workSize()
                            // TODO they are chance that it's negative for file progress again
                            //      this is because that, while progressing, editing it again in earlier position will
                            //      trigger file processing again
                            if (newStep >= currentStep) {
                                reporter.step(newStep - currentStep)
                                currentStep = newStep
                            }
                            // Watchdog: if the server sends no fileProgress update for a while, it likely will
                            // never send the "finished" (empty) one (server hung on this file / missed
                            // notification / restart). Treat that as finished so this background task and its
                            // gutter markers can't stay stuck forever. A later update just starts a fresh cycle.
                            info = withTimeoutOrNull(PROGRESS_WATCHDOG_MILLIS) { processingInfoChannel.receive() }
                                ?: FileProgressProcessingInfo(info.textDocument, emptyList())
                            highlighters = tryAddLineMarker(info, highlighters)
                        } while (info.isProcessing())
                    }
                } catch (e: CancellationException) {

                } catch (e: Exception) {
                    // TODO here should only handle for task cancelling
                    e.printStackTrace()
                }
                buildWindowService.endBuild(file)
            }
        }
        // it seems facing some initialization order problem
        // scope.launch {
        //     getAllMessages()
        // }
    }

    /**
     * this is for avoiding flashing, a highlighter is always added in the first line
     */
    private var firstLineHighlighter :RangeHighlighter? = null
    private val leanFileProgressEmptyTextAttributesKey = TextAttributesKey.createTextAttributesKey("LEAN_FILE_PROGRESS_EMPTY")

    /**
     */
    private suspend fun tryAddLineMarker(info: FileProgressProcessingInfo, highlighters: MutableList<RangeHighlighter>): MutableList<RangeHighlighter> {
        if (!lean4Settings.enableFileProgressBar) return mutableListOf()
        // selectedTextEditor and the MarkupModel add/remove calls below are EDT-confined and fire UI events,
        // but this runs from the background file-progress loop. Do the editor lookup and all markup mutation
        // on the EDT (matching LeanProjectService.highlightCurrentContent), guarding a disposed project.
        return withContext(Dispatchers.EDT) {
            val ret = mutableListOf<RangeHighlighter>()
            if (project.isDisposed) return@withContext ret
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext ret
            if (editor.virtualFile?.path != unquotedFile) return@withContext ret
            val document = editor.document
            val markupModel = editor.markupModel
            if (firstLineHighlighter == null) {
                firstLineHighlighter = markupModel.addLineHighlighter(0, 1, null)
            }
            firstLineHighlighter!!.lineMarkerRenderer = leanFileProgressFinishedFillingLineMarkerRender
            for (highlighter in highlighters) {
                markupModel.removeHighlighter(highlighter)
            }
            for (processingInfo in info.processing) {
                val startLine = processingInfo.range.start.line.let {
                    if (it == 0) {
                        firstLineHighlighter!!.lineMarkerRenderer = leanFileProgressFillingLineMarkerRender
                        1
                    } else {
                        it
                    }
                }
                val endLine = min(processingInfo.range.end.line, document.lineCount)
                val startLineOffset = StringUtil.lineColToOffset(document.charsSequence, startLine, 0)
                val endLineOffset = StringUtil.lineColToOffset(document.charsSequence, min(endLine, document.lineCount-1), 0)
                // TODO Here it may incur an exception:
                //      https://github.com/onriv/lean4ij/issues/148
                //      In a large chance it may be caused by the file is edited currently
                //      For a temporary solution, just ignore it
                if (endLineOffset == -1 || startLineOffset == -1 || startLineOffset > endLineOffset) {
                    // start > end happens when the server's (stale) progress range inverts after an edit
                    // (e.g. start=41, end=0); addRangeHighlighter would throw "Incorrect offsets". Skip it.
                    continue
                }
                val rangeHighlighter = markupModel.addRangeHighlighter(
                    leanFileProgressEmptyTextAttributesKey,
                    startLineOffset, endLineOffset, HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE)
                rangeHighlighter.lineMarkerRenderer = leanFileProgressFillingLineMarkerRender
                ret.add(rangeHighlighter)
            }
            ret
        }
    }

    private suspend fun withBackgroundFileProgress(action: suspend (reporter: ProgressReporter) -> Unit) {
        withBackgroundProgress(project, Constants.FILE_PROGRESS) {
            withProgressText(leanProjectService.getRelativePath(file)) {
                reportProgress { reporter ->
                    action(reporter)
                }
            }
        }
    }

    /**
     * current file update caret
     * now it's just forward back to project service
     * but maybe later it can do its customized job
     * TODO NOW it's very awkward also to add getAllMessages for it seems
     *      different time point: updating goals and term goal is at caret moving
     *      but all message is updating at after diagnostic finished, maybe just
     *      get the content here...
     * TODO the keypoint maybe currently no tree structure for infoview like html that can be rendered
     *      more smoothly and independently
     * TODO maybe try psi for infoview tool window
     * TODO passing things like editor etc seems cumbersome, maybe add some implement for context
     * TODO this should maybe named as [updateInternalInfoview], but it contains a switch...
     *      The switch should put in [updateInternalInfoview]
     * TODO maybe move some logic back to [lean4ij.project.listeners.LeanFileCaretListener]
     *      here in fact messed up two different source for update infoview : from the caret change and from the document update
     */
    fun updateCaret(editor: Editor, logicalPosition: LogicalPosition, forceUpdate: Boolean = false) {
        val position = Position(line = logicalPosition.line, character = logicalPosition.column)
        val textDocument = TextDocumentIdentifier(LspUtil.quote(file))
        val params = PlainGoalParams(textDocument, position)
        if (lean4Settings.enableVscodeInfoview) {
            // TODO this is in fact not fully controlling the behavior for the vscode/internal/jcef infoview
            leanProjectService.updateCaret(params)
        }

        // update goal popover

        // update info view
        if (lean4Settings.enableNativeInfoview) {
            if (!lean4Settings.autoUpdateInternalInfoview && !forceUpdate) return
            updateInternalInfoview(editor, params)
        } else {
            InfoViewWindowFactory.getLeanInfoview(project)?.let { leanInfoviewWindow ->
                leanProjectService.scope.launch {
                    leanInfoviewWindow.updateDirectText("Internal infoview is not enable.")
                }
            }
        }
    }

    private fun updateInternalInfoview(editor: Editor, params: PlainGoalParams) {
        val textDocument = params.textDocument
        val position = params.position
        leanProjectService.scope.launch {
            if (virtualFile == null) {
                thisLogger().info("No virtual file for $file, skip updating infoview")
                return@launch
            }
            try {
                val session = getSession()
                val interactiveGoalsParams = InteractiveGoalsParams(session, params, textDocument, position)
                val interactiveTermGoalParams = InteractiveTermGoalParams(session, params, textDocument, position)
                // TODO how to determine which diagnostic get?
                val line = position.line
                val diagnosticsParams = InteractiveDiagnosticsParams(session, LineRangeParam(LineRange(line, line+1)), textDocument, position)
                val interactiveGoalsAsync = async { getInteractiveGoals(interactiveGoalsParams) }
                val interactiveTermGoalAsync = async { getInteractiveTermGoal(interactiveTermGoalParams) }
                val interactiveDiagnosticsAsync = async { getInteractiveDiagnostics(diagnosticsParams) }
                // val diagnostics = file.getInteractiveDiagnostics(diagnosticsParams)
                // Both interactiveGoals and interactiveTermGoal can be null and hence we pass them to
                // updateInteractiveGoal nullable
                val interactiveGoals = interactiveGoalsAsync.await()
                val interactiveTermGoal = interactiveTermGoalAsync.await()
                val interactiveDiagnostics = interactiveDiagnosticsAsync.await()

                // TODO the arguments are passing very deep, need some refactor
                InfoViewWindowFactory.updateInteractiveGoal(editor, project, virtualFile!!, position, interactiveGoals, interactiveTermGoal, interactiveDiagnostics, allMessage)

                project.service<MiniInfoviewService>()
                    .updateCaret(editor, position, interactiveGoals, interactiveTermGoal);
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // lsp4ij frequently restarts the Lean server while the (huge) monorepo re-indexes, so these
                // RPC/LSP calls fail transiently (e.g. ResponseErrorException "Cannot process request to
                // closed file", or a closed stream). Swallow here: otherwise they propagate as unhandled
                // coroutine exceptions and IntelliJ shows a SEVERE error popup for every caret/edit event.
                thisLogger().debug("Skip infoview update for $file (language server unavailable/restarting): ${e.message}")
            }
        }
    }

    fun updateFileProcessingInfo(info: FileProgressProcessingInfo) {
        // Enqueue IN ORDER. lsp4ij invokes this notification handler sequentially, so a non-suspending trySend
        // onto the UNLIMITED channel preserves the server's event order. The previous `scope.launch { send }`
        // spawned a coroutine per notification that raced on the dispatcher: on the (then unbuffered) channel a
        // `finished` event could be delivered BEFORE the preceding `processing`, so the loop consumed `finished`
        // early and then blocked forever waiting for one that never came again: the stuck progress bar.
        processingInfoChannel.trySend(info)
    }

    /**
     * Clears an in-flight file-progress bar by feeding a synthetic "finished" notification.
     *
     * The Lean server signals that a file is done elaborating with a `$/lean/fileProgress`
     * notification whose `processing` list is empty (see [FileProgressProcessingInfo.isFinished]).
     * The background-progress coroutine in [init] blocks on [processingInfoChannel] waiting for
     * that terminating event; if the server dies mid-elaboration it never arrives and the progress
     * bar stays up forever. Calling this on server stop (see [LeanProjectService.resetServer])
     * unblocks the coroutine so the bar is removed.
     */
    fun clearFileProgress() {
        updateFileProcessingInfo(FileProgressProcessingInfo(TextDocumentIdentifier(file), emptyList()))
    }

    private var session : String? = null
    private val sessionMutex : Mutex = Mutex()
    suspend fun getSession() : String {
        updateSession(null)
        return session!!
    }

    /**
     * Here the argument [oldSession] must be passed for there maybe concurrent access for updating session, for example
     * multiple rpc calls like "Lean.Widget.getInteractiveGoals" and "Lean.Widget.getInteractiveTermGoal" and
     * "Lean.Widget.getWidgets" etc
     * TODO check [Mutex]'s behavior, for example: in [here](https://discuss.kotlinlang.org/t/is-it-always-safe-to-just-convert-synchronized-to-mutex-withlock/26519)
     * TODO check if it's better way than double locking check
     */
    private suspend fun updateSession(oldSession: String?) {
        if (oldSession == session) {
            // TODO check this timeout, check the following rpcConnect for the following timeout
            withTimeout(5*1000) {
                sessionMutex.withLock {
                    if (oldSession == session) {
                        session = leanProjectService.languageServerForFile(file).await().rpcConnect(RpcConnectParams(file)).sessionId
                        // keep alive making infoToInteractive behave better, for the reference must have the same session
                        // as the goal result, so keep it alive here...
                        // TODO is here will cause multiple keep alive loop?
                        keepAlive()
                    }
                }
            }
        }
    }


    /**
     * TODO maybe it should not always keep alive
     */
    private fun keepAlive() {
        // Exactly one keep-alive loop per file: every session (re)connect calls this, so cancel the previous
        // loop before starting a new one. Otherwise loops accumulate against the shared `session` field and
        // never terminate. Launched on the project scope (not a free, never-cancelled IO scope) so it is torn
        // down on project dispose instead of leaking for the IDE's lifetime.
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(9 * 1000)
                try {
                    leanProjectService.languageServerForFile(file).await().rpcKeepAlive(RpcKeepAliveParams(file, session!!))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Server may be restarting / the session may be stale; don't let this loop crash with
                    // an unhandled exception (SEVERE IDE error popup). It resumes once the server is back.
                    thisLogger().debug("rpcKeepAlive failed for $file (language server unavailable/restarting): ${e.message}")
                }
            }
        }
    }

    suspend fun getInteractiveGoals(params: InteractiveGoalsParams): InteractiveGoals? {
        return rpcCallWithRetry(params) {
            leanProjectService.languageServerForFile(file).await().getInteractiveGoals(it)
        }
    }

    public suspend fun getInteractiveTermGoal(params : InteractiveTermGoalParams) : InteractiveTermGoal? {
        return rpcCallWithRetry(params) {
            leanProjectService.languageServerForFile(file).await().getInteractiveTermGoal(it)
        }
    }

    public suspend fun lazyTraceChildrenToInteractive(params: LazyTraceChildrenToInteractiveParams) : List<TaggedText<MsgEmbed>>? {
        return rpcCallWithRetry(params) {
            leanProjectService.languageServerForFile(file).await().lazyTraceChildrenToInteractive(it)
        }
    }

    private suspend fun getInteractiveDiagnostics(params : InteractiveDiagnosticsParams) : List<InteractiveDiagnostics>? {
        return rpcCallWithRetry(params) {
            leanProjectService.languageServerForFile(file).await().getInteractiveDiagnostics(it)
        }
    }

    /**
     * Fetch Lean interactive (rich) diagnostics for the lines `[startLine, endLine)` of this file, reusing the
     * file's RPC session + stale-session retry. The returned [InteractiveDiagnostics] carry the prose/code
     * structure that lets us render syntax-highlighted diagnostic tooltips (see
     * [lean4ij.lsp.LeanDiagnosticTooltipService]). `endLine` is exclusive (e.g. pass the document line count
     * for the whole file), matching [getAllMessages]' `LineRange(0, maxLine + 1)`.
     */
    suspend fun getInteractiveDiagnosticsForLineRange(startLine: Int, endLine: Int): List<InteractiveDiagnostics>? {
        val session = getSession()
        val textDocument = TextDocumentIdentifier(LspUtil.quote(file))
        val params = InteractiveDiagnosticsParams(
            session,
            LineRangeParam(LineRange(startLine, endLine)),
            textDocument,
            Position(0, 0)
        )
        return getInteractiveDiagnostics(params)
    }

    suspend fun getGotoLocation(params: GetGoToLocationParams) : List<DefinitionTarget>? {
         return rpcCallWithRetry(params) {
             leanProjectService.languageServerForFile(file).await().getGotoLocation(it)
         }
    }

    private suspend fun <Params, Resp> rpcCallWithRetry(params: Params, action: suspend (Params) -> Resp): Resp?
            where Params: RpcCallParams {
        try {
            return action(params)
        } catch (ex: ResponseErrorException) {
            // TODO these codes are defined in org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
            //      just don't know if it's full range
            //      no! it's not full range
            // TODO refactor this
            val responseError = ex.responseError
            // TODO remove this magic number and find lean source code for it
            if (responseError.code == -32900 && responseError.message == "Outdated RPC session") {
                // Here there is a possibility that rpcCallRaw is called concurrently and all of them failed
                // the lock in updateSession will avoid update session continuously
                // also check the comment inside updateSession, in fact we keep it alive forever...
                updateSession(params.sessionId)
                params.sessionId = session!!
                return action(params)
            }
            if (responseError.code == -32603 && responseError.message == "elaboration interrupted") {
                return null
            }
            if (responseError.code == -32601 && responseError.message.contains("No RPC method")) {
                /**
                 * TODO this seems weird too
                 *      2024-08-11 14:17:38,335 [ 624441]   WARN - org.eclipse.lsp4j.jsonrpc.RemoteEndpoint - Unmatched response message: {
                 *        "jsonrpc": "2.0",
                 *        "id": "142",
                 *        "error": {
                 *          "code": -32601,
                 *          "message": "No RPC method \u0027Lean.Widget.getInteractiveDiagnostics\u0027 found"
                 *        }
                 *      }
                 */
                return null
            }
            /**
             * TODO for the following error ,
             *      Error: {
             *          "code": -32801,
             *          "message": "Cannot process request to closed file \u0027file:///....\u0027"
             *      }
             * should it be automatically reopen?
             */
            if (responseError.code == -32801 && responseError.message.contains("Cannot process request to closed file ")) {
                return null
            }
            if (responseError.code == -32602 && responseError.message.contains("Cannot decode params in RPC call")) {
                /**
                 * TODO weird for this error
                 *      handle it
                 * {
                 *   "code": -32602,
                 *   "message": "Cannot decode params in RPC call \u0027Lean.Widget.InteractiveDiagnostics.infoToInteractive({\"p\":\"2\"})\u0027\nRPC reference \u00272\u0027 is not valid"
                 * }
                 */
                return null
            }
            throw ex
        } catch (ex: Exception) {
            // org.eclipse.lsp4j.jsonrpc.ResponseErrorException: elaboration interrupted
            // TODO outdated session seems not reported here
            throw ex
        }
    }

    suspend fun rpcCallRaw(params: RpcCallParamsRaw): JsonElement? {
        // always use the session in the file rather than the external infoview
        params.sessionId = session!!
        return rpcCallWithRetry(params) {
            leanProjectService.languageServerForFile(file).await().rpcCall(it)
        }
    }

    /**
     * TODO add log/notification in intellij idea for it
     */
    suspend fun restart() {
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            if (editor.virtualFile.path == unquotedFile) {
                session = null
                val languageServer = leanProjectService.languageServerForFile(file).await()
                val didCloseParams = DidCloseTextDocumentParams(TextDocumentIdentifier(file))
                languageServer.didClose(didCloseParams)
                val textDocumentItem = TextDocumentItem(
                    // Re-open the server with the live document text, not the on-disk bytes: if the editor has
                    // unsaved edits, contentsToByteArray() would desync the server from what the user sees.
                    file, Constants.LEAN_LANGUAGE_ID, 0,
                    editor.document.text
                )
                val didOpenTextDocumentParams = DidOpenTextDocumentParams(textDocumentItem)
                languageServer.didOpen(didOpenTextDocumentParams)
            }
        }
    }

    /**
     * TODO can this be replaced with flow?
     * TODO this form is changed from a initialization order error
     *      that getAllMessages run before it init
     */
    private val diagnosticsChannel = run {
        val channel = Channel<List<Diagnostic>>()
        leanProjectService.scope.launch {
            try {
                this@LeanFile.getAllMessages(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // getSession()/RPC inside the loop can fail while the server is restarting; swallow so it
                // doesn't surface as an unhandled coroutine exception (SEVERE IDE error popup).
                thisLogger().debug("getAllMessages stopped for $file (language server unavailable/restarting): ${e.message}")
            }
        }
        channel
    }

    private var allMessage : List<InteractiveDiagnostics>? = null

    private suspend fun getAllMessages(channel: Channel<List<Diagnostic>>) {
        var lastMaxLine = -1
        var maxLine = -1
        while (true) {
            try {
                val diagnostics = withTimeout(1 * 1000) {
                    channel.receive()
                }
                // if it's empty, trigger a getAllMessage
                if (diagnostics.isEmpty()) {
                    maxLine = lastMaxLine
                }
                for (diagnostic in diagnostics) {
                    maxLine = max(maxLine, diagnostic.range.end.line)
                }
            } catch (ex: TimeoutCancellationException) {
                if (maxLine > -1) {
                    // TODO here do get all messages
                    // TODO not sure this maxLine logic is correct or necessary
                    //      it seems it quite often just quite almost the end of the file
                    // TODO if triggered all messages, should intermediately render the infoview like invoke updateCaret?
                    thisLogger().info("get all messages for $file, maxLine: $maxLine")
                    val session = getSession()
                    val position = Position(0, 0)
                    val textDocument = TextDocumentIdentifier(LspUtil.quote(file))
                    val diagnosticsParams = InteractiveDiagnosticsParams(
                        session,
                        LineRangeParam(LineRange(0, maxLine + 1)),
                        textDocument,
                        position
                    )
                    allMessage = getInteractiveDiagnostics(diagnosticsParams)
                    // after getting all Messages, do an update intermediately...
                    // to avoid lag
                    updateCaretIntermediately()
                    lastMaxLine = maxLine
                    maxLine = -1
                }
            }
        }
    }

    private fun updateCaretIntermediately() {
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            if (editor.virtualFile.path == unquotedFile) {
                updateCaret(editor, editor.caretModel.logicalPosition)
            }
        }
    }

    /**
     * checking a bug for all messages not updated correctly. It shows that there is a cases like:
     * [Trace - 10:59:11] Received notification 'textDocument/publishDiagnostics'
     * Params: {
     *   "uri": "....",
     *   "diagnostics": [],
     *   "version": 30
     * }
     * this simply may be a notification for triggering all message, and hence we pass it to the specific file
     * even diagnostics is empty
     */
    fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        scope.launch {
            diagnosticsChannel.send(diagnostics.diagnostics)
        }
        for (d in diagnostics.diagnostics) {
            buildWindowService.addBuildEvent(file, diagnosticMessage(d))
        }
    }

    /**
     * Read a [Diagnostic]'s message without binding to a specific `getMessage()` signature.
     *
     * lean4ij is compiled against the IntelliJ platform's lsp4j, where `Diagnostic.getMessage()` returns
     * `String`. At runtime, however, lean4ij runs against the lsp4ij plugin's bundled lsp4j 1.0.0, where
     * `getMessage()` returns `Either<String, MarkupContent>`. Calling `d.message` directly therefore
     * throws `NoSuchMethodError` the moment a diagnostic is published (i.e. whenever the file has errors),
     * which lsp4ij surfaces to the user as "Lean Language Server: Cannot start server". Resolving the
     * method reflectively avoids binding to the `String`-returning overload at compile time and works
     * against either lsp4j; any failure degrades to an empty message rather than crashing the handler.
     */
    private fun diagnosticMessage(diagnostic: Diagnostic): String = try {
        when (val raw = Diagnostic::class.java.getMethod("getMessage").invoke(diagnostic)) {
            is String -> raw
            null -> ""
            else -> {
                // lsp4j 1.0.0: Either<String, MarkupContent>; prefer the left String, else MarkupContent.value
                val left = runCatching { raw.javaClass.getMethod("getLeft").invoke(raw) }.getOrNull()
                (left as? String) ?: runCatching {
                    val right = raw.javaClass.getMethod("getRight").invoke(raw)
                    right?.javaClass?.getMethod("getValue")?.invoke(right) as? String
                }.getOrNull() ?: raw.toString()
            }
        }
    } catch (e: Throwable) {
        ""
    }

    fun applyEdit(changes: List<ApplyEditChange>) {
        if (virtualFile == null) {
            return
        }
        if (changes.isEmpty()) {
            return
        }
        // TODO this is kind of duplicated with tryAddLineMarker?
        //      maybe the logic for getting editor can be abstract and unify
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            if (editor.virtualFile.path == unquotedFile) {
                val document = editor.document
                var text = document.text
                changes.map { change ->
                    val start = change.range.start
                    val end = change.range.end
                    val startPos = StringUtil.lineColToOffset(text, start.line, start.character)
                    val endPos = StringUtil.lineColToOffset(text, end.line, end.character)
                    Triple(startPos, endPos, change)
                }.sortedBy { p ->
                    // sort the changes from the end to the start so that the offset does not change for replacing the range
                    -p.first
                }.forEach { p ->
                    text = text.replaceRange(p.first, p.second, p.third.newText)
                }
                val application = ApplicationManager.getApplication()
                // TODO tested and it seems
                application.invokeLater {
                    application.runWriteAction {
                        document.setText(text)
                    }
                }
            }
        }
    }

}

