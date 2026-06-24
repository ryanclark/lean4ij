package lean4ij.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticsApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lean4ij.infoview.renderInteractiveDiagnosticHtml
import lean4ij.project.LeanProjectService
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches pre-rendered, syntax-highlighted HTML tooltips for Lean diagnostics, keyed by file + document
 * modification stamp + diagnostic range.
 *
 * Why this exists: lsp4ij's [com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature.getTooltip] is
 * synchronous and renders the diagnostic message as escaped plain text, so Lean's pretty-printed
 * types/expressions in an error collapse into flat text. The richly-tagged version (prose vs code) only comes
 * from Lean's async `getInteractiveDiagnostics` RPC. This service bridges the two: on a cache miss the
 * diagnostic annotation pass shows the plain default, [warm] fetches + renders the interactive diagnostics
 * off-EDT, and then asks lsp4ij to repaint diagnostics so the now-cached rich tooltip replaces the plain one.
 *
 * Keying on [Document.getModificationStamp] matches lsp4ij's own staleness model (its `applyHighlights`
 * discards results whose stamp changed), so an edit naturally invalidates stale entries.
 */
@Service(Service.Level.PROJECT)
class LeanDiagnosticTooltipService(private val project: Project, private val scope: CoroutineScope) {

    private data class RangeKey(val startLine: Int, val startChar: Int, val endLine: Int, val endChar: Int)

    private fun keyOf(range: org.eclipse.lsp4j.Range): RangeKey =
        RangeKey(range.start.line, range.start.character, range.end.line, range.end.character)

    private fun keyOf(range: lean4ij.lsp.data.Range): RangeKey =
        RangeKey(range.start.line, range.start.character, range.end.line, range.end.character)

    private class FileTooltips(val modStamp: Long, val byRange: Map<RangeKey, String>)

    /** uri -> rendered tooltips for a specific document modification stamp. */
    private val cache = ConcurrentHashMap<String, FileTooltips>()

    /** uri -> modification stamp currently being fetched, to dedupe concurrent warms. */
    private val inFlight = ConcurrentHashMap<String, Long>()

    /**
     * The rich tooltip HTML for [diagnosticRange] if it has been rendered for the current [modStamp], else
     * null (caller should fall back to the plain default tooltip).
     */
    fun lookup(uri: String, modStamp: Long, diagnosticRange: org.eclipse.lsp4j.Range): String? {
        val fileTooltips = cache[uri] ?: return null
        if (fileTooltips.modStamp != modStamp) return null
        return fileTooltips.byRange[keyOf(diagnosticRange)]
    }

    /**
     * Fetch + pre-render this file's interactive diagnostics for [modStamp] off the EDT, populate the cache,
     * and schedule a diagnostics repaint so the warm tooltips take effect. No-op if already cached or a fetch
     * for this exact stamp is already running; a fetch for an outdated stamp simply doesn't publish.
     */
    fun warm(file: VirtualFile, document: Document, modStamp: Long, lineCount: Int) {
        val uri = file.url
        if (cache[uri]?.modStamp == modStamp) return
        // Dedupe: at most one in-flight fetch per file. If one for this same stamp is already running, skip;
        // otherwise record this stamp as the latest requested and proceed (an older in-flight fetch will see
        // the stamp advanced and decline to publish).
        if (inFlight.put(uri, modStamp) == modStamp) return
        scope.launch {
            try {
                val leanFile = project.service<LeanProjectService>().file(file)
                val diagnostics = leanFile.getInteractiveDiagnosticsForLineRange(0, lineCount) ?: emptyList()
                val byRange = HashMap<RangeKey, String>(diagnostics.size * 2)
                for (diagnostic in diagnostics) {
                    val html = renderInteractiveDiagnosticHtml(diagnostic)
                    // The lsp4j Diagnostic range published to the editor matches Lean's `range`; also index by
                    // `fullRange` so the lookup hits regardless of which the server used for this diagnostic.
                    byRange[keyOf(diagnostic.range)] = html
                    byRange.putIfAbsent(keyOf(diagnostic.fullRange), html)
                }
                // Only publish if this is still the latest requested stamp (no newer edit raced ahead).
                if (inFlight[uri] == modStamp) {
                    cache[uri] = FileTooltips(modStamp, byRange)
                    LSPDiagnosticsApplier.getInstance(project).scheduleRefresh(file, document)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The Lean server restarts often (transient session/closed-file RPC errors); swallow so the
                // tooltip warm doesn't surface as an unhandled coroutine exception (SEVERE IDE error popup).
                thisLogger().debug("interactive diagnostic tooltip warm failed for ${file.path}: ${e.message}")
            } finally {
                inFlight.remove(uri, modStamp)
            }
        }
    }
}
