package lean4ij.project

import lean4ij.util.Constants
import lean4ij.util.LspUtil
import com.intellij.build.BuildDescriptor
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.SyncViewManager
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

open class BuildEvent(val file: String)
class BuildStart(file: String): BuildEvent(file)
class BuildEnd(file: String): BuildEvent(file)
class BuildMessage(file: String, val message: String): BuildEvent(file)


/**
 * A simple service for build tool window
 */
@Service(Service.Level.PROJECT)
@Suppress("UnstableApiUsage")
class BuildWindowService(val project: Project) {

    private val leanProject : LeanProjectService = project.service()
    private val systemId = ProjectSystemId("LEAN4")
    private val syncId = ExternalSystemTaskId.create(systemId, ExternalSystemTaskType.RESOLVE_PROJECT, project)
    // UNLIMITED Channel, not a zero-buffer SharedFlow: events emitted before this collector subscribes (a
    // startup race) would otherwise be dropped, and emit() to a zero-buffer flow blocks the producer.
    private val events = Channel<BuildEvent>(Channel.UNLIMITED)
    private val builds = HashMap<String, BuildProgress<*>>()

    init {
        leanProject.scope.launch {
            /**
             * TODO check createBuildProgress method
             * and https://github.com/JetBrains/intellij-community/blob/1d45fcdd827f7bc3fde15d7eda2b4399780fb632/platform/lang-impl/testSources/com/intellij/build/BuildViewTest.kt#L50
             */
            var progress : BuildProgress<BuildProgressDescriptor>? = null

            // Single collector, so `builds`/`progress` are confined to this coroutine and need no mutex.
            for (s in events) {
                // A single throwing event must not terminate this collector: if this coroutine dies, the build
                // tool window silently stops updating for the rest of the session. Rethrow cancellation, log
                // everything else and continue with the next event.
                try {
                    // TODO rather than using string, use class for this
                    // TODO never mind, keep it running
                    // if (s == "--") {
                    //     if (builds.isEmpty()) {
                    //         progress!!.finish()
                    //         progress = null
                    //     }
                    // } else
                    (s as? BuildStart)?.let {
                        if (progress == null) {
                            progress = createProgress()
                        }
                        // TODO there seems still some duplicated start events...
                        // builds[s.file] = progress!!.startChildProgress(s.file)
                        builds.computeIfAbsent(s.file) {
                            progress!!.startChildProgress(s.file)
                        }
                    }
                    (s as? BuildEnd)?.let {
                        if (builds[s.file] == null) {
                            // A build-end without a matching build-start: the server can report this, and the
                            // builds[s.file]?.let below already no-ops. Not a real error; Logger.error throws in
                            // internal/dev mode, so log at debug instead.
                            thisLogger().debug("no build for ${s.file}")
                        }
                        // TODO there still seems build end without build start
                        builds[s.file]?.let {
                            it.finish()
                            builds.remove(s.file)
                        }
                        // launch {
                        //     delay(10 * 1000)
                        //     flow.emit("--")
                        // }
                    }
                    (s as? BuildMessage)?.let {
                        // TODO this can even before build start
                        if (progress == null) {
                            progress = createProgress()
                        }
                        val fileProgress = builds.computeIfAbsent(s.file) {
                            progress!!.startChildProgress(s.file)
                        }
                        fileProgress.output("${s.message}\n", false)
                        if (s.message.contains("error: build failed")) {
                            // for (entry in builds.entries) {
                            //     entry.value.cancel()
                            // }
                            // TODO better way for handling this
                            fileProgress.cancel()
                            builds.remove(s.file)
                            // project.notifyErr("Build failed, check build window for detail:\n${s.message}")
                            thisLogger().warn("${s.file} build failed with message:\n${s.message}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    thisLogger().warn("build event handling failed for ${s.file}: ${e.message}", e)
                }
            }
        }
    }

    fun createProgress(): BuildProgress<BuildProgressDescriptor> {
        val descriptor = DefaultBuildDescriptor(syncId, Constants.FILE_PROGRESS, project.basePath!!, System.currentTimeMillis())
        return  SyncViewManager.createBuildProgress(project)
            .start(object : BuildProgressDescriptor {
                override fun getTitle(): String {
                    return Constants.FILE_PROGRESS
                }
                override fun getBuildDescriptor(): BuildDescriptor {
                    return descriptor
                }
            })
    }

    fun startBuild(file: String) {
        events.trySend(BuildStart(leanProject.getRelativePath(LspUtil.unquote(file))))
    }

    fun endBuild(file: String) {
        events.trySend(BuildEnd(leanProject.getRelativePath(LspUtil.unquote(file))))
    }

    fun addBuildEvent(file: String, message: String) {
        events.trySend(BuildMessage(leanProject.getRelativePath(LspUtil.unquote(file)), message))
    }

}
