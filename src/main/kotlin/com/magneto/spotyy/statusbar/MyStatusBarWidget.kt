package com.magneto.spotyy.statusbar

import com.intellij.ide.ui.LafManagerListener
import com.magneto.spotyy.spotify.SpotifyMacService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.magneto.spotyy.spotify.SpotifyState
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

    // Track dark theme state
    private var isDarkTheme = true

    // Theme listener to update UI when theme changes
    private val lafListener = LafManagerListener {
        updateThemeState()
        updateIconsForTheme()
        statusBar?.updateWidget(ID())
    }

    private inner class SpotifyStatusBarPanel : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder()

            // Set hover prevention properties
            putClientProperty("StatusBar.hoverBackground", null)
            putClientProperty("JComponent.NO_HOVER", true)
        }

        // Completely override the painting to ensure no hover effect is shown
        override fun paintComponent(g: Graphics) {
            // Continue with normal painting, but never with hover colors
            super.paintComponent(g)
        }
    }

    private val panel = SpotifyStatusBarPanel()
    private val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
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
    private var prevIcon = IconLoader.findIcon("/icons/left_light.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private val playIconSize = 18
    private var playIcon = IconLoader.findIcon("/icons/play_light.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, playIconSize, playIconSize)
    }
    private var pauseIcon = IconLoader.findIcon("/icons/pause_light.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, playIconSize, playIconSize)
    }
    private var nextIcon = IconLoader.findIcon("/icons/right_light.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private var volumeIcon = IconLoader.findIcon("/icons/volume_light.svg", MyStatusBarWidget::class.java)?.let {
        IconUtil.toSize(it, iconSize, iconSize)
    }
    private var volumeMuteIcon =
        IconLoader.findIcon("/icons/volume_mute_light.svg", MyStatusBarWidget::class.java)?.let {
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

    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(MyStatusBarWidget::class.java)

    init {
        setupUI()
        UIManager.put("StatusBarWidget.hoverBackground", UIManager.getColor("StatusBar.background"))
        // Increase update interval to reduce AppleScript calls
        updateTimer = Timer(3000) { // Update every 3 seconds instead of every 1 second
            ApplicationManager.getApplication().executeOnPooledThread {
                // Run Spotify communication on a background thread
                val state = spotifyService.getCurrentTrack()
                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    updateUIWithState(state)
                }
            }
        }
        updateTimer.start()

        // Initialize theme state
        updateThemeState()

        // Register theme change listener
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, lafListener)
    }

    private fun isDarkTheme(): Boolean {
        return JBColor.isBright().not() // Check if using dark theme (not bright)
    }

    private fun updateThemeState() {
        isDarkTheme = isDarkTheme()
    }

    private fun updateIconsForTheme() {
        // Update icons based on current theme
        ApplicationManager.getApplication().invokeLater {
            try {
                // Load appropriate icons for the current theme
                val iconSuffix = if (isDarkTheme) "_light" else "_dark"

                // Load all icons with the appropriate suffix
                val newPrevIcon =
                    IconLoader.getIcon("/icons/left${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                    IconUtil.toSize(it, iconSize, iconSize)
                }

                val newPlayIcon =
                    IconLoader.getIcon("/icons/play${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                    IconUtil.toSize(it, playIconSize, playIconSize)
                }

                val newPauseIcon =
                    IconLoader.getIcon("/icons/pause${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                    IconUtil.toSize(it, playIconSize, playIconSize)
                }

                val newNextIcon =
                    IconLoader.getIcon("/icons/right${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                    IconUtil.toSize(it, iconSize, iconSize)
                }

                val newVolumeIcon =
                    IconLoader.getIcon("/icons/volume${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                    IconUtil.toSize(it, iconSize, iconSize)
                }

                val newVolumeMuteIcon =
                    IconLoader.getIcon("/icons/volume_mute${iconSuffix}.svg", MyStatusBarWidget::class.java)?.let {
                        IconUtil.toSize(it, iconSize, iconSize)
                    }

                // Update button icons
                if (newPrevIcon != null) {
                    prevButton.icon = newPrevIcon
                    prevIcon = newPrevIcon
                }
                if (newNextIcon != null) {
                    nextButton.icon = newNextIcon
                    nextIcon = newNextIcon
                }

                // Update other icons conditionally based on state
                if (newPlayIcon != null && newPauseIcon != null) {
                    val isPlaying = playPauseButton.icon == pauseIcon
                    playPauseButton.icon = if (isPlaying) newPauseIcon else newPlayIcon
                    playIcon = newPlayIcon
                    pauseIcon = newPauseIcon
                }

                if (newVolumeIcon != null && newVolumeMuteIcon != null) {
                    val isMuted = volumeButton.icon == volumeMuteIcon
                    volumeButton.icon = if (isMuted) newVolumeMuteIcon else newVolumeIcon
                    volumeIcon = newVolumeIcon
                    volumeMuteIcon = newVolumeMuteIcon
                }

                // Update spotty icon too
                val spotyyIconSuffix = if (isDarkTheme) "_light" else "_dark"
                val newSpotyyIcon =
                    IconLoader.getIcon("/icons/spotyy_icon${spotyyIconSuffix}.svg", MyStatusBarWidget::class.java)
                if (newSpotyyIcon != null) {
                    spotifyIconLabel.icon = newSpotyyIcon
                    trackInfoLabel.icon = IconUtil.scale(newSpotyyIcon, trackInfoLabel, 0.9f)
                }

                // Update text color based on theme
                val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
                trackInfoLabel.foreground = textColor

                panel.revalidate()
                panel.repaint()
            } catch (e: Exception) {
                logger.warn("Error updating icons for theme", e)
            }
        }
    }

    private fun setupUI() {
        // Always use dark theme
        controlsPanel.isOpaque = false
        controlsPanel.putClientProperty("JComponent.NO_HOVER", true)

        // Set minimum size to reduce vertical space
        controlsPanel.minimumSize = Dimension(0, 0)

        // Make background transparent
        panel.isOpaque = false
        controlsPanel.isOpaque = false

        // Remove any borders
        prevButton.border = null
        playPauseButton.border = null
        nextButton.border = null
        volumeButton.border = null
        controlsPanel.border = null

        // Set up Spotify icon - use green spotify logo
        spotifyIconLabel.border = BorderFactory.createEmptyBorder(0, 8, 0, 10)
        spotifyIconLabel.preferredSize = Dimension(22, 16)
        try {
            // Get appropriate icon for current theme
            val iconSuffix = if (isDarkTheme) "_light" else "_dark"
            val spotifyIcon = IconLoader.getIcon("/icons/spotyy_icon${iconSuffix}.svg", MyStatusBarWidget::class.java)
            spotifyIconLabel.icon = spotifyIcon
        } catch (e: Exception) {
            logger.warn("Failed to load Spotify icon", e)
        }

        // Set up track info label
        trackInfoLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 0) // Remove extra padding
        trackInfoLabel.maximumSize = Dimension(Short.MAX_VALUE.toInt(), trackInfoLabel.font.size)
        trackInfoLabel.toolTipText = null
        trackInfoLabel.foreground = if (isDarkTheme) Color.WHITE else Color.BLACK

        // Configure button colors for dark theme
        val buttonColor = Color.LIGHT_GRAY

        configureControlButton(prevButton, prevIcon, PREV_TEXT) {
            // Execute the action on a background thread to prevent UI freezing
            ApplicationManager.getApplication().executeOnPooledThread {
                spotifyService.previousTrack()
                updateCurrentTrack()
            }
        }

        configureControlButton(playPauseButton, playIcon, PLAY_TEXT, true) {
            // Execute the action on a background thread to prevent UI freezing
            ApplicationManager.getApplication().executeOnPooledThread {
                spotifyService.playPause()
                updateCurrentTrack()
            }
        }

        configureControlButton(nextButton, nextIcon, NEXT_TEXT) {
            // Execute the action on a background thread to prevent UI freezing
            ApplicationManager.getApplication().executeOnPooledThread {
                spotifyService.nextTrack()
                updateCurrentTrack()
            }
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

        // Create a separator before controls
        val separator = JSeparator(SwingConstants.VERTICAL)
        separator.preferredSize = Dimension(1, 16)
        separator.background = Color(80, 80, 80)
        separator.foreground = Color(80, 80, 80)

        // Add components in the right order with proper spacing
        controlsPanel.add(Box.createHorizontalStrut(8)) // Start padding
        controlsPanel.add(trackInfoLabel)
        controlsPanel.add(Box.createHorizontalStrut(15)) // Reduced gap between text and previous button
        controlsPanel.add(prevButton)
        controlsPanel.add(Box.createHorizontalStrut(8))
        controlsPanel.add(playPauseButton)
        controlsPanel.add(Box.createHorizontalStrut(8))
        controlsPanel.add(nextButton)
        controlsPanel.add(Box.createHorizontalStrut(10))
        controlsPanel.add(volumeButton)
        controlsPanel.add(Box.createHorizontalStrut(12)) // End padding

        panel.add(controlsPanel, BorderLayout.CENTER)

        for (component in arrayOf(panel, controlsPanel, trackInfoLabel, spotifyIconLabel)) {
            component.putClientProperty("JComponent.NO_HOVER", true)
            component.putClientProperty("StatusBar.hoverBackground", null)
            component.putClientProperty("StatusBarWidget.hoverBackground", null)
            if (component != trackInfoLabel) {
                component.border = EmptyBorder(0, 0, 0, 0)
            }
        }
    }

    private fun showVolumeSliderDialog(component: Component, x: Int, y: Int) {
        try {
            // Dismiss existing dialog if any
            dismissVolumePopup()

            // Get volume in background thread and then show UI
            ApplicationManager.getApplication().executeOnPooledThread {
                val currentVolume = spotifyService.getVolume()
                // Create and show UI on EDT
                ApplicationManager.getApplication().invokeLater {
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
                }
            }
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val state = spotifyService.getCurrentTrack()
            // Update UI on EDT
            ApplicationManager.getApplication().invokeLater {
                updateUIWithState(state)
            }
        }
    }

    private fun updateUIWithState(state: SpotifyState) {
        // Update track info
        val trackInfo = when {
            !state.isRunning -> "Spotyy"
            state.trackInfo == null || state.trackInfo == "Not playing" -> "Spotyy"
            else -> "Spotyy   |   ${state.trackInfo}"
        }
        trackInfoLabel.text = trackInfo

        // Add icon to text label (in addition to the main icon)
        try {
            if (trackInfoLabel.icon == null) {
                // Get appropriate icon for current theme
                val iconSuffix = if (isDarkTheme) "_light" else "_dark"
                val originalIcon =
                    IconLoader.getIcon("/icons/spotyy_icon${iconSuffix}.svg", MyStatusBarWidget::class.java)
                val icon = IconUtil.scale(originalIcon, trackInfoLabel, 0.9f)
                // Position the icon to appear at the beginning of the text
                trackInfoLabel.setIconTextGap(10) // Exact gap from screenshot
                trackInfoLabel.icon = icon
            }
        } catch (e: Exception) {
            logger.warn("Could not load spotyy icon for text label", e)
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
        // Initialize with the correct theme
        updateThemeState()
        updateIconsForTheme()
    }

    override fun dispose() {
        updateTimer.stop()
        cancelPopupDismissTimer()
        dismissVolumePopup()
        // Clean up theme listener
        ApplicationManager.getApplication().messageBus.connect().disconnect()
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

            // For play/pause button, always show a circular background
            if (alwaysShowBackground) {
                g2.color = Color(90, 90, 90)
                g2.fillOval(x, y, size, size)
            }

            // Show highlight on hover for all buttons
            if (button.model.isRollover) {
                g2.color = if (alwaysShowBackground) Color(110, 110, 110) else Color(60, 60, 60)
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

            addActionListener {
                // Execute the action on a background thread to prevent UI freezing
                ApplicationManager.getApplication().executeOnPooledThread {
                    action()
                    // Update UI after action is complete
                    updateCurrentTrack()
                }
            }
        }
    }
}