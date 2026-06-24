/**
 * For os process related utilities
 * TODO for intellij idea related stuff it should
 *      have some other ns like `intellij` or `idea`
 */
package lean4ij.util

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * TODO the api seems not in a good design here, the concrete command should not be the subject
 */
fun String.execute(workingDir: File, environments: Map<String, String> = mapOf()): String {
    val parts = this.split("\\s".toRegex())
    val processBuilder = ProcessBuilder(*parts.toTypedArray())
    // set environments
    processBuilder.environment().putAll(environments)
    val proc = processBuilder
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    // Drain stdout and stderr concurrently BEFORE waiting. With PIPE the OS pipe buffer is ~64KB, so a child
    // that writes more than that to either stream blocks on write while waitFor blocks and nothing drains the
    // buffer (deadlock). The reader threads finish at EOF (process exit), and join() publishes their results.
    var out = ""
    var err = ""
    val outReader = thread { out = proc.inputStream.bufferedReader().use { it.readText() } }
    val errReader = thread { err = proc.errorStream.bufferedReader().use { it.readText() } }

    val finished = proc.waitFor(60, TimeUnit.MINUTES)
    outReader.join()
    errReader.join()
    if (!finished) {
        proc.destroyForcibly()
        thisLogger().warn("command timed out after 60 minutes: $this")
    }
    return "$out\n$err"
}