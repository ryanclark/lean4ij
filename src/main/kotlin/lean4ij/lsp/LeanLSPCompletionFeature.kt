package lean4ij.lsp

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature
import lean4ij.setting.Lean4Settings
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Add an impl for disable lsp completion for lean
 * for sometimes it's slow...
 */
class LeanLSPCompletionFeature : LSPCompletionFeature() {
    private val lean4Settings = service<Lean4Settings>()

    override fun isEnabled(file: PsiFile): Boolean {
        return lean4Settings.enableLspCompletion
    }

    /**
     * Rank completion items by prefix-match relevance, not just the Lean server's sortText. lsp4ij defaults
     * this to false, which left the popup in the server's order, so an exact-prefix match (e.g. `Line` when
     * you have typed `L`) stayed buried under other `L...` items until enough was typed to filter them out.
     * lsp4ij's comparator still uses the server's sortText as a tiebreaker, so Lean's semantic ranking is kept.
     */
    override fun useContextAwareSorting(file: PsiFile): Boolean = true

    /**
     * Force completion-item resolve support so the type/signature shows in the popup. The Lean server computes
     * a completion's type lazily via `completionItem/resolve` (keeping the initial response fast), so items
     * arrive with no `detail`. lsp4ij gates resolve on the server advertising `resolveProvider`, which the Lean
     * server does not, so it never resolved and the type column stayed empty. With this on, lsp4ij's
     * resolve-on-focus path (LSPCompletionProposal.getExpensiveRenderer) resolves the highlighted item on a
     * background thread and fills its type from the resolved `detail` - the same mechanism VS Code uses. If the
     * server cannot resolve an item, lsp4ij handles the null result gracefully (no type, no error).
     */
    override fun isResolveCompletionSupported(file: PsiFile): Boolean = true

    /**
     * The type text shown on the right of each item. lsp4ij's default returns `labelDetails.description`
     * whenever `labelDetails` is non-null - even if that description is blank - and only falls back to `detail`
     * when `labelDetails` is null. Prefer the first non-blank of description / labelDetails.detail / detail so a
     * populated type (including the one filled in by resolve above) is always shown.
     */
    override fun getTypeText(item: CompletionItem): String? {
        item.labelDetails?.description?.takeIf { it.isNotBlank() }?.let { return it }
        item.labelDetails?.detail?.takeIf { it.isNotBlank() }?.let { return it }
        return item.detail?.takeIf { it.isNotBlank() }
    }

    override fun createLookupElement(
        item: CompletionItem,
        context: LSPCompletionContext
    ): LookupElement? {

        // This all fixes a bug, where accepting a completion suggestion erases everything before the cursor
        //
        // The LSP does not fill out the textEdit field properly (despite receiving insertReplaceSupport during init).
        // This makes LSP4IJ try to find the current semantic token under the caret, using its text range
        // to insert the completion into.
        // However, whenever a letter is typed, the whole semantic token map is invalidated and cleared out.
        // The map doesn't get repopulated until the completion popup is closed, and the token text range retrieval
        // method instead returns a range referring to the whole file.
        // The start of this range (0) ends up as the prefix offset for the completion, so applying the completion
        // inserts it starting from offset 0 (i.e. the start of the file), erasing everything before the caret.
        //
        // When we don't get a TextEdit from the LSP, we can create our own using the IDE's PSI elements.
        // This tells LSP4IJ that we're _certain_ about what we want to replace, bypassing the bug in the logic
        // where it would try to figure this out based on the position of the caret.

        if (item.textEdit == null) {
            // The PSI element comes from the "completion file", and has a dummy suffix appended to it.
            // Its reported length is about 19 characters longer, so the TextEdit end position is set
            // until the current cursor position.
            // When working with PSI elements, it's better to use the provided methods
            // (in this case, replacing the element with a new one would be better).
            // This is simply bolted onto the existing logic, but could be reworked by handling
            // the replacement part later on too.

            // Find the start of the range to replace by walking back over the identifier prefix already
            // typed before the caret. We deliberately compute this from the document text rather than from
            // context.parameters.position.startOffset: relying on the PSI position offset here was unreliable
            // during typing (see the semantic-token-map note above), producing a (0,0)->caret replace range
            // that dumped the accepted suggestion at the top of the file and erased everything before the
            // caret. Scanning the document text is PSI-independent and robust.
            val document = context.parameters.position.containingFile.fileDocument
            val elementEnd = context.parameters.offset
            val chars = document.charsSequence
            var elementStart = elementEnd
            while (elementStart > 0 && isLeanIdentifierPrefixChar(chars[elementStart - 1])) {
                elementStart--
            }

            // Char offset -> (line:char). startPos and endPos are both at or before the caret, on the caret's
            // line, so they are identical in the completion-copy and original document coordinates.
            val lineNum = document.getLineNumber(elementEnd)
            val lineOffset = document.getLineStartOffset(lineNum)

            val startPos = org.eclipse.lsp4j.Position(lineNum, elementStart - lineOffset)
            val endPos = org.eclipse.lsp4j.Position(lineNum, elementEnd - lineOffset)

            // Use an InsertReplaceEdit, not a plain TextEdit: we announce insertReplaceSupport in
            // Lean4LSPClientFeatures.initializeParams, so when this item is sent back to the server for
            // completionItem/resolve the Lean server decodes textEdit as an InsertReplaceEdit. A plain
            // TextEdit (range+newText) then fails with "Lean.Lsp.InsertReplaceEdit.insert ... Natural
            // number expected". Both insert and replace cover the typed prefix [start, cursor].
            val range = org.eclipse.lsp4j.Range(startPos, endPos)
            item.textEdit = Either.forRight(InsertReplaceEdit(item.label, range, range))
        }
        return super.createLookupElement(item, context)
    }

    /**
     * Characters that can be part of the identifier prefix being completed. Excludes `.` so member
     * completion (e.g. `g.|`) replaces only the segment after the dot, and excludes whitespace/operators.
     * Covers ASCII identifiers, primes, and subscript digits (Lean allows e.g. `x₁`); unicode letters such
     * as Greek are handled by [Char.isLetterOrDigit].
     */
    private fun isLeanIdentifierPrefixChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '\'' || c in '₀'..'₉'
}