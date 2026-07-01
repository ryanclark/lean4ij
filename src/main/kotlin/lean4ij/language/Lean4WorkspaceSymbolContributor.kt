package lean4ij.language

// it is removed
// import com.redhat.devtools.lsp4ij.features.workspaceSymbol.LSPWorkspaceSymbolContributor
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.ExecutionError
import com.google.common.util.concurrent.UncheckedExecutionException
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.jetbrains.rd.util.AtomicInteger
import com.redhat.devtools.lsp4ij.LanguageServerManager
import kotlinx.coroutines.FlowPreview
import lean4ij.project.LeanProjectService
import lean4ij.setting.Lean4Settings
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException


/**
 * copy from LSPWorkspaceSymbolContributor
 * for currently in lean4 the api `$/cancelRequest` seems, not cancellable in fact...
 * Hence, we copy the class for do some
 * ref: https://plugins.jetbrains.com/docs/intellij/go-to-class-and-go-to-symbol.html
 * TODO maybe do some PR to Lean4 and move back to LSPWorkspaceSymbolContributor
 * TODO the order seems incorrect and cannot rewrite
 * TODO cannot open result in find tool window, don't know why
 * TODO if possible remove this and move back to lsp4ij
 * TODO remove using the class LeanWorkspaceSymbolData, the involvement
 */
abstract class Lean4ChooseByNameContributorEx : ChooseByNameContributorEx {

    abstract fun filter(data: LeanWorkspaceSymbolData): Boolean

    override fun processNames(
        processor: Processor<in String?>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val project = scope.project ?: return
        // TODO is it better way for this?
        if (!project.service<LeanProjectService>().isLeanProject()) {
            return
        }
        var queryString = project.getUserData(ChooseByNamePopup.CURRENT_SEARCH_PATTERN)
        if (queryString == null) {
            queryString = ""
        }
        val items = getWorkspaceSymbols(queryString, project)
        items?.stream()
            ?.filter { data -> data.file != null && data.file.extension == "lean" }
            ?.filter { data -> filter(data) }
            ?.filter { data: LeanWorkspaceSymbolData -> scope.accept(data.file!!) }
            ?.map { obj: LeanWorkspaceSymbolData -> obj.name }
            ?.forEach { t: String? ->
                processor.process(t)
            }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem?>,
        parameters: FindSymbolParameters
    ) {
        val items = getWorkspaceSymbols(name, parameters.project)
        items?.stream()
            ?.filter { data -> data.file != null && data.file.extension == "lean" }
            ?.filter { data -> filter(data) }
            ?.filter { ni: LeanWorkspaceSymbolData -> parameters.searchScope.accept(ni.file!!) }
            ?.forEach { t: LeanWorkspaceSymbolData? ->
                processor.process(t)
            }
    }

    private fun getWorkspaceSymbols(key: String, project: Project): List<LeanWorkspaceSymbolData>? {
        val workspaceCache = project.service<WorkspaceSymbolsCache>()
        val items = workspaceCache.getWorkspaceSymbols(key)
        return items
    }

}

class Lean4WorkspaceSymbolContributor : Lean4ChooseByNameContributorEx() {
    override fun filter(data: LeanWorkspaceSymbolData): Boolean {
        return true
    }
}

/**
 * Pure predicate for [Lean4WorkspaceClassContributor.filter]: a symbol is treated as a class
 * when the last dotted segment of its (qualified) name starts with an uppercase character.
 * Extracted so the logic can be characterized without constructing a [LeanWorkspaceSymbolData]
 * (whose initialization needs a live IDE/Application).
 */
internal fun isClassSymbolName(name: String): Boolean =
    name.split(".").last().let { it[0].isUpperCase() }

/**
 * Pure suffix-trigger predicate for [WorkspaceSymbolsCache.canTrigger]: a query triggers a
 * workspace request once it ends with the configured trigger suffix.
 */
internal fun queryCanTrigger(queryString: String, suffix: String): Boolean =
    queryString.endsWith(suffix)

/**
 * Pure query normalization for [WorkspaceSymbolsCache.normalize]: strip the configured trigger
 * suffix off the end of the query before sending/looking up the symbols request.
 */
internal fun normalizeQuery(queryString: String, suffix: String): String =
    queryString.removeSuffix(suffix)

class Lean4WorkspaceClassContributor : Lean4ChooseByNameContributorEx() {
    override fun filter(data: LeanWorkspaceSymbolData): Boolean {
        return isClassSymbolName(data.name)
    }
}

class WorkspaceSymbolsCacheLoader(private val project: Project) :
    CacheLoader<String, List<LeanWorkspaceSymbolData>>() {

    companion object {
        // Lean's server ignores $/cancelRequest, so an un-timed get() on a hung server would pin this
        // Goto-Symbol worker thread indefinitely. Bound both blocking gets.
        private const val GET_TIMEOUT_SECONDS = 30L
    }

    override fun load(key: String): List<LeanWorkspaceSymbolData> {
        thisLogger().info("loading symbols for $key")
        // Pure server-fetch: waking the server (isEnable.set(true)) is now done by the caller
        // (WorkspaceSymbolsCache.getWorkspaceSymbols), so this loader has no side effect beyond the RPC.
        // TODO change the old way getting language server to this maybe!
        val languageServerItem = try {
            LanguageServerManager.getInstance(project)
                .getLanguageServer("lean").get(GET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            thisLogger().warn("timed out resolving lean language server for workspace symbols ($key)")
            return listOf()
        }
        if (languageServerItem == null) {
            // for guava loading cache, in fact this cannot be null
            // return null will trigger an exception and do not cache the value(null)
            // this is the expected behavior
            // we will catch it when using
            throw IllegalStateException("Language server not found")
        }
        val ls = languageServerItem.server
        val params = WorkspaceSymbolParams(key)
        val symbols = try {
            ls.workspaceService.symbol(params).get(GET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            thisLogger().warn("timed out fetching workspace symbols for $key")
            return listOf()
        } ?: return listOf()
        val items: MutableList<LeanWorkspaceSymbolData> = ArrayList()
        if (symbols.isLeft) {
            // lsp4j deprecated SymbolInformation wholesale, but the workspace/symbol left branch still delivers it.
            @Suppress("DEPRECATION")
            for (si in symbols.left) {
                // Skip a malformed symbol rather than !! and abort the whole batch with an NPE.
                val name = si?.name ?: continue
                val kind = si.kind ?: continue
                val location = si.location ?: continue
                items.add(LeanWorkspaceSymbolData(name, kind, location, project))
            }
        } else if (symbols.isRight) {
            val ws = symbols.right
            for (si in ws) {
                val item = createItem(si!!, project)
                items.add(item)
            }
        }
        return items
    }

    fun createItem(si: WorkspaceSymbol, project: Project): LeanWorkspaceSymbolData {
        val name = si.name
        val symbolKind = si.kind
        if (si.location.isLeft) {
            return LeanWorkspaceSymbolData(
                name, symbolKind, si.location.left, project
            )
        }
        return LeanWorkspaceSymbolData(
            name, symbolKind, si.location.right.uri, null, project
        )
    }
}

@OptIn(FlowPreview::class)
@Service(Service.Level.PROJECT)
class WorkspaceSymbolsCache(private val project: Project) {
    private val lean4Settings = service<Lean4Settings>()

    // TODO here every output should also be cache
    private val symbolsCache: LoadingCache<String, List<LeanWorkspaceSymbolData>> = CacheBuilder.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build(WorkspaceSymbolsCacheLoader(project))

    private fun canTrigger(queryString: String) = queryCanTrigger(queryString, lean4Settings.workspaceSymbolTriggerSuffix)

    private fun normalize(queryString: String) = normalizeQuery(queryString, lean4Settings.workspaceSymbolTriggerSuffix)

    /**
     * [LoadingCache.get] but never propagates a loader failure to the platform-invoked contributor (which
     * would surface as an IDE error). Cache-loader exceptions are downgraded to info, matching the original
     * debounce-path handling; returns null on failure.
     */
    private fun safeGet(key: String): List<LeanWorkspaceSymbolData>? {
        return try {
            symbolsCache.get(key)
        } catch (e: Exception) {
            when {
                e is ExecutionException || e is UncheckedExecutionException || e is ExecutionError ->
                    thisLogger().info(e)
                else ->
                    thisLogger().error(e)
            }
            null
        }
    }

    fun getWorkspaceSymbolsTriggeredBySuffix(queryString: String): List<LeanWorkspaceSymbolData> {
        if (canTrigger(queryString)) {
            // Was an unguarded symbolsCache.get(): the loader can throw (e.g. server not ready -> the
            // IllegalStateException), which on this path propagated out of processNames as an IDE error.
            safeGet(normalize(queryString))
            return listOf()
        }
        // immediately return if the cache contains it
        val data = symbolsCache.getIfPresent(normalize(queryString))
        if (data != null) {
            for (entry in data) {
                symbolsCache.put(entry.name, listOf(entry))
            }
        }
        return data ?: listOf()
    }

    private val requestCounter = AtomicInteger(0)
    private val SLEEP_TIME = 10L

    fun getWorkspaceSymbolsTriggeredByDebounce(queryString: String): List<LeanWorkspaceSymbolData> {
        // immediately return if the cache contains it
        symbolsCache.getIfPresent(queryString)?.let {
            return it
        }
        val currentCnt = requestCounter.incrementAndGet()
        for (i in 1..1000) {
            if (i * SLEEP_TIME > lean4Settings.workspaceSymbolTriggerDebouncingTime) {
                break
            }
            // Honor cancellation: Goto-Symbol/Class runs this on a pooled thread under a ProgressIndicator,
            // so when the user keeps typing or closes the popup this unwinds instead of sleeping on.
            ProgressManager.checkCanceled()
            Thread.sleep(SLEEP_TIME)
            val newCnt = requestCounter.get()
            if (currentCnt != newCnt) {
                thisLogger().info("current cnt $currentCnt != new cnt $newCnt for $queryString")
                return listOf()
            }
        }
        // TODO here it in fact cannot be null
        val symbolDataList = safeGet(queryString) ?: return listOf()
        for (symbolData in symbolDataList) {
            // put all entries in the symbolsCache, for IJ seems making request to the returned entries too
            symbolData.name.let { symbolsCache.put(it, listOf(symbolData)) }
        }
        return symbolDataList
    }

    fun getWorkspaceSymbols(queryString: String): List<LeanWorkspaceSymbolData> {
        // Wake the (lazy) Lean server on any Goto-Symbol/Class query. Done here rather than inside the cache
        // loader so the loader is a pure server-fetch; the set is an idempotent AtomicBoolean, so firing it on
        // every query (not only on a cache miss) is harmless and preserves the server-wake contract.
        project.service<LeanProjectService>().isEnable.set(true)
        if (lean4Settings.strategyForTriggeringSymbolsOrClassesRequests == Lean4Settings.SYMBOL_REQUEST_SUFFIX) {
            return getWorkspaceSymbolsTriggeredBySuffix(queryString)
        } else {
            return getWorkspaceSymbolsTriggeredByDebounce(queryString)
        }
    }

}