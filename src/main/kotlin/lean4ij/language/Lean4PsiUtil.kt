package lean4ij.language

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import lean4ij.language.psi.TokenType

/** Helpers over the skeleton PSI ([Lean4Command] / [Lean4Definition]). */
object Lean4PsiUtil {
    private val COMMAND_KEYWORDS = setOf(
        TokenType.KEYWORD_COMMAND1, TokenType.KEYWORD_COMMAND2, TokenType.KEYWORD_COMMAND3,
        TokenType.KEYWORD_COMMAND4, TokenType.KEYWORD_COMMAND5,
    )

    /** The declared name of a command (its [Lean4Definition]), or null for commands without one. */
    fun declName(command: Lean4Command): String? =
        PsiTreeUtil.findChildOfType(command, Lean4Definition::class.java)?.text?.trim()?.takeIf { it.isNotEmpty() }

    /** The command keyword element (def/theorem/import/namespace/...), or null. */
    fun keywordElement(command: Lean4Command): PsiElement? {
        var child: PsiElement? = command.firstChild
        while (child != null) {
            if (child.elementType in COMMAND_KEYWORDS) return child
            child = child.nextSibling
        }
        return null
    }

    /** The command keyword text, or null. */
    fun keywordText(command: Lean4Command): String? = keywordElement(command)?.text
}
