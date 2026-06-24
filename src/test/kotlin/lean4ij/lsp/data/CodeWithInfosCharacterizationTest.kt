package lean4ij.lsp.data

import lean4ij.infoview.Lean4TextAttributesKeys
import lean4ij.infoview.dsl.InfoObjectModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT rendering behavior of the infoview data-model
 * classes, so the upcoming TaggedText/CodeWithInfos sealed redesign can be proven
 * behavior-preserving.
 *
 * These tests are PURE: they build real model objects and assert on structure and on
 * [InfoObjectModel.toString] output (which only concatenates `text` + children text, never
 * applied highlighter attributes), so no Application/EditorColorsManager is required.
 *
 * Building the models does touch [Lean4TextAttributesKeys].key, i.e.
 * TextAttributesKey.createTextAttributesKey, which works without a live IDE (the existing
 * DslKtTest.testDslInteractiveGoals exercises the same path).
 */
class CodeWithInfosCharacterizationTest {

    // ---- small helpers for building real objects ----------------------------------------

    private fun ctx(p: String) = ContextInfo(p)

    /** A SubexprInfo with the given diffStatus (and a fixed ContextInfo). */
    private fun subexpr(diffStatus: String?, p: String = "1") =
        SubexprInfo(subexprPos = "/", info = ctx(p), diffStatus = diffStatus)

    private fun text(s: String): TaggedTextText<SubexprInfo> = TaggedTextText(s)

    private fun tag(f0: SubexprInfo, f1: TaggedText<SubexprInfo>): TaggedTextTag<SubexprInfo> =
        TaggedTextTag(f0, f1)

    private fun append(vararg c: TaggedText<SubexprInfo>): TaggedTextAppend<SubexprInfo> =
        TaggedTextAppend(c.toList())

    // =====================================================================================
    // TaggedTextText.toInfoObjectModel
    // =====================================================================================

    @Test
    fun taggedTextText_plain_rendersTextVerbatim() {
        val model = text("Nat").toInfoObjectModel()
        // info { p("Nat") } => outer empty-text model with one child "Nat"
        assertEquals("Nat", model.toString())
        assertEquals("", model.text)
        assertEquals(1, model.children.size)
        assertEquals("Nat", model.children[0].text)
        // plain `p(text)` carries no attributes
        assertTrue(model.children[0].attr.isEmpty())
    }

    @Test
    fun taggedTextText_empty_rendersEmpty() {
        val model = text("").toInfoObjectModel()
        assertEquals("", model.toString())
    }

    @Test
    fun taggedTextText_sorry_usesSorryAttributeButSameText() {
        val sorry = "declaration uses 'sorry'"
        val model = text(sorry).toInfoObjectModel()
        // the sorry branch routes through p(text, SwingInfoviewAllMessageSorryPos)
        assertEquals(sorry, model.toString())
        assertEquals(1, model.children.size)
        val child = model.children[0]
        assertEquals(sorry, child.text)
        assertEquals(1, child.attr.size)
        assertSame(Lean4TextAttributesKeys.SwingInfoviewAllMessageSorryPos.key, child.attr[0])
    }

    @Test
    fun taggedTextText_nonSorryNeverUsesSorryAttribute() {
        // a near-miss string must NOT take the sorry branch
        val model = text("uses sorry").toInfoObjectModel()
        assertEquals("uses sorry", model.toString())
        assertTrue(model.children[0].attr.isEmpty())
    }

    // =====================================================================================
    // SubexprInfo.toInfoObjectModel (diffStatus mapping)
    // =====================================================================================

    @Test
    fun subexprInfo_diffStatusMapping() {
        // null -> empty text, no attr
        val n = subexpr(null).toInfoObjectModel()
        assertEquals("", n.text)
        assertTrue(n.attr.isEmpty())

        // wasChanged -> empty text, InsertedText attr
        val was = subexpr("wasChanged").toInfoObjectModel()
        assertEquals("", was.text)
        assertEquals(1, was.attr.size)
        assertSame(Lean4TextAttributesKeys.InsertedText.key, was.attr[0])

        // willChange -> empty text, RemovedText attr
        val willChange = subexpr("willChange").toInfoObjectModel()
        assertEquals("", willChange.text)
        assertSame(Lean4TextAttributesKeys.RemovedText.key, willChange.attr[0])

        // willDelete -> empty text, RemovedText attr
        val willDelete = subexpr("willDelete").toInfoObjectModel()
        assertEquals("", willDelete.text)
        assertSame(Lean4TextAttributesKeys.RemovedText.key, willDelete.attr[0])
    }

