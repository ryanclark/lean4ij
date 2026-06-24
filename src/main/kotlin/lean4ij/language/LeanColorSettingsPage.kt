package lean4ij.language

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Lets users theme lean4ij's editor symbol colors (Settings | Editor | Color Scheme | Lean). Each color
 * defaults to the semantically matching standard color (class / function declaration / constant), so it
 * inherits the theme like any other language; override here if you want something specific (e.g. green types).
 */
class LeanColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Lean"
    override fun getIcon(): Icon? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()
    override fun getDemoText(): String = DEMO
    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey> = TAGS

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Structure", LeanConstReferenceAnnotator.LEAN_STRUCTURE),
            AttributesDescriptor("Inductive", LeanConstReferenceAnnotator.LEAN_INDUCTIVE),
            AttributesDescriptor("Class (type class)", LeanConstReferenceAnnotator.LEAN_CLASS),
            AttributesDescriptor("Type reference (project / other types)", LeanConstReferenceAnnotator.LEAN_TYPE),
            AttributesDescriptor("Built-in / stdlib type (Option, List, Nat ...)", LeanConstReferenceAnnotator.LEAN_BUILTIN_TYPE),
            AttributesDescriptor("Definition (def / abbrev / instance)", LeanConstReferenceAnnotator.LEAN_DEFINITION),
            AttributesDescriptor("Imported / stdlib function (le, bmax, List.foldl ...)", LeanConstReferenceAnnotator.LEAN_IMPORTED_FUNCTION),
            AttributesDescriptor("Theorem / lemma", LeanConstReferenceAnnotator.LEAN_THEOREM),
            AttributesDescriptor("Constructor (none / some / inductive ctors)", LeanConstReferenceAnnotator.LEAN_CONSTRUCTOR),
            AttributesDescriptor("Tactic (obtain / cases / simp ...)", LeanConstReferenceAnnotator.LEAN_TACTIC),
            AttributesDescriptor("Operator (:= => -> = * ∀ ...)", Lean4SyntaxHighlighter.LEAN_OPERATOR),
            AttributesDescriptor("Inline `code` in comments", LeanConstReferenceAnnotator.LEAN_COMMENT_CODE),
        )
        private val TAGS: MutableMap<String, TextAttributesKey> = mutableMapOf(
            "struct" to LeanConstReferenceAnnotator.LEAN_STRUCTURE,
            "ind" to LeanConstReferenceAnnotator.LEAN_INDUCTIVE,
            "cls" to LeanConstReferenceAnnotator.LEAN_CLASS,
            "type" to LeanConstReferenceAnnotator.LEAN_TYPE,
            "builtin" to LeanConstReferenceAnnotator.LEAN_BUILTIN_TYPE,
            "def" to LeanConstReferenceAnnotator.LEAN_DEFINITION,
            "impfn" to LeanConstReferenceAnnotator.LEAN_IMPORTED_FUNCTION,
            "ccode" to LeanConstReferenceAnnotator.LEAN_COMMENT_CODE,
            "thm" to LeanConstReferenceAnnotator.LEAN_THEOREM,
            "ctor" to LeanConstReferenceAnnotator.LEAN_CONSTRUCTOR,
            "tac" to LeanConstReferenceAnnotator.LEAN_TACTIC,
        )
        private val DEMO = """
            structure <struct>Region</struct> where
              start : <builtin>Nat</builtin>
              stop  : <builtin>Nat</builtin>

            inductive <ind>Color</ind> where
              | <ctor>red</ctor> | <ctor>green</ctor> | <ctor>blue</ctor>

            class <cls>Widthy</cls> (a : <type>Type</type>) where
              width : a -> <builtin>Nat</builtin>

            def <def>parseRegion</def> (lo hi : <builtin>Nat</builtin>) : <builtin>Option</builtin> <struct>Region</struct> :=
              <ctor>some</ctor> { start := lo, stop := hi }

            theorem <thm>region_width_nonneg</thm> (r : <struct>Region</struct>) : True := by
              <tac>simp</tac> [<impfn>le_trans</impfn>, <thm>region_bound</thm>]
        """.trimIndent()
    }
}
