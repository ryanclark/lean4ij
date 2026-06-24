package lean4ij.project

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lean4ij.lsp.LeanLanguageServer
import lean4ij.setting.Lean4Settings
import lean4ij.util.LspUtil
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which identifiers in a Lean file are real symbol references (defs / theorems / functions) so
 * [lean4ij.language.LeanConstReferenceAnnotator] can color them accurately, by LSP resolution rather than
 * a naming heuristic.
 *
 * Lean's LSP has NO bulk "all constant references" request (its semantic tokens deliberately omit
 * constants), so this combines two sources:
 *  - **in-file** declarations come from `textDocument/documentSymbol` (one request for the whole file);
 *  - **imported** references are resolved per DISTINCT identifier via `textDocument/definition`: an
 *    identifier whose definition lands in a different file is an imported constant worth coloring.
 *
 * Resolutions are cached per name (persisted across edits, since a name's meaning rarely changes), so the
 * per-identifier cost is paid roughly once. Coloring is computed in two stages: in-file defs paint
 * immediately, then imports fill in, each followed by a daemon restart. Mirrors the inlay-hint debounce.
 */
@Service(Service.Level.PROJECT)
class LeanSymbolColoringService(private val project: Project) {

    /**
     * How a candidate identifier resolves:
     *  - NOT_IMPORT: defined in this same file (or unresolved); colored by the in-file paths.
     *  - PROJECT_DEF: a `def`/`abbrev`/… in ANOTHER file of THIS project. Colored blue like in-file defs, so
     *    your own multi-file code reads consistently (not teal in consumers and blue at its definition).
     *  - EXTERNAL_FUNCTION: a function defined OUTSIDE the project (toolchain / `.lake/packages` like mathlib):
     *    teal, the "imported/stdlib function" color.
     *  - THEOREM: a `theorem`/`lemma` anywhere (project or external); red.
     */
    enum class ImportKind { NOT_IMPORT, PROJECT_DEF, EXTERNAL_FUNCTION, THEOREM }

    /** The resolved name-set for one document version (the annotator tests each identifier against it). */
    class Coloring(
        val stamp: Long,
        val names: Set<String>,
        val theoremNames: Set<String>,
        val constructorNames: Set<String>,
        val importedFunctionNames: Set<String>,
    )

    /** file path -> last computed coloring. */
    private val cache = ConcurrentHashMap<String, Coloring>()
    /** file path -> (distinct identifier -> how it resolves). Persisted across edits, since a name's kind rarely
     *  changes; this is also what avoids re-reading a def-site file for a name already classified. */
    private val importResolution = ConcurrentHashMap<String, MutableMap<String, ImportKind>>()
    /** file path -> the modificationStamp currently being (re)computed, to avoid duplicate launches. */
    private val computing = ConcurrentHashMap<String, Long>()

    /** The def/theorem/function names to color in [path] (the annotator tests each identifier against this set). */
    fun namesFor(path: String): Set<String> = cache[path]?.names ?: emptySet()

    /** Of [namesFor], those declared with theorem/lemma; colored as theorems, the rest as definitions. */
    fun theoremNamesFor(path: String): Set<String> = cache[path]?.theoremNames ?: emptySet()

    /** Data-constructor / enum-member names (from documentSymbol); colored distinctly from defs. */
    fun constructorNamesFor(path: String): Set<String> = cache[path]?.constructorNames ?: emptySet()

    /** Imported/stdlib FUNCTION names (resolve to a `def` in another file); colored distinctly from in-file defs. */
    fun importedFunctionNamesFor(path: String): Set<String> = cache[path]?.importedFunctionNames ?: emptySet()

    /**
     * Ensure the coloring for [file] is up to date with [document]; (re)computes asynchronously if stale,
     * then triggers a re-highlight. Safe to call from the annotator on every highlighting pass.
     */
    fun requestUpdate(file: VirtualFile, document: Document) {
        if (!service<Lean4Settings>().enableConstReferenceHighlighting) return
        val path = file.path
        val stamp = document.modificationStamp
        if (cache[path]?.stamp == stamp) return
        // Atomically claim the (path, stamp) slot so two concurrent annotator passes don't both launch a compute.
        // put returns the previous value: if it already equals this stamp another pass owns it; a stale
        // different-stamp claim is overwritten so a newer edit isn't blocked (its superseded coroutine bails).
        if (computing.put(path, stamp) == stamp) return
        // immutableCharSequence is a thread-safe snapshot, safe to read off the EDT.
        val text = document.immutableCharSequence.toString()
        val leanProject = project.service<LeanProjectService>()
        leanProject.scope.launch {
            try {
                delay(DEBOUNCE_MILLIS)
                if (document.modificationStamp != stamp) return@launch  // superseded by a newer edit
                compute(path, file, stamp, text) { document.modificationStamp == stamp }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Server restarting / closed file / transient RPC failure; don't leak, the next pass retries.
                thisLogger().debug("const-reference coloring failed for $path: ${e.message}")
            } finally {
                computing.remove(path, stamp)
            }
        }
    }

    private suspend fun compute(path: String, file: VirtualFile, stamp: Long, text: String, stillCurrent: () -> Boolean) {
        val leanProject = project.service<LeanProjectService>()
        val quoted = LspUtil.quote(path)
        // Bound the wait: the deferred is completed only when the server initializes, which never happens for
        // a failing build / wrong toolchain. On timeout, bail and let a later pass retry.
        val server: LeanLanguageServer = withTimeoutOrNull(SERVER_AWAIT_TIMEOUT_MILLIS) {
            leanProject.languageServerForFile(quoted).await()
        } ?: return
        val textDocument = TextDocumentIdentifier(quoted)

        // Stage 1: in-file declarations (defs/theorems/structures/...). One documentSymbol request; paint now.
        // If it returns null / times out (server busy mid-elaboration), bail WITHOUT storing: otherwise we'd
        // cache an empty name-set, leaving every in-file def/theorem usage (e.g. `openGluedOutput`) white until
        // the next edit, even though its declaration is still lexer-colored, which looks inconsistent. A null
        // result leaves the cache stale so a later highlighting pass retries.
        val symbols = withTimeoutOrNull(DOCUMENT_SYMBOL_TIMEOUT_MILLIS) {
            server.documentSymbol(DocumentSymbolParams(textDocument))
        } ?: return
        val names = LinkedHashSet<String>()
        val constructors = LinkedHashSet<String>()
        symbols.forEach { collectSymbolNames(it, names, constructors) }
        if (!stillCurrent()) return
        store(path, stamp, names, constructors, emptySet(), emptySet(), emptySet(), text, file)

        // Stage 2: imported references. Skip on very large files (library sources opened read-only): the
        // per-identifier definition fan-out isn't worth the editor cost there, and in-file coloring already ran.
        if (text.length > MAX_TEXT_LEN_FOR_IMPORTS) return

        val resolved = importResolution.computeIfAbsent(path) { ConcurrentHashMap() }
        val candidates = distinctCandidates(text)        // name -> first occurrence offset
        // Imported references, split by kind so theorems read red and stdlib functions read distinctly from
        // in-file defs (`names`). In-file decls are NOT added here; they stay in `names` (blue / in-file-theorem).
        val importedTheorems = LinkedHashSet<String>()
        val importedFunctions = LinkedHashSet<String>()
        val projectDefs = LinkedHashSet<String>()

        // Fold in names already classified (from a previous pass), with no RPC.
        for (name in candidates.keys) {
            if (name in names) continue
            when (resolved[name]) {
                ImportKind.THEOREM -> importedTheorems.add(name)
                ImportKind.EXTERNAL_FUNCTION -> importedFunctions.add(name)
                ImportKind.PROJECT_DEF -> projectDefs.add(name)
                else -> {}
            }
        }
        var added = importedTheorems.isNotEmpty() || importedFunctions.isNotEmpty() || projectDefs.isNotEmpty()
        // Resolve the still-unknown candidates with BOUNDED CONCURRENCY: the Lean server is slow, and a serial
        // fan-out of up to MAX_DEFINITION_REQUESTS round-trips on a fresh file starved the goal/diagnostic calls
        // the user is actively waiting on. The current file's and project's real-paths are resolved once.
        // Canonical paths are blocking filesystem stats; resolve them once on the IO dispatcher.
        val (selfRealPath, projectRoot) = withContext(Dispatchers.IO) {
            realPathString(path) to project.basePath?.let { realPathString(it) }
        }
        // Per-pass cache of target-file lines (uri -> lines), so a definition file is read at most once per pass
        // even when several imported names resolve into it. Combined with the persisted `resolved` kinds, each
        // name's def-site is read at most once ever.
        val fileLines = ConcurrentHashMap<String, List<String>>()
        // Resolve ALL still-unknown candidates, in batches of MAX_DEFINITION_REQUESTS, so imported functions
        // beyond the first batch (e.g. le_trans / le_bmax_left in a big proof file) still get colored. Each batch
        // paints incrementally; the loop ends when nothing's left, when a whole batch goes unanswered (server
        // wedged, retry on a later pass), or when a newer edit supersedes this run.
        while (stillCurrent()) {
            val toResolve = candidates.entries
                .filter { (name, _) -> name !in names && resolved[name] == null }
                .take(MAX_DEFINITION_REQUESTS)
            if (toResolve.isEmpty()) break
            val gate = Semaphore(DEFINITION_CONCURRENCY)
            val results = coroutineScope {
                toResolve.map { (name, offset) ->
                    async {
                        gate.withPermit {
                            if (!stillCurrent()) return@withPermit null
                            runCatching {
                                val lc = StringUtil.offsetToLineColumn(text, offset) ?: return@runCatching null
                                // Per-request timeout: a busy server (mid-elaboration) may never answer a
                                // definition query; without this the request (and its concurrency permit) would
                                // stall indefinitely. On timeout the whole block is null, so the name is left
                                // unresolved and a later batch/pass retries it. A real answer (NOT_IMPORT, or a
                                // FUNCTION/THEOREM kind from the def-site) is recorded so it isn't re-queried.
                                withTimeoutOrNull(DEFINITION_REQUEST_TIMEOUT_MILLIS) {
                                    val site = server.definitionSite(DefinitionParams(textDocument, Position(lc.line, lc.column)))
                                    // classifyResolution reads the def-site file and resolves real paths (blocking I/O).
                                    name to withContext(Dispatchers.IO) { classifyResolution(site, path, selfRealPath, projectRoot, fileLines) }
                                }
                            }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
            var answered = 0
            var batchAdded = false
            for (r in results) {
                val (name, kind) = r ?: continue
                answered++
                resolved[name] = kind
                when (kind) {
                    ImportKind.THEOREM -> if (importedTheorems.add(name)) { added = true; batchAdded = true }
                    ImportKind.EXTERNAL_FUNCTION -> if (importedFunctions.add(name)) { added = true; batchAdded = true }
                    ImportKind.PROJECT_DEF -> if (projectDefs.add(name)) { added = true; batchAdded = true }
                    ImportKind.NOT_IMPORT -> {}
                }
            }
            if (answered == 0) break  // whole batch timed out -> server wedged; stop, retry on a later pass
            if (batchAdded && stillCurrent()) store(path, stamp, names, constructors, importedTheorems, importedFunctions, projectDefs, text, file)  // paint incrementally
        }
        if (added && stillCurrent()) store(path, stamp, names, constructors, importedTheorems, importedFunctions, projectDefs, text, file)
    }

    private fun store(
        path: String, stamp: Long, names: Set<String>, constructors: Set<String>,
        importedTheorems: Set<String>, importedFunctions: Set<String>, projectDefs: Set<String>,
        text: CharSequence, file: VirtualFile,
    ) {
        // Project defs in OTHER files color blue like in-file defs (snapshot), so your own multi-file code is
        // consistent. theoremDeclaredNames only matches `theorem X` in THIS file's text, so adding projectDefs
        // can't mis-bucket them. Imported (external/stdlib) functions are colored separately (teal).
        val snapshot = HashSet(names)
        snapshot.addAll(projectDefs)
        val theoremNames = HashSet(theoremDeclaredNames(text, snapshot))
        theoremNames.addAll(importedTheorems)
        cache[path] = Coloring(stamp, snapshot, theoremNames, HashSet(constructors), HashSet(importedFunctions))
        restartHighlighting(file)
    }

    /**
     * Classify how a candidate identifier resolves (see [ImportKind]). NOT_IMPORT if the definition is in this
     * file or unresolved. Otherwise the def-site file is read: a `theorem`/`lemma` -> THEOREM (red); a function
     * is PROJECT_DEF (blue) when its file is inside this project, or EXTERNAL_FUNCTION (teal) when it's under the
     * toolchain / `.lake/packages` (mathlib, core). Files are read at most once per pass via [fileLines].
     */
    private fun classifyResolution(
        site: LeanLanguageServer.DefinitionSite?, path: String, selfRealPath: String?, projectRoot: String?,
        fileLines: ConcurrentHashMap<String, List<String>>,
    ): ImportKind {
        if (site == null) return ImportKind.NOT_IMPORT
        if (!resolvesOutsideFile(site.uri, path, selfRealPath)) return ImportKind.NOT_IMPORT
        val lines = fileLines.computeIfAbsent(site.uri) { readUriLines(it) ?: emptyList() }
        if (isTheoremDefSite(lines, site.line)) return ImportKind.THEOREM
        val targetReal = runCatching { java.nio.file.Path.of(uriToPath(site.uri)).toRealPath().toString() }.getOrNull()
        return if (isExternalPath(targetReal, projectRoot)) ImportKind.EXTERNAL_FUNCTION else ImportKind.PROJECT_DEF
    }

    /** True if a definition's file is OUTSIDE this project: a dependency under `.lake/packages` or the Lean
     *  toolchain under `.elan`, or simply not under [projectRoot]. Those are the "imported/stdlib" symbols. */
    private fun isExternalPath(targetReal: String?, projectRoot: String?): Boolean {
        if (targetReal == null) return true
        // Normalize separators so the markers match on Windows too (toRealPath yields backslashes there).
        val normalized = targetReal.replace('\\', '/')
        if (normalized.contains("/.lake/") || normalized.contains("/.elan/")) return true
        val root = projectRoot?.replace('\\', '/') ?: return false
        // Compare by path segment (under "root/"), not a raw prefix, so /a/proj does not falsely match a
        // sibling /a/proj-extra.
        return normalized != root && !normalized.startsWith("$root/")
    }

    /** Read the lines of a `file://` URI's file, or null if it can't be read or is implausibly large. */
    private fun readUriLines(uri: String): List<String>? = runCatching {
        val p = java.nio.file.Path.of(uriToPath(uri))
        if (!java.nio.file.Files.isRegularFile(p) || java.nio.file.Files.size(p) > MAX_DEF_SITE_FILE_BYTES) return null
        java.nio.file.Files.readAllLines(p)
    }.getOrNull()

    /** Scan from the def-site line upward a few lines for the nearest declaration keyword; true iff it's a
     *  theorem/lemma. An imported reference always resolves to a `def`/`theorem`/…; default to false (function). */
    private fun isTheoremDefSite(lines: List<String>, line: Int): Boolean {
        if (lines.isEmpty()) return false
        val start = line.coerceIn(0, lines.size - 1)
        for (i in start downTo maxOf(0, start - DEF_SITE_LOOKBACK)) {
            val m = DECL_KEYWORD.find(lines[i]) ?: continue
            return m.groupValues[1] in THEOREM_KEYWORDS
        }
        return false
    }

    /** Of [names], those declared in [text] with theorem/lemma; colored as theorems, not definitions. */
    private fun theoremDeclaredNames(text: CharSequence, names: Set<String>): Set<String> {
        val out = HashSet<String>()
        for (m in THEOREM_DECL.findAll(text)) {
            val nm = m.groupValues[1].substringAfterLast('.')
            if (nm in names) out.add(nm)
        }
        return out
    }

    private fun collectSymbolNames(
        symbol: Either<SymbolInformation, DocumentSymbol>,
        out: MutableSet<String>,
        constructors: MutableSet<String>,
    ) {
        if (symbol.isRight) {
            val ds = symbol.right
            when {
                isFieldLike(ds.kind) -> {}                       // fields are painted by the LSP property token
                isConstructorLike(ds.kind) -> addName(ds.name, constructors)
                else -> addName(ds.name, out)
            }
            // Still recurse into children (e.g. an inductive's constructors) even when the parent is skipped.
            ds.children?.forEach { collectSymbolNames(Either.forRight(it), out, constructors) }
        } else if (symbol.isLeft) {
            val si = symbol.left
            when {
                isFieldLike(si.kind) -> {}
                isConstructorLike(si.kind) -> addName(si.name, constructors)
                else -> addName(si.name, out)
            }
        }
    }

    /** documentSymbol reports an inductive's constructors as [SymbolKind.Constructor] / [SymbolKind.EnumMember]. */
    private fun isConstructorLike(kind: SymbolKind?): Boolean =
        kind == SymbolKind.Constructor || kind == SymbolKind.EnumMember

    /**
     * A structure field is reported by documentSymbol with [SymbolKind.Field]; do NOT add it to the def matcher.
     * lsp4ij already paints field references (e.g. `r.stop`) from the Lean server's `property` semantic token, so
     * adding the field name here would make the annotator paint EVERY `stop` as a def (LEAN_DEFINITION, blue),
     * racing with that property color at equal INFORMATION severity (non-deterministic flicker). Skipping
     * Field-kind names keeps fields consistently field-colored / plain.
     */
    private fun isFieldLike(kind: SymbolKind?): Boolean = kind == SymbolKind.Field

    /** Lean symbol names may be qualified (Ttyterminal.parseBytePos); color the full name and its last segment. */
    private fun addName(raw: String?, out: MutableSet<String>) {
        val name = raw?.trim() ?: return
        // Skip Capitalized names (types / namespaces / constructors): the native syntax highlighter already
        // colors those, and repainting them here would replace their type color (the "Region goes blue then
        // white" flicker). This feature only colors lowercase function/def references.
        if (name.length >= MIN_NAME_LEN && !name[0].isUpperCase() && name !in KEYWORDS) out.add(name)
        val last = name.substringAfterLast('.')
        if (last.length >= MIN_NAME_LEN && last != name && !last[0].isUpperCase() && last !in KEYWORDS) out.add(last)
    }

    /** Distinct lowercase identifier segments worth resolving, mapped to their first occurrence offset IN CODE.
     *  Occurrences inside comments/strings are skipped: a name often appears in a doc comment before its first
     *  real use (`/-- ... a defined transition. -/` above `theorem ... transition s b`), and firing
     *  textDocument/definition at that comment offset resolves to nothing, so the symbol was mis-classified as
     *  NOT_IMPORT and left white. Whether a name first shows up in a comment varies per file, which is exactly
     *  why the same code colored differently across projects. */
    private fun distinctCandidates(text: CharSequence): Map<String, Int> {
        val skipStarts = ArrayList<Int>()
        val skipEnds = ArrayList<Int>()
        for (s in SKIP.findAll(text)) { skipStarts.add(s.range.first); skipEnds.add(s.range.last) }
        val out = LinkedHashMap<String, Int>()
        for (m in IDENTIFIER.findAll(text)) {
            val off = m.range.first
            if (inSkipSpan(skipStarts, skipEnds, off)) continue
            val tok = m.value
            if (tok.length < MIN_NAME_LEN) continue
            if (tok[0].isUpperCase()) continue
            if (tok in KEYWORDS) continue
            out.putIfAbsent(tok, off)
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }

    /** True if [pos] is inside one of the ascending, non-overlapping comment/string spans (binary search). */
    private fun inSkipSpan(starts: List<Int>, ends: List<Int>, pos: Int): Boolean {
        var lo = 0; var hi = starts.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        return idx >= 0 && pos <= ends[idx]
    }

    /** The canonical filesystem path of [path], resolved ONCE per compute, or null if it can't be resolved. */
    private fun realPathString(path: String): String? =
        runCatching { java.nio.file.Path.of(path).toRealPath().toString() }.getOrNull()

    /**
     * True if a definition result [targetUri] points to a file OTHER than [path] (an imported symbol). Compares
     * canonicalized filesystem paths rather than raw strings, so percent-encoding / case / separator differences
     * in the server's `file://` URI don't mis-classify a same-file definition as an import. [selfRealPath] is
     * the current file's canonical path, pre-resolved by the caller so it isn't re-stat'd per candidate.
     */
    private fun resolvesOutsideFile(targetUri: String, path: String, selfRealPath: String?): Boolean {
        val targetPath = uriToPath(targetUri)
        val targetReal = runCatching { java.nio.file.Path.of(targetPath).toRealPath().toString() }.getOrNull()
        if (targetReal != null && selfRealPath != null) return targetReal != selfRealPath
        return targetPath != path
    }

    /** Convert a server `file://` URI to a filesystem path, tolerating percent-encoding / non-file schemes. */
    private fun uriToPath(uri: String): String = runCatching {
        val u = java.net.URI(uri)
        if (u.scheme == "file") java.nio.file.Paths.get(u).toString() else LspUtil.unquote(uri)
    }.getOrElse { LspUtil.unquote(uri) }

    private fun restartHighlighting(file: VirtualFile) {
        if (project.isDisposed) return
        val psiFile: PsiFile = ReadAction.compute<PsiFile?, Throwable> {
            if (project.isDisposed) null else PsiManager.getInstance(project).findFile(file)
        } ?: return
        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 80L
        private const val SERVER_AWAIT_TIMEOUT_MILLIS = 15_000L
        private const val MIN_NAME_LEN = 2
        // How many imported references to resolve per pass. Raised so imported functions/lemmas (le_trans,
        // getObjVal?, ...) actually color on big proof files; the per-request timeout below keeps a busy server
        // from stalling, and resolutions are cached, so the cost is paid roughly once per name.
        private const val MAX_DEFINITION_REQUESTS = 200
        private const val MAX_CANDIDATES = 600
        // Resolve imported-reference definitions this many at a time (bounded concurrency vs. the slow server).
        private const val DEFINITION_CONCURRENCY = 6
        // Give up on a single textDocument/definition query after this long (busy/mid-elaboration server).
        private const val DEFINITION_REQUEST_TIMEOUT_MILLIS = 4_000L
        // Give up on the documentSymbol request after this long; on timeout we DON'T cache, so a later pass retries
        // (otherwise an empty cache would leave in-file def/theorem usages uncolored until the next edit).
        private const val DOCUMENT_SYMBOL_TIMEOUT_MILLIS = 8_000L
        // Skip the imported-reference resolution stage above this document length (large read-only library files).
        private const val MAX_TEXT_LEN_FOR_IMPORTS = 500_000
        // An identifier SEGMENT (no dot): a dotted name like List.foldl yields the segments List and foldl.
        private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_'?!]*""")
        // Line comments, block/doc comments and double-quoted strings: candidate offsets inside these are
        // skipped so a name's resolution fires at a real code position. The string clause uses the unrolled
        // form (matches runs of non-quote/non-escape iteratively) to avoid the per-char recursion that
        // overflowed the stack on long strings in large library files.
        private val SKIP = Regex("""--[^\n]*|/-[\s\S]*?-/|"[^"\\]*(?:\\.[^"\\]*)*"""")
        // Captures the declared name after a theorem/lemma/example keyword (to bucket it as a theorem).
        private val THEOREM_DECL = Regex("""\b(?:theorem|lemma|example)\s+([\p{L}_][\p{L}\p{N}_'.]*)""")
        // Declaration keyword at an imported symbol's def-site, used to tell a theorem from a function.
        private val DECL_KEYWORD = Regex("""\b(theorem|lemma|def|abbrev|instance|opaque|axiom)\b""")
        private val THEOREM_KEYWORDS = setOf("theorem", "lemma")
        // How many lines above the def-site line to scan for the keyword (multi-line signatures / attribute lines).
        private const val DEF_SITE_LOOKBACK = 3
        // Don't read implausibly large def-site files just to classify one symbol.
        private const val MAX_DEF_SITE_FILE_BYTES = 8_000_000L
        private val KEYWORDS = setOf(
            "def", "theorem", "lemma", "example", "instance", "structure", "inductive", "class", "abbrev",
            "axiom", "constant", "mutual", "where", "with", "match", "fun", "let", "have", "show", "from",
            "suffices", "by", "do", "then", "else", "if", "calc", "return", "open", "namespace", "end",
            "import", "section", "variable", "universe", "deriving", "extends", "set_option", "macro",
            "notation", "infix", "infixl", "infixr", "prefix", "postfix", "syntax", "true", "false",
            "Prop", "Type", "Sort", "sorry", "admit", "nomatch", "rec", "in", "at"
        )
    }
}
