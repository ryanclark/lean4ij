package lean4ij.module

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.joinCanonicalPath
import com.intellij.openapi.ui.getPresentablePath
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.UIBundle
import lean4ij.project.ElanService
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * The data model for the New Lean4 Project wizard (the UI panels, wizard step, and module builder live in
 * Lean4ProjectWizard.kt). [QUICK_STARTER_MODEL_KEY] bridges the model from LeanPanel.updateModel (writer) to
 * LeanProjectWizard.createStep (reader); its string identity is a contract between the two sides.
 */
val QUICK_STARTER_MODEL_KEY = Key<GraphProperty<QuickStarterModel?>>("lean4_quick_starter_model")

/**
 * Pure `lake new` command string builder. Extracted from [QuickStarterModel.lakeCommand]
 * so the (default-vs-non-default template/language) branching can be characterization tested
 * without a live PropertyGraph/WizardContext.
 *
 * [template] is compared against [QuickStarterModel.TEMPLATES]`[0]` and [language] against
 * [QuickStarterModel.LANGUAGES]`[0]` to decide which suffixes to append.
 */
internal fun buildLakeCommand(entityName: String, template: String, language: String): String {
    val commandBuilder = StringBuilder("lake new $entityName")
    val isDefaultTemplate = template == QuickStarterModel.TEMPLATES[0]
    val isDefaultLanguage = language == QuickStarterModel.LANGUAGES[0]
    if (!isDefaultTemplate) {
        commandBuilder.append(" $template")
    }
    if (!isDefaultLanguage) {
        if (isDefaultTemplate) {
            commandBuilder.append(" ")
        }
        commandBuilder.append(".$language")
    }
    return commandBuilder.toString()
}

/**
 * Pure construction of a [Proxy] from a proxy URL string. Extracted from
 * [QuickStarterModel.getVersions] so the SOCKS-vs-HTTP selection and host/port wiring
 * can be characterization tested without the surrounding property graph / network call.
 *
 * Mirrors the original logic: a protocol containing "sock" yields [Proxy.Type.SOCKS],
 * otherwise [Proxy.Type.HTTP].
 */
internal fun proxyFromUrl(proxyValue: String): Proxy {
    val url = URI(proxyValue).toURL()
    val type = if (url.protocol.contains("sock")) {
        Proxy.Type.SOCKS
    } else {
        Proxy.Type.HTTP
    }
    return Proxy(type, InetSocketAddress(url.host, url.port))
}

