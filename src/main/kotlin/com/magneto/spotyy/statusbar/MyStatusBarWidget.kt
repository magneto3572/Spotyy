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
import com.magneto.spotyy.network.NetworkDiscoveryService
import com.magneto.spotyy.network.VibeMatch
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
import javax.swing.plaf.basic.BasicScrollBarUI
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

    // Peers button and popup
    private val peersButton = JButton()
    private var peersDialog: JDialog? = null

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
                // Broadcast current playback state to the local network
                NetworkDiscoveryService.broadcast(state.trackInfo ?: "", state.isPlaying)
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
                spotifyIconLabel.icon = newSpotyyIcon
                trackInfoLabel.icon = IconUtil.scale(newSpotyyIcon, trackInfoLabel, 0.9f)

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
            spotifyService.previousTrack()
        }

        configureControlButton(playPauseButton, playIcon, PLAY_TEXT, true) {
            spotifyService.playPause()
        }

        configureControlButton(nextButton, nextIcon, NEXT_TEXT) {
            spotifyService.nextTrack()
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

        // Peers button — shows who else on the network is listening
        peersButton.apply {
            text = "👥"
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            background = null
            foreground = if (isDarkTheme) Color.WHITE else Color.BLACK
            preferredSize = Dimension(44, 28)
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            isVisible = false
            ui = CircularButtonUI(false)
            putClientProperty("JComponent.NO_HOVER", false)
            putClientProperty("JButton.arc", 999)
            addActionListener {
                ApplicationManager.getApplication().invokeLater {
                    if (peersDialog?.isVisible == true) {
                        dismissPeersDialog()
                    } else {
                        showPeersDialog(peersButton)
                    }
                }
            }
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
        controlsPanel.add(Box.createHorizontalStrut(6))
        controlsPanel.add(peersButton)
        controlsPanel.add(Box.createHorizontalStrut(12)) // End padding

        panel.add(controlsPanel, BorderLayout.CENTER)

        for (component in arrayOf<JComponent>(panel, controlsPanel, trackInfoLabel, spotifyIconLabel)) {
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
            state.trackInfo.isNullOrBlank() -> "Spotyy"
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

        // Update peers button — ghost / vibe match / normal / hidden
        val ghostMode    = NetworkDiscoveryService.isGhostMode()
        val peers        = NetworkDiscoveryService.getActivePeers()
        val songMatches  = peers.filter { NetworkDiscoveryService.vibeMatch(it) == VibeMatch.SAME_SONG }
        val hasSongMatch = songMatches.isNotEmpty()

        // Record any new song-level vibe matches (deduped inside the service)
        songMatches.forEach { NetworkDiscoveryService.recordVibeMatch(it.username, it.track) }

        when {
            ghostMode -> {
                peersButton.text = "👻"
                peersButton.foreground = if (isDarkTheme) Color(168, 168, 184) else Color(118, 118, 138)
                peersButton.toolTipText = "Ghost mode — you're invisible"
                peersButton.isVisible = true
            }
            hasSongMatch -> {
                peersButton.text = "✦ ${peers.size}"
                peersButton.foreground = if (isDarkTheme) Color(30, 215, 96) else Color(18, 168, 74)
                peersButton.toolTipText = "Vibing with ${songMatches.first().username}!"
                peersButton.isVisible = true
            }
            peers.isNotEmpty() -> {
                peersButton.text = "👥 ${peers.size}"
                peersButton.foreground = if (isDarkTheme) Color.WHITE else Color.BLACK
                peersButton.toolTipText = "${peers.size} people listening nearby"
                peersButton.isVisible = true
            }
            else -> peersButton.isVisible = false
        }

        ApplicationManager.getApplication().invokeLater {
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun showPeersDialog(component: Component) {
        dismissPeersDialog()

        val peers      = NetworkDiscoveryService.getActivePeers()
        val ghostMode  = NetworkDiscoveryService.isGhostMode()
        val vibeCount  = NetworkDiscoveryService.getTodayVibeCount()
        val dark       = isDarkTheme

        // Palette
        val bg       = if (dark) Color(26, 26, 30)    else Color(251, 251, 253)
        val border   = if (dark) Color(48, 48, 54)    else Color(216, 216, 224)
        val sep      = if (dark) Color(40, 40, 46)    else Color(230, 230, 238)
        val fg       = if (dark) Color(228, 228, 234) else Color(18, 18, 24)
        val fgMuted  = if (dark) Color(112, 112, 126) else Color(116, 116, 130)
        val green    = if (dark) Color(30, 215, 96)   else Color(18, 168, 74)
        val vibeBg   = if (dark) Color(30, 215, 96, 18) else Color(18, 168, 74, 16)
        val pillOff  = if (dark) Color(62, 62, 72)    else Color(200, 200, 212)

        val avatarPalette = listOf(
            Color(82, 130, 255), Color(255, 88, 88),  Color(255, 168, 40),
            Color(160, 90, 255), Color(40, 192, 212), Color(255, 120, 172)
        )

        fun divider() = object : JComponent() {
            override fun paintComponent(g: Graphics) { g.color = sep; g.fillRect(0, 0, width, 1) }
            override fun getPreferredSize() = Dimension(0, 1)
            override fun getMaximumSize()   = Dimension(Int.MAX_VALUE, 1)
        }

        // Root panel — rounded + bordered
        val root = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, 14, 14)
                g2.dispose()
            }
            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = border
                g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
                g2.dispose()
            }
            override fun isOpaque() = false
        }

        val stack = JPanel()
        stack.layout = BoxLayout(stack, BoxLayout.Y_AXIS)
        stack.isOpaque = false
        stack.minimumSize = Dimension(280, 0)

        // ── Header ────────────────────────────────────────────────────────────
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = BorderFactory.createEmptyBorder(13, 14, 13, 14)

        val title = JLabel("Listening nearby")
        title.foreground = fg
        title.font = title.font.deriveFont(Font.BOLD, 12f)

        val countLbl = JLabel(if (peers.isEmpty()) "0" else "${peers.size}")
        countLbl.foreground = if (peers.isNotEmpty()) green else fgMuted
        countLbl.font = countLbl.font.deriveFont(Font.BOLD, 12f)

        header.add(title,    BorderLayout.WEST)
        header.add(countLbl, BorderLayout.EAST)
        stack.add(header)
        stack.add(divider())

        // ── Vibe stats row (only when > 0) ────────────────────────────────────
        if (vibeCount > 0) {
            val vibeRow = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    g.color = vibeBg
                    g.fillRect(0, 0, width, height)
                }
                override fun isOpaque() = false
            }
            vibeRow.border = BorderFactory.createEmptyBorder(8, 14, 8, 14)
            vibeRow.maximumSize = Dimension(Int.MAX_VALUE, 34)

            val vibeLabel = JLabel("✦  $vibeCount vibe ${if (vibeCount == 1) "match" else "matches"} today")
            vibeLabel.foreground = green
            vibeLabel.font = vibeLabel.font.deriveFont(Font.BOLD, 11f)
            vibeRow.add(vibeLabel, BorderLayout.WEST)
            stack.add(vibeRow)
            stack.add(divider())
        }

        // ── Peer rows ─────────────────────────────────────────────────────────
        val peerList = JPanel()
        peerList.layout = BoxLayout(peerList, BoxLayout.Y_AXIS)
        peerList.isOpaque = false

        if (peers.isEmpty()) {
            val row = JPanel(BorderLayout())
            row.isOpaque = false
            row.border = BorderFactory.createEmptyBorder(13, 14, 13, 14)
            val lbl = JLabel("No one else is listening right now")
            lbl.foreground = fgMuted
            lbl.font = lbl.font.deriveFont(12f)
            row.add(lbl, BorderLayout.WEST)
            peerList.add(row)
        } else {
            peers.forEachIndexed { idx, peer ->
                val match = NetworkDiscoveryService.vibeMatch(peer)

                val row = JPanel(BorderLayout(10, 0))
                row.isOpaque = false
                row.border = BorderFactory.createEmptyBorder(9, 14, 9, 14)
                row.maximumSize = Dimension(Int.MAX_VALUE, 50)

                // Avatar
                val avatarColor = avatarPalette[Math.abs(peer.username.hashCode()) % avatarPalette.size]
                val initial     = peer.username.firstOrNull()?.uppercase() ?: "?"
                val avatar      = object : JComponent() {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        // Glow ring for song-level match
                        if (match == VibeMatch.SAME_SONG) {
                            g2.color = Color(green.red, green.green, green.blue, 60)
                            g2.fillOval(-2, -2, 32, 32)
                        }
                        g2.color = avatarColor
                        g2.fillOval(0, 0, 28, 28)
                        g2.color = Color.WHITE
                        g2.font = g2.font.deriveFont(Font.BOLD, 11f)
                        val fm = g2.fontMetrics
                        g2.drawString(initial, (28 - fm.stringWidth(initial)) / 2, (28 - fm.height) / 2 + fm.ascent)
                        g2.dispose()
                    }
                    override fun getPreferredSize() = Dimension(30, 30)
                }
                val avatarWrap = JPanel(BorderLayout())
                avatarWrap.isOpaque = false
                avatarWrap.preferredSize = Dimension(38, 30)
                avatarWrap.add(avatar, BorderLayout.WEST)

                // Name + track + vibe tag
                val info = JPanel(BorderLayout(0, 2))
                info.isOpaque = false

                val nameLbl = JLabel(peer.username)
                nameLbl.foreground = fg
                nameLbl.font = nameLbl.font.deriveFont(Font.BOLD, 12f)

                // Track label + inline vibe badge on same row
                val trackRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
                trackRow.isOpaque = false

                val raw      = peer.track
                val trackLbl = JLabel(if (raw.length > 28) raw.take(28) + "…" else raw)
                trackLbl.foreground = fgMuted
                trackLbl.font = trackLbl.font.deriveFont(11f)
                trackRow.add(trackLbl)

                when (match) {
                    VibeMatch.SAME_SONG -> {
                        val badge = object : JLabel("  ✦ Vibing!") {
                            override fun paintComponent(g: Graphics) {
                                val g2 = g.create() as Graphics2D
                                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g2.color = Color(green.red, green.green, green.blue, 28)
                                g2.fillRoundRect(0, 1, width, height - 2, height - 2, height - 2)
                                g2.dispose()
                                super.paintComponent(g)
                            }
                        }
                        badge.foreground = green
                        badge.font = badge.font.deriveFont(Font.BOLD, 10f)
                        badge.border = BorderFactory.createEmptyBorder(1, 6, 1, 6)
                        trackRow.add(Box.createHorizontalStrut(6))
                        trackRow.add(badge)
                    }
                    VibeMatch.SAME_ARTIST -> {
                        val badge = JLabel("  ∼ Same artist")
                        badge.foreground = fgMuted
                        badge.font = badge.font.deriveFont(10f)
                        trackRow.add(Box.createHorizontalStrut(4))
                        trackRow.add(badge)
                    }
                    VibeMatch.NONE -> {}
                }

                info.add(nameLbl,  BorderLayout.NORTH)
                info.add(trackRow, BorderLayout.CENTER)

                row.add(avatarWrap, BorderLayout.WEST)
                row.add(info,       BorderLayout.CENTER)
                peerList.add(row)
                if (idx < peers.size - 1) peerList.add(divider())
            }
        }

        // Scroll wrapper — max 5 rows visible, thin scrollbar
        val maxVisible = 5
        val rowHeight  = 50
        if (peers.size > maxVisible) {
            val scroll = JScrollPane(peerList).apply {
                preferredSize = Dimension(0, maxVisible * rowHeight)
                setBorder(BorderFactory.createEmptyBorder())
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.preferredSize = Dimension(4, 0)
                verticalScrollBar.ui = object : BasicScrollBarUI() {
                    override fun configureScrollBarColors() {
                        thumbColor = if (dark) Color(90, 90, 104) else Color(180, 180, 196)
                        trackColor = Color(0, 0, 0, 0)
                    }
                    override fun createDecreaseButton(o: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                    override fun createIncreaseButton(o: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                }
            }
            stack.add(scroll)
        } else {
            stack.add(peerList)
        }

        stack.add(divider())

        // ── Ghost mode toggle ─────────────────────────────────────────────────
        val ghostRow = JPanel(BorderLayout(12, 0))
        ghostRow.isOpaque = false
        ghostRow.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ghostRow.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        ghostRow.maximumSize = Dimension(Int.MAX_VALUE, 44)

        val ghostLbl = JLabel("Ghost mode")
        ghostLbl.foreground = fg
        ghostLbl.font = ghostLbl.font.deriveFont(Font.PLAIN, 12f)

        val pill = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val on = NetworkDiscoveryService.isGhostMode()
                g2.color = if (on) green else pillOff
                g2.fillRoundRect(0, 0, width, height, height, height)
                val knob = height - 4
                val kx   = if (on) width - knob - 2 else 2
                g2.color = Color.WHITE
                g2.fillOval(kx, 2, knob, knob)
                g2.dispose()
            }
            override fun getPreferredSize() = Dimension(36, 20)
            override fun isOpaque() = false
        }
        pill.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        ghostRow.add(ghostLbl, BorderLayout.CENTER)
        ghostRow.add(pill,     BorderLayout.EAST)

        val onToggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val newGhost    = !NetworkDiscoveryService.isGhostMode()
                NetworkDiscoveryService.setGhostMode(newGhost)
                dismissPeersDialog()
                val activePeers = NetworkDiscoveryService.getActivePeers()
                val songMatch   = activePeers.any { NetworkDiscoveryService.vibeMatch(it) == VibeMatch.SAME_SONG }
                when {
                    newGhost  -> {
                        peersButton.text = "👻"
                        peersButton.foreground = if (isDarkTheme) Color(168, 168, 184) else Color(118, 118, 138)
                        peersButton.isVisible = true
                    }
                    songMatch -> {
                        peersButton.text = "✦ ${activePeers.size}"
                        peersButton.foreground = if (isDarkTheme) Color(30, 215, 96) else Color(18, 168, 74)
                        peersButton.isVisible = true
                    }
                    activePeers.isNotEmpty() -> {
                        peersButton.text = "👥 ${activePeers.size}"
                        peersButton.foreground = if (isDarkTheme) Color.WHITE else Color.BLACK
                        peersButton.isVisible = true
                    }
                    else -> peersButton.isVisible = false
                }
                panel.revalidate()
                panel.repaint()
            }
        }
        ghostRow.addMouseListener(onToggle)
        pill.addMouseListener(onToggle)
        stack.add(ghostRow)

        root.add(stack)

        // ── Dialog ────────────────────────────────────────────────────────────
        peersDialog = JDialog().apply {
            isUndecorated = true
            modalityType  = Dialog.ModalityType.MODELESS
            background    = Color(0, 0, 0, 0)
            contentPane   = root
            rootPane.isOpaque = false
            rootPane.border   = BorderFactory.createEmptyBorder()
            pack()
        }

        val sz     = peersDialog!!.size
        val screen = Toolkit.getDefaultToolkit().screenSize
        val loc    = component.locationOnScreen
        val xPos   = (loc.x - sz.width / 2 + component.width / 2).coerceIn(16, screen.width - sz.width - 16)
        val yPos   = (loc.y - sz.height - 10).coerceAtLeast(16)
        peersDialog!!.setLocation(xPos, yPos)
        peersDialog!!.isVisible = true

        Timer(6000) { dismissPeersDialog() }.apply { isRepeats = false; start() }
        peersDialog!!.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {}
            override fun windowLostFocus(e: WindowEvent?) { dismissPeersDialog() }
        })
    }

    private fun dismissPeersDialog() {
        peersDialog?.dispose()
        peersDialog = null
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
        dismissPeersDialog()
        // The messageBus connection was created with connect(this), so it is
        // auto-disposed by IntelliJ when this Disposable is disposed.
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
                ApplicationManager.getApplication().executeOnPooledThread {
                    action()
                    // Wait for Spotify to process the command before reading state back
                    Thread.sleep(300)
                    updateCurrentTrack()
                }
            }
        }
    }
}