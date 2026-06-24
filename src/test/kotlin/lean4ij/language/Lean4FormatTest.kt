package lean4ij.language

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the conservative formatter transform (no IntelliJ platform needed). */
class Lean4FormatTest {

    @Test
    fun sortsConsecutiveImports() {
        val input = "import Z\nimport A\nimport M\n\ndef f := 1\n"
        val expected = "import A\nimport M\nimport Z\n\ndef f := 1\n"
        assertEquals(expected, Lean4Format.normalize(input))
    }

    @Test
    fun collapsesBlankLinesAndTrailingWhitespace() {
        val input = "def a := 1   \n\n\n\ndef b := 2\n"
        val expected = "def a := 1\n\ndef b := 2\n"
        assertEquals(expected, Lean4Format.normalize(input))
    }

    @Test
    fun leavesTacticBodiesUntouched() {
        val input = "theorem t : x = x := by\n  rfl\n"
        assertEquals(input, Lean4Format.normalize(input))
    }

    @Test
    fun doesNotReorderImportsSeparatedByOtherLines() {
        val input = "import Z\nopen Foo\nimport A\n"
        // two separate import runs of length 1 each → no reordering
        assertEquals(input, Lean4Format.normalize(input))
    }
}
