package lean4ij.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import lean4ij.setting.Lean4Settings
import lean4ij.language.psi.TokenType
import lean4ij.language.psi.TokenType.WHITE_SPACE


/**
 * TODO use customized textAttributes
 */
class Lean4SyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        // Operator/symbol color (`:=`, `=>`, `→`, `=`, comparison/arith symbols, `*`, `∀`). Falls back to
        // OPERATION_SIGN, so it inherits the active theme's operator color (no bundled default); themeable via
        // LeanColorSettingsPage.
        val LEAN_OPERATOR: TextAttributesKey = createTextAttributesKey("LEAN_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)

    // The keys and their single-element arrays are interned/immutable, so keep them in the companion: a fresh
    // Lean4SyntaxHighlighter is created per getSyntaxHighlighter call, which would otherwise re-allocate ~15
    // single-element arrays each time.
    val SEPARATOR: TextAttributesKey = createTextAttributesKey("LEAN_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val KEY: TextAttributesKey = createTextAttributesKey("LEAN_KEY", DefaultLanguageHighlighterColors.KEYWORD)
    val VALUE: TextAttributesKey = createTextAttributesKey("LEAN_VALUE", DefaultLanguageHighlighterColors.STRING)
    val COMMENT: TextAttributesKey = createTextAttributesKey("LEAN_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val NUMBER: TextAttributesKey = createTextAttributesKey("LEAN_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val SORRY : TextAttributesKey = createTextAttributesKey("LEAN_SORRY", DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
    val KEYWORD_IN_PROOF : TextAttributesKey = createTextAttributesKey("LEAN_KEYWORD_IN_PROOF", DefaultLanguageHighlighterColors.KEYWORD)

    val BAD_CHAR_KEYS: Array<TextAttributesKey> = arrayOf(BAD_CHARACTER)
    val SEPARATOR_KEYS: Array<TextAttributesKey> = arrayOf(SEPARATOR)
    val KEY_KEYS: Array<TextAttributesKey> = arrayOf(KEY)
    val VALUE_KEYS: Array<TextAttributesKey> = arrayOf(VALUE)
    val COMMENT_KEYS: Array<TextAttributesKey> = arrayOf(COMMENT)
    val NUMBER_KEYS: Array<TextAttributesKey> = arrayOf(NUMBER)
    val EMPTY_KEYS: Array<TextAttributesKey> = arrayOf()
    val SORRY_KEYS: Array<TextAttributesKey> = arrayOf(SORRY)
    val KEYWORD_IN_PROOF_KEYS: Array<TextAttributesKey> = arrayOf(KEYWORD_IN_PROOF)
    // Declaration-name colors reuse the resolution annotator's keys so decl + usage match (teal/blue/purple).
    val TYPE_NAME_KEYS: Array<TextAttributesKey> = arrayOf(LeanConstReferenceAnnotator.LEAN_TYPE)
    val DEF_NAME_KEYS: Array<TextAttributesKey> = arrayOf(LeanConstReferenceAnnotator.LEAN_DEFINITION)
    val THEOREM_NAME_KEYS: Array<TextAttributesKey> = arrayOf(LeanConstReferenceAnnotator.LEAN_THEOREM)
    val OPERATOR_KEYS: Array<TextAttributesKey> = arrayOf(LEAN_OPERATOR)
    }


    override fun getHighlightingLexer(): Lexer {
        return Lean4LexerAdapter()
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        if (tokenType == null) {
            return emptyArray()
        }
        if (tokenType == TokenType.KEYWORD_COMMAND1 ||
            tokenType == TokenType.KEYWORD_COMMAND2 ||
            tokenType == TokenType.KEYWORD_COMMAND3 ||
            tokenType == TokenType.KEYWORD_COMMAND4 ||
            tokenType == TokenType.KEYWORD_COMMAND5 ||
            tokenType == TokenType.KEYWORD_MODIFIER ||
            tokenType == TokenType.DEFAULT_TYPE ||
            tokenType == TokenType.KEYWORD_COMMAND_PREFIX
            ) {
            return KEY_KEYS;
        }
        if (tokenType == TokenType.KEYWORD_COMMAND6) {
            return KEYWORD_IN_PROOF_KEYS
        }
        if (tokenType == TokenType.LINE_COMMENT || tokenType == TokenType.BLOCK_COMMENT || tokenType == TokenType.DOC_COMMENT) {
            return COMMENT_KEYS;
        }
        if (tokenType == TokenType.NUMBER||tokenType==TokenType.NEGATIVE_NUMBER) {
            return NUMBER_KEYS;
        }
        if (tokenType == TokenType.KEYWORD_SORRY) {
            return SORRY_KEYS;
        }
        // Lexer-colored declaration names: the kind comes from the declaring keyword, not capitalization.
        if (tokenType == TokenType.TYPE_NAME) {
            return TYPE_NAME_KEYS
        }
        if (tokenType == TokenType.DEF_NAME) {
            return DEF_NAME_KEYS
        }
        if (tokenType == TokenType.THEOREM_NAME) {
            return THEOREM_NAME_KEYS
        }
        // String literals.
        if (tokenType == TokenType.STRING) {
            return VALUE_KEYS
        }
        // Operators and symbols use LEAN_OPERATOR. This includes the structural brackets, parens, braces,
        // commas, dots, and `@`, as well as `:=`, `=>`, `->`, `→`, `=`, comparison/arithmetic symbols,
        // `*`, and `∀`.
        if (tokenType == TokenType.ASSIGN ||
            tokenType == TokenType.EQUAL ||
            tokenType == TokenType.COLON ||
            tokenType == TokenType.VERTICAL_BAR ||
            tokenType == TokenType.LEFT_BRACE ||
            tokenType == TokenType.RIGHT_BRACE ||
            tokenType == TokenType.LEFT_PAREN ||
            tokenType == TokenType.RIGHT_PAREN ||
            tokenType == TokenType.LEFT_BRACKET ||
            tokenType == TokenType.RIGHT_BRACKET ||
            tokenType == TokenType.LEFT_UNI_BRACKET ||
            tokenType == TokenType.RIGHT_UNI_BRACKET ||
            tokenType == TokenType.COMMA ||
            tokenType == TokenType.DOT ||
            tokenType == TokenType.AT ||
            tokenType == TokenType.MISC_ARROW_SYM ||
            tokenType == TokenType.MISC_COMPARISON_SYM ||
            tokenType == TokenType.MISC_PLUS_SYM ||
            tokenType == TokenType.MISC_MULTIPLY_SYM ||
            tokenType == TokenType.MISC_EXPONENT_SYM ||
            tokenType == TokenType.STAR ||
            tokenType == TokenType.FOR_ALL
            ) {
            return OPERATOR_KEYS
        }
        return EMPTY_KEYS;
    }
}

class Lean4SyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return Lean4SyntaxHighlighter()
    }
}