    @Test
    fun subexprInfo_alwaysCarriesContextInfo() {
        // toInfoObjectModel must attach the SubexprInfo.info as the model contextInfo
        val info = ctx("42")
        val si = SubexprInfo(subexprPos = "/", info = info, diffStatus = null)
        val model = si.toInfoObjectModel()
        assertSame(info, model.contextInfo)
    }

    @Test
    fun subexprInfo_contextInfo_returnsTripleWithOffsets() {
        val info = ctx("7")
        val si = SubexprInfo(subexprPos = "/", info = info, diffStatus = "wasChanged")
        val triple = si.contextInfo(offset = 5, startOffset = 3, endOffset = 9)
        assertEquals(Triple(info, 3, 9), triple)
    }

    // =====================================================================================
    // TaggedTextTag.toInfoObjectModel
    // =====================================================================================

    @Test
    fun taggedTextTag_rendersF0ThenF1_andPropagatesAttrAndContext() {
        // tag(SubexprInfo(wasChanged), text("foo"))
        // f0 model => InfoObjectModel("" , InsertedText) with contextInfo = info
        // f1 model => info { p("foo") } => "foo"
        val info = ctx("11")
        val si = SubexprInfo(subexprPos = "/", info = info, diffStatus = "wasChanged")
        val model = tag(si, text("foo")).toInfoObjectModel()

        // text comes only from f1 (f0 text is "")
        assertEquals("foo", model.toString())

        // outer info{} model: addAttr(f0.attr) copies the InsertedText key up
        assertEquals(1, model.attr.size)
        assertSame(Lean4TextAttributesKeys.InsertedText.key, model.attr[0])

        // setContextInfo(f0.contextInfo) propagates the SubexprInfo.info
        assertSame(info, model.contextInfo)

        // structure: two children, the f0 model (empty) then the f1 model ("foo")
        assertEquals(2, model.children.size)
        assertEquals("", model.children[0].text)
        assertEquals("foo", model.children[1].toString())
    }

    @Test
    fun taggedTextTag_nullDiffStatus_hasNoPropagatedAttr() {
        val si = subexpr(null, p = "3")
        val model = tag(si, text("bar")).toInfoObjectModel()
        assertEquals("bar", model.toString())
        // f0 (null diffStatus) carries no attr, so nothing propagates up
        assertTrue(model.attr.isEmpty())
        assertSame(si.info, model.contextInfo)
    }

    // =====================================================================================
    // TaggedTextAppend.toInfoObjectModel
    // =====================================================================================

    @Test
    fun taggedTextAppend_concatenatesChildrenInOrder() {
        val model = append(text("a"), text("b"), text("c")).toInfoObjectModel()
        assertEquals("abc", model.toString())
        // three children, each an info{} wrapper around one text node
        assertEquals(3, model.children.size)
        assertEquals("a", model.children[0].toString())
        assertEquals("b", model.children[1].toString())
        assertEquals("c", model.children[2].toString())
    }

    @Test
    fun taggedTextAppend_empty_rendersEmpty() {
        val model = append().toInfoObjectModel()
        assertEquals("", model.toString())
        assertTrue(model.children.isEmpty())
    }

    @Test
    fun taggedTextAppend_nestedTagAndText() {
        // append( tag(subexpr, text("Setoid ")), text("X") ) => "Setoid X"
        val si = subexpr("willChange", p = "100")
        val model = append(tag(si, text("Setoid ")), text("X")).toInfoObjectModel()
        assertEquals("Setoid X", model.toString())
    }

    // =====================================================================================
    // MsgEmbedExpr.toInfoObjectModel
    // =====================================================================================

    @Test
    fun msgEmbedExpr_wrapsExprModel() {
        val expr: TaggedText<SubexprInfo> = append(text("a "), text("b"))
        val model = MsgEmbedExpr(expr).toInfoObjectModel()
        assertEquals("a b", model.toString())
    }

    // =====================================================================================
    // MsgEmbedTrace.toInfoObjectModel  (StrictOrLazy branching)
    // =====================================================================================

    private fun msgAppend(vararg c: TaggedText<MsgEmbed>): TaggedTextAppend<MsgEmbed> =
        TaggedTextAppend(c.toList())

    private fun msgText(s: String): TaggedTextText<MsgEmbed> = TaggedTextText(s)

    @Test
    fun msgEmbedTrace_strict_rendersClsThenMsg_noTriangle() {
        val msg = msgAppend(msgText("hello"))
        val trace = MsgEmbedTrace(
            indent = 0,
            cls = "Meta.synthInstance",
            collapsed = false,
            children = StrictOrLazyStrict(listOf()),
            msg = msg
        )
        val model = trace.toInfoObjectModel()
        // strict branch: "[cls] " + msg, and NO " ▶" trailing triangle
        assertEquals("[Meta.synthInstance] hello", model.toString())
    }

    @Test
    fun msgEmbedTrace_lazy_rendersClsThenMsgThenTriangle() {
        val msg = msgAppend(msgText("world"))
        val trace = MsgEmbedTrace(
            indent = 0,
            cls = "Meta.synthInstance",
            collapsed = true,
            children = StrictOrLazyLazy(ctx("9")),
            msg = msg
        )
        val model = trace.toInfoObjectModel()
        // lazy branch: "[cls] " + msg + " ▶"
        assertEquals("[Meta.synthInstance] world ▶", model.toString())
    }

    @Test
    fun msgEmbedTrace_contextInfo_returnsNull() {
        val trace = MsgEmbedTrace(
            indent = 0, cls = "c", collapsed = false,
            children = StrictOrLazyStrict(listOf()),
            msg = msgAppend(msgText(""))
        )
        assertNull(trace.contextInfo(0, 0, 0))
    }

    // =====================================================================================
    // MsgUnsupported.toInfoObjectModel
    // =====================================================================================

    @Test
    fun msgUnsupported_rendersMessageVerbatim() {
        val model = MsgUnsupported("unsupported msg").toInfoObjectModel()
        assertEquals("unsupported msg", model.toString())
        assertNull(MsgUnsupported("x").contextInfo(0, 0, 0))
    }

    // =====================================================================================
    // StrictOrLazy sealed-ish data classes (equality / branching)
    // =====================================================================================

    @Test
    fun strictOrLazy_dataClassEqualityAndBranching() {
        val strict: StrictOrLazy<List<String>, ContextInfo> = StrictOrLazyStrict(listOf("a"))
        val lazy: StrictOrLazy<List<String>, ContextInfo> = StrictOrLazyLazy(ctx("5"))

        assertTrue(strict is StrictOrLazyStrict)
        assertTrue(lazy is StrictOrLazyLazy)
        assertEquals(listOf("a"), (strict as StrictOrLazyStrict).strict)
        assertEquals(ctx("5"), (lazy as StrictOrLazyLazy).lazy)

        // data class equality is value based
        assertEquals(StrictOrLazyStrict<List<String>, ContextInfo>(listOf("a")), strict)
        assertEquals(StrictOrLazyLazy<List<String>, ContextInfo>(ctx("5")), lazy)
    }

    // =====================================================================================
    // InteractiveGoal.toInfoObjectModel
    // =====================================================================================

    private fun hyp(
        names: List<String>,
        type: TaggedText<SubexprInfo>,
        isInserted: Boolean? = null,
        isRemoved: Boolean? = null
    ) = InteractiveHypothesisBundle(
        names = names,
        fvarIds = names.map { "fv_$it" },
        type = type,
        isInserted = isInserted,
        isRemoved = isRemoved
    )

    private fun goal(
        userName: String?,
        hyps: Array<InteractiveHypothesisBundle>,
        type: TaggedText<SubexprInfo>,
        goalPrefix: String = "⊢ "
    ) = InteractiveGoal(
        userName = userName,
        type = type,
        mvarId = "mv1",
        hyps = hyps,
        ctx = ctx("0"),
        goalPrefix = goalPrefix
    )

    @Test
    fun interactiveGoal_withUserName_rendersCaseHeaderHypsAndTurnstile() {
        val g = goal(
            userName = "intro",
            hyps = arrayOf(hyp(listOf("a", "b"), text("Nat"))),
            type = text("a = b")
        )
        val model = g.toInfoObjectModel()
        // createGoalObjectModel:
        //   "case intro\n"  (h3 appends \n)
        //   "a b" + " : " + "Nat" + "\n"
        //   "⊢ " + "a = b"
        val expected = "case intro\na b : Nat\n⊢ a = b"
        assertEquals(expected, model.toString())
    }

    @Test
    fun interactiveGoal_withoutUserName_noCaseHeaderNoFold() {
        val g = goal(
            userName = null,
            hyps = arrayOf(hyp(listOf("h"), text("p"))),
            type = text("q")
        )
        val model = g.toInfoObjectModel()
        // no case header; just hyp line + turnstile
        val expected = "h : p\n⊢ q"
        assertEquals(expected, model.toString())
    }

    @Test
    fun interactiveGoal_noHyps_onlyTurnstile() {
        val g = goal(
            userName = null,
            hyps = arrayOf(),
            type = text("True")
        )
        assertEquals("⊢ True", g.toInfoObjectModel().toString())
    }

    // =====================================================================================
    // InteractiveGoals.toInfoObjectModel  (0 / 1 / N goals)
    // =====================================================================================

    @Test
    fun interactiveGoals_zeroGoals_rendersNoGoals() {
        val model = InteractiveGoals(goals = listOf()).toInfoObjectModel()
        // fold { h2("Tactic state"); +"No goals"; return@fold }
        // h2 appends "\n", then "No goals"
        assertEquals("Tactic state\nNo goals", model.toString())
    }

    @Test
    fun interactiveGoals_oneGoal_rendersSingularCountAndGoal() {
        val g = goal(
            userName = null,
            hyps = arrayOf(),
            type = text("True")
        )
        val model = InteractiveGoals(goals = listOf(g)).toInfoObjectModel()
        // "Tactic state\n" + "1 goal" + "\n"(br) + goal("⊢ True")
        assertEquals("Tactic state\n1 goal\n⊢ True", model.toString())
    }

    @Test
    fun interactiveGoals_twoGoals_rendersPluralCountAndBrBetween() {
        val g1 = goal(userName = null, hyps = arrayOf(), type = text("A"))
        val g2 = goal(userName = null, hyps = arrayOf(), type = text("B"))
        val model = InteractiveGoals(goals = listOf(g1, g2)).toInfoObjectModel()
        // "Tactic state\n" + "2 goals" + "\n"(br after count)
        //   + goal1("⊢ A") + "\n"(br between goals) + goal2("⊢ B")
        assertEquals("Tactic state\n2 goals\n⊢ A\n⊢ B", model.toString())
    }

    @Test
    fun interactiveGoals_threeGoals_pluralCount() {
        val g1 = goal(userName = null, hyps = arrayOf(), type = text("A"))
        val g2 = goal(userName = null, hyps = arrayOf(), type = text("B"))
        val g3 = goal(userName = null, hyps = arrayOf(), type = text("C"))
        val model = InteractiveGoals(goals = listOf(g1, g2, g3)).toInfoObjectModel()
        assertEquals("Tactic state\n3 goals\n⊢ A\n⊢ B\n⊢ C", model.toString())
    }

    // =====================================================================================
    // InteractiveTermGoal.toInfoObjectModel
    // =====================================================================================

    @Test
    fun interactiveTermGoal_rendersExpectedTypeHypsAndTurnstile() {
        val termGoal = InteractiveTermGoal(
            ctx = ctx("0"),
            hyps = arrayOf(hyp(listOf("n"), text("Nat"))),
            range = Range(Position(0, 0), Position(0, 1)),
            term = ctx("1"),
            type = text("n > 0")
        )
        val model = termGoal.toInfoObjectModel()
        // "Expected type\n" + "n" + " : " + "Nat" + "\n" + "⊢ " + "n > 0"
        assertEquals("Expected type\nn : Nat\n⊢ n > 0", model.toString())
    }

    // =====================================================================================
    // InfoObjectModel.toString stability (no mutation across repeated calls)
    // =====================================================================================

    @Test
    fun toString_isStableAcrossRepeatedCalls() {
        val model: InfoObjectModel =
            InteractiveGoals(goals = listOf(goal(null, arrayOf(), text("True")))).toInfoObjectModel()
        val first = model.toString()
        val second = model.toString()
        assertEquals(first, second)
        assertEquals("Tactic state\n1 goal\n⊢ True", first)
    }
}
