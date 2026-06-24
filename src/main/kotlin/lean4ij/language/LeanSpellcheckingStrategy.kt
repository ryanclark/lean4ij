package lean4ij.language

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import lean4ij.util.LeanUtil

/**
 * Turns IntelliJ's spell-checker OFF for Lean files.
 *
 * Registered as `<spellchecker.support language="lean4">`. Without it the native PSI would feed every
 * IDENTIFIER token to the platform spell-checker, which flags identifiers (`eprintln`, `cmds`, ...) as
 * "Typo": pure noise on code. [isMyContext] claims only Lean files and [getTokenizer] hands back the
 * [EMPTY_TOKENIZER] so nothing in them is spell-checked; other files fall through to the default strategy.
 */
class LeanSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun isMyContext(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        return LeanUtil.isLeanFile(file)
    }

    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        return SpellcheckingStrategy.EMPTY_TOKENIZER
    }
}
