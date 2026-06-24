package lean4ij.project

import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import lean4ij.lsp.data.TaggedText
import lean4ij.lsp.data.DefinitionTarget
import lean4ij.util.Constants
import lean4ij.util.LspUtil
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import kotlin.math.max


/**
 * The decision taken by [classifyRpcRetry] for a given LSP [org.eclipse.lsp4j.jsonrpc.messages.ResponseError].
 *
 * This is a behavior-preserving extraction of the inline error-code classification that used to live
 * directly inside [LeanFile.rpcCallWithRetry]. It maps a `(code, message)` pair to one of three actions.
 */
internal enum class RpcRetryDecision {
    /** code == -32900 && message == "Outdated RPC session": refresh the RPC session and re-issue the call. */
    RETRY_AFTER_SESSION_UPDATE,

    /** A known, swallowable error: return `null` from the rpc call. */
    RETURN_NULL,

    /** An unrecognized error: rethrow the original exception. */
    RETHROW,
}

/**
 * Pure classification of an LSP RPC [org.eclipse.lsp4j.jsonrpc.messages.ResponseError] into a
 * [RpcRetryDecision]. Semantics are IDENTICAL to the original inline `if` chain in
 * [LeanFile.rpcCallWithRetry]:
 *
 *  - `-32900` + `"Outdated RPC session"`                  -> [RpcRetryDecision.RETRY_AFTER_SESSION_UPDATE]
 *  - `-32603` + `"elaboration interrupted"`               -> [RpcRetryDecision.RETURN_NULL]
 *  - `-32601` + message contains `"No RPC method"`        -> [RpcRetryDecision.RETURN_NULL]
 *  - `-32801` + message contains `"Cannot process request to closed file "` -> [RpcRetryDecision.RETURN_NULL]
 *  - `-32602` + message contains `"Cannot decode params in RPC call"`        -> [RpcRetryDecision.RETURN_NULL]
 *  - anything else                                        -> [RpcRetryDecision.RETHROW]
 *
 * As in the original, the `contains`-based branches dereference [message] (it is a non-null platform
 * String there); reaching such a branch with a null message throws, exactly as before.
 */
internal fun classifyRpcRetry(code: Int, message: String?): RpcRetryDecision {
    if (code == -32900 && message == "Outdated RPC session") {
        return RpcRetryDecision.RETRY_AFTER_SESSION_UPDATE
    }
    if (code == -32603 && message == "elaboration interrupted") {
        return RpcRetryDecision.RETURN_NULL
    }
    if (code == -32601 && message!!.contains("No RPC method")) {
        return RpcRetryDecision.RETURN_NULL
    }
    if (code == -32801 && message!!.contains("Cannot process request to closed file ")) {
        return RpcRetryDecision.RETURN_NULL
    }
    if (code == -32602 && message!!.contains("Cannot decode params in RPC call")) {
        return RpcRetryDecision.RETURN_NULL
    }
    return RpcRetryDecision.RETHROW
}

class LeanFile(private val leanProjectService: LeanProjectService, private val file: String) {

    private val lean4Settings = service<Lean4Settings>()

    /**
     * TODO this should be better named
     */
    private val unquotedFile = LspUtil.unquote(file)

    var virtualFile : VirtualFile? = null

    private val project = leanProjectService.project
    private val buildWindowService: BuildWindowService = project.service()
    private val scope = leanProjectService.scope

    /** The file-progress bar + gutter markers, extracted out of this otherwise-god object. */
    private val progressRenderer = LeanFileProgressRenderer(project, scope, buildWindowService, leanProjectService, file, unquotedFile)

    /** The RPC session lifecycle (connect / keep-alive / reset), extracted out of this otherwise-god object. */
    private val sessionManager = LeanFileSession(leanProjectService, scope, file)

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

    fun updateFileProcessingInfo(info: FileProgressProcessingInfo) = progressRenderer.updateFileProcessingInfo(info)

    /** See [LeanFileProgressRenderer.clearFileProgress]; called on server stop (LeanProjectService.resetServer). */
    fun clearFileProgress() = progressRenderer.clearFileProgress()

    /** Delegates to [LeanFileSession]; public because LeanProjectService.getSession routes through it. */
    suspend fun getSession() : String = sessionManager.getSession()

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
            when (classifyRpcRetry(responseError.code, responseError.message)) {
                RpcRetryDecision.RETRY_AFTER_SESSION_UPDATE -> {
                    // Here there is a possibility that rpcCallRaw is called concurrently and all of them failed
                    // the lock in updateSession will avoid update session continuously
                    // also check the comment inside updateSession, in fact we keep it alive forever...
                    sessionManager.updateSession(params.sessionId)
                    params.sessionId = sessionManager.session!!
                    return action(params)
                }
                /**
                 * The RETURN_NULL branch (see classifyRpcRetry) covers:
                 * -32603 / "elaboration interrupted"
                 * -32601 / "No RPC method ..." e.g. "No RPC method 'Lean.Widget.getInteractiveDiagnostics' found"
                 * -32801 / "Cannot process request to closed file ..." e.g. "Cannot process request to closed file 'file:///....'"
                 * -32602 / "Cannot decode params in RPC call ..." e.g. "Cannot decode params in RPC call '...'"
                 */
                RpcRetryDecision.RETURN_NULL -> return null
                RpcRetryDecision.RETHROW -> throw ex
            }
        } catch (ex: Exception) {
            // org.eclipse.lsp4j.jsonrpc.ResponseErrorException: elaboration interrupted
            // TODO outdated session seems not reported here
            throw ex
        }
    }

    suspend fun rpcCallRaw(params: RpcCallParamsRaw): JsonElement? {
        // always use the session in the file rather than the external infoview
        params.sessionId = sessionManager.session!!
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
                sessionManager.reset()
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

