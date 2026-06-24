package lean4ij.language

import com.google.common.io.Resources
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import lean4ij.project.LeanSymbolColoringService
import lean4ij.setting.Lean4Settings
import lean4ij.util.LeanUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Colors Lean symbol references by kind. Runs over the native Lean PSI as an `<annotator language="lean4">`,
 * doing a whole-text scan of the file when handed the [PsiFile] element:
 *  - Types: every capitalized identifier (type / namespace / constructor) gets [LEAN_TYPE], or the specific
 *    [LEAN_STRUCTURE] / [LEAN_INDUCTIVE] / [LEAN_CLASS] when declared in-file with that keyword;
 *  - Definitions: lowercase resolved `def`/`abbrev`/`instance` references get [LEAN_DEFINITION];
 *  - Theorems: lowercase resolved `theorem`/`lemma` references get [LEAN_THEOREM].
 *
 * Def/theorem names are resolution-backed (documentSymbol + textDocument/definition) and bucketed by their
 * declaring keyword in [LeanSymbolColoringService]; types need no resolution.
 *
 * Performance: the document is scanned once per pass with [IDENTIFIER] and each identifier is classified by a
 * set lookup (no per-name alternation regex). The derived per-document data (comment/string skip-spans and
 * in-file decl names) is cached by `modificationStamp` in [DERIVED], so an unchanged file (caret move, focus,
 * daemon restart) isn't re-scanned, and the skip test is an O(log S) binary search over the sorted spans.
 *
 * Short-circuits on anything that isn't the [PsiFile], and is guarded by [LeanUtil.isLeanFile]. Occurrences
 * inside comments and strings are skipped. All colors are editable in the Lean color settings page.
 */
class LeanConstReferenceAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val file = element.viewProvider.virtualFile
        if (!LeanUtil.isLeanFile(file)) return
        val settings = service<Lean4Settings>()
        if (!settings.enableConstReferenceHighlighting) return
        val colorTactics = settings.enableHeuristicTactic

        val document = element.viewProvider.document ?: return
        val coloring = element.project.service<LeanSymbolColoringService>()
        coloring.requestUpdate(file, document)  // (re)compute lowercase def/theorem names if stale

        val text = document.immutableCharSequence
        val derived = derivedFor(file.path, document.modificationStamp, text)
        val defNames = coloring.namesFor(file.path)
        val theoremNames = coloring.theoremNamesFor(file.path)
        val constructorNames = coloring.constructorNamesFor(file.path)
        val importedFunctionNames = coloring.importedFunctionNamesFor(file.path)

        // Single pass: classify each identifier. Capitalized means type (no LSP needed). Lowercase is colored
        // only if it resolved to a real def/theorem, so arbitrary camelCase locals stay uncolored.
        for (m in IDENTIFIER.findAll(text)) {
            val start = m.range.first
            if (inSkip(derived, start)) continue  // inside a comment or string
            // Skip an identifier immediately preceded by a digit: it's the tail of a radix literal (the `x3A` of
            // `0x3A`), which the lexer already colors as one NUMBER token. Painting it here would override that
            // number color and leave `0x3A` looking half-colored.
            if (start > 0 && text[start - 1].isDigit()) continue
            val tok = m.value
            val key = when {
                // A leading-dot accessor (`.none`, `.clear`, `.inl`, `.csiDispatch`) is anonymous-constructor /
                // enum notation in value/pattern position: always a data constructor, never a tactic. Without
                // this, an accessor whose name collides with a tactic (`.clear`, `.cases`, `.simp`) would get
                // tactic-colored while a builtin like `.none` stays the constructor color, splitting sibling
                // constructors in the same match across two colors. A field/method projection (`r.stop`,
                // `(xs).map`, `·.x`) has a value before the dot, so it's excluded by isLeadingDotAccessor.
                isLeadingDotAccessor(text, start) -> LEAN_CONSTRUCTOR
                // Every capitalized identifier (type / namespace / constructor) gets the same type color, so a
                // type reads identically at its declaration and at every usage. Kind-specific coloring would
                // need the declaring kind at every reference site, which isn't available without resolving each
                // one, so one consistent type color is used instead.
                // Curated Lean core/stdlib types (Option, List, Nat, ...) get a distinct color from project
                // types, so an imported `Option` reads differently from a domain type like `BytePos` / `Line`.
                // Checked before the generic capitalized rule since these names are all capitalized too.
                tok in BUILTIN_TYPES -> LEAN_BUILTIN_TYPE
                tok[0].isUpperCase() -> LEAN_TYPE
                // First identifier after a line-leading `|`: an inductive-constructor declaration (`| none`,
                // `| clear`) or a match/cases arm pattern (`| some pc =>`). Always a data constructor, so these
                // color consistently whether or not documentSymbol reported the constructor, and a tactic-named
                // one (`| clear`) is not diverted to the tactic color. Lowercase only: a capitalized arm head is
                // a qualified type/namespace (`| State.ground`) already handled by the uppercase rule above.
                isMatchArmConstructor(text, start) -> LEAN_CONSTRUCTOR
                tok in BUILTIN_CONSTRUCTORS || tok in constructorNames -> LEAN_CONSTRUCTOR
                colorTactics && tok in TACTICS -> LEAN_TACTIC
                tok in derived.localTheorems || tok in theoremNames -> LEAN_THEOREM
                tok in derived.localDefs || tok in defNames -> LEAN_DEFINITION
                // Imported/stdlib functions (resolve to a `def` in another file), distinct from in-file defs.
                // Checked after in-file defs/theorems so a name defined here keeps its in-file color.
                tok in importedFunctionNames -> LEAN_IMPORTED_FUNCTION
                else -> null
            } ?: continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(start, m.range.last + 1))
                .textAttributes(key)
                .create()
        }

        // `inline code` spans inside comments (markdown code refs like `transition`, `inStart`) get a distinct,
        // brighter color so they stand out from the dim prose. Done here in the full-text scan rather than per
        // comment-token, and with an explicit themeable default rather than DOC_COMMENT_MARKUP, which is unset
        // in many dark themes (Material Darker) and renders flat.
        for (c in COMMENT.findAll(text)) {
            for (code in INLINE_CODE.findAll(c.value)) {
                val s = c.range.first + code.range.first
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(s, s + code.value.length))
                    .textAttributes(LEAN_COMMENT_CODE)
                    .create()
            }
        }
    }

    /** Per-document derived scan, cached by `modificationStamp`: comment/string skip-spans + in-file decl names. */
    private class Derived(
        val stamp: Long,
        val skipStarts: IntArray,   // ascending, parallel to skipEnds
        val skipEnds: IntArray,     // inclusive end offsets
        // Names declared in THIS file by `def`/`abbrev`/... and `theorem`/`lemma`/..., scanned from the text so
        // in-file defs/theorems color reliably + consistently (decl and all usages) without depending on the LSP
        // documentSymbol round-trip. The LSP-resolved sets still cover imported references.
        val localDefs: Set<String>,
        val localTheorems: Set<String>,
    )

    private fun derivedFor(path: String, stamp: Long, text: CharSequence): Derived {
        val cached = DERIVED[path]
        if (cached != null && cached.stamp == stamp) return cached
        if (DERIVED.size > MAX_DERIVED_ENTRIES) DERIVED.clear()  // bound retention across a long session
        return buildDerived(stamp, text).also { DERIVED[path] = it }
    }

    private fun buildDerived(stamp: Long, text: CharSequence): Derived {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        for (m in SKIP.findAll(text)) {  // findAll yields non-overlapping ranges in ascending order
            starts.add(m.range.first)
            ends.add(m.range.last)
        }
        val localDefs = HashSet<String>()
        for (m in DEF_DECL.findAll(text)) {
            val nm = m.groupValues[1].substringAfterLast('.')
            if (nm.isNotEmpty() && !nm[0].isUpperCase()) localDefs.add(nm)
        }
        val localTheorems = HashSet<String>()
        for (m in THM_DECL.findAll(text)) {
            val nm = m.groupValues[1].substringAfterLast('.')
            if (nm.isNotEmpty() && !nm[0].isUpperCase()) localTheorems.add(nm)
        }
        return Derived(stamp, starts.toIntArray(), ends.toIntArray(), localDefs, localTheorems)
    }

    /** True if [pos] falls inside a skip span. O(log S) binary search (spans are ascending and non-overlapping). */
    private fun inSkip(d: Derived, pos: Int): Boolean {
        val starts = d.skipStarts
        var lo = 0
        var hi = starts.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= pos) { idx = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return idx >= 0 && pos <= d.skipEnds[idx]
    }

    /**
     * True if the identifier starting at [start] is an anonymous-constructor / enum accessor: a `.` immediately
     * before it, and the char before that `.` is a separator (start of text, whitespace, or one of `([{⟨|,`),
     * so the dot opens a value/pattern (`.none`, `| .clear =>`, `(.inl hd)`). A field/method projection like
     * `r.stop`, `(xs).map` or `·.x` has a value (identifier / close-bracket / `·`) before the dot and is not
     * matched.
     */
    private fun isLeadingDotAccessor(text: CharSequence, start: Int): Boolean {
        if (start == 0 || text[start - 1] != '.') return false
        if (start < 2) return true
        val prev = text[start - 2]
        return prev.isWhitespace() || prev == '(' || prev == '[' || prev == '{' || prev == '⟨' || prev == '|' || prev == ','
    }

    /**
     * True if the identifier at [start] is the first token after a line-leading `|`: a constructor in an
     * inductive declaration (`  | none`) or a match/cases arm (`  | some pc =>`). Requires only whitespace
     * between the identifier and the `|`, and only whitespace between the `|` and the start of its line, and
     * excludes `||` (boolean or). Mid-line `|` (inline `match ... | a | b`, `rcases ... with x | y`) is not
     * matched, so its bound names stay uncolored; those constructors are still covered when they're builtins or
     * documentSymbol-resolved.
     */
    private fun isMatchArmConstructor(text: CharSequence, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        if (i < 0 || text[i] != '|') return false
        if (i > 0 && text[i - 1] == '|') return false   // part of `||`
        var j = i - 1
        while (j >= 0 && (text[j] == ' ' || text[j] == '\t')) j--
        return j < 0 || text[j] == '\n'
    }

    companion object {
        // An identifier (one token; a dotted name like List.foldl tokenizes into the segments List and foldl).
        private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_'?!]*""")
        // In-file declarations: the name following a def/theorem keyword (so usages color by the right role).
        private val DEF_DECL = Regex("""\b(?:def|abbrev|instance|opaque|axiom)\s+([\p{L}_][\p{L}\p{N}_'.]*)""")
        private val THM_DECL = Regex("""\b(?:theorem|lemma|example)\s+([\p{L}_][\p{L}\p{N}_'.]*)""")
        // Skip line comments (-- to EOL), block/doc comments (/- ... -/), and double-quoted strings.
        // The string clause uses the unrolled-loop form `"[^"\\]*(?:\\.[^"\\]*)*"` instead of `"(\\.|[^"\\])*"`:
        // the latter is a quantified group-with-alternation that java.util.regex matches by recursing once per
        // character, overflowing the stack on long strings / large library files (StackOverflowError seen on
        // Nat.lean). The unrolled form matches runs of non-quote/non-escape chars with an iterative char-class
        // loop and only recurses per escape sequence.
        private val SKIP = Regex("""--[^\n]*|/-[\s\S]*?-/|"[^"\\]*(?:\\.[^"\\]*)*"""")
        // Comments only (no strings): used to find `inline code` spans to highlight inside them.
        private val COMMENT = Regex("""--[^\n]*|/-[\s\S]*?-/""")
        // A single-line markdown inline-code span: `code` (no nested backtick, no newline).
        private val INLINE_CODE = Regex("""`[^`\r\n]+`""")

        // Per-document derived scans, keyed by file path; one small entry per open document, bounded below.
        private val DERIVED = ConcurrentHashMap<String, Derived>()
        private const val MAX_DERIVED_ENTRIES = 512

        // Each color falls back to the semantically matching standard key (color by role, like Go/Rust/Java),
        // so it inherits the active theme instead of an arbitrary hue, and all are editable in
        // LeanColorSettingsPage:
        //  - structure / inductive: the class color (enums fall back to class in Java);
        //  - class (type class): the interface color (a type class is an interface/trait);
        //  - generic / imported type references: the class-reference color;
        //  - def: function declaration; theorem: constant (a proven, named constant).
        // The bundled schemes don't define these type-family keys, so the type family inherits its
        // CLASS_NAME / CLASS_REFERENCE / INTERFACE_NAME fallback from the active theme.
        val LEAN_STRUCTURE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_STRUCTURE", DefaultLanguageHighlighterColors.CLASS_NAME)
        val LEAN_INDUCTIVE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_INDUCTIVE", DefaultLanguageHighlighterColors.CLASS_NAME)
        val LEAN_CLASS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_CLASS", DefaultLanguageHighlighterColors.INTERFACE_NAME)
        val LEAN_TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_TYPE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
        // Lean core/stdlib types (Option, List, Nat, Bool, ...), colored distinctly from project-defined types
        // (which use LEAN_TYPE) so an imported stdlib type doesn't read identically to a domain type. Falls back
        // to CLASS_NAME, with an explicit gold default in the bundled schemes (ffcb6b dark / 8a6d00 light);
        // editable in LeanColorSettingsPage.
        val LEAN_BUILTIN_TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_BUILTIN_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME)
        val LEAN_DEFINITION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_DEFINITION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        // Imported / stdlib functions (resolve to a `def` in another file), a distinct color from in-file defs
        // (LEAN_DEFINITION), so a call into the library doesn't read like one of your own defs. Falls back to
        // FUNCTION_CALL, with an explicit teal default in the bundled schemes (80cbc4 dark / 00695c light);
        // editable in the settings page.
        val LEAN_IMPORTED_FUNCTION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_IMPORTED_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
        val LEAN_THEOREM: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_THEOREM", DefaultLanguageHighlighterColors.CONSTANT)
        //  - data constructors / enum members (none, some, an inductive's ctors): static-field color, with an
        //    explicit default in the bundled schemes (coral f07178 dark / purple 871094 light).
        val LEAN_CONSTRUCTOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_CONSTRUCTOR", DefaultLanguageHighlighterColors.STATIC_FIELD)

        // Common built-in value constructors/constants, colored without an LSP round-trip so terms like
        // `none`/`some` are colored eagerly. Excludes names that are also tactics (rfl, trivial), which the
        // tactic path colors; coloring them here too would race it. User-defined in-file constructors come from
        // LeanSymbolColoringService.constructorNamesFor.
        private val BUILTIN_CONSTRUCTORS = setOf("none", "some", "nil", "cons", "true", "false")

        // Curated Lean 4 core/stdlib type and type-class names, colored as LEAN_BUILTIN_TYPE (distinct from a
        // project type's LEAN_TYPE). All capitalized, so matched before the generic capitalized -> LEAN_TYPE
        // rule. Excludes the sort universes Type/Sort/Prop: `Type` is lexed as a keyword (DEFAULT_TYPE), so
        // coloring them here would race that. A capitalized name absent from this set falls through to
        // LEAN_TYPE, so extend freely if a commonly-used stdlib type is missing.
        private val BUILTIN_TYPES = setOf(
            // primitives & basic data
            "Nat", "Int", "Bool", "String", "Char", "Float", "Float32", "Unit", "PUnit", "Empty", "PEmpty",
            // containers / algebraic
            "Option", "List", "Array", "Vector", "Prod", "Sum", "PProd", "PSum", "MProd", "Sigma", "PSigma",
            "Subtype", "Except", "Quot", "Thunk", "Task", "Substring",
            // sized numerics
            "Fin", "BitVec", "UInt8", "UInt16", "UInt32", "UInt64", "USize",
            "Int8", "Int16", "Int32", "Int64", "ISize",
            // ordering / decidability
            "Ordering", "Decidable", "DecidableEq",
            // IO / monads / transformers
            "IO", "EIO", "BaseIO", "ST", "StateM", "ReaderM", "Id",
            "ExceptT", "StateT", "ReaderT", "OptionT",
            // common type classes
            "Inhabited", "Repr", "ToString", "BEq", "Ord", "Hashable", "Functor", "Applicative", "Monad", "Coe",
            // common collections
            "HashMap", "HashSet", "RBMap", "RBTree", "AssocList",
        )

        // Tactic names (obtain, cases, simp, ...) fall back to the KEYWORD color to match the LSP. The Lean LSP
        // emits tactic names as `keyword` semantic tokens, which lsp4ij paints with the theme's keyword color
        // once the file elaborates. A different color here would make a tactic flip between the two as
        // elaboration state changes; falling back to KEYWORD keeps both paths in agreement. Overridable in
        // settings.
        val LEAN_TACTIC: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_TACTIC", DefaultLanguageHighlighterColors.KEYWORD)

        // `inline code` spans inside comments (markdown code references like `transition`, `inStart`). Falls back
        // to DOC_COMMENT_MARKUP, with an explicit brighter default in the bundled schemes since that fallback is
        // unset in many dark themes and renders flat. The explicit default keeps the code distinct from the dim
        // comment prose. Editable in the settings page.
        val LEAN_COMMENT_CODE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "LEAN_COMMENT_CODE", DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP)

        // Names the lexer already colors as keywords. Tactic coloring must not override these: `let`, `have`,
        // `show`, `calc`, `match`, `suffices`, ... appear in tactics.txt but are keywords, and painting a
        // variable-binding keyword with the tactic color reads wrong. Mirrors the lexer's KEYWORD_COMMAND*
        // macros.
        private val KEYWORDS = setOf(
            "prelude", "module", "import", "include", "export", "open", "mutual", "universe",
            "public", "local", "private", "protected", "scoped", "partial", "noncomputable", "unsafe",
            "renaming", "hiding", "where", "extends", "using", "with", "at", "rec", "deriving",
            "syntax", "elab_rules", "elab", "macro_rules", "macro", "notation",
            "infixl", "infix", "infixr", "prefix", "postfix",
            "namespace", "section", "end", "structure", "inductive", "class", "def", "abbrev",
            "instance", "axiom", "opaque", "theorem", "lemma", "example", "set_option", "variable",
            "match", "have", "with", "by", "in", "fun", "let", "do", "show", "from", "calc",
            "if", "then", "else", "return", "suffices", "nomatch", "assume", "try", "for", "while", "unless", "mut",
            // `case` is colored as a keyword by the lexer (KEYWORD_COMMAND6); listing it here keeps the text-scan
            // from re-coloring it as a tactic (it's in tactics.txt). `cases`/`case'` stay tactics.
            "case",
        )

        // The tactic vocabulary (obtain/cases/simp/...), loaded once from tactics.txt. Robust to malformed lines
        // (takes the name before the first space). Keyword-named entries are dropped so they keep their keyword color.
        private val TACTICS: Set<String> = loadTactics()

        private fun loadTactics(): Set<String> {
            val out = HashSet<String>()
            val resource = LeanConstReferenceAnnotator::class.java.classLoader.getResource("tactics.txt") ?: return out
            try {
                for (line in Resources.readLines(resource, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("--")) continue
                    val name = line.substringBefore(' ').trim()
                    if (name.isNotEmpty() && name !in KEYWORDS) out.add(name)
                }
            } catch (e: Exception) {
                // Leave whatever loaded; coloring degrades gracefully rather than throwing during class init.
            }
            return out
        }
    }
}
