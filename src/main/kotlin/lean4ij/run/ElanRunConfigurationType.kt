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
import lean4ij.project.ElanService

/**
 * Run configuration for arbitrary `elan` commands. The shared base classes live in
 * [lean4ij.run] (RunConfigurationSupport.kt): [LeanProcessRunState], [BaseRunConfigurationOptions],
 * [BaseLeanRunConfiguration], [ArgsEnvRunSettingsEditor].
 */
class ElanRunConfigurationType : ConfigurationTypeBase(
    "ElanCommandConfiguration",
    "Elan",
    "Elan Command Configuration Type",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console }
) {
    init {
        addFactory(ElanConfigurationFactory(this))
    }
}

class ElanConfigurationFactory(configurationType: ElanRunConfigurationType) : ConfigurationFactory(configurationType) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = ElanRunConfiguration(project, this, "Elan")
    override fun getId() = "Elan"
    override fun getOptionsClass(): Class<out BaseState> = ElanRunConfigurationOptions::class.java
}

class ElanRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    BaseLeanRunConfiguration<ElanRunConfigurationOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ElanRunSettingsEditor()

    override fun buildCommandLine(): GeneralCommandLine =
        service<ElanService>().commandForRunningElan(options.arguments, project, options.environments)
}

class ElanRunSettingsEditor : ArgsEnvRunSettingsEditor<ElanRunConfiguration>("elan")

class ElanRunConfigurationOptions : BaseRunConfigurationOptions()
