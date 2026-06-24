package lean4ij.infoview

import lean4ij.lsp.LeanLanguageServer
import lean4ij.lsp.data.InteractiveGoals
import lean4ij.test.readResource
import lean4ij.util.fromJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of the pure goal-selection
 * decision logic used by [MiniInfoviewService] (extracted as the top-level internal
 * function [selectMiniInfoviewGoal]).
 *
 * These tests exercise ONLY the pure model-building/decision logic. They deliberately do
 * NOT touch any of the EDT/popover/Editor coupled members of [MiniInfoviewService]
 * (createPopover, showAtCursor, displayContent, setupListeners, toggleVisibility,
 * updateCaret, cancel) because those require a live IntelliJ Application/Editor and would
 * NPE in a plain unit test.
 *
 * Real data is constructed by deserializing the same fixture used by
 * [lean4ij.infoview.dsl.DslKtTest] (lsp/interactiveGoals_sample.json), which contains
 * exactly one interactive goal whose rendered type is "a * (w * w') = c * (z * z')".
 */
class MiniInfoviewLogicCharacterizationTest {

    private fun singleGoal(): InteractiveGoals =
        LeanLanguageServer.gson.fromJson(readResource("lsp/interactiveGoals_sample.json"))

    /**
     * Pin the literal default of the companion flag that drives the decision.
     * Term goals are currently disabled.
     */
    @Test
    fun testAllowTermGoalsDefaultIsFalse() {
        assertEquals(false, MiniInfoviewService.ALLOW_TERM_GOALS)
    }

    /**
     * With exactly one goal, the function returns a non-null model whose toString equals
     * the goal symbol prefix "⊢ " concatenated with the goal type's own rendered string.
     */
    @Test
    fun testSingleGoalRendersGoalSymbolPrefixPlusType() {
        val goals = singleGoal()
        // sanity: fixture really has exactly one goal
        assertEquals(1, goals.goals.size)

        val typeRendered = goals.goals[0].type.toInfoObjectModel().toString()
        // pin the fixture's actual type rendering so the expectation is explicit
        assertEquals("a * (w * w') = c * (z * z')", typeRendered)

        val model = selectMiniInfoviewGoal(goals, null, allowTermGoals = false)
        assertNotNull(model)
        assertEquals("⊢ $typeRendered", model.toString())
        assertEquals("⊢ a * (w * w') = c * (z * z')", model.toString())
    }

    /**
     * The single-goal branch builds the model from goals[0].type irrespective of the
     * allowTermGoals flag (the flag only gates the term-goal fallback). Pin that both
     * flag values yield the same single-goal rendering.
     */
    @Test
    fun testSingleGoalIgnoresAllowTermGoalsFlag() {
        val goals = singleGoal()
        val withFlagFalse = selectMiniInfoviewGoal(goals, null, allowTermGoals = false)
        val withFlagTrue = selectMiniInfoviewGoal(goals, null, allowTermGoals = true)
        assertNotNull(withFlagFalse)
        assertNotNull(withFlagTrue)
        assertEquals(withFlagFalse.toString(), withFlagTrue.toString())
        assertEquals("⊢ a * (w * w') = c * (z * z')", withFlagFalse.toString())
    }

    /**
     * Pin the model structure for the single-goal case: the top model has exactly two
     * children, the first being the goal-symbol paragraph "⊢ " (which is what carries
     * the SwingInfoviewGoalSymbol attribute key), the second being the added type model.
     */
    @Test
    fun testSingleGoalModelStructure() {
        val goals = singleGoal()
        val model = selectMiniInfoviewGoal(goals, null, allowTermGoals = false)!!
        // top-level model itself has empty own text
        assertEquals("", model.text)
        assertEquals(2, model.children.size)
        assertEquals("⊢ ", model.children[0].text)
        // the goal symbol prefix paragraph carries exactly one attribute key
        assertEquals(1, model.children[0].attr.size)
        assertEquals(
            Lean4TextAttributesKeys.SwingInfoviewGoalSymbol.key,
            model.children[0].attr[0]
        )
        // the second child is the added type model and renders the type text
        assertEquals("a * (w * w') = c * (z * z')", model.children[1].toString())
    }

    /**
     * Null interactiveGoals: goals?.size is null (not 1) and allowTermGoals is false,
     * so the function returns null regardless of any term goal being absent.
     */
    @Test
    fun testNullGoalsReturnsNullWhenTermGoalsDisabled() {
        assertNull(selectMiniInfoviewGoal(null, null, allowTermGoals = false))
    }

    /**
     * Null interactiveGoals + allowTermGoals=true but a null term goal: the term-goal
     * fallback dereferences interactiveTermGoal?.type which is null, so returns null.
     */
    @Test
    fun testNullGoalsAndNullTermGoalReturnsNullEvenWhenTermGoalsEnabled() {
        assertNull(selectMiniInfoviewGoal(null, null, allowTermGoals = true))
    }

    /**
     * Empty goals list: size is 0 (not 1). With term goals disabled -> null.
     */
    @Test
    fun testEmptyGoalsReturnsNullWhenTermGoalsDisabled() {
        val empty = InteractiveGoals(emptyList())
        assertNull(selectMiniInfoviewGoal(empty, null, allowTermGoals = false))
    }

    /**
     * Two goals: size is 2 (not 1). With term goals disabled -> null.
     * Constructed from the real deserialized goal so the data is genuine.
     */
    @Test
    fun testMultipleGoalsReturnsNullWhenTermGoalsDisabled() {
        val one = singleGoal().goals[0]
        val two = InteractiveGoals(listOf(one, one))
        assertEquals(2, two.goals.size)
        assertNull(selectMiniInfoviewGoal(two, null, allowTermGoals = false))
    }

    /**
     * toString of the returned model is deterministic across repeated calls (the DSL
     * model's toString does not rely on one-shot side effects). Pin that property here,
     * matching the rationale documented in DslKtTest.
     */
    @Test
    fun testSingleGoalToStringIsRepeatable() {
        val model = selectMiniInfoviewGoal(singleGoal(), null, allowTermGoals = false)!!
        val first = model.toString()
        val second = model.toString()
        assertEquals(first, second)
        assertTrue(first.startsWith("⊢ "))
    }
}
