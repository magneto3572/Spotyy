package com.magneto.spotyy.statusbar

import com.magneto.spotyy.spotify.SpotifyMacService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicSliderUI

class MyStatusBarWidget : CustomStatusBarWidget {

    private val spotifyService = SpotifyMacService()
    private val updateTimer: Timer
    private var statusBar: StatusBar? = null

    // Custom panel that overrides hover behavior
    private inner class SpotifyStatusBarPanel : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder()

            // Set hover prevention properties
            putClientProperty("StatusBar.hoverBackground", UIManager.getColor("StatusBar.background"))
            putClientProperty("JComponent.NO_HOVER", true)
        }

        // Completely override the painting to ensure no hover effect is shown
        override fun paintComponent(g: Graphics) {
            // Don't change background on hover
            val bg = if (UIManager.getColor("StatusBar.background") != null) {
                UIManager.getColor("StatusBar.background")
            } else {
                background
            }

            if (bg != null && isOpaque) {
                g.color = bg
                g.fillRect(0, 0, width, height)
            }

            // Continue with normal painting, but never with hover colors
            super.paintComponent(g)
        }
    }

    private val panel = SpotifyStatusBarPanel()
    private val controlsPanel = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
    private val trackInfoLabel = JLabel("")
    private val prevButton = JButton()
    private val playPauseButton = JButton()
    private val nextButton = JButton()
    private val volumeButton = JButton()

    // Track the volume dialog to allow dismissal on second click
    private var volumeDialog: JDialog? = null

    // Timer for auto-dismissal of volume popup
    private var volumePopupTimer: Timer? = null

    // Icons for controls - load from resources and ensure consistent sizing
    private val iconSize = 16  // Define standard icon size
    private val prevIcon = IconLoader.findIcon("/icons/left.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private val playIconSize = 18
    private val playIcon = IconLoader.findIcon("/icons/play.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, playIconSize, playIconSize)
    }
    private val pauseIcon = IconLoader.findIcon("/icons/pause.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, playIconSize, playIconSize)
    }
    private val nextIcon = IconLoader.findIcon("/icons/right.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private val volumeIcon = IconLoader.findIcon("/icons/volume.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private val volumeMuteIcon = IconLoader.findIcon("/icons/volume_mute.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }

    // Add Spotify icon label 
    private val spotifyIconLabel = JLabel()

    // Default fallback Unicode characters in case icons don't load
    private val PREV_TEXT = "⏪"
    private val PLAY_TEXT = "⏵"
    private val PAUSE_TEXT = "⏸"
    private val NEXT_TEXT = "⏩"
    private val VOLUME_TEXT = "🔊"
    private val VOLUME_MUTE_TEXT = "🔇"

    init {
        setupUI()
        UIManager.put("StatusBarWidget.hoverBackground", UIManager.getColor("StatusBar.background"))
        updateTimer = Timer(1000) { // Update every second
            updateCurrentTrack()
        }
        updateTimer.start()
    }

    private fun isDarkTheme(): Boolean {
        val background = JBColor.background()
        return (background.red * 0.299 + background.green * 0.587 + background.blue * 0.114) < 128
    }

    private fun setupUI() {
        val isDark = isDarkTheme()

        // Set panels to be transparent
        controlsPanel.isOpaque = false
        controlsPanel.putClientProperty("JComponent.NO_HOVER", true)

        // Set minimum size to reduce vertical space
        controlsPanel.minimumSize = Dimension(0, 0)

        // Remove any borders
        prevButton.border = null
        playPauseButton.border = null
        nextButton.border = null
        volumeButton.border = null
        controlsPanel.border = null

        // Set up Spotify icon
        spotifyIconLabel.border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
        spotifyIconLabel.preferredSize = Dimension(18, 16) // Increase height slightly to avoid clipping

        // Set up track info label
        trackInfoLabel.border = null
        trackInfoLabel.maximumSize = Dimension(Short.MAX_VALUE.toInt(), trackInfoLabel.font.size)
        trackInfoLabel.toolTipText = null  // Remove tooltip since the click functionality is gone

        // Use JBColor for foreground - this automatically adapts to dark/light theme
        trackInfoLabel.foreground = JBColor(Color.BLACK, Color.WHITE)

        // No mouse listener needed since click functionality is removed

        // Use JBColor for control buttons too - automatically adapts to dark/light theme
        val buttonColor = JBColor(Color.BLACK, Color.WHITE)

        configureControlButton(prevButton, prevIcon, PREV_TEXT) {
            spotifyService.previousTrack()
            updateCurrentTrack()
        }

        configureControlButton(playPauseButton, playIcon, PLAY_TEXT, true) {
            spotifyService.playPause()
            updateCurrentTrack()
        }

        configureControlButton(nextButton, nextIcon, NEXT_TEXT) {
            spotifyService.nextTrack()
            updateCurrentTrack()
        }

        configureControlButton(volumeButton, volumeIcon, VOLUME_TEXT) {
            // If dialog is already visible, dismiss it
            if (volumeDialog != null && volumeDialog!!.isVisible) {
                volumeDialog!!.dispose()
                volumeDialog = null
                return@configureControlButton
            }

            // Get position relative to the button
            val bounds = volumeButton.bounds
            showVolumeSliderDialog(volumeButton, bounds.x + bounds.width / 2, bounds.y)
        }

        controlsPanel.add(spotifyIconLabel)
        controlsPanel.add(Box.createHorizontalStrut(4))
        controlsPanel.add(trackInfoLabel)
        controlsPanel.add(Box.createHorizontalStrut(6)) // Space between text and controls
        controlsPanel.add(prevButton)
        controlsPanel.add(Box.createHorizontalStrut(6))
        controlsPanel.add(playPauseButton)
        controlsPanel.add(Box.createHorizontalStrut(6))
        controlsPanel.add(nextButton)
        controlsPanel.add(Box.createHorizontalStrut(6))
        controlsPanel.add(volumeButton)

        panel.add(controlsPanel, BorderLayout.CENTER)

        for (component in arrayOf(panel, controlsPanel, trackInfoLabel, spotifyIconLabel)) {
            component.putClientProperty("JComponent.NO_HOVER", true)
            component.putClientProperty("StatusBar.hoverBackground", UIManager.getColor("StatusBar.background"))
            component.putClientProperty("StatusBarWidget.hoverBackground", UIManager.getColor("StatusBar.background"))
            component.border = EmptyBorder(0, 0, 0, 0)
        }
    }

    private fun showVolumeSliderDialog(component: Component, x: Int, y: Int) {
        try {
            // Dismiss existing dialog if any
            dismissVolumePopup()

            val currentVolume = spotifyService.getVolume()

            volumeDialog = JDialog()
            volumeDialog?.isUndecorated = true
            volumeDialog?.modalityType = Dialog.ModalityType.MODELESS
            volumeDialog?.background = Color(43, 43, 43)

            // Create rounded panel for the content
            class RoundedPanel(layout: LayoutManager) : JPanel(layout) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(43, 43, 43)
                    g2.fillRoundRect(0, 0, width, height, 15, 15)
                    g2.dispose()
                }

                override fun paintBorder(g: Graphics) {
                    // No border
                }

                override fun isOpaque(): Boolean {
                    return false
                }
            }

            // Create the rounded content panel
            val contentPanel = RoundedPanel(BorderLayout())
            contentPanel.border = BorderFactory.createEmptyBorder(10, 15, 10, 15)
            contentPanel.putClientProperty("JComponent.outline", null)

            // Add volume control heading
            val titleLabel = JLabel("Volume Control", SwingConstants.CENTER)
            titleLabel.foreground = Color.WHITE
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
            contentPanel.add(titleLabel, BorderLayout.NORTH)

            // Create the slider with custom white circle thumb
            val volumeSlider = JSlider(JSlider.HORIZONTAL, 0, 100, currentVolume)
            volumeSlider.paintTicks = false  // Don't show tick marks
            volumeSlider.paintLabels = false  // Don't paint number labels
            volumeSlider.background = Color(43, 43, 43)
            volumeSlider.foreground = Color.WHITE
            volumeSlider.isOpaque = false // Make transparent to show rounded background

            // Make slider more responsive by setting faster update interval
            volumeSlider.setMinorTickSpacing(1)
            volumeSlider.putClientProperty("JSlider.isFilled", true)
            volumeSlider.putClientProperty("Slider.paintThumbArrowShape", false)

            // Explicitly remove any borders from the slider
            volumeSlider.border = BorderFactory.createEmptyBorder()
            volumeSlider.putClientProperty("JComponent.outline", null)
            volumeSlider.isFocusable = false

            // Custom UI for white circle thumb
            volumeSlider.ui = object : BasicSliderUI(volumeSlider) {
                private val THUMB_SIZE = 12

                override fun paintThumb(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    val knobBounds = thumbRect
                    g2.color = Color.WHITE

                    // Center the circle vertically on the track
                    val trackMidpoint = trackRect.y + (trackRect.height / 2)
                    val thumbY = trackMidpoint - (THUMB_SIZE / 2)

                    g2.fillOval(knobBounds.x, thumbY, THUMB_SIZE, THUMB_SIZE)
                    g2.dispose()
                }

                override fun paintTrack(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)

                    // Draw track background - simplified for performance
                    val trackBounds = trackRect
                    val height = 4
                    val y = trackBounds.y + (trackBounds.height - height) / 2

                    // Background track (darker gray)
                    g2.color = Color(90, 90, 90)
                    g2.fillRoundRect(trackBounds.x, y, trackBounds.width, height, height, height)

                    // Filled part of track (white) - only draw if needed
                    val thumbPos = thumbRect.x + thumbRect.width / 2
                    if (thumbPos > trackBounds.x) {
                        g2.color = Color.WHITE
                        g2.fillRoundRect(trackBounds.x, y, thumbPos - trackBounds.x, height, height, height)
                    }

                    g2.dispose()
                }

                override fun getThumbSize(): Dimension {
                    return Dimension(THUMB_SIZE, THUMB_SIZE)
                }
            }

            // Add change listener to slider
            volumeSlider.addChangeListener {
                val newVolume = volumeSlider.value

                // Update UI immediately but only send to Spotify when sliding stops
                // Update volume button tooltip immediately for visual feedback
                volumeButton.toolTipText = "Volume: $newVolume%"

                // Only update Spotify when sliding stops to avoid lag
                if (!volumeSlider.valueIsAdjusting) {
                    // Use background thread for Spotify communication to avoid UI lag
                    ApplicationManager.getApplication().executeOnPooledThread {
                        spotifyService.setVolume(newVolume)

                        // Update UI on EDT after setting volume
                        ApplicationManager.getApplication().invokeLater {
                            updateVolumeIcon(newVolume)
                        }
                    }
                } else {
                    // During sliding, just update the UI for smoother experience 
                    updateVolumeIcon(newVolume)
                }
            }

            // Add some padding above the slider
            val sliderPanel = JPanel(BorderLayout())
            sliderPanel.background = Color(43, 43, 43)
            sliderPanel.isOpaque = false
            sliderPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
            sliderPanel.putClientProperty("JComponent.outline", null)
            sliderPanel.add(volumeSlider, BorderLayout.CENTER)

            // Add to the content panel (after the title)
            contentPanel.add(sliderPanel, BorderLayout.CENTER)

            // Add to dialog and show
            volumeDialog?.contentPane = contentPanel
            volumeDialog?.rootPane?.putClientProperty("JRootPane.isDialogRootPane", true)
            volumeDialog?.rootPane?.putClientProperty("JComponent.outline", null)
            volumeDialog?.rootPane?.border = BorderFactory.createEmptyBorder()
            volumeDialog?.pack()

            // Position the dialog properly relative to the volume button
            val dialogSize = volumeDialog?.size ?: Dimension(300, 80)
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val componentLocation = component.locationOnScreen

            // Calculate ideal position above the status bar
            var xPos = componentLocation.x - (dialogSize.width / 2) + (component.width / 2)
            var yPos = componentLocation.y - dialogSize.height - 15  //

            // Keep dialog within screen bounds
            xPos = xPos.coerceIn(50, screenSize.width - dialogSize.width - 50)
            yPos = yPos.coerceAtLeast(30)

            volumeDialog?.setLocation(xPos, yPos)
            volumeDialog?.isVisible = true

            // Start a simple timer that will close the popup after exactly 5 seconds
            startPopupDismissTimer()

            // Add window listener to cancel timer if popup is closed by other means
            volumeDialog?.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) {
                    cancelPopupDismissTimer()
                }
            })

            // Simple listeners to detect any activity
            volumeSlider.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Restart the timer on any activity
                    startPopupDismissTimer()
                }

                override fun mousePressed(e: MouseEvent) {
                    startPopupDismissTimer()
                }

                override fun mouseReleased(e: MouseEvent) {
                    startPopupDismissTimer()
                }
            })

            volumeSlider.addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    startPopupDismissTimer()
                }

                override fun mouseMoved(e: MouseEvent) {
                    startPopupDismissTimer()
                }
            })

            // Add window listener for when the dialog loses focus
            volumeDialog?.addWindowFocusListener(object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    startPopupDismissTimer()
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    // When focus is lost, dismiss immediately
                    dismissVolumePopup()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            volumeDialog = null
        }
    }

    private fun updateVolumeIcon(volume: Int) {
        if (volume == 0) {
            volumeButton.icon = volumeMuteIcon
            volumeButton.text = if (volumeMuteIcon == null) VOLUME_MUTE_TEXT else ""
        } else {
            volumeButton.icon = volumeIcon
            volumeButton.text = if (volumeIcon == null) VOLUME_TEXT else ""
        }
        volumeButton.toolTipText = "Volume: $volume%"

        // Only update activity time if volume dialog is visible
        if (volumeDialog?.isVisible == true) {
            startPopupDismissTimer()
        }
    }

    private fun updateCurrentTrack() {
        val state = spotifyService.getCurrentTrack()

        // Update track info
        val trackInfo = when {
            !state.isRunning -> "Spotyy"
            state.trackInfo == null || state.trackInfo == "Not playing" -> "Spotyy"
            else -> "Spotyy    |    ${state.trackInfo}"
        }
        trackInfoLabel.text = trackInfo

        // Load and set Spotify icon for the label (white color)
        if (spotifyIconLabel.icon == null) {
            try {
                val whiteIcon = IconLoader.getIcon("/icons/spotyy_icon.svg", MyStatusBarWidget::class.java)
                spotifyIconLabel.icon = whiteIcon
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Update play/pause button based on state
        if (state.isPlaying) {
            playPauseButton.icon = pauseIcon
            playPauseButton.text = if (pauseIcon == null) PAUSE_TEXT else ""
        } else {
            playPauseButton.icon = playIcon
            playPauseButton.text = if (playIcon == null) PLAY_TEXT else ""
        }

        // Update volume icon based on volume level
        updateVolumeIcon(state.volume)

        ApplicationManager.getApplication().invokeLater {
            panel.revalidate()
            panel.repaint()
        }
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        updateTimer.stop()
        cancelPopupDismissTimer()
        dismissVolumePopup()
        statusBar = null
        UIManager.put("StatusBarWidget.hoverBackground", null)
    }

    override fun ID(): String = "MyStatusBarWidget"

    override fun getComponent(): JComponent {
        return panel
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? {
        return null
    }

    // Start a timer to automatically dismiss the volume popup after 5 seconds
    private fun startPopupDismissTimer() {
        // Cancel any existing timer
        cancelPopupDismissTimer()

        // Create and start a new timer
        volumePopupTimer = Timer(5000) { _ ->
            dismissVolumePopup()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // Cancel the popup dismiss timer if it exists
    private fun cancelPopupDismissTimer() {
        volumePopupTimer?.stop()
        volumePopupTimer = null
    }

    // Helper method to dismiss the volume popup safely
    private fun dismissVolumePopup() {
        volumePopupTimer?.stop()
        volumeDialog?.dispose()
        volumeDialog = null
    }

    private class CircularButtonUI(private val alwaysShowBackground: Boolean = false) : BasicButtonUI() {
        init {
            // Make sure hover only happens within button bounds
            UIManager.put("Button.paintShadow", false)
            UIManager.put("Button.rollover", true)
        }

        override fun paint(g: Graphics, c: JComponent) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val button = c as JButton
            val size = minOf(button.width, button.height) - 4
            val x = (button.width - size) / 2
            val y = (button.height - size) / 2

            // For play/pause button, always show a background
            if (alwaysShowBackground) {
                g2.color = Color(50, 50, 50)
                g2.fillOval(x, y, size, size)
            }

            // Show highlight on hover for all buttons
            if (button.model.isRollover) {
                g2.color = if (alwaysShowBackground) Color(80, 80, 80) else Color(60, 60, 60)
                g2.fillOval(x, y, size, size)
            }

            g2.dispose()
            super.paint(g, c)
        }
    }

    private fun configureControlButton(
        button: JButton,
        icon: Icon?,
        fallbackText: String,
        isPrimary: Boolean = false,
        action: () -> Unit
    ) {
        button.apply {
            this.icon = icon
            text = if (icon == null) fallbackText else ""
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            background = null

            // Ensure proper sizing and centering of icons
            if (isPrimary) {
                // Make the play/pause button slightly larger
                preferredSize = Dimension(32, 32)
                maximumSize = Dimension(32, 32)
                minimumSize = Dimension(32, 32)
                margin = Insets(5, 5, 5, 5)
                border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            } else {
                preferredSize = Dimension(28, 28)
                margin = Insets(4, 4, 4, 4)
                border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            }

            // Set custom UI for circular appearance with option for persistent background
            ui = CircularButtonUI(isPrimary)

            // Make the button circular in IntelliJ's UI system
            putClientProperty("JButton.backgroundColor", null)
            putClientProperty("JButton.arc", 999)
            putClientProperty("JButton.hoverBorderColor", null)
            putClientProperty("JComponent.NO_HOVER", false)  // Enable hover only on the button itself
            putClientProperty("JComponent.transparentChildren", true)
            putClientProperty("JButton.mouseHoverColor", null)
            putClientProperty("JButton.mouseHoverBorder", null)

            // Remove all existing listeners
            for (listener in actionListeners) {
                removeActionListener(listener)
            }

            addActionListener { action() }
        }
    }
}