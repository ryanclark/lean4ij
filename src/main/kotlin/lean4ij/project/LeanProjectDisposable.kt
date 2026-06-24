package lean4ij.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * A project-lifetime [Disposable] to parent listeners and other resources that must be torn down when the
 * project closes. Registering against the application-wide EditorFactory event multicaster without a parent
 * disposable leaks the listener (and, since these listeners capture the project, the project) under
 * ROOT_DISPOSABLE; using this service as the parent removes them on project close.
 */
@Service(Service.Level.PROJECT)
class LeanProjectDisposable : Disposable {
    override fun dispose() {}
}
