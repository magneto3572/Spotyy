package com.magneto.spotyy.startup

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.magneto.spotyy.focus.FocusRoom
import com.magneto.spotyy.focus.FocusRoomService
import com.magneto.spotyy.network.NetworkDiscoveryService
import com.magneto.spotyy.onboarding.OnboardingService
import com.magneto.spotyy.review.ReviewNudgeService
import com.magneto.spotyy.spotify.SpotifyLinuxService
import com.magneto.spotyy.spotify.SpotifyMacService
import com.magneto.spotyy.spotify.SpotifyServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

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

            OnboardingService.maybeShow()
            ReviewNudgeService.maybeShowReviewNudge(project)

            val service = SpotifyServiceFactory.instance
            delay(2000)
            service.getCurrentTrack()

            when (service) {
                is SpotifyLinuxService -> {
                    if (service.snapPermissionBlocked) {
                        ApplicationManager.getApplication().invokeLater {
                            showSnapPermissionNotification(project)
                        }
                    }
                }
                is SpotifyMacService -> {
                    if (service.automationPermissionBlocked) {
                        ApplicationManager.getApplication().invokeLater {
                            showAutomationPermissionNotification(project)
                        }
                    }
                }
            }
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

    private fun showAutomationPermissionNotification(project: Project) {
        Notification(
            "Spotyy",
            "Spotyy: Automation permission required",
            "macOS is blocking AppleScript access to Spotify. Open <b>System Settings → Privacy &amp; Security → Automation</b> and enable Spotify for your IDE.",
            NotificationType.WARNING
        ).apply {
            addAction(object : NotificationAction("Copy steps") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    CopyPasteManager.getInstance().setContents(
                        StringSelection(
                            "1. Open System Settings\n" +
                            "2. Go to Privacy & Security → Automation\n" +
                            "3. Find your IDE (IntelliJ IDEA / Android Studio)\n" +
                            "4. Enable the Spotify checkbox\n" +
                            "5. Restart the IDE"
                        )
                    )
                }
            })
        }.notify(project)
    }

    private fun showSnapPermissionNotification(project: Project) {
        // Snap Spotify's AppArmor policy blocks D-Bus from any process spawned by the JVM
        // (all child processes inherit the IDE's AppArmor label, which snap Spotify rejects).
        // The only reliable fix is to replace the snap with the official .deb package.
        val installCommands = listOf(
            "sudo snap remove spotify",
            "curl -sS https://download.spotify.com/debian/pubkey_6224F9941A8AA6D1.gpg | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/spotify.gpg",
            "echo \"deb http://repository.spotify.com stable non-free\" | sudo tee /etc/apt/sources.list.d/spotify.list",
            "sudo apt update && sudo apt install spotify-client"
        ).joinToString("\n")

        Notification(
            "Spotyy",
            "Spotyy: Spotify (snap) is incompatible",
            "Snap Spotify's AppArmor policy blocks D-Bus access from IDE processes. " +
            "Reinstall Spotify from the official apt repository for full Spotyy support. " +
            "Click <b>Copy fix commands</b> to get the terminal commands.",
            NotificationType.WARNING
        ).apply {
            addAction(object : NotificationAction("Copy fix commands") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    CopyPasteManager.getInstance().setContents(StringSelection(installCommands))
                }
            })
        }.notify(project)
    }
}
