package lean4ij.language

import com.intellij.testFramework.ParsingTestCase

/**
 * Skeleton-parser regression test. doTest(true) parses src/test/testData/<TestName>.lean and compares the PSI
 * dump to <TestName>.txt (created on first run). See
 * https://plugins.jetbrains.com/docs/intellij/parsing-test.html
 */
class Lean4ParsingTest : ParsingTestCase("", "lean", Lean4ParserDefinition()) {

    fun testLeanSkeleton() {
        doTest(true)
    }

    override fun getTestDataPath(): String = "src/test/testData"

    override fun includeRanges(): Boolean = true
}
