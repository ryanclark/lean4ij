package lean4ij.sdk

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import java.util.function.BooleanSupplier
import javax.swing.Icon

class LeanLibraryExcludeFileCondition : SyntheticLibrary.ExcludeFileCondition {
    companion object {
        // val EXCLUDE_NAMES = arrayOf("test", "deps", "docs")
        val EXCLUDE_NAMES = arrayOf("docs")
    }

    override fun shouldExclude(
        isDir: Boolean,
        filename: String,
        isRoot: BooleanSupplier,
        isStrictRootChild: BooleanSupplier,
        hasParentNotGrandparent: BooleanSupplier
    ): Boolean {
        val result = when {
            isRoot.asBoolean -> false
            filename.startsWith(".") -> true
            filename in EXCLUDE_NAMES -> true
            isDir -> false
            else -> !filename.endsWith(".lean")
        }
        return result
    }

}

class LeanLibrary(
    private val name: String,
    private val root: VirtualFile,
) : SyntheticLibrary(null, LeanLibraryExcludeFileCondition()), ItemPresentation {

    companion object {
        // TODO this absolutely should be check with detail
        val LIBRARY_ICON = IconLoader.getIcon("/icons/libraryFolder.svg", javaClass)
    }

    override fun hashCode() = root.hashCode()

    override fun equals(other: Any?): Boolean = other is LeanLibrary && other.root == root

    // Computed once: getSourceRoots/getBinaryRoots are queried frequently (indexing, scope checks) and
    // root.children can trigger directory enumeration + a fresh list each call. A new LeanLibrary is created
    // when the provider re-queries, so a stale snapshot is replaced rather than mutated here.
    private val childRoots by lazy { root.children.toList() }

    override fun getSourceRoots() = childRoots

    override fun getBinaryRoots() = childRoots

    override fun getLocationString() = ""

    override fun getIcon(p0: Boolean): Icon = LIBRARY_ICON

    override fun getPresentableText() = name

    override fun isShowInExternalLibrariesNode(): Boolean = false

}