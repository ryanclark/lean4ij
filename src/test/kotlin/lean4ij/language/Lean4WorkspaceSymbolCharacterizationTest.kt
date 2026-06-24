package lean4ij.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT, pure behavior of the workspace symbol search logic
 * in [Lean4WorkspaceSymbolContributor] / [WorkspaceSymbolData] so an upcoming cache/control-flow
 * redesign can be proven behavior-preserving.
 *
 * These exercise the pure helpers extracted (behavior-identically) from the production code:
 *  - [isClassSymbolName]: the predicate behind [Lean4WorkspaceClassContributor.filter]
 *    (a symbol is a "class" when the last dotted segment of its name starts uppercase).
 *  - [queryCanTrigger] / [normalizeQuery]: the suffix handling behind
 *    [WorkspaceSymbolsCache] canTrigger/normalize (default trigger suffix is ",,").
 *
 * Anything that constructs a [LeanWorkspaceSymbolData] or reaches `service<Lean4Settings>()` /
 * a live LSP server is intentionally NOT exercised here: those NPE without a live Application.
 * See the subagent notes for what is/ isn't unit-testable without a fixture.
 */
class Lean4WorkspaceSymbolCharacterizationTest {

    // ---------------------------------------------------------------------------------------------
    // isClassSymbolName: the Lean4WorkspaceClassContributor.filter predicate.
    // current logic: name.split(".").last().let { it[0].isUpperCase() }
    // ---------------------------------------------------------------------------------------------

    @Test
    fun classPredicate_simpleUppercaseName_isClass() {
        assertTrue(isClassSymbolName("Foo"))
    }

    @Test
    fun classPredicate_simpleLowercaseName_isNotClass() {
        assertFalse(isClassSymbolName("foo"))
    }

    @Test
    fun classPredicate_qualifiedNameLastSegmentLowercase_isNotClass() {
        // "Nat.add" -> last segment "add" -> 'a' is lowercase
        assertFalse(isClassSymbolName("Nat.add"))
    }

    @Test
    fun classPredicate_qualifiedNameLastSegmentUppercase_isClass() {
        // "List.Cons" -> last segment "Cons" -> 'C' is uppercase
        assertTrue(isClassSymbolName("List.Cons"))
    }

    @Test
    fun classPredicate_onlyLastSegmentMatters_evenWhenEarlierSegmentsLowercase() {
        // earlier "x" lowercase is ignored; only last "Y" decides
        assertTrue(isClassSymbolName("x.Y"))
    }

    @Test
    fun classPredicate_onlyLastSegmentMatters_evenWhenEarlierSegmentsUppercase() {
        // earlier "Nat" uppercase is ignored; only last "succ" decides
        assertFalse(isClassSymbolName("Nat.succ"))
    }

    @Test
    fun classPredicate_deeplyQualifiedName_usesFinalSegment() {
        assertTrue(isClassSymbolName("Mathlib.Algebra.Group.Basic.CommMonoid"))
        assertFalse(isClassSymbolName("Mathlib.Algebra.Group.Basic.mul_comm"))
    }

    @Test
    fun classPredicate_nonLetterFirstChar_isNotUppercase() {
        // '+'.isUpperCase() == false, so an operator-like symbol is not a class
        assertFalse(isClassSymbolName("+"))
        assertFalse(isClassSymbolName("HAdd.+"))
    }

    @Test
    fun classPredicate_digitFirstChar_isNotUppercase() {
        assertFalse(isClassSymbolName("1abc"))
    }

    @Test
    fun classPredicate_underscoreFirstChar_isNotUppercase() {
        assertFalse(isClassSymbolName("_private"))
    }

    @Test
    fun classPredicate_nameWithoutDot_usesWholeName() {
        // no '.' -> split yields the single element -> first char decides
        assertTrue(isClassSymbolName("CommMonoid"))
        assertFalse(isClassSymbolName("commMonoid"))
    }

    @Test(expected = StringIndexOutOfBoundsException::class)
    fun classPredicate_trailingDotProducesEmptyLastSegment_throws() {
        // CURRENT (buggy) behavior: "Foo." -> ["Foo", ""], last is "", ""[0] throws.
        // Pinned so the redesign preserves (or deliberately changes) this edge.
        isClassSymbolName("Foo.")
    }

