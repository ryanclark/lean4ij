package lean4ij.project

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import lean4ij.util.fromJson
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Path

data class GitHubTag(val name: String)

// Application-level: ElanService holds no project state (its paths derive from user.home, and commandForRunningElan
// takes the project as a parameter). It was previously PROJECT-level but looked up via the application-level
// service<ElanService>() from the new-project wizard (which has no project yet); APP-level makes every call site
// resolve consistently. project.service<ElanService>() still works for app-level services (it delegates to the
// application container), so the run-config call site is unaffected.
@Service(Service.Level.APP)
class ElanService {

    val elanHomePath = Path.of(System.getProperty("user.home"), ".elan")
    val elanBinPath = elanHomePath.resolve("bin")
    val elanPath = elanBinPath.resolve("elan")

    /**
     * All versions are extracted locally from the lean4 repo with the following shell command:
     * ```
     * git --no-pager tag|grep v4|python -c 'import sys; print("".join(sorted(sys.stdin,key=lambda x:tuple(map(int,x.replace("v","").replace("-rc", ".").replace("-m", ".").split("."))), reverse=True)))'
     * ```
     * TODO maybe it can fetch locally or update in the pipeline
     * A curl version of this using the github api is
     * curl https://api.github.com/repos/leanprover/lean4/tags |grep name|awk '{print $2}'
     */
    fun toolchains(includeRemote: Boolean): List<String> {
        return javaClass.classLoader.getResource("toolchains.txt").readText().split("\n")
    }

    /**
     * Fetching all versions from https://api.github.com/repos/leanprover/lean4/tags
     */
    fun toolchainsFromGithub(proxy: Proxy? = null): List<String> {
        return getGitHubTags("leanprover", "lean4", proxy)
    }

    /**
     * The method here is using the GitHub API to fetch the tags of a repository
     * and then return the names of the tags.
     *
     * The method is from standard library and avoids relying on third party libraries
     */
    fun getGitHubTags(owner: String, repo: String, proxy: Proxy?): List<String> {
        val url = URL("https://api.github.com/repos/$owner/$repo/tags")
        val connection = (if (proxy != null) {
            url.openConnection(proxy)
        } else {
            url.openConnection()
        }) as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                connectTimeout = 5000
                readTimeout = 5000
            }

            return when (connection.responseCode) {
                in 200..299 -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Gson().fromJson<List<GitHubTag>>(response).map { it.name }
                }
                else -> throw Exception("HTTP Error ${connection.responseCode}: ${connection.responseMessage}")
            }
        } finally {
            connection.disconnect()
        }
    }


    fun commandForRunningElan(arguments: String, project: Project, environment: Map<String, String>) : GeneralCommandLine {
        val command = mutableListOf(elanPath.toString())
        // TODO what if it's empty?
        if (arguments.isNotEmpty()) {
            // TODO DRY this
            command.addAll(arguments.split(Regex("\\s+")))
        }
        return GeneralCommandLine(*command.toTypedArray()).apply {
            this.workDirectory = Path.of(project.basePath!!).toFile()
            this.environment.putAll(environment)
        }
    }

}

/**
 * TODO this is project level service
 *      there should be a global service similar for system level elan/lake
 */
@Service(Service.Level.PROJECT)
class ToolchainService(val project: Project) {
    // set to true when the toolchain could not properly be
    // initialized
    var toolChainPath: Path? = null
    var lakePath:  Path? = null
    var leanPath: Path? = null

    companion object {
        // TODO DRY this with the above elan service
        private val ARGUMENT_SEPARATOR = Regex("\\s+")
        const val TOOLCHAIN_FILE_NAME = "lean-toolchain"

        fun expectedToolchainPath(project: Project): Path {
            return Path.of(project.basePath!!, TOOLCHAIN_FILE_NAME)
        }
    }

    fun expectedToolchainPath(): Path {
        return expectedToolchainPath(this.project)
    }

    fun toolchainNotFound(): Boolean {
        return !expectedToolchainPath().toFile().isFile
    }

    /** The expected `lean-toolchain` path for [file]'s OWN Lake package (multi-package aware). */
    fun expectedToolchainPathFor(file: VirtualFile): Path =
        project.getService(LakePackageService::class.java).packageRootOf(file).resolve(TOOLCHAIN_FILE_NAME)

    /**
     * True if [file]'s own Lake package has no `lean-toolchain`. Mirrors the per-package serve-command
     * resolution in [lean4ij.lsp.LeanLanguageServerProvider.setServerCommand], so the open-time error and the
     * actual server use the same toolchain path (a nested package can pin a different toolchain than the root).
     */
    fun toolchainNotFoundFor(file: VirtualFile): Boolean = !expectedToolchainPathFor(file).toFile().isFile

    /**
     * Run a lean file using lake env, for lean it's ran as the command
     * `lean --run <file>`,
     * but using lake it handles the imports like Mathlib
     * TODO test arguments and working directory
     */
    fun commandLineForRunningLeanFile(filePath: String, arguments: String = "", environments: Map<String, String> = mapOf()): GeneralCommandLine {
        val command = mutableListOf(lakePath.toString(), "env", "lean", "--run", filePath)
        if (arguments.isNotEmpty()) {
            command.addAll(arguments.split(ARGUMENT_SEPARATOR))
        }
        return GeneralCommandLine(*command.toTypedArray()).apply {
            // Apply the user's environment variables (the Lean run-config UI exposes them); without this they
            // were silently dropped, unlike the Lake and Elan run configs.
            this.environment.putAll(environments)
            // Run lake from the file's own Lake package root, not the project root. In a multi-package
            // monorepo, a nested-package file (e.g. e/proofs/ttyterminal/Main.lean) must resolve its
            // package's modules (e.g. TtyTilingFfi), which are only on `lake env lean`'s search path when
            // lake runs from that package's directory. For a single-package project this is the base path,
            // so behavior is unchanged.
            this.workDirectory = project.getService(LakePackageService::class.java).packageRootOf(filePath).toFile()
        }
    }

    fun commandForRunningLake(arguments: String, environments: Map<String, String> = mapOf()): GeneralCommandLine {
        val command = mutableListOf(lakePath.toString())
        // TODO what if it's empty?
        if (arguments.isNotEmpty()) {
            command.addAll(arguments.split(ARGUMENT_SEPARATOR))
        }
        return GeneralCommandLine(*command.toTypedArray()).apply {
            this.workDirectory = Path.of(project.basePath!!).toFile()
            this.environment.putAll(environments)
        }
    }
}