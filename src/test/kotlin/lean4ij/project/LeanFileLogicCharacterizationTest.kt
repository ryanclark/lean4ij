package lean4ij.project

import lean4ij.lsp.data.FileProgressProcessingInfo
import lean4ij.lsp.data.Position
import lean4ij.lsp.data.ProcessingInfo
import lean4ij.lsp.data.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of the pure logic extracted from / living next to
 * [LeanFile]:
 *
 *  - [classifyRpcRetry]: the RPC retry / error-code classification that used to be an inline `if` chain
 *    inside `LeanFile.rpcCallWithRetry`. Extracted behavior-preservingly into an internal pure function.
 *  - [FileProgressProcessingInfo.workSize]: the file-progress percentage arithmetic.
 *
 * Everything else in [LeanFile] is coroutine / LSP / IDE-Application coupled and is NOT unit-testable
 * without a live Application, so it is intentionally not covered here.
 */
class LeanFileLogicCharacterizationTest {

    // ---------------------------------------------------------------------------------------------
    // classifyRpcRetry
    // ---------------------------------------------------------------------------------------------

    @Test
    fun outdatedRpcSessionRetries() {
        assertEquals(
            RpcRetryDecision.RETRY_AFTER_SESSION_UPDATE,
            classifyRpcRetry(-32900, "Outdated RPC session"),
        )
    }

    @Test
    fun outdatedRpcSessionRequiresExactMessage() {
        // -32900 only retries on the exact "Outdated RPC session" message; otherwise it is unknown -> rethrow.
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32900, "outdated rpc session"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32900, "Outdated RPC session "))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32900, "something else"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32900, null))
    }

    @Test
    fun elaborationInterruptedReturnsNull() {
        assertEquals(RpcRetryDecision.RETURN_NULL, classifyRpcRetry(-32603, "elaboration interrupted"))
    }

    @Test
    fun elaborationInterruptedRequiresExactMessage() {
        // -32603 uses an exact-equality check, not contains.
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32603, "elaboration interrupted!"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32603, "some other internal error"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32603, null))
    }

    @Test
    fun noRpcMethodReturnsNull() {
        // -32601 uses a contains() check on "No RPC method".
        assertEquals(
            RpcRetryDecision.RETURN_NULL,
            classifyRpcRetry(-32601, "No RPC method 'Lean.Widget.getInteractiveDiagnostics' found"),
        )
        assertEquals(RpcRetryDecision.RETURN_NULL, classifyRpcRetry(-32601, "No RPC method"))
        // contains, so a leading prefix still matches.
        assertEquals(RpcRetryDecision.RETURN_NULL, classifyRpcRetry(-32601, "xxNo RPC methodyy"))
    }

    @Test
    fun noRpcMethodWithNonMatchingMessageRethrows() {
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32601, "no rpc method"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32601, "method not found"))
    }

    @Test
    fun cannotProcessRequestToClosedFileReturnsNull() {
        // -32801 uses contains() on "Cannot process request to closed file " (note the trailing space).
        assertEquals(
            RpcRetryDecision.RETURN_NULL,
            classifyRpcRetry(-32801, "Cannot process request to closed file 'file:///foo.lean'"),
        )
    }

    @Test
    fun cannotProcessRequestToClosedFileRequiresTrailingSpace() {
        // The pinned substring includes a trailing space; without it the substring is not present -> rethrow.
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32801, "Cannot process request to closed file"))
    }

    @Test
    fun cannotDecodeParamsReturnsNull() {
        assertEquals(
            RpcRetryDecision.RETURN_NULL,
            classifyRpcRetry(-32602, "Cannot decode params in RPC call 'Lean.Widget...'"),
        )
        assertEquals(RpcRetryDecision.RETURN_NULL, classifyRpcRetry(-32602, "Cannot decode params in RPC call"))
    }

    @Test
    fun cannotDecodeParamsWithNonMatchingMessageRethrows() {
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32602, "Invalid params"))
    }

    @Test
    fun unknownCodeRethrows() {
        // An unrelated/unknown code is rethrown regardless of message.
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32000, "Outdated RPC session"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(0, "elaboration interrupted"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32700, "anything"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(12345, null))
    }

    @Test
    fun codeMismatchEvenWithMatchingMessageRethrows() {
        // The classification is keyed on (code, message): a matching message under the wrong code is unknown.
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32603, "Outdated RPC session"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32900, "elaboration interrupted"))
        assertEquals(RpcRetryDecision.RETHROW, classifyRpcRetry(-32602, "No RPC method"))
    }

    @Test
    fun nullMessageOnContainsBranchesThrowsLikeOriginal() {
        // The original inline code dereferenced the non-null platform String for the contains() branches,
        // so reaching one of those branches (code matches) with a null message throws NPE. Pinned here.
        assertThrows(NullPointerException::class.java) { classifyRpcRetry(-32601, null) }
        assertThrows(NullPointerException::class.java) { classifyRpcRetry(-32801, null) }
        assertThrows(NullPointerException::class.java) { classifyRpcRetry(-32602, null) }
    }

    // ---------------------------------------------------------------------------------------------
    // FileProgressProcessingInfo.workSize()
    // ---------------------------------------------------------------------------------------------

    private fun processing(startLine: Int, endLine: Int): FileProgressProcessingInfo {
        val range = Range(
            start = Position(line = startLine, character = 0),
            end = Position(line = endLine, character = 0),
        )
        return FileProgressProcessingInfo(
            textDocument = TextDocumentIdentifier("file:///foo.lean"),
            processing = listOf(ProcessingInfo(range = range, kind = 0)),
        )
    }

    private fun finished(): FileProgressProcessingInfo =
        FileProgressProcessingInfo(
            textDocument = TextDocumentIdentifier("file:///foo.lean"),
            processing = emptyList(),
        )

    @Test
    fun workSizeWhenFinishedIsHundred() {
        assertEquals(100, finished().workSize())
    }

    @Test
    fun workSizeAtStartOfFullFile() {
        // (0+1)*100/(99+1) = 100/100 = 1
        assertEquals(1, processing(0, 99).workSize())
    }

    @Test
    fun workSizeHalfwayThroughFullFile() {
        // (49+1)*100/(99+1) = 5000/100 = 50
        assertEquals(50, processing(49, 99).workSize())
    }

    @Test
    fun workSizeAtEndOfFullFileIsHundred() {
        // (99+1)*100/(99+1) = 10000/100 = 100
        assertEquals(100, processing(99, 99).workSize())
    }

    @Test
    fun workSizeSingleLineFileIsHundred() {
        // (0+1)*100/(0+1) = 100/1 = 100
        assertEquals(100, processing(0, 0).workSize())
    }

    @Test
    fun workSizeUsesIntegerTruncation() {
        // (1+1)*100/(2+1) = 200/3 = 66 (truncated, not 66.67)
        assertEquals(66, processing(1, 2).workSize())
        // (3+1)*100/(9+1) = 400/10 = 40
        assertEquals(40, processing(3, 9).workSize())
    }

    @Test
    fun workSizeCanExceedHundredWhenStartPastEnd() {
        // The current implementation does NOT clamp; start past end yields > 100.
        // (10+1)*100/(4+1) = 1100/5 = 220
        assertEquals(220, processing(10, 4).workSize())
    }
}
