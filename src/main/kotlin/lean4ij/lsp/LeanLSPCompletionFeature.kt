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
            // typed before the caret. We must NOT use context.parameters.position.startOffset: since the
            // native Lean parser was removed, .lean files are TextMate/plain-text backed and that element is
            // the whole-file leaf, so its startOffset is 0. That made the replace range (0,0)->caret, which
            // dumped the accepted suggestion at the top of the file and erased everything before the caret.
            // Scanning the document text is PSI-independent and works for TextMate/plain/native alike.
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