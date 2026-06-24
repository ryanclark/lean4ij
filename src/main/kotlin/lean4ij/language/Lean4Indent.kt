package lean4ij.language

/**
 * Pure indentation logic for Lean's Enter handler, kept separate from the editor plumbing so it can be
 * unit-tested without the IntelliJ platform fixture (which does not boot in this project's test harness; see
 * MyPluginTest).
 *
 * Lean is whitespace-significant and its term/tactic syntax is user-extensible (notation/macro), so there is
 * no tractable "true" indentation to compute. Heuristic: a new line copies the previous line's indent, and
 * adds one [INDENT_UNIT] level when the previous line ends with a block-opener: `:=`, `by`, `do`, `where`,
 * `=>`, `↦`, `then`, `else`, `from`, `with`, or a trailing `(` / `{` / `⟨` / `[`.
 */
object Lean4Indent {
    /** Lean community style is two spaces. The plugin registers no code-style provider, so this is fixed. */
    const val INDENT_UNIT: String = "  "

    private val WORD_OPENERS: Set<String> = setOf("by", "do", "where", "from", "with", "then", "else")
    // `:` is included so a signature line ending in `:` (e.g. `theorem foo (..) :`) indents its continuation
    // (the return type). It only fires when the line ends with `:`; mid-line binders like `(x : T)` are
    // unaffected, and `:=` ends with `=` so it matches the `:=` opener, not this one.
    private val SYMBOL_OPENERS: List<String> = listOf(":=", "=>", "↦", "(", "{", "⟨", "[", ":")

    /** Leading run of spaces/tabs of [line]. */
    fun leadingIndent(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /** True when [prevLine] ends with a construct whose body should be indented one level deeper. */
    fun opensBlock(prevLine: String): Boolean {
        // Strip a trailing line comment so `:= by -- note` still counts as opening a block.
        val code = prevLine.substringBefore("--").trimEnd()
        if (code.isEmpty()) return false
        if (SYMBOL_OPENERS.any { code.endsWith(it) }) return true
        // Word openers must be whole tokens, so `def rugby` does not match `by`.
        val lastToken = code.takeLastWhile { !it.isWhitespace() }
        return lastToken in WORD_OPENERS
    }

    /** Target indentation for a new line that was split off the end of [prevLine]. */
    fun newLineIndent(prevLine: String): String {
        val base = leadingIndent(prevLine)
        return if (opensBlock(prevLine)) base + INDENT_UNIT else base
    }

    /**
     * For a closing bracket just typed at [closerOffset] in [text], return the indentation of the line that
     * holds its matching opener, so a line-leading `)` / `]` / `}` / `⟩` snaps back to the opener's column.
     * Returns null when there is no matching opener (e.g. the would-be opener is inside a string/comment).
     *
     * Forward scan that skips brackets inside string literals, `--` line comments and nestable `/- -/` block
     * comments. The typed closer at [closerOffset] is not consumed, so the matching opener is the stack top.
     */
    fun matchingOpenerIndent(text: CharSequence, closerOffset: Int): String? {
        val stack = ArrayDeque<String>() // line-indent of each currently-open bracket
        var i = 0
        var lineStart = 0
        while (i < closerOffset) {
            val c = text[i]
            when {
                c == '\n' -> { i++; lineStart = i }
                c == '-' && i + 1 < closerOffset && text[i + 1] == '-' -> {
                    while (i < closerOffset && text[i] != '\n') i++ // line comment to EOL
                }
                c == '/' && i + 1 < closerOffset && text[i + 1] == '-' -> {
                    var depth = 1; i += 2 // nestable block comment
                    while (i < closerOffset && depth > 0) {
                        when {
                            text[i] == '/' && i + 1 < closerOffset && text[i + 1] == '-' -> { depth++; i += 2 }
                            text[i] == '-' && i + 1 < closerOffset && text[i + 1] == '/' -> { depth--; i += 2 }
                            else -> { if (text[i] == '\n') lineStart = i + 1; i++ }
                        }
                    }
                }
                c == '"' -> {
                    i++
                    while (i < closerOffset && text[i] != '"') {
                        when {
                            text[i] == '\\' -> i += 2 // skip escaped char
                            else -> { if (text[i] == '\n') lineStart = i + 1; i++ }
                        }
                    }
                    if (i < closerOffset) i++ // closing quote
                }
                c == '(' || c == '[' || c == '{' || c == '⟨' -> { stack.addLast(indentAt(text, lineStart)); i++ }
                c == ')' || c == ']' || c == '}' || c == '⟩' -> { if (stack.isNotEmpty()) stack.removeLast(); i++ }
                else -> i++
            }
        }
        return stack.lastOrNull()
    }

    /** Leading run of spaces/tabs starting at [lineStart] in [text]. */
    private fun indentAt(text: CharSequence, lineStart: Int): String {
        val sb = StringBuilder()
        var j = lineStart
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) { sb.append(text[j]); j++ }
        return sb.toString()
    }
}
