package lean4ij.util

import java.io.IOException
import java.net.InetAddress
import java.util.*
import javax.net.ServerSocketFactory


object OsUtil {

    // os.name does not change at runtime, so detect once.
    private val operatingSystem: String by lazy {
        val osName = System.getProperty("os.name").lowercase()
        when {
            "windows" in osName -> "Windows"
            listOf("mac", "nix", "sunos", "solaris", "bsd").any { it in osName } -> "*nix"
            else -> "Other"
        }
    }

    fun isWindows(): Boolean = operatingSystem == "Windows"

    fun findAvailableTcpPort(): Int {
        val minPort = 50000
        val maxPort = 65500
        val portRange = maxPort - minPort
        val maxAttempts = 1000
        var candidatePort: Int
        var searchCounter = 0
        val random: Random = Random(System.nanoTime())
        do {
            check(searchCounter <= maxAttempts) {
                String.format(
                    "Could not find an available TCP port in the range [%d, %d] after %d attempts",
                    minPort, maxPort, maxAttempts
                )
            }
            candidatePort = minPort + random.nextInt(portRange + 1)
            searchCounter++
        } while (!isPortAvailable(candidatePort))

        return candidatePort
    }

    // Note: TOCTOU. The port is free at probe time but another process can bind it before the caller does;
    // callers accept this small race rather than threading the bound socket through to the server.
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocketFactory.getDefault().createServerSocket(port, 1, InetAddress.getByName("localhost")).use { }
            true
        } catch (ex: IOException) {
            false
        }
    }

}