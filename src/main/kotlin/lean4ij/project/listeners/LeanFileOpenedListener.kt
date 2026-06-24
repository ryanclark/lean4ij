package lean4ij.project.listeners

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileOpenedSyncListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lean4ij.language.DiagInlayManager
import lean4ij.run.MainRunGutter
import lean4ij.project.LakePackageService
import lean4ij.project.LeanProjectService
import lean4ij.project.ToolchainService
import lean4ij.util.LeanUtil
import lean4ij.util.notifyErr

class LeanFileOpenedListener: FileOpenedSyncListener {
    override fun fileOpenedSync(
        source: FileEditorManager,
        file: VirtualFile,
        editorsWithProviders: List<FileEditorWithProvider>
    ) {
        super.fileOpenedSync(source, file, editorsWithProviders)
        if (!LeanUtil.isLeanFile(file)) return

        val project = source.project
        val leanProject = project.service<LeanProjectService>()
        // fileOpenedSync runs on the EDT, but package detection and toolchain probing walk ancestor
        // directories and stat files. Do that filesystem work OFF the EDT, then hop back to register the
        // editor-bound features (or surface the toolchain error), so we don't risk the platform's
        // "Slow operations on EDT" assertion on large files / networked filesystems.
        leanProject.scope.launch {
            // Multi-package: if this file belongs to a nested Lake package, ensure that package has its own
            // `lake serve` language server registered. No-op for the root package / single-package projects.
            project.service<LakePackageService>().ensurePackageServerRegistered(file)

            val toolchainService = project.service<ToolchainService>()
            // Package-aware: probe THIS file's package toolchain (matching the per-package serve command in
            // LeanLanguageServerProvider.setServerCommand), not always the project root.
            val toolchainMissing = toolchainService.toolchainNotFoundFor(file)
            val expectedToolchainPath = toolchainService.expectedToolchainPathFor(file)

            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                if (toolchainMissing) {
                    project.notifyErr(
                        "Unable to locate lean toolchain. Please verify you opened a lean project. " +
                            "Expected toolchain location: $expectedToolchainPath. "
                    )
                    return@withContext
                }
                // Install the diag-hint listener and the "Run" gutter on each opened text editor.
                for (editorWrapper in editorsWithProviders) {
                    val editor = editorWrapper.fileEditor
                    if (editor !is TextEditor) continue
                    if (!LeanUtil.isLeanFile(editor.file)) continue
                    if (editor.editor.isDisposed) continue
                    DiagInlayManager.register(editor)
                    // Editor-driven "Run" gutter icon on `def main` (see MainRunGutter for why not a RunLineMarkerContributor).
                    MainRunGutter.install(editor)
                }
            }
        }
    }
}
