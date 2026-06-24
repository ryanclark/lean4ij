package lean4ij.project

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import lean4ij.lsp.data.RpcConnectParams
import lean4ij.lsp.data.RpcKeepAliveParams

/**
 * Owns one [LeanFile]'s Lean RPC session lifecycle: connect (with double-checked locking under a mutex), the
 * single keep-alive loop, and reset on restart. Extracted from LeanFile (an otherwise god-object); strictly
 * behavior-preserving.
 *
 * [session] is the single source of truth. It is mutated only inside [updateSession] under [sessionMutex] (and
 * nulled by [reset]); callers read it after a successful [getSession]/[updateSession]. Kept a plain var (as in
 * the original LeanFile) - concurrent reconnects collapse via the mutex + double check.
 */
class LeanFileSession(
    private val leanProjectService: LeanProjectService,
    private val scope: CoroutineScope,
    private val file: String,
) {

    var session : String? = null
    private val sessionMutex : Mutex = Mutex()

    /** The single keep-alive loop's job; a new session cancels the previous loop. */
    private var keepAliveJob: Job? = null

    suspend fun getSession() : String {
        updateSession(null)
        return session!!
    }

    /**
     * Here the argument [oldSession] must be passed for there maybe concurrent access for updating session, for example
     * multiple rpc calls like "Lean.Widget.getInteractiveGoals" and "Lean.Widget.getInteractiveTermGoal" and
     * "Lean.Widget.getWidgets" etc
     * TODO check [Mutex]'s behavior, for example: in [here](https://discuss.kotlinlang.org/t/is-it-always-safe-to-just-convert-synchronized-to-mutex-withlock/26519)
     * TODO check if it's better way than double locking check
     */
    suspend fun updateSession(oldSession: String?) {
        if (oldSession == session) {
            // TODO check this timeout, check the following rpcConnect for the following timeout
            withTimeout(5*1000) {
                sessionMutex.withLock {
                    if (oldSession == session) {
                        session = leanProjectService.languageServerForFile(file).await().rpcConnect(RpcConnectParams(file)).sessionId
                        // keep alive making infoToInteractive behave better, for the reference must have the same session
                        // as the goal result, so keep it alive here...
                        // TODO is here will cause multiple keep alive loop?
                        keepAlive()
                    }
                }
            }
        }
    }

    /** Reset on server restart (see [LeanFile.restart]); a subsequent [getSession] reconnects. */
    fun reset() {
        // Cancel the keep-alive loop too. LeanFile.restart() nulls the session but does not reconnect, and the
        // loop now survives exceptions (the catch below), so without this it keeps firing every 9s on
        // RpcKeepAliveParams(file, session!!) -> NPE (swallowed, debug-logged) until the next getSession()
        // reconnects. A fresh getSession() starts a new loop.
        keepAliveJob?.cancel()
        keepAliveJob = null
        session = null
    }

    /**
     * TODO maybe it should not always keep alive
     */
    private fun keepAlive() {
        // Exactly one keep-alive loop per file: every session (re)connect calls this, so cancel the previous
        // loop before starting a new one. Otherwise loops accumulate against the shared `session` field and
        // never terminate. Launched on the project scope (not a free, never-cancelled IO scope) so it is torn
        // down on project dispose instead of leaking for the IDE's lifetime.
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(9 * 1000)
                try {
                    leanProjectService.languageServerForFile(file).await().rpcKeepAlive(RpcKeepAliveParams(file, session!!))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Server may be restarting / the session may be stale; don't let this loop crash with
                    // an unhandled exception (SEVERE IDE error popup). It resumes once the server is back.
                    thisLogger().debug("rpcKeepAlive failed for $file (language server unavailable/restarting): ${e.message}")
                }
            }
        }
    }
}
