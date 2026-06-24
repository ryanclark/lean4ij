package lean4ij.infoview

import lean4ij.lsp.data.MsgEmbed
import lean4ij.lsp.data.MsgEmbedExpr
import lean4ij.lsp.data.SubexprInfo
import lean4ij.lsp.data.TaggedText
import lean4ij.lsp.data.TaggedTextAppend
import lean4ij.lsp.data.TaggedTextTag
import lean4ij.lsp.data.TaggedTextText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for the pure prose/code segmentation of a Lean interactive diagnostic message
 * ([TaggedText]<[MsgEmbed]>). This is the part that decides which spans render as plain prose and which
 * render as syntax-highlighted Lean code in the editor diagnostic tooltip. It is platform-free so it runs
 * as a plain JUnit test (same as [lean4ij.infoview.dsl.DslKtTest]).
 */
class InteractiveDiagnosticHtmlTest {

    /** Build a code embed wrapping a Lean expression whose flattened text is [code]. */
    private fun expr(code: String): TaggedTextTag<MsgEmbed> =
        TaggedTextTag(MsgEmbedExpr(TaggedTextText<SubexprInfo>(code)), TaggedTextText(""))

    @Test
    fun testSegmentMessageSplitsProseAndCode() {
        // Mirrors a Lean "type mismatch" message: prose interleaved with code expressions.
        val message: TaggedText<MsgEmbed> = TaggedTextAppend(
            listOf(
                TaggedTextText("type mismatch\n  "),
                expr("h"),
                TaggedTextText("\nhas type\n  "),
                expr("Inv st"),
                TaggedTextText("\nbut is expected to have type\n  "),
                expr("Inv (if c then a else b)"),
            )
        )

        val segments = segmentMessage(message)

        assertEquals(
            listOf(
                DiagSegment.Prose("type mismatch\n  "),
                DiagSegment.Code("h"),
                DiagSegment.Prose("\nhas type\n  "),
                DiagSegment.Code("Inv st"),
                DiagSegment.Prose("\nbut is expected to have type\n  "),
                DiagSegment.Code("Inv (if c then a else b)"),
            ),
            segments,
        )
    }

    @Test
    fun testSegmentMessageNestedTagAndEmptyTextDropped() {
        // A bare prose leaf, and a tag whose trailing sub-text is empty (the common Lean shape) must not
        // produce spurious empty prose segments.
        val message: TaggedText<MsgEmbed> = TaggedTextTag(
            MsgEmbedExpr(TaggedTextText<SubexprInfo>("x")),
            TaggedTextText(" : Nat"),
        )

        val segments = segmentMessage(message)

        assertEquals(
            listOf(
                DiagSegment.Code("x"),
                DiagSegment.Prose(" : Nat"),
            ),
            segments,
        )
    }
}
