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
import com.intellij.openapi.util.NotNullLazyValue
import lean4ij.project.ToolchainService

/**
 * Run configuration for arbitrary `lake` commands. Shares the base classes in RunConfigurationSupport.kt.
 */
class LakeRunConfigurationType : ConfigurationTypeBase(
    "LakeCommandConfiguration",
    "Lake",
    "Lake Command Configuration Type",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console }
) {
    init {
        addFactory(LakeConfigurationFactory(this))
    }
}

class LakeConfigurationFactory(configurationType: LakeRunConfigurationType) : ConfigurationFactory(configurationType) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LakeRunConfiguration(project, this, "Lake")
    override fun getId() = "Lake"
    override fun getOptionsClass(): Class<out BaseState> = LakeRunConfigurationOptions::class.java
}

class LakeRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    BaseLeanRunConfiguration<LakeRunConfigurationOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LakeRunSettingsEditor()

    override fun buildCommandLine(): GeneralCommandLine =
        project.service<ToolchainService>().commandForRunningLake(options.arguments, options.environments)
}

class LakeRunSettingsEditor : ArgsEnvRunSettingsEditor<LakeRunConfiguration>("lake")

class LakeRunConfigurationOptions : BaseRunConfigurationOptions()
