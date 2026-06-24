package lean4ij.language

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Smart Enter indentation for Lean. The skeleton formatter is intentionally a no-op (Lean keeps WHITE_SPACE as
 * a real PSI token for infoview hover, which the block-indent engine can't manage), so the platform's default
 * Enter only copies the previous line's indent. This delegate adds one level after a block-opener
 * (`:=` / `by` / `where` / `do` / `=>` / ...), driving the decision through the pure, unit-tested [Lean4Indent].
 *
 * Registered application-wide via the `enterHandlerDelegate` EP, so it guards on the file type itself.
 */
class Lean4EnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (file.fileType != Lean4FileType) return EnterHandlerDelegate.Result.Continue

        val document = editor.document
        val caretLine = document.getLineNumber(editor.caretModel.offset)
        if (caretLine == 0) return EnterHandlerDelegate.Result.Continue

        // The previous line is the one we just split off; its tail decides whether to add a level.
        val prevText = document.getText(
            TextRange(document.getLineStartOffset(caretLine - 1), document.getLineEndOffset(caretLine - 1))
        )
        val target = Lean4Indent.newLineIndent(prevText)

        // Replace whatever leading whitespace the default Enter produced with the deterministic target.
        val lineStart = document.getLineStartOffset(caretLine)
        val lineEnd = document.getLineEndOffset(caretLine)
        val existing = Lean4Indent.leadingIndent(document.getText(TextRange(lineStart, lineEnd)))
        if (existing != target) {
            document.replaceString(lineStart, lineStart + existing.length, target)
        }
        editor.caretModel.moveToOffset(lineStart + target.length)
        return EnterHandlerDelegate.Result.Stop
    }
}
