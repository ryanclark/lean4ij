package lean4ij.util

object Constants {
    /**
     * This is duplicated in plugin.xml
     */
    const val LEAN_LANGUAGE_SERVER_ID = "lean"
    const val LEAN_LANGUAGE_ID = "lean"

    /**
     * Server id for a non-root Lake package, e.g. "lean::e" for `<root>/e`. The root package keeps the
     * static [LEAN_LANGUAGE_SERVER_ID] from plugin.xml.
     */
    fun leanServerId(relPackagePath: String): String = "$LEAN_LANGUAGE_SERVER_ID::$relPackagePath"

    /** Whether [id] is one of lean4ij's servers (the root "lean" or a per-package "lean::..."). */
    fun isLeanServerId(id: String): Boolean =
        id == LEAN_LANGUAGE_SERVER_ID || id.startsWith("$LEAN_LANGUAGE_SERVER_ID::")

    const val LEAN_PLAIN_GOAL = "\$/lean/plainGoal"
    const val LEAN_PLAIN_TERM_GOAL = "\$/lean/plainTermGoal"
    const val LEAN_RPC_CONNECT = "\$/lean/rpc/connect"
    const val LEAN_RPC_CALL = "\$/lean/rpc/call"
    const val LEAN_RPC_KEEP_ALIVE = "\$/lean/rpc/keepAlive"
    const val RPC_METHOD_INFO_TO_INTERACTIVE = "Lean.Widget.InteractiveDiagnostics.infoToInteractive"
    const val RPC_METHOD_GET_INTERACTIVE_GOALS = "Lean.Widget.getInteractiveGoals"
    const val RPC_METHOD_GET_INTERACTIVE_TERM_GOAL = "Lean.Widget.getInteractiveTermGoal"
    const val RPC_METHOD_GET_INTERACTIVE_DIAGNOSTICS = "Lean.Widget.getInteractiveDiagnostics"
    const val RPC_METHOD_LAZY_TRACE_CHILDREN_TO_INTERACTIVE = "Lean.Widget.lazyTraceChildrenToInteractive"
    const val RPC_METHOD_GET_GOTO_LOCATION = "Lean.Widget.getGoToLocation"
    const val FILE_PROGRESS = "\$/lean/fileProgress"

    /**
     * This is duplicated in App.tsx
     */
    const val EXTERNAL_INFOVIEW_SERVER_INITIALIZED = "serverInitialized"
    const val EXTERNAL_INFOVIEW_CHANGED_CURSOR_LOCATION = "changedCursorLocation"
}