package lean4ij.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import lean4ij.language.Lean4SdkType
import lean4ij.project.ToolchainService
import lean4ij.util.notifyErr
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Pure transform from a lean-toolchain string to the directory name used under
 * `$HOME/.elan/toolchains/`. Extracted from [SdkService.getHomePath] to allow
 * characterization testing without an Application/Project.
 */
internal fun mangleToolchainDir(toolchain: String): String =
    toolchain.replace("/", "--").replace(":", "---")

/**
 * Pure transform from a lean-toolchain string to the SDK display name.
 * Extracted from [SdkService.setupModule] to allow characterization testing.
 */
internal fun toolchainSdkName(toolchain: String): String =
    toolchain.split('/').last().replace(':', ' ')

@Service(Service.Level.PROJECT)
class SdkService(private val project: Project) {

    /**
     * TODO this is duplicated with [lean4ij.lsp.LeanLanguageServerProvider.setServerCommand]
     */
    fun getLeanVersion(): String? {
        val toolchainFile = ToolchainService.expectedToolchainPath(project);
        if (!toolchainFile.exists()) {
            // error only shown
            // if they open a lean file
            // see LeanFileOpenedListener
            return null
        }
        if (!toolchainFile.isRegularFile()) {
            val content =
                "File $toolchainFile lean-toolchain is not a regular file. Please check if the project is setup correctly"
            project.notifyErr(content)
            return null
        }
        val toolchain = toolchainFile.toFile().readText().trim()
        return toolchain
    }

    /**
     * TODO this is duplicated with [lean4ij.lsp.LeanLanguageServerProvider.setServerCommand]
     */
    fun getHomePath(toolchain: String): Path? {
        val toolchainDir = mangleToolchainDir(toolchain)
        // toolchain path is $HOME/.elan/toolchains/<toolchainDir>
        val toolchainPath = Path.of(System.getProperty("user.home"), ".elan", "toolchains", toolchainDir)
        if (!toolchainPath.exists()) {
            val content = "Path $toolchainPath does not exist. Please try to setup the toolchain outside the IDE."
            project.notifyErr(content)
            return null
        }
        if (!toolchainPath.isDirectory()) {
            val content = "Path $toolchainPath is not a directory. Please check if the toolchain setup correctly."
            project.notifyErr(content)
            return null
        }
        return toolchainPath
    }

    fun setupModule() {
        val application = ApplicationManager.getApplication()
        val toolchain = getLeanVersion() ?: return
        val toolchainPath = getHomePath(toolchain)?.toString() ?: return
        val sdkName = toolchainSdkName(toolchain)
        var sdk: Sdk? = ProjectJdkTable.getInstance().findJdk(sdkName)
        if (sdk == null) {
            // Create the SDK in a write action. invokeAndWait + a holder works whether setupModule runs on the
            // EDT (createProject's ToolWindowManager.invokeLater) or a background thread. The previous code
            // blocked on a future completed from invokeLater, which deadlocked for 20s when setupModule was
            // itself already on the EDT (the completing invokeLater could not run until this returned).
            val created = AtomicReference<Sdk>()
            application.invokeAndWait {
                application.runWriteAction {
                    ProjectJdkTable.getInstance().run {
                        val newSdk = ProjectJdkImpl(sdkName, Lean4SdkType.INSTANCE)
                        newSdk.sdkModificator.run {
                            // TODO showing homePath in external library seems cumbersome
                            // homePath = toolchainPath
                            // A release toolchain may ship without src/lean; skip the CLASSES root rather than
                            // crash SDK creation with an NPE inside the write action.
                            VfsUtil.findFile(Path.of(toolchainPath, "src", "lean"), true)?.let { srcRoot ->
                                addRoot(srcRoot, OrderRootType.CLASSES)
                            }
                            commitChanges()
                        }
                        addJdk(newSdk)
                        created.set(newSdk)
                    }
                }
            }
            sdk = created.get()
        }

        try {
            project.basePath?.let { basePath ->
                thisLogger().info("current module is $basePath")
                project.modules.singleOrNull()?.let {
                    ModuleRootModificationUtil.updateModel(it) { rootModule ->
                        rootModule.contentEntries.singleOrNull()?.run {
                            rootModule.sdk = sdk
                            val projectRootManager = ProjectRootManager.getInstance(project)
                            application.invokeLater {
                                application.runWriteAction {
                                    projectRootManager.projectSdk = sdk
                                    projectRootManager.setProjectSdkName(toolchain, "Lean4")
                                }
                            }
                            val lakePath = Paths.get(basePath, ".lake")
                            // This path can be not exist for the first setup, in the case we skip adding it to exclude folder
                            if (lakePath.exists()) {
                                VfsUtil.findFile(Paths.get(basePath, ".lake"), true)?.let { addExcludeFolder(it) }
                            }
                        }
                    }
                    project.save()
                }
            }
        } catch (e: Exception) {
            project.notifyErr("Failed to set up the SDK for $toolchain")
            thisLogger().error(e)
        }
    }
}
