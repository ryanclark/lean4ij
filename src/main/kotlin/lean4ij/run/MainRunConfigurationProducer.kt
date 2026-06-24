package lean4ij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import lean4ij.util.LeanUtil
import kotlin.io.path.relativeTo

/**
 * Creates a Lean run configuration from a Lean file that declares a `def main`. The skeleton Lean PSI does not
 * expose a `def main` identifier node to anchor to, so `def main` is detected from the document text instead.
 * The run target is the whole file, so a file that declares `def main` anywhere offers the configuration.
 */
class MainRunConfigurationProducer : LazyRunConfigurationProducer<LeanRunConfiguration>() {

    companion object {
        private val LEAN_RUN_CONFIGURATION_FACTORY = LeanConfigurationFactory(LeanRunConfigurationType())
        private val MAIN_DEF = Regex("""(?m)^\s*def\s+main\b""")
    }

    override fun getConfigurationFactory(): ConfigurationFactory =
        LEAN_RUN_CONFIGURATION_FACTORY

    override fun setupConfigurationFromContext(
        configuration: LeanRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val intermediateConfiguration = getConfiguration(context) ?: return false
        configuration.name = intermediateConfiguration.name
        configuration.options.fileName = intermediateConfiguration.fileName
        configuration.options.arguments = intermediateConfiguration.arguments
        return true
    }

    private fun getConfiguration(context: ConfigurationContext): IntermediateLeanRunConfiguration? {
        val element = context.location?.psiElement ?: return null
        val containingFile = element.containingFile ?: return null
        val file = containingFile.virtualFile ?: return null
        if (!LeanUtil.isLeanFile(file)) return null
        // Scan the document char sequence rather than PsiFile.text: this runs on every Run-context reconcile,
        // and .text materializes a full copy of the file each call.
        val content: CharSequence = containingFile.viewProvider.document?.charsSequence ?: containingFile.text
        if (!MAIN_DEF.containsMatchIn(content)) return null
        val projectDir = context.project.guessProjectDir() ?: return null
        val name = file.toNioPath().relativeTo(projectDir.toNioPath()).toString()
        return IntermediateLeanRunConfiguration(name, file.toNioPath().toString(), "")
    }

    override fun isConfigurationFromContext(
        configuration: LeanRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val intermediateConfiguration = getConfiguration(context) ?: return false
        return intermediateConfiguration.arguments == configuration.options.arguments &&
                intermediateConfiguration.fileName == configuration.options.fileName
    }

    private data class IntermediateLeanRunConfiguration(
        val name: String,
        val fileName: String,
        val arguments: String
    )
}
