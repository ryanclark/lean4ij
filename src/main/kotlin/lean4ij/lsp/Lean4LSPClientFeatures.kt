package lean4ij.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature
import com.redhat.devtools.lsp4ij.client.features.LSPDocumentHighlightFeature
import com.redhat.devtools.lsp4ij.client.features.LSPWorkspaceSymbolFeature
import org.eclipse.lsp4j.Diagnostic
import lean4ij.project.LakePackageService
import lean4ij.project.LeanProjectService
import lean4ij.setting.Lean4Settings
import org.eclipse.lsp4j.InitializeParams
import java.nio.file.Path

/**
 * Client features for one Lean language server, bound to the Lake package rooted at [packageRoot]. The
 * root package passes the project base path; per-package servers ([LeanPackageServerDefinition]) pass the
 * nested package directory. [isEnabled] uses this to route each file to the server for its own package.
 * (per project with the getProject method in the base class)
 */
class Lean4LSPClientFeatures(private val explicitPackageRoot: Path? = null) : LSPClientFeatures() {
    private val lean4Settings = service<Lean4Settings>()

    /**
     * This server's Lake package root. Per-package servers pass it explicitly; the static root definition
     * leaves it null (its `createClientFeatures()` has no project), so it resolves lazily to the project
     * base path once lsp4ij has attached the project to these features.
     */
    private val packageRoot: Path get() = explicitPackageRoot ?: Path.of(project.basePath ?: ".")

