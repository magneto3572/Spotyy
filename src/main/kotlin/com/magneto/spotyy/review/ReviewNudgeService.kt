package com.magneto.spotyy.review

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import java.util.concurrent.TimeUnit

object ReviewNudgeService {

    private const val INSTALL_DATE_KEY = "spotyy.install.date"
    private const val REVIEW_SHOWN_KEY = "spotyy.review.shown"
    private const val DAYS_BEFORE_NUDGE = 3L

    // Update this URL once your Marketplace plugin page is live:
    // https://plugins.jetbrains.com/plugin/<your-plugin-id>/reviews
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/spotyy"

    fun maybeShowReviewNudge(project: Project) {
        val props = PropertiesComponent.getInstance()

        // Record first-launch date
        if (props.getValue(INSTALL_DATE_KEY) == null) {
            props.setValue(INSTALL_DATE_KEY, System.currentTimeMillis().toString())
        }

        // Only ever show once
        if (props.getBoolean(REVIEW_SHOWN_KEY, false)) return

        val installDate = props.getValue(INSTALL_DATE_KEY)?.toLongOrNull() ?: return
        val daysSinceInstall = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installDate)

        if (daysSinceInstall >= DAYS_BEFORE_NUDGE) {
            showReviewNotification(project)
            props.setValue(REVIEW_SHOWN_KEY, true)
        }
    }

    private fun showReviewNotification(project: Project) {
        val notification = Notification(
            "Spotyy",
            "Enjoying Spotyy?",
            "If Spotyy is keeping you in the zone, a quick review on the JetBrains Marketplace would mean a lot!",
            NotificationType.INFORMATION
        )

        notification.addAction(NotificationAction.createSimpleExpiring("Leave a Review") {
            BrowserUtil.browse(MARKETPLACE_URL)
        })

        notification.addAction(NotificationAction.createSimpleExpiring("No Thanks") {
            // dismiss — already marked as shown, won't appear again
        })

        notification.notify(project)
    }
}
