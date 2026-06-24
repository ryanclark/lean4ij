package lean4ij.language

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import lean4ij.language.psi.TokenType

/**
 * Folds multi-line command bodies (the part after a declaration's name/keyword) and multi-line block/doc
 * comments. Built on the skeleton PSI, so it never needs to understand term/tactic syntax.
 */
class Lean4FoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val out = ArrayList<FoldingDescriptor>()
        for (command in PsiTreeUtil.findChildrenOfType(root, Lean4Command::class.java)) {
            val head = PsiTreeUtil.findChildOfType(command, Lean4Definition::class.java)
                ?: Lean4PsiUtil.keywordElement(command) ?: continue
            val start = head.textRange.endOffset
            val end = lastMeaningfulEnd(command)
            if (end <= start) continue
            if (document.getLineNumber(start) == document.getLineNumber(end)) continue
            out.add(FoldingDescriptor(command.node, TextRange(start, end)))
        }
        for (comment in PsiTreeUtil.findChildrenOfType(root, PsiComment::class.java)) {
            val r = comment.textRange
            if (r.length > 0 && document.getLineNumber(r.startOffset) != document.getLineNumber(r.endOffset)) {
                out.add(FoldingDescriptor(comment.node, r))
            }
        }
        return out.toTypedArray()
    }

    /** End offset of the last non-whitespace token of [command] (so trailing blank lines aren't folded). */
    private fun lastMeaningfulEnd(command: PsiElement): Int {
        var child: PsiElement? = command.lastChild
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE) return child.textRange.endOffset
            child = child.prevSibling
        }
        return command.textRange.endOffset
    }

    override fun getPlaceholderText(node: ASTNode): String = " ⋯"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
