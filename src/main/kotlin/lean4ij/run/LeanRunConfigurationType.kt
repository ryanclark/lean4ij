package lean4ij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.dsl.builder.Panel
import lean4ij.project.ToolchainService

/**
 * Run configuration for running a single Lean file (`lake env lean --run <file>`). Adds a file field on top
 * of the shared arguments/environments editor; the rest is shared via RunConfigurationSupport.kt.
 */
class LeanRunConfigurationType : ConfigurationTypeBase(
    "LeanRunConfiguration",
    "Lean",
    "Lean Run Configuration Type",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console }
) {
    init {
        addFactory(LeanConfigurationFactory(this))
    }
}

class LeanConfigurationFactory(configurationType: LeanRunConfigurationType) : ConfigurationFactory(configurationType) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LeanRunConfiguration(project, this, "Lean")
    override fun getId() = "Lean"
    override fun getOptionsClass(): Class<out BaseState> = LeanRunConfigurationOptions::class.java
}

class LeanRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    BaseLeanRunConfiguration<LeanRunConfigurationOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LeanRunSettingsEditor()

    override fun buildCommandLine(): GeneralCommandLine =
        project.service<ToolchainService>().commandLineForRunningLeanFile(options.fileName, options.arguments, options.environments)
}

class LeanRunSettingsEditor : ArgsEnvRunSettingsEditor<LeanRunConfiguration>("lean") {
    private val leanFilePathField = TextFieldWithBrowseButton()

    override fun Panel.extraRows() {
        row("&File") {
            fullWidthCell(leanFilePathField)
                .resizableColumn()
                .comment("The file to run")
        }
    }

    override fun resetExtra(config: LeanRunConfiguration) {
        leanFilePathField.text = config.options.fileName
    }

    override fun applyExtra(config: LeanRunConfiguration) {
        config.options.fileName = leanFilePathField.text
    }
}

class LeanRunConfigurationOptions : BaseRunConfigurationOptions() {
    private var fileNameOption = string("").provideDelegate(this, "fileName")

    var fileName: String
        get() = fileNameOption.getValue(this) ?: ""
        set(value) = fileNameOption.setValue(this, value)
}
