package lean4ij.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure unit tests for the Enter-handler indentation decision (no IntelliJ platform needed). */
class Lean4IndentTest {

    @Test
    fun indentsAfterAssignOnDef() {
        // The reported case: Enter after `... :=` should land one level in.
        assertEquals("  ", Lean4Indent.newLineIndent("def firstField (r : Region) : Nat :="))
    }

    @Test
    fun indentsAfterBy() {
        assertEquals("  ", Lean4Indent.newLineIndent("theorem t : x = x := by"))
    }

    @Test
    fun indentsAfterWhere() {
        assertEquals("  ", Lean4Indent.newLineIndent("structure Region where"))
    }

    @Test
    fun indentsAfterArrowAtColumnZero() {
        assertEquals("  ", Lean4Indent.newLineIndent("fun x =>"))
    }

    @Test
    fun nestsRelativeToExistingIndent() {
        assertEquals("    ", Lean4Indent.newLineIndent("  def g : Nat :="))
    }

    @Test
    fun copiesIndentWhenNoOpener() {
        assertEquals("  ", Lean4Indent.newLineIndent("  exact rfl"))
    }

    @Test
    fun noIndentForPlainColumnZeroLine() {
        assertEquals("", Lean4Indent.newLineIndent("namespace Demo"))
    }

    @Test
    fun wordOpenerMustBeWholeToken() {
        // `rugby` ends in "by" but is not the keyword — must not trigger an extra level.
        assertEquals("", Lean4Indent.newLineIndent("def rugby"))
    }

    @Test
    fun stripsTrailingLineCommentBeforeOpenerCheck() {
        assertEquals("  ", Lean4Indent.newLineIndent("def f := by -- start"))
    }

    @Test
    fun indentsAfterTrailingOpenParen() {
        assertEquals("  ", Lean4Indent.newLineIndent("def f ("))
    }

    @Test
    fun indentsAfterTrailingColonOnSignature() {
        // The reported case: a signature ending in `:` should indent the continuation (the return type).
        assertEquals("  ", Lean4Indent.newLineIndent("theorem coverage_wf (lines : List Line) (endp : BytePos) :"))
    }

    @Test
    fun midLineColonDoesNotIndent() {
        // A `(x : T)` binder mid-line ends with `)`, not `:`, so the colon opener must not fire.
        assertEquals("", Lean4Indent.newLineIndent("theorem foo (lines : List Line) (endp : BytePos)"))
    }

    // --- closing-bracket auto-dedent (matchingOpenerIndent) ---

    /** Offset of the last occurrence of [closer] in [text] (where the typed closer sits). */
    private fun closerAt(text: String, closer: Char): Int = text.lastIndexOf(closer)

    @Test
    fun closerAlignsToOpenerAtColumnZero() {
        val text = "def x := ⟨\n  a,\n  b\n⟩"
        assertEquals("", Lean4Indent.matchingOpenerIndent(text, closerAt(text, '⟩')))
    }

    @Test
    fun closerAlignsToNestedOpenerIndent() {
        // The `⟩` matches the `⟨` on the `  g ⟨` line (indent "  ").
        val text = "def f := (\n  g ⟨\n    x\n  ⟩\n)"
        assertEquals("  ", Lean4Indent.matchingOpenerIndent(text, closerAt(text, '⟩')))
    }

    @Test
    fun outerCloserAlignsToOuterOpener() {
        val text = "def f := (\n  g ⟨\n    x\n  ⟩\n)"
        assertEquals("", Lean4Indent.matchingOpenerIndent(text, closerAt(text, ')')))
    }

    @Test
    fun bracketInsideStringIsIgnored() {
        // The only `(` is inside the string literal, so the `)` has no real opener.
        val text = "def s := \"(\"\n)"
        assertNull(Lean4Indent.matchingOpenerIndent(text, closerAt(text, ')')))
    }

    @Test
    fun bracketInsideLineCommentIsIgnored() {
        // The real `(` opens at column 0; the `(` in the comment must not be counted.
        val text = "def f := ( -- (\n)"
        assertEquals("", Lean4Indent.matchingOpenerIndent(text, closerAt(text, ')')))
    }
}
