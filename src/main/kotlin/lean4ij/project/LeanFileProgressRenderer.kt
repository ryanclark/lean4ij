package lean4ij.project

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.ProgressReporter
import com.intellij.platform.util.progress.reportProgress
import com.intellij.platform.util.progress.withProgressText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lean4ij.lsp.data.FileProgressProcessingInfo
import lean4ij.setting.Lean4Settings
import lean4ij.util.Constants
import lean4ij.util.step
import org.eclipse.lsp4j.TextDocumentIdentifier
import kotlin.math.min

/**
 * Renders the file-progress UI for one [LeanFile]: the IDE background-progress bar and the gutter "filling"
 * line markers, driven by `$/lean/fileProgress` notifications. Extracted from LeanFile (an otherwise
 * god-object) but strictly behavior-preserving - the channel ordering, the monotonic progress step, the
 * EDT-confined markup mutation, and the watchdog are unchanged.
 *
 * [file] is the quoted file uri; [unquotedFile] is the plain path used to match the selected editor.
 */
class LeanFileProgressRenderer(
    private val project: Project,
    private val scope: CoroutineScope,
    private val buildWindowService: BuildWindowService,
    private val leanProjectService: LeanProjectService,
    private val file: String,
    private val unquotedFile: String,
) {

    companion object {
        // If no $/lean/fileProgress update arrives within this window while a file is "processing", treat it as
        // finished so the background progress task can't hang on a missed/never-sent completion. This only needs
        // to outlast the gap between progress events, not the whole elaboration, but a large proof or a fresh
        // Mathlib import can sit on one file for minutes, so keep it generous to avoid a premature "finished".
        private const val PROGRESS_WATCHDOG_MILLIS = 300_000L
    }

    private val lean4Settings = service<Lean4Settings>()

    // UNLIMITED + ordered trySend (see updateFileProcessingInfo): the server's fileProgress events MUST be
    // delivered in the order they arrive, or a `finished` (empty) event can overtake its preceding `processing`
    // one and the progress loop blocks forever.
    private val processingInfoChannel = Channel<FileProgressProcessingInfo>(Channel.UNLIMITED)

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
    }

    /**
     * Enqueue IN ORDER. lsp4ij invokes the notification handler sequentially, so a non-suspending trySend onto
     * the UNLIMITED channel preserves the server's event order. The previous `scope.launch { send }` spawned a
     * coroutine per notification that raced on the dispatcher: on the (then unbuffered) channel a `finished`
     * event could be delivered BEFORE the preceding `processing`, so the loop consumed `finished` early and then
     * blocked forever waiting for one that never came again: the stuck progress bar.
     */
    fun updateFileProcessingInfo(info: FileProgressProcessingInfo) {
        processingInfoChannel.trySend(info)
    }

    /**
     * Clears an in-flight file-progress bar by feeding a synthetic "finished" notification.
     *
     * The Lean server signals that a file is done elaborating with a `$/lean/fileProgress` notification whose
     * `processing` list is empty (see [FileProgressProcessingInfo.isFinished]). The background-progress
     * coroutine above blocks on [processingInfoChannel] waiting for that terminating event; if the server dies
     * mid-elaboration it never arrives and the progress bar stays up forever. Calling this on server stop (see
     * [LeanProjectService.resetServer]) unblocks the coroutine so the bar is removed.
     */
    fun clearFileProgress() {
        updateFileProcessingInfo(FileProgressProcessingInfo(TextDocumentIdentifier(file), emptyList()))
    }

    /** this is for avoiding flashing, a highlighter is always added in the first line */
    private var firstLineHighlighter: RangeHighlighter? = null
    // The editor [firstLineHighlighter] was created on. A LeanFile (and its renderer) lives for the whole
    // project session, so if the file is closed and reopened in a NEW editor the cached highlighter would be
    // stale (bound to the old, now-disposed editor); recreate it when the selected editor changes.
    private var firstLineHighlighterEditor: com.intellij.openapi.editor.Editor? = null
    // The editor the current line-marker [highlighters] batch was created on. selectedTextEditor can return a
    // different editor for the same file (split, reopen, Restart File); the markers must be removed from the
    // model that owns them, or removeHighlighter asserts (checkBelongsToTheTree, an AssertionError the caller's
    // catch(Exception) would not even catch).
    private var lineMarkersEditor: com.intellij.openapi.editor.Editor? = null
    private val leanFileProgressEmptyTextAttributesKey = TextAttributesKey.createTextAttributesKey("LEAN_FILE_PROGRESS_EMPTY")

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
            if (firstLineHighlighter == null || firstLineHighlighterEditor !== editor) {
                firstLineHighlighter = markupModel.addLineHighlighter(0, 1, null)
                firstLineHighlighterEditor = editor
            }
            firstLineHighlighter!!.lineMarkerRenderer = LeanFileProgressFinishedFillingLineMarkerRenderer
            lineMarkersEditor?.takeIf { !it.isDisposed }?.let { owner ->
                for (highlighter in highlighters) {
                    owner.markupModel.removeHighlighter(highlighter)
                }
            }
            for (processingInfo in info.processing) {
                val startLine = processingInfo.range.start.line.let {
                    if (it == 0) {
                        firstLineHighlighter!!.lineMarkerRenderer = LeanFileProgressFillingLineMarkerRenderer
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
                rangeHighlighter.lineMarkerRenderer = LeanFileProgressFillingLineMarkerRenderer
                ret.add(rangeHighlighter)
            }
            lineMarkersEditor = editor
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
}
