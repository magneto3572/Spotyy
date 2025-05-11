package com.magneto.spotyy.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import javax.swing.UIManager

class MyStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String {
        return "com.github.magneto3572.spotyy.MyStatusBarWidget"
    }

    override fun getDisplayName(): String {
        return "Spotify"
    }

    override fun isAvailable(project: Project): Boolean {
        // Always available
        return true
    }

    override fun createWidget(project: Project): StatusBarWidget {
        val widget = MyStatusBarWidget()

        // Apply no-hover CSS to the widget
        UIManager.put("StatusBarWidget.hoverBackground", UIManager.getColor("StatusBar.background"))

        return widget
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        // Reset UI manager properties when disposing
        UIManager.put("StatusBarWidget.hoverBackground", null)

        // Dispose of the widget
        if (widget is MyStatusBarWidget) {
            widget.dispose()
        }
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean {
        return true
    }
}