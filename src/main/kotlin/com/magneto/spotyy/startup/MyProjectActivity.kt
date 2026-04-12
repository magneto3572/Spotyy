package com.magneto.spotyy.startup

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.magneto.spotyy.focus.FocusRoom
import com.magneto.spotyy.focus.FocusRoomService
import com.magneto.spotyy.network.NetworkDiscoveryService
import com.magneto.spotyy.review.ReviewNudgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            NetworkDiscoveryService.start()
            NetworkDiscoveryService.roomMessageHandler = { FocusRoomService.handleMessage(it) }

            FocusRoomService.project = project
            FocusRoomService.onRoomInvite = { room ->
                ApplicationManager.getApplication().invokeLater {
                    showRoomInviteNotification(room, project)
                }
            }

            ReviewNudgeService.maybeShowReviewNudge(project)
        }
    }

    private fun showRoomInviteNotification(room: FocusRoom, project: Project) {
        val mins = room.durationSeconds / 60
        Notification(
            "Spotyy",
            "${room.hostName} started a Focus Room",
            "$mins min session — join to code together",
            NotificationType.INFORMATION
        ).apply {
            addAction(object : NotificationAction("Join") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    FocusRoomService.joinRoom(room)
                    n.expire()
                }
            })
        }.notify(project)
    }
}
