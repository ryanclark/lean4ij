package lean4ij.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for [LeanUtil.isValidRange] (guards the hover-highlight crash). */
class LeanRangeTest {

    @Test fun rejectsLineColToOffsetMiss() {
        // StringUtil.lineColToOffset returns -1 for an out-of-bounds line/col -> the crash input.
        assertFalse(LeanUtil.isValidRange(-1, -1, 100))
        assertFalse(LeanUtil.isValidRange(-1, 5, 100))
        assertFalse(LeanUtil.isValidRange(5, -1, 100))
    }

    @Test fun rejectsInvertedRange() {
        assertFalse(LeanUtil.isValidRange(10, 5, 100))
    }

    @Test fun rejectsEndPastDocument() {
        assertFalse(LeanUtil.isValidRange(5, 120, 100))
    }

    @Test fun acceptsNormalRange() {
        assertTrue(LeanUtil.isValidRange(5, 10, 100))
    }

    @Test fun acceptsEmptyRangeAtBounds() {
        assertTrue(LeanUtil.isValidRange(0, 0, 100))
        assertTrue(LeanUtil.isValidRange(100, 100, 100))
    }
}
