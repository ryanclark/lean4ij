package lean4ij.language

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Auto-dedent for Lean closing brackets. The skeleton formatter is a no-op (Lean keeps WHITE_SPACE as a real
 * PSI token for hover), so closers don't realign on their own. When the user types `)` / `]` / `}` / `⟩` as the
 * first non-whitespace on a line, this snaps that line's indent to the line holding the matching opener (the
 * standard editor behavior). Matching is delegated to the pure, unit-tested [Lean4Indent.matchingOpenerIndent].
 *
 * Registered application-wide via the `typedHandler` EP, so it guards on the file type itself.
 */
class Lean4TypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.fileType != Lean4FileType) return Result.CONTINUE
        if (c != ')' && c != ']' && c != '}' && c != '⟩') return Result.CONTINUE

        val document = editor.document
        val closerOffset = editor.caretModel.offset - 1 // the char just typed
        val text = document.immutableCharSequence
        if (closerOffset < 0 || closerOffset >= text.length || text[closerOffset] != c) return Result.CONTINUE

        // Only realign when the closer is the first non-whitespace on its line.
        val lineStart = document.getLineStartOffset(document.getLineNumber(closerOffset))
        val before = text.subSequence(lineStart, closerOffset)
        if (before.isNotBlank()) return Result.CONTINUE

        val target = Lean4Indent.matchingOpenerIndent(text, closerOffset) ?: return Result.CONTINUE
        if (before.toString() == target) return Result.CONTINUE

        document.replaceString(lineStart, closerOffset, target)
        editor.caretModel.moveToOffset(lineStart + target.length + 1) // past the closer
        return Result.STOP
    }
}