    @Test(expected = StringIndexOutOfBoundsException::class)
    fun classPredicate_emptyName_throws() {
        // CURRENT behavior: "" -> [""], last is "", ""[0] throws.
        isClassSymbolName("")
    }

    // ---------------------------------------------------------------------------------------------
    // queryCanTrigger / normalizeQuery: WorkspaceSymbolsCache suffix handling. These characterize the pure
    // helpers against an arbitrary trigger suffix; they do NOT assert it equals the production default
    // (Lean4Settings.workspaceSymbolTriggerSuffix, which happens to be ",," but needs an Application to read).
    // current logic: canTrigger  = queryString.endsWith(suffix)
    //                normalize   = queryString.removeSuffix(suffix)
    // ---------------------------------------------------------------------------------------------

    private val triggerSuffix = ",,"

    @Test
    fun canTrigger_queryEndingWithSuffix_triggers() {
        assertTrue(queryCanTrigger("Nat,,", triggerSuffix))
    }

    @Test
    fun canTrigger_queryWithoutSuffix_doesNotTrigger() {
        assertFalse(queryCanTrigger("Nat", triggerSuffix))
    }

    @Test
    fun canTrigger_partialSuffix_doesNotTrigger() {
        // single comma is not the two-comma suffix
        assertFalse(queryCanTrigger("Nat,", triggerSuffix))
    }

    @Test
    fun canTrigger_emptyQuery_doesNotTrigger() {
        assertFalse(queryCanTrigger("", triggerSuffix))
    }

    @Test
    fun canTrigger_queryEqualToSuffix_triggers() {
        assertTrue(queryCanTrigger(",,", triggerSuffix))
    }

    @Test
    fun canTrigger_suffixInMiddleNotAtEnd_doesNotTrigger() {
        assertFalse(queryCanTrigger("a,,b", triggerSuffix))
    }

    @Test
    fun canTrigger_extraTrailingComma_stillTriggers() {
        // ",,," ends with ",," -> true
        assertTrue(queryCanTrigger("Nat,,,", triggerSuffix))
    }

    @Test
    fun normalize_stripsTrailingSuffixOnce() {
        assertEquals("Nat", normalizeQuery("Nat,,", triggerSuffix))
    }

    @Test
    fun normalize_noSuffix_leavesQueryUnchanged() {
        assertEquals("Nat", normalizeQuery("Nat", triggerSuffix))
    }

    @Test
    fun normalize_partialSuffix_leavesQueryUnchanged() {
        // removeSuffix only strips an exact full-suffix match
        assertEquals("Nat,", normalizeQuery("Nat,", triggerSuffix))
    }

    @Test
    fun normalize_queryEqualToSuffix_becomesEmpty() {
        assertEquals("", normalizeQuery(",,", triggerSuffix))
    }

    @Test
    fun normalize_emptyQuery_staysEmpty() {
        assertEquals("", normalizeQuery("", triggerSuffix))
    }

    @Test
    fun normalize_removesSuffixOnlyOnce_notRepeatedly() {
        // removeSuffix strips a single occurrence: "Nat,,,," -> "Nat,," (NOT "Nat")
        assertEquals("Nat,,", normalizeQuery("Nat,,,,", triggerSuffix))
    }

    @Test
    fun normalize_suffixInMiddle_isNotStripped() {
        assertEquals("a,,b", normalizeQuery("a,,b", triggerSuffix))
    }

    @Test
    fun canTriggerAndNormalize_areConsistentForTriggeringQuery() {
        // a triggering query, once normalized, is exactly the query with the suffix removed
        val query = "Mathlib.CommGroup,,"
        assertTrue(queryCanTrigger(query, triggerSuffix))
        assertEquals("Mathlib.CommGroup", normalizeQuery(query, triggerSuffix))
    }

    @Test
    fun suffixHandling_worksWithCustomSuffix() {
        // helpers are parameterized over the suffix; pin behavior for a non-default suffix too
        val custom = ";;"
        assertTrue(queryCanTrigger("Nat;;", custom))
        assertFalse(queryCanTrigger("Nat,,", custom))
        assertEquals("Nat", normalizeQuery("Nat;;", custom))
        assertEquals("Nat,,", normalizeQuery("Nat,,", custom))
    }
}
