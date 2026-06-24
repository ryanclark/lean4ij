package lean4ij.lsp.data

import com.google.gson.reflect.TypeToken
import lean4ij.lsp.LeanLanguageServer
import lean4ij.test.readResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the hand-written gson deserialization of the TaggedText / MsgEmbed /
 * StrictOrLazy widget trees (the discriminated-union JSON from `$/lean/rpc/call`). The rendering tests in
 * CodeWithInfosCharacterizationTest build objects directly; these instead feed REAL LSP JSON fixtures through
 * LeanLanguageServer.gson, so they protect the by-JSON-key discrimination in
 * LeanLanguageServer.registerTaggedText (tag[]/append[]/text), the MsgEmbed (expr/goal/trace/widget) adapter,
 * the List<TaggedText<T>> adapter, and the StrictOrLazy (strict/lazy) adapter against changes to the
 * TaggedText hierarchy (e.g. making it `sealed`, which discriminates here, not in gson).
 *
 * Plain JUnit (no BasePlatformTestCase / Application): gson deserialization + toInfoObjectModel().toString()
 * need no live IDE, as the existing MiniInfoview characterization test already demonstrates.
 */
class CodeWithInfosDeserializationTest {

    /** Recursively count the concrete TaggedText subtypes in a tree (proves tag/append/text discrimination). */
    private fun <T : InfoViewContent> counts(t: TaggedText<T>, acc: IntArray = IntArray(3)): IntArray {
        when (t) {
            is TaggedTextText -> acc[0]++
            is TaggedTextTag -> { acc[1]++; counts(t.f1, acc) }
            is TaggedTextAppend -> { acc[2]++; t.append.forEach { counts(it, acc) } }
        }
        return acc
    }

    @Test
    fun interactiveGoals_sample_deserializesAndRenders() {
        val s = readResource("lsp/interactiveGoals_sample.json")
        val goals: InteractiveGoals =
            LeanLanguageServer.gson.fromJson(s, object : TypeToken<InteractiveGoals>() {}.type)

        assertTrue("expected at least one goal", goals.goals.isNotEmpty())
        // Each goal's type is a TaggedText<SubexprInfo> reconstructed by registerTaggedText<SubexprInfo>.
        val type = goals.goals[0].type
        val c = counts(type)
        assertTrue("type tree must have text/tag/append nodes", c.sum() > 0)
        assertTrue("type tree must contain a text leaf", c[0] > 0)

        // The whole tactic-state render is non-empty and (for this fixture) carries the known goal text.
        val render = goals.toInfoObjectModel().toString()
        assertTrue("render not empty", render.isNotBlank())
        assertTrue("render contains the fixture's goal text, got: $render", render.contains("a * (w * w')"))
    }

    @Test
    fun lazyTraceChildren_sample_deserializesToFiveMsgEmbedTrees() {
        val s = readResource("lsp/lazyTraceChildrenToInteractive_resp_sample1.json")
        // Exercises the List<TaggedText<MsgEmbed>> adapter + registerTaggedText<MsgEmbed> + the MsgEmbed adapter.
        val trees: List<TaggedText<MsgEmbed>> =
            LeanLanguageServer.gson.fromJson(s, object : TypeToken<List<TaggedText<MsgEmbed>>>() {}.type)
        assertEquals(5, trees.size)
        for (tree in trees) {
            val c = counts(tree)
            assertTrue("each tree has nodes", c.sum() > 0)
            // Rendering must not throw and produce a (possibly empty for some) string.
            tree.toInfoObjectModel().toString()
        }
    }

    @Test
    fun msgEmbedTrace_sample_deserializesToTrace() {
        val s = readResource("lsp/msgEmbedTrace_sample1.json")
        // Exercises the MsgEmbed 'trace' branch + MsgEmbedTrace reflective fields + StrictOrLazy adapter.
        val embed: MsgEmbed =
            LeanLanguageServer.gson.fromJson(s, object : TypeToken<MsgEmbed>() {}.type)
        assertTrue("expected a MsgEmbedTrace, got ${embed::class.simpleName}", embed is MsgEmbedTrace)
        // contextInfo() is the documented-null path for a trace; rendering must not throw.
        assertEquals(null, embed.contextInfo(0, 0, 0))
        assertTrue(embed.toInfoObjectModel().toString().isNotBlank())
    }
}
