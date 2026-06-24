package lean4ij.language

/**
 * Pure, conservative text normalization for the Lean formatter: sorts consecutive `import` lines, collapses
 * runs of blank lines to a single one, and strips trailing whitespace. It never touches expression/tactic
 * interiors, only import ordering and inter-line whitespace, which is the only safe layout transform for Lean
 * (its term syntax is user-extensible and can't be reflowed without the elaborator).
 */
object Lean4Format {

    private val TRAILING_WS = Regex("[ \\t]+(?=\\n)")          // trailing whitespace on each line
    private val BLANK_LINE_RUNS = Regex("\\n([ \\t]*\\n){2,}") // 2+ blank lines

    fun normalize(text: String): String {
        var t = sortImportBlocks(text)
        t = t.replace(TRAILING_WS, "")
        t = t.replace(BLANK_LINE_RUNS, "\n\n")
        return t
    }

    /** Sort each maximal run of consecutive `import` lines alphabetically; everything else is left in place. */
    fun sortImportBlocks(text: String): String {
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (isImportLine(lines[i])) {
                var j = i
                while (j < lines.size && isImportLine(lines[j])) j++
                out.addAll(lines.subList(i, j).sortedBy { it.trim() })
                i = j
            } else {
                out.add(lines[i]); i++
            }
        }
        return out.joinToString("\n")
    }

    private fun isImportLine(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("import ") && !t.contains("--")
    }
}
