package lean4ij.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Editor-driven "Run" gutter icon for Lean files that declare a `def main`.
 *
 * Rather than a [RunLineMarkerContributor], which anchors to a per-identifier PSI element, we scan the
 * document text once on file open and add a clickable run icon directly to the editor's markup model, on the
 * actual `def main` line. The highlighter tracks edits, so the icon follows the line as text above it
 * changes; adding/removing a `def main` after open isn't reflected until the file is reopened.
 *
 * Right-click Run still works independently via [MainRunConfigurationProducer].
 */
object MainRunGutter {
    private val MAIN_DEF = Regex("""(?m)^[ \t]*def[ \t]+main\b""")

    fun install(editor: TextEditor) {
        val ed = editor.editor
        val file = editor.file ?: return
        val match = MAIN_DEF.find(ed.document.charsSequence) ?: return
        val line = ed.document.getLineNumber(match.range.first)
        val highlighter = ed.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
        highlighter.gutterIconRenderer = RunMainGutterIconRenderer(file)
    }
}

private class RunMainGutterIconRenderer(private val file: VirtualFile) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

    override fun getTooltipText(): String = "Run Lean main"

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            runLeanFile(project, file)
        }
    }

    override fun equals(other: Any?): Boolean = other is RunMainGutterIconRenderer && other.file == file
    override fun hashCode(): Int = file.hashCode()
}

private fun runLeanFile(project: Project, file: VirtualFile) {
    val runManager = RunManager.getInstance(project)
    val type = ConfigurationTypeUtil.findConfigurationType(LeanRunConfigurationType::class.java)
    val factory = type.configurationFactories.firstOrNull() ?: return
    val settings = runManager.createConfiguration("Run ${file.name}", factory)
    (settings.configuration as LeanRunConfiguration).options.fileName = file.path
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
