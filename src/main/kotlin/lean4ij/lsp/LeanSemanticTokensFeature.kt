package lean4ij.lsp

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature

/**
 * Tunes how lsp4ij paints the Lean server's semantic tokens, for two reasons.
 *
 * 1. [shouldVisitPsiElement] = false forces lsp4ij's "direct" (PSI-independent) paint path, so semantic-token
 *    coloring does not depend on lsp4ij walking the native Lean PSI.
 *
 * 2. [getTextAttributesKey] makes the semantic-token layer DEFER to [lean4ij.language.LeanConstReferenceAnnotator]
 *    for everything that is a real symbol (type / def / theorem reference), so a given symbol has ONE consistent
 *    color at its declaration and at its usages.
 *
 *    The Lean server's token collector only matches `Expr.fvar` (verified in lean4
 *    src/Lean/Server/FileWorker/SemanticHighlighting.lean): it emits NO token for a declaration name, for a global
 *    constant reference (`Nat`), or for a `def`/`theorem`/type-decl name. The only `function`-typed token it ever
 *    produces is the self-reference of a recursive definition (an auxiliary binder). If we let lsp4ij paint that
 *    with its default FUNCTION_CALL color, the recursive self-call would differ from the (annotator-painted)
 *    declaration and external call sites, reintroducing the very decl-vs-usage inconsistency we're removing. So we
 *    return null for the type- and function-family token types, which makes lsp4ij skip painting entirely (it only
 *    paints when the key is non-null), leaving the annotator authoritative for those symbols. `keyword`,
 *    `variable` (locals), `property` (fields) and the rest keep their default lsp4ij colors via `super`.
 */
class LeanSemanticTokensFeature : LSPSemanticTokensFeature() {
    override fun shouldVisitPsiElement(file: PsiFile): Boolean = false

    override fun getTextAttributesKey(
        tokenType: String,
        tokenModifiers: List<String>,
        file: PsiFile,
    ): TextAttributesKey? = when (tokenType) {
        // Symbol references are owned by LeanConstReferenceAnnotator (teal types / blue defs / purple theorems),
        // so a symbol is colored identically at its declaration and its usages. Returning null makes lsp4ij skip
        // painting these tokens, leaving the annotator's color in place (lsp4ij only paints when the key != null).
        //  - type family: Lean never emits these; nulled defensively.
        //  - function/method: the only real occurrence is a recursive def's self-binder; it must NOT fall through
        //    to super (FUNCTION_CALL), which would differ from the annotator's LEAN_DEFINITION on the decl/calls.
        "type", "class", "struct", "enum", "interface", "typeParameter", "namespace" -> null
        "function", "method" -> null
        // Binder parameters / hypotheses and other locals -> the PARAMETER color (orange in Material Darker),
        // matching how Go colors function arguments (they were left white before). The Lean server tags fvars
        // as `variable`/`parameter`; both go orange, at declaration and at every usage.
        "variable", "parameter" -> DefaultLanguageHighlighterColors.PARAMETER
        // keyword / property (fields) / leanSorryLike / ... keep their default lsp4ij colors.
        else -> super.getTextAttributesKey(tokenType, tokenModifiers, file)
    }
}
