package lean4ij.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager.getInstance
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import lean4ij.project.LeanProjectService

// Must match the notificationGroup id registered in plugin.xml.
private const val NOTIFICATION_GROUP_ID = "Custom Notification Group"

/**
 * notify an error with [content]
 */
fun Project.notifyErr(content: String) {
    getInstance()
        // TODO custom notification group
        .getNotificationGroup(NOTIFICATION_GROUP_ID)
        .createNotification(content, NotificationType.ERROR)
        .notify(this)
}

fun Project.notify(content: String) {
    getInstance()
        // TODO custom notification group
        .getNotificationGroup(NOTIFICATION_GROUP_ID)
        .createNotification(content, NotificationType.INFORMATION)
        .notify(this)
}

fun Project.notify(content: String, type: NotificationType) {
    getInstance()
        // TODO custom notification group
        .getNotificationGroup(NOTIFICATION_GROUP_ID)
        .createNotification(content, type)
        .notify(this)
}

fun Project.notify(content: String, action: (Notification) -> Notification) {
    val notification = getInstance()
        // TODO custom notification group
        .getNotificationGroup(NOTIFICATION_GROUP_ID)
        .createNotification(content, NotificationType.INFORMATION)
    action(notification).notify(this)
}

val Project.leanProjectService get(): LeanProjectService = service()

val Project.leanProjectScope get() = leanProjectService.scope
