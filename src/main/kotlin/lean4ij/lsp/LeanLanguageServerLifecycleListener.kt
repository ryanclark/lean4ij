package lean4ij.lsp

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerWrapper
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleListener
import lean4ij.project.LeanProjectService
import lean4ij.util.Constants
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import java.lang.reflect.Proxy

class LeanLanguageServerLifecycleListener(val project: Project) {
    private val leanProjectService: LeanProjectService = project.service()

    fun handleStatusChanged(languageServer: LanguageServerWrapper) {
        val serverId = languageServer.serverDefinition.id
        // Accept the root "lean" server AND every per-package "lean::..." server (multi-package monorepos).
        if (!Constants.isLeanServerId(serverId)) {
            return
        }
        // Log every transition so unexpected stop/restart churn is visible in idea.log. A burst of
        // started -> stopping -> started here means lsp4ij is restarting the server, not that the
        // Lean server itself crashed.
        thisLogger().info("Lean language server '$serverId' status changed to ${languageServer.serverStatus}")
        // Reset (and clear the file-progress bar) whenever the server is going away. A *restart*
        // transitions stopping -> starting -> started and never emits `stopped`, so keying only off
        // stopped/none left the in-flight progress bar stuck forever after a restart.
        if (languageServer.serverStatus == ServerStatus.none
            || languageServer.serverStatus == ServerStatus.stopped
            || languageServer.serverStatus == ServerStatus.stopping) {
            leanProjectService.resetServer(serverId)
        }
        // TODO maybe reset initializedServer to null also in here?
        if (languageServer.serverStatus == ServerStatus.started) {
            languageServer.initializedServer.thenAccept {
                leanProjectService.setInitializedServer(serverId, it)
            }
        }
    }

    private val hoverRequests = mutableSetOf<String>()

    fun handleLSPMessage(message: Message, consumer: MessageConsumer, languageServer: LanguageServerWrapper) {
        val serverId = languageServer.serverDefinition.id
        if (!Constants.isLeanServerId(serverId)) {
            return
        }
        if (message is RequestMessage) {
            if (message.params is HoverParams) {
                hoverRequests.add(message.id)
            }
        }
        if (message is ResponseMessage) {
            if (message.result is InitializeResult) {
                leanProjectService.setInitializedResult(serverId, message.result as InitializeResult)
            }
            if (message.id in hoverRequests) {
                // get current hover results for showing highlight of current content
                hoverRequests.remove(message.id)
                // TODO here I got a cast Exception earlier, but
                //      but unable to reproduce it in develop environment yet
                //      Checking LSP the message.result do be a Hover object
                //      in org.eclipse.lsp4j.services.TextDocumentService.hover
                //      So why could it fail to cast to Hover?
                try {
                    leanProjectService.highlightCurrentContent(message.result as Hover?)
                } catch (e: Exception) {
                    thisLogger().error("Failed to cast message.result to Hover with result ${message.result}", e)
                    throw e
                }
            }
            // This is not customize used in yet
            // if (message.result is SemanticTokens) {
            // }
        }
        (message as? NotificationMessage)?.let {
            // TODO for it seems no duplicated method can be defined for current version of lsp4j, we use
            //      listener here
            leanProjectService.emitServerEvent(message)
            if (it.method == "textDocument/publishDiagnostics") {
                val diagnostic = it.params as PublishDiagnosticsParams
                leanProjectService.file(diagnostic.uri).publishDiagnostics(diagnostic)
            }
        }
    }

}

/**
 * temporally implement [LanguageServerLifecycleListener] for it's marked as internal,
 * but I don't have enough time to refactor it yet
 * But it does need to be removed, see https://github.com/redhat-developer/lsp4ij/discussions/1324
 * Currently the reason for using the listener is:
 * - There are some features like vertical status bar for file progressing, requires knowing the status of language protocol server
 * - Hover highlight requires the start/end information of Hover
 * TODO remove this
 */
object LeanLanguageServerLifecycleListenerProxyFactory {
    fun create(project: Project): Any {
        val target = LeanLanguageServerLifecycleListener(project)
        val interfaceClass = Class.forName("com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleListener")
        return Proxy.newProxyInstance(
            this::class.java.classLoader,
            // target::class.java.classLoader,
            arrayOf(interfaceClass)
        ) { proxy, method, args ->
            return@newProxyInstance when (method.name) {
                "handleLSPMessage" -> target.handleLSPMessage(args[0] as Message, args[1] as MessageConsumer, args[2] as LanguageServerWrapper)
                "handleStatusChanged" -> target.handleStatusChanged(args[0] as LanguageServerWrapper)
                else -> {

                }
            }
        }
    }
}