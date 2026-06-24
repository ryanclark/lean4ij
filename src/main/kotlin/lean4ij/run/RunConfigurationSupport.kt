package lean4ij.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

fun <T : JComponent> Row.fullWidthCell(component: T): Cell<T> {
    return cell(component).align(Align.FILL)
}

/** Shared run state for the Lean/Lake/Elan configs: a colored process handler with a termination listener. */
class LeanProcessRunState(private val commandLine: GeneralCommandLine, environment: ExecutionEnvironment) :
    CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(processHandler)
        return processHandler
    }
}

/** Shared options: a command `arguments` string and an `environments` map, persisted as XML option tags. */
abstract class BaseRunConfigurationOptions : RunConfigurationOptions() {

    private var argumentsOption = string("").provideDelegate(this, "arguments")
    private var environmentsOption = map<String, String>().provideDelegate(this, "environments")

    var arguments: String
        get() = argumentsOption.getValue(this) ?: ""
        set(value) = argumentsOption.setValue(this, value)

    // working directory is not applicable here; lake/lean/elan must run from the project root
    var environments: MutableMap<String, String>
        get() = environmentsOption.getValue(this)
        set(value) {
            environmentsOption.setValue(this, value.toMutableMap())
        }
}

/**
 * Shared run-configuration base. Exposes the typed [getOptions] and builds the run state from a
 * subclass-provided command line; subclasses supply the editor and the command line.
 */
abstract class BaseLeanRunConfiguration<O : BaseRunConfigurationOptions>(
    project: Project, factory: ConfigurationFactory, name: String
) : RunConfigurationBase<O>(project, factory, name) {

    @Suppress("UNCHECKED_CAST")
    public override fun getOptions(): O = super.getOptions() as O

    /** Build the command line to run; called from [getState]. */
    protected abstract fun buildCommandLine(): GeneralCommandLine

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        LeanProcessRunState(buildCommandLine(), environment)
}

/**
 * Shared settings editor for the `arguments` + `environments` rows. State is synced through
 * resetEditorFrom/applyEditorTo; the previous per-editor `*Prop` getters plus `.bind(...)` were redundant
 * with those overrides and are dropped. Subclasses provide the [tool] name shown in the row comments and may
 * add extra rows (e.g. the Lean file field) via [extraRows]/[resetExtra]/[applyExtra].
 */
abstract class ArgsEnvRunSettingsEditor<C : BaseLeanRunConfiguration<*>>(
    private val tool: String
) : SettingsEditor<C>() {

    protected val argumentsField = RawCommandLineEditor()
    protected val environmentField = EnvironmentVariablesComponent()

    final override fun resetEditorFrom(config: C) {
        argumentsField.text = config.options.arguments
        environmentField.envs = config.options.environments
        resetExtra(config)
    }

    final override fun applyEditorTo(config: C) {
        config.options.arguments = argumentsField.text
        config.options.environments = environmentField.envs
        applyExtra(config)
    }

    protected open fun resetExtra(config: C) {}
    protected open fun applyExtra(config: C) {}
    protected open fun Panel.extraRows() {}

    final override fun createEditor(): JComponent = panel {
        extraRows()
        row("&Arguments") {
            fullWidthCell(argumentsField)
                .resizableColumn()
                .comment("Arguments for $tool")
        }
        row("&Environment Variables") {
            fullWidthCell(environmentField)
                .comment("Environment variables for running $tool")
        }
    }
}