class QuickStarterModel(private val propertyGraph: PropertyGraph, private val wizardContext: WizardContext) :
    BaseState() {

    companion object {
        val TEMPLATES = listOf("std", "exe", "lib", "math")
        val LANGUAGES = listOf("lean", "toml")
    }

    val entityNameProperty = propertyGraph.lazyProperty(::suggestName)
    val locationProperty = propertyGraph.lazyProperty(::suggestLocationByName)
    val fetchingTagsFromGithubProperty = propertyGraph.lazyProperty { false }
    val fetchingTagsError = propertyGraph.lazyProperty { "" }
    // Initialized with the local (non-network) versions. The GitHub fetch runs off the EDT in the afterChange
    // listener in init below and is pushed back here, instead of running a blocking HTTPS request inside a
    // synchronous property transform on the EDT, which froze the wizard for up to ~10s when the checkbox toggled.
    val allVersionsProperty: GraphProperty<List<String>> = propertyGraph.property(getVersions(false).versions)
    val versionProperty = propertyGraph.lazyProperty {
        // firstOrNull, not [0]: getVersions can return an empty list (empty toolchains.txt / empty GitHub
        // response), which would otherwise crash the wizard UI with IndexOutOfBoundsException.
        allVersionsProperty.get().firstOrNull() ?: ""
    }
    val useProxyProperty = propertyGraph.lazyProperty { false }
    val proxyValueProperty = propertyGraph.lazyProperty { "" }
    val canonicalPathProperty = locationProperty.joinCanonicalPath(entityNameProperty)
    val templatesProperty = propertyGraph.property(TEMPLATES.first())
    val languagesProperty = propertyGraph.property(LANGUAGES.first())

    // Monotonic id so a slow fetch (e.g. GitHub) that finishes after a newer toggle does not overwrite the
    // newer result with a stale version list. Read/written only on the EDT (afterChange and invokeLater).
    private var versionFetchGeneration = 0

    init {
        // When the "fetch tags from GitHub" toggle changes, fetch off the EDT and publish the result back on
        // the EDT (any modality, so it applies while the wizard dialog is open) so the version combobox, which
        // observes allVersionsProperty, refreshes without freezing the wizard on the blocking HTTPS request.
        fetchingTagsFromGithubProperty.afterChange { fromGithub ->
            val generation = ++versionFetchGeneration
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = getVersions(fromGithub)
                ApplicationManager.getApplication().invokeLater({
                    // Drop a stale out-of-order result, and publish BOTH versions and error text on the EDT.
                    // getVersions runs on the pooled thread, so it must not set the Swing-bound properties itself
                    // (the fetchingTagsError listener mutates JLabel state off the EDT otherwise); it returns them.
                    if (generation == versionFetchGeneration) {
                        allVersionsProperty.set(result.versions)
                        fetchingTagsError.set(result.error)
                    }
                }, ModalityState.any())
            }
        }
    }

    private fun suggestName(): String {
        return suggestName("Untitled")
    }

    fun suggestName(prefix: String): String {
        val projectFileDirectory = File(wizardContext.projectFileDirectory)
        return FileUtil.createSequentFileName(projectFileDirectory, prefix, "")
    }

    private fun suggestLocationByName(): String {
        return wizardContext.projectFileDirectory
    }

    /** The version list plus the error text to display; returned (not set) so the off-EDT fetch mutates no
     *  Swing-bound property. The caller publishes both on the EDT. */
    private class VersionFetchResult(val versions: List<String>, val error: String)

    private fun getVersions(fromGithub : Boolean): VersionFetchResult {
        val elanService = service<ElanService>()
        if (!fromGithub) {
            return VersionFetchResult(elanService.toolchains(includeRemote = true), "")
        }

        return try {
            val proxy = if (useProxyProperty.get()) {
                proxyFromUrl(proxyValueProperty.get())
            } else {
                null
            }
            VersionFetchResult(elanService.toolchainsFromGithub(proxy), "")
        } catch (e: ConnectException) {
            VersionFetchResult(
                elanService.toolchains(includeRemote = true),
                (e.message ?: "unknown error") + ", please consider using proxy<br>fallback to preset versions"
            )
        } catch (e: Exception) {
            VersionFetchResult(
                elanService.toolchains(includeRemote = true),
                (e.message ?: "unknown error") + "<br>fallback to preset versions"
            )
        }
    }

    fun getLocationComment(): String {
        val shortPath = StringUtil.shortenPathWithEllipsis(getPresentablePath(canonicalPathProperty.get()), 60)
        return UIBundle.message(
            "label.project.wizard.new.project.path.description",
            wizardContext.isCreatingNewProjectInt,
            shortPath,
        )
    }

    fun commentForTemplate() = when (templatesProperty.get()) {
        "std" -> "library and executable; default"
        "exe" -> "executable only"
        "lib" -> "library only"
        "math" -> "library only with a mathlib dependency"
        else -> "unrecognized template"
    }

    fun commentForConfigurationLanguage() = when (languagesProperty.get()) {
        "lean" -> "a Lean version of the the configuration file; default"
        "toml" -> "a TOML version of the the configuration file"
        else -> "unrecognized language"
    }

    fun lakeCommandForComment(): String = "Command to create project: ${lakeCommand()}"

    fun lakeCommand(): String =
        buildLakeCommand(entityNameProperty.get(), templatesProperty.get(), languagesProperty.get())
}