/**
 * ref: https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html
 * TODO use customized text attributes
 */
class Lean4Annotator : Annotator, DumbAware {
    private val lean4Settings = service<Lean4Settings>()

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Declaration-name coloring (def / theorem / structure names) is owned by LeanConstReferenceAnnotator,
        // not done here. It is kind-aware and stays consistent at declaration and usage: types use the type
        // color, def uses FUNCTION_DECLARATION, theorem uses CONSTANT. Coloring every declaration name as
        // FUNCTION_DECLARATION here would miscolor `structure Region` and `theorem foo` names.
        if (element.parent is Lean4Attributes) {
            if (!lean4Settings.enableHeuristicAttributes) return
            // check the parent rather than the element itself for skipping comments
            if (element.node.elementType == TokenType.IDENTIFIER) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(element.textRange).textAttributes(DefaultLanguageHighlighterColors.METADATA).create();
            }
            if (element.node.elementType == TokenType.ATTRIBUTE) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(element.textRange).textAttributes(DefaultLanguageHighlighterColors.KEYWORD).create();
            }
        } else if (element.node.elementType == TokenType.IDENTIFIER) {
            // Field heuristic only. Tactic coloring is owned entirely by LeanConstReferenceAnnotator: its single
            // text-scan classifies constructors / defs / theorems / types before tactics, so a tactic-named
            // constructor (`clear`, `cases`) or a `.clear` accessor keeps its symbol color instead of being
            // painted a tactic. Adding tactic coloring here (with no such priority) would let a tactic-named
            // constructor disagree with its siblings. isField requires a trailing `:`/`:=`, so a line-leading
            // tactic application is not mistaken for a field.
            if (isField(element)) {
                if (!lean4Settings.enableHeuristicField) return
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(element.textRange).textAttributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD).create();
            }
        }
    }

    private fun isField(element: PsiElement): Boolean {
        // A real field/binder declaration is a line-leading name followed by `:` or `:=`
        // (`cmdStart : BytePos`, `floor := ...`). The nextSiblingIsAssign half is required: without it every
        // line-leading identifier is treated as a field, so a line-leading function/def/theorem application
        // (`openGluedOutput st l = ...`, `attribution_persists pre post ...`) is painted INSTANCE_FIELD. That
        // races LeanConstReferenceAnnotator's LEAN_DEFINITION/LEAN_THEOREM at equal INFORMATION severity and
        // can win non-deterministically. Requiring the trailing `:`/`:=` restricts this to genuine declarations
        // and lets the resolution annotator color line-leading references.
        return prevSiblingIsNewLine(element) && nextSiblingIsAssign(element)
    }

    private fun prevSiblingIsNewLine(element: PsiElement): Boolean {
        val prevElement = element.prevSibling?:return false
        return prevElement.elementType == WHITE_SPACE && prevElement.text.contains('\n')
    }

    private fun nextSiblingIsAssign(element: PsiElement): Boolean {
        var nextValidElement : PsiElement? = element.nextSibling
        while (!isValid(nextValidElement)) {
            nextValidElement = nextValidElement?.nextSibling
        }
        val elementType = nextValidElement?.node?.elementType
        return elementType == TokenType.ASSIGN || elementType == TokenType.COLON
    }

    private fun isValid(element: PsiElement?): Boolean {
        return element?.node?.elementType != WHITE_SPACE && element?.node?.elementType != TokenType.PLACEHOLDER;
    }
}