    init {
        completionFeature = LeanLSPCompletionFeature()
        // Tune the Lean server's semantic-token painting: LeanSemanticTokensFeature forces lsp4ij's direct
        // paint path and defers type/function-family tokens to LeanConstReferenceAnnotator for consistent
        // decl-vs-usage colors.
        semanticTokensFeature = LeanSemanticTokensFeature()
        diagnosticFeature = object : LSPDiagnosticFeature() {
            override fun isEnabled(file: PsiFile): Boolean {
                return true
            }

            /**
             * Reproduce lsp4ij's default [createAnnotation] body but render the tooltip from Lean's interactive
             * diagnostics (prose plain, code syntax-highlighted) instead of the default escaped-plain-text. The
             * default [getTooltip] only sees a [Diagnostic] with no file context; this override is the only hook
             * with the [Document], which we need to resolve the file/session for the async RPC. On a cache miss
             * we emit the plain default now and warm the cache for the next paint (see
             * [lean4ij.lsp.LeanDiagnosticTooltipService]).
             */
            override fun createAnnotation(
                diagnostic: Diagnostic,
                document: Document,
                fixes: MutableList<IntentionAction>,
                holder: AnnotationHolder
            ) {
                val range = LSPIJUtils.toTextRange(diagnostic.range, document, null, true) ?: return
                val severity = getHighlightSeverity(diagnostic) ?: return
                val builder = holder.newAnnotation(severity, getMessage(diagnostic))
                    .tooltip(richOrDefaultTooltip(diagnostic, document))
                    .range(range)
                if (range.startOffset == range.endOffset) {
                    builder.afterEndOfLine()
                }
                getProblemHighlightType(diagnostic.tags)?.let { builder.highlightType(it) }
                for (fix in fixes) {
                    builder.withFix(fix)
                }
                builder.create()
            }

            private fun richOrDefaultTooltip(diagnostic: Diagnostic, document: Document): String {
                val virtualFile = FileDocumentManager.getInstance().getFile(document)
                    ?: return getTooltip(diagnostic)
                val service = project.service<LeanDiagnosticTooltipService>()
                val modStamp = document.modificationStamp
                service.lookup(virtualFile.url, modStamp, diagnostic.range)?.let { return it }
                // Cold cache: render the plain default now and fetch+render the rich version for the next pass.
                service.warm(virtualFile, document, modStamp, document.lineCount)
                return getTooltip(diagnostic)
            }
        }
        // Use the Lean server's own inlay hints (textDocument/inlayHint: elaborator-driven `.type` and
        // `.parameter` name hints). lsp4ij enables this by default, so we no longer override it. lean4ij's
        // goal hints stay (they're unique); its now-redundant omit-type hints are off-by-default in
        // plugin.xml. To revert to lean4ij-only hints: re-add
        //   inlayHintFeature = object : LSPInlayHintFeature() { override fun isEnabled(file: PsiFile) = false }
        // (import com.redhat.devtools.lsp4ij.client.features.LSPInlayHintFeature) and flip the
        // lean.def.omit.type provider back to isEnabledByDefault="true".
        // Disable lsp4ij's workspace-symbol feature: a performance measure, since the Lean server appears
        // not to honor cancelRequests. We handle workspace symbols manually instead.
        workspaceSymbolFeature = object : LSPWorkspaceSymbolFeature() {
            override fun isEnabled(): Boolean {
                return false
            }
        }
        // Disable LSP document-highlight (the "highlight other occurrences of the symbol under the cursor"
        // feature). On cmd/ctrl-hover the platform asks lsp4ij for the reference range to underline, and lsp4ij
        // derives it from textDocument/documentHighlight. The Lean server, while a file is still elaborating,
        // answers that request with ranges that effectively span the whole file, so the entire document gets
        // underlined as one hyperlink: the user sees the whole file highlighted and can't click any single
        // identifier. Occurrence-highlighting is low value in a theorem prover (the same name recurs everywhere)
        // and this does NOT affect cmd+click go-to-definition (LSPDefinitionFeature/LSPDeclarationFeature are
        // separate), which keeps working.
        documentHighlightFeature = object : LSPDocumentHighlightFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        }
    }

    override fun isEnabled(file: VirtualFile): Boolean {
        // Route each file to the server for ITS Lake package: serve a file only if this server's package
        // is the file's nearest-ancestor lakefile. This is the decisive multi-package router (programmatic
        // language mappings are match-all) and makes the root server reject files in nested packages. For a
        // single-package project every file belongs to the root, so this is always true and behaves as before.
        if (!project.service<LakePackageService>().belongsTo(file, packageRoot)) {
            return false
        }
        if (lean4Settings.fileProgressTriggeringStrategy == Lean4Settings.FILE_PROGRESS_ALL_OPENED) {
            return true
        }
        val fileEditorManager = FileEditorManager.getInstance(project)
        // OnlySelectedEditor strategy (AllOpenedEditor already returned true above): keep the server enabled
        // while the file is OPEN; do NOT narrow to the *selected* editor. In a large monorepo the IDE
        // constantly enters dumb mode on "Push on VFS changes", during which selectedTextEditor is
        // null/flapping. Keying off it made lsp4ij see isEnabled=false and stop -> restart the server on every
        // such flap (stuck progress bar, no completion, status balloon). So this reduces to "is the file open".
        val enabled = fileEditorManager.isFileOpen(file)
        if (!enabled) {
            thisLogger().debug("isEnabled(file)=false for ${file.name} (file not open)")
        }
        return enabled
    }

    /**
     * Keep the Lean language server running even when its last .lean editor briefly drops its opened
     * document.
     *
     * lsp4ij does NOT listen to project roots/SDK changes. But IntelliJ's workspace-model churn around
     * the Lean SDK/Library entities causes transient editor close+reopen flaps. On the close,
     * [com.redhat.devtools.lsp4ij.LSPFileListener.fileClosed] -> LanguageServerWrapper.disconnect with
     * stopIfNoOpenedFiles, and since the default keepServerAlive() is false and no documents remain,
     * LanguageServerWrapper.maybeShutdown() arms a stop timer (status -> stopping); the reopen cancels it
     * (status -> started). That stopping/starting churn is what causes the stuck progress bar, the
     * "Cannot process request to closed file" flood, missing completion, and the occasional shutdown
     * timeout. Returning true short-circuits LanguageServerWrapper.keepAlive() so the server is never
     * torn down on these flaps. The Lean server is slow/expensive to start, so a persistent server is
     * also what users expect. This does not touch the SDK/indexing, so toolchain go-to-definition is
     * unaffected.
     */
    override fun keepServerAlive(): Boolean = true

    override fun initializeParams(initializeParams: InitializeParams) {
        // The Lean LSP expects the client to announce insertReplaceSupport, otherwise it never sends textEdit data.
        // It still only provides it in some cases (see below), so we still need to provide our own to avoid
        // a bug where it would think that the completion starts at pos 0 (and clear everything before the caret).
        // However, if the LSP provides its own textEdit, we might as well use what it suggests.
        // An LSP test that expects textEdit (though note that most others don't expect it):
        // https://github.com/leanprover/lean4/blob/9dc4dbebe136522a6226a0a4ff6552526cbce3bb/tests/lean/interactive/completionOption.lean.expected.out#L38
        // The LSP code that checks for insertReplaceSupport:
        // https://github.com/leanprover/lean4/blob/dedd9275ec162e181bbbd11ce65ba3bdfbf38e02/src/Lean/Server/Completion/CompletionCollectors.lean#L528
        initializeParams.capabilities.textDocument.completion.completionItem.insertReplaceSupport = true
        super.initializeParams(initializeParams)
    }
}
