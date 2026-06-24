package lean4ij.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists

/**
 * from julia-intellij, check src/org/ice1000/julia/lang/module/julia-sdks.kt
 */
class Lean4StdLibraryProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<LeanLibrary> {
        val basePath = project.basePath ?: return listOf()
        // There is only ever one `.lake/packages` directory, so resolve it directly. The previous
        // Files.list(.lake) returned a Stream backed by an open directory handle that was never closed,
        // leaking a file descriptor on every library-root re-query during indexing.
        val packagesPath = Path.of(basePath, ".lake", "packages")
        if (packagesPath.notExists() || !packagesPath.isDirectory()) {
            return listOf()
        }
        // refresh=false: getAdditionalProjectLibraries runs under a read action during indexing, where a
        // synchronous VFS refresh is disallowed. The platform refreshes the VFS for .lake on its own.
        val root = VfsUtil.findFile(packagesPath, false) ?: return listOf()
        return listOf(LeanLibrary("packages", root))
    }

}