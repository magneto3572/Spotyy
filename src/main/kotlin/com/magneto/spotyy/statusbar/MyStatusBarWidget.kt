package com.magneto.spotyy.statusbar

import com.intellij.ide.ui.LafManagerListener
import com.magneto.spotyy.spotify.SpotifyServiceFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.magneto.spotyy.focus.FocusRoomService
import com.magneto.spotyy.onboarding.OnboardingService
import com.magneto.spotyy.focus.RoomMember
import com.magneto.spotyy.network.NetworkDiscoveryService
import com.magneto.spotyy.network.VibeMatch
import com.magneto.spotyy.spotify.SpotifyState
import java.awt.*
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
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

    private val spotifyService = SpotifyServiceFactory.instance
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
    @Volatile private var lastVolume = 50         // updated every 3s by the polling timer
    @Volatile private var volumeDismissedAt = 0L  // timestamp of last focus-loss dismissal

    // Timer for auto-dismissal of volume popup
    private var volumePopupTimer: Timer? = null

    // Peers button and popup
    private val peersButton = JButton()
    private var peersDialog: JDialog? = null
    private var popupRefreshTimer: Timer? = null
    private var lastPeersButtonRightX = 0
    private var lastPeersButtonScreenY = 0

    private val pillUI = PillButtonUI()
    private val playPauseUI = CircularButtonUI(alwaysShowBackground = true)

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
                // Ping Focus Room (keeps membership alive + expires room when timer ends)
                FocusRoomService.ping()
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

                // Re-apply custom UIs — L&F change resets button UI to the default, wiping our custom painters
                prevButton.ui = CircularButtonUI(false)
                nextButton.ui = CircularButtonUI(false)
                volumeButton.ui = CircularButtonUI(false)
                playPauseButton.ui = playPauseUI
                peersButton.ui = pillUI

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
        trackInfoLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
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
        playPauseButton.ui = playPauseUI  // use shared instance so progress can be updated

        configureControlButton(nextButton, nextIcon, NEXT_TEXT) {
            spotifyService.nextTrack()
        }

        // Volume button: appearance via configureControlButton, positioning via MouseListener.
        // MouseEvent.xOnScreen / yOnScreen come directly from the OS native event — guaranteed
        // correct on all platforms, bypassing Swing component-hierarchy coordinate issues on Linux.
        configureControlButton(volumeButton, volumeIcon, VOLUME_TEXT) {}
        for (listener in volumeButton.actionListeners.toList()) {
            volumeButton.removeActionListener(listener)
        }
        volumeButton.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (volumeDialog?.isVisible == true) {
                    dismissVolumePopup()
                } else {
                    // If the popup was just dismissed by focus loss (because this click stole focus),
                    // don't reopen — the click's intent was to close it.
                    if (System.currentTimeMillis() - volumeDismissedAt < 250) return
                    val rightEdgeX = e.xOnScreen - e.x + volumeButton.width
                    val buttonTopY = e.yOnScreen - e.y
                    showVolumeSliderDialog(rightEdgeX, buttonTopY)
                }
            }
        })

        // Peers button — shows who else on the network is listening
        peersButton.apply {
            text = "👥"
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            background = null
            foreground = if (isDarkTheme) Color.WHITE else Color.BLACK
            // No fixed preferredSize — let the button grow to fit its text (timer can be wide)
            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
            isVisible = false
            ui = pillUI
            putClientProperty("JComponent.NO_HOVER", false)
            putClientProperty("JButton.arc", 999)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    ApplicationManager.getApplication().invokeLater {
                        if (peersDialog?.isVisible == true) {
                            dismissPeersDialog()
                        } else {
                            lastPeersButtonRightX = e.xOnScreen - e.x + peersButton.width
                            lastPeersButtonScreenY = e.yOnScreen - e.y
                            showPeersDialog(lastPeersButtonRightX, lastPeersButtonScreenY)
                        }
                    }
                }
            })
        }

        // Create a separator before controls
        val separator = JSeparator(SwingConstants.VERTICAL)
        separator.preferredSize = Dimension(1, 16)
        val separatorColor = if (isDarkTheme) Color(80, 80, 80) else Color(190, 190, 200)
        separator.background = separatorColor
        separator.foreground = separatorColor

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

    // buttonRightX = screen X of the button's right edge.
    // buttonTopY   = screen Y of the button's top edge (e.yOnScreen - e.y) — constant regardless of click position.
    private fun showVolumeSliderDialog(buttonRightX: Int, buttonTopY: Int) {
        try {
            dismissVolumePopup()

            val currentVolume = lastVolume

            // Theme colours
            val dark       = isDarkTheme
            val popupBg    = if (dark) Color(30, 30, 32)      else Color(248, 248, 250)
            val labelFg    = if (dark) Color(255, 255, 255)   else Color(20, 20, 20)
            val trackBg    = if (dark) Color(60, 60, 65)      else Color(205, 205, 212)
            val fillGreen  = if (dark) Color(30, 215, 96)     else Color(18, 168, 74)
            val thumbCol   = if (dark) Color(255, 255, 255)   else Color(255, 255, 255)
            val borderCol  = if (dark) Color(55, 55, 58)      else Color(220, 220, 226)

            volumeDialog = JDialog()
            volumeDialog?.isUndecorated = true
            volumeDialog?.modalityType = Dialog.ModalityType.MODELESS
            volumeDialog?.background = Color(0, 0, 0, 0)

            val pctLabel = JLabel("$currentVolume%")

            // Outer card — rounded rect with subtle border
            val card = object : JPanel(BorderLayout(0, 0)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = popupBg
                    g2.fillRoundRect(0, 0, width, height, 14, 14)
                    g2.color = borderCol
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
                    g2.dispose()
                }
                override fun isOpaque() = false
            }
            card.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)

            // Header row: "Volume" label left, percentage right
            val header = JPanel(BorderLayout())
            header.isOpaque = false
            header.border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            val titleLbl = JLabel("Volume")
            titleLbl.foreground = labelFg
            titleLbl.font = titleLbl.font.deriveFont(Font.BOLD, 12f)
            pctLabel.foreground = if (dark) Color(160, 160, 168) else Color(120, 120, 130)
            pctLabel.font = pctLabel.font.deriveFont(Font.PLAIN, 11f)
            header.add(titleLbl, BorderLayout.WEST)
            header.add(pctLabel, BorderLayout.EAST)
            card.add(header, BorderLayout.NORTH)

            // Slider
            val volumeSlider = JSlider(JSlider.HORIZONTAL, 0, 100, currentVolume)
            volumeSlider.paintTicks  = false
            volumeSlider.paintLabels = false
            volumeSlider.isOpaque    = false
            volumeSlider.isFocusable = false
            volumeSlider.border      = BorderFactory.createEmptyBorder()
            volumeSlider.putClientProperty("JComponent.outline", null)

            volumeSlider.ui = object : BasicSliderUI(volumeSlider) {
                private val TRACK_H  = 3
                private val THUMB_D  = 13

                override fun getThumbSize() = Dimension(THUMB_D, THUMB_D)

                override fun paintTrack(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val ty = trackRect.y + (trackRect.height - TRACK_H) / 2
                    // Background track
                    g2.color = trackBg
                    g2.fillRoundRect(trackRect.x, ty, trackRect.width, TRACK_H, TRACK_H, TRACK_H)
                    // Filled portion
                    val fillW = thumbRect.x + thumbRect.width / 2 - trackRect.x
                    if (fillW > 0) {
                        g2.color = fillGreen
                        g2.fillRoundRect(trackRect.x, ty, fillW, TRACK_H, TRACK_H, TRACK_H)
                    }
                    g2.dispose()
                }

                override fun paintThumb(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val mid = trackRect.y + trackRect.height / 2
                    val tx  = thumbRect.x
                    val ty  = mid - THUMB_D / 2
                    // Shadow ring
                    g2.color = Color(0, 0, 0, 40)
                    g2.fillOval(tx, ty + 1, THUMB_D, THUMB_D)
                    // Thumb
                    g2.color = thumbCol
                    g2.fillOval(tx, ty, THUMB_D, THUMB_D)
                    g2.dispose()
                }

                override fun paintFocus(g: Graphics) {}
            }

            volumeSlider.addChangeListener {
                val v = volumeSlider.value
                pctLabel.text = "$v%"
                volumeButton.toolTipText = "Volume: $v%"
                if (!volumeSlider.valueIsAdjusting) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        spotifyService.setVolume(v)
                        ApplicationManager.getApplication().invokeLater { updateVolumeIcon(v) }
                    }
                } else {
                    updateVolumeIcon(v)
                }
            }

            card.add(volumeSlider, BorderLayout.CENTER)

            volumeDialog?.contentPane = card
            volumeDialog?.rootPane?.isOpaque = false
            volumeDialog?.rootPane?.border   = BorderFactory.createEmptyBorder()
            volumeDialog?.pack()

            // Position: right-align to button, 12px gap above button top
            val dialogSize = volumeDialog?.size ?: Dimension(220, 70)
            val refPoint = Point(buttonRightX, buttonTopY)
            val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices.map { it.defaultConfiguration.bounds }
                .firstOrNull { it.contains(refPoint) }
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.bounds
            val xPos = (buttonRightX - dialogSize.width)
                .coerceIn(screenBounds.x, screenBounds.x + screenBounds.width - dialogSize.width)
            val yPos = (buttonTopY - dialogSize.height - 12).coerceAtLeast(screenBounds.y)

            volumeDialog?.setLocation(xPos, yPos)
            volumeDialog?.isVisible = true
            startPopupDismissTimer()

            volumeDialog?.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) { cancelPopupDismissTimer() }
            })
            volumeDialog?.addWindowFocusListener(object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) { startPopupDismissTimer() }
                override fun windowLostFocus(e: WindowEvent?) {
                    volumeDismissedAt = System.currentTimeMillis()
                    dismissVolumePopup()
                }
            })
            volumeSlider.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent)  { startPopupDismissTimer() }
                override fun mousePressed(e: MouseEvent)  { startPopupDismissTimer() }
                override fun mouseReleased(e: MouseEvent) { startPopupDismissTimer() }
            })
            volumeSlider.addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) { startPopupDismissTimer() }
                override fun mouseMoved(e: MouseEvent)   { startPopupDismissTimer() }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            volumeDialog = null
        }
    }

    private fun updateVolumeIcon(volume: Int) {
        lastVolume = volume
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
        // Update song progress ring on play/pause button
        playPauseUI.progress = state.progress
        playPauseUI.isPlaying = state.isPlaying
        playPauseButton.repaint()

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
            // Always show when in a Focus Room so guests can see timer and leave
            FocusRoomService.isInRoom -> {
                val room        = FocusRoomService.currentRoom
                val memberCount = room?.members?.size ?: 1
                peersButton.text        = "⏱ ${room?.formattedTime() ?: "00:00"}"
                peersButton.foreground  = if (isDarkTheme) Color(30, 215, 96) else Color(18, 168, 74)
                peersButton.toolTipText = "Focus Room · $memberCount member${if (memberCount != 1) "s" else ""}"
                peersButton.isVisible   = true
            }
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

    private fun showPeersDialog(buttonRightX: Int, buttonTopY: Int) {
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

        val popupWidth = 300
        val stack = object : JPanel() {
            override fun getPreferredSize() = Dimension(popupWidth, super.getPreferredSize().height)
            override fun getMaximumSize()   = Dimension(popupWidth, Int.MAX_VALUE)
            override fun getMinimumSize()   = Dimension(popupWidth, 0)
        }
        stack.layout = BoxLayout(stack, BoxLayout.Y_AXIS)
        stack.isOpaque = false

        // ── Header ────────────────────────────────────────────────────────────
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = BorderFactory.createEmptyBorder(13, 14, 13, 14)
        header.minimumSize = Dimension(popupWidth, 0)
        header.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)

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
            vibeRow.maximumSize = Dimension(popupWidth, 34)

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
            row.minimumSize = Dimension(popupWidth, 0)
            row.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)
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
                row.minimumSize = Dimension(popupWidth, 0)
                row.maximumSize = Dimension(popupWidth, 50)

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

                val raw       = peer.track
                // Budget: popupWidth - rowPadding(28) - avatar(38) - hgaps(20) - inviteBtn(74 if host) - fudge(4)
                val hasInviteBtn = FocusRoomService.isHost && FocusRoomService.isInRoom
                val maxTrackPx   = popupWidth - 28 - 38 - 20 - (if (hasInviteBtn) 74 else 0) - 4
                val trackFont    = (UIManager.getFont("Label.font") ?: Font("SansSerif", Font.PLAIN, 11)).deriveFont(11f)
                val tmpLbl       = JLabel()
                val fm           = tmpLbl.getFontMetrics(trackFont)
                val truncated    = if (fm.stringWidth(raw) <= maxTrackPx) raw
                                   else {
                                       var t = raw
                                       while (t.isNotEmpty() && fm.stringWidth("$t…") > maxTrackPx) t = t.dropLast(1)
                                       "$t…"
                                   }
                val trackLbl = JLabel(truncated)
                trackLbl.foreground = fgMuted
                trackLbl.font = trackFont
                trackRow.add(trackLbl)

                when (match) {
                    VibeMatch.SAME_SONG -> {
                        val badge = object : JLabel("✦ Vibing!") {
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
                        badge.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                        trackRow.add(Box.createHorizontalStrut(8))
                        trackRow.add(badge)
                    }
                    VibeMatch.SAME_ARTIST -> {
                        val badge = JLabel("∼ Same artist")
                        badge.foreground = fgMuted
                        badge.font = badge.font.deriveFont(10f)
                        trackRow.add(Box.createHorizontalStrut(6))
                        trackRow.add(badge)
                    }
                    VibeMatch.NONE -> {}
                }

                info.add(nameLbl,  BorderLayout.NORTH)
                info.add(trackRow, BorderLayout.CENTER)

                row.add(avatarWrap, BorderLayout.WEST)
                row.add(info,       BorderLayout.CENTER)

                // Invite button — only visible to the host when a room is active
                if (FocusRoomService.isHost && FocusRoomService.isInRoom) {
                    val alreadyInRoom = FocusRoomService.currentRoom?.members?.containsKey(peer.username) == true
                    val inviteBtn = JButton(if (alreadyInRoom) "✓ Joined" else "Invite")
                    inviteBtn.apply {
                        isOpaque = false
                        isBorderPainted = true
                        isContentAreaFilled = false
                        isFocusable = false
                        isEnabled = !alreadyInRoom
                        font = font.deriveFont(Font.BOLD, 10f)
                        foreground = if (alreadyInRoom) fgMuted else green
                        setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(if (alreadyInRoom) fgMuted else green, 1, true),
                            BorderFactory.createEmptyBorder(3, 8, 3, 8)
                        ))
                        preferredSize = Dimension(64, 24)
                        addActionListener {
                            // Update UI immediately on EDT
                            isEnabled = false
                            text = "Sent"
                            foreground = fgMuted
                            setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(fgMuted, 1, true),
                                BorderFactory.createEmptyBorder(3, 8, 3, 8)
                            ))
                            // Send invite packet on background thread — never do network I/O on EDT
                            ApplicationManager.getApplication().executeOnPooledThread {
                                FocusRoomService.invitePeer(peer.username)
                            }
                        }
                    }
                    val btnWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
                    btnWrap.isOpaque = false
                    btnWrap.add(inviteBtn)
                    row.add(btnWrap, BorderLayout.EAST)
                }

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

        // ── Focus Room ────────────────────────────────────────────────────────
        val room = FocusRoomService.currentRoom
        val red  = if (dark) Color(220, 80, 80) else Color(200, 50, 50)
        // Declared here so the refresh timer closure can reference them regardless of room state
        var timerLbl = JLabel("")
        val nearbyTimerLabels = mutableListOf<Pair<com.magneto.spotyy.focus.NearbyRoom, JLabel>>()

        if (room != null) {
            val me = NetworkDiscoveryService.localUsername

            // Header — title left, live timer right
            val roomHeader = JPanel(BorderLayout())
            roomHeader.isOpaque = false
            roomHeader.border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            roomHeader.minimumSize = Dimension(popupWidth, 0)
            roomHeader.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)

            val roomTitle = JLabel("Focus Room")
            roomTitle.foreground = fg
            roomTitle.font = roomTitle.font.deriveFont(Font.BOLD, 12f)

            timerLbl = JLabel(room.formattedTime())
            timerLbl.foreground = green
            timerLbl.font = timerLbl.font.deriveFont(Font.BOLD, 12f)

            roomHeader.add(roomTitle, BorderLayout.WEST)
            roomHeader.add(timerLbl,  BorderLayout.EAST)
            stack.add(roomHeader)

            // Member list with avatars + kick button for host
            val memberPanel = JPanel()
            memberPanel.layout = BoxLayout(memberPanel, BoxLayout.Y_AXIS)
            memberPanel.isOpaque = false
            memberPanel.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

            room.members.values.sortedWith(
                compareByDescending<RoomMember> { it.username == room.hostName }
                    .thenBy { it.username }
            ).forEach { member ->
                val isMe      = member.username == me
                val isMbrHost = member.username == room.hostName

                val mRow = JPanel(BorderLayout(10, 0))
                mRow.isOpaque = false
                mRow.border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
                mRow.minimumSize = Dimension(popupWidth, 0)
                mRow.maximumSize = Dimension(popupWidth, 42)

                // Avatar circle
                val aColor  = avatarPalette[Math.abs(member.username.hashCode()) % avatarPalette.size]
                val initial = member.username.firstOrNull()?.uppercase() ?: "?"
                val avatar  = object : JComponent() {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = if (isMe) Color(green.red, green.green, green.blue, 160) else aColor
                        g2.fillOval(0, 0, 28, 28)
                        g2.color = Color.WHITE
                        g2.font = g2.font.deriveFont(Font.BOLD, 11f)
                        val fm = g2.fontMetrics
                        g2.drawString(initial, (28 - fm.stringWidth(initial)) / 2, (28 - fm.height) / 2 + fm.ascent)
                        g2.dispose()
                    }
                    override fun getPreferredSize() = Dimension(28, 28)
                }
                val avatarWrap = JPanel(BorderLayout())
                avatarWrap.isOpaque = false
                avatarWrap.preferredSize = Dimension(36, 28)
                avatarWrap.add(avatar, BorderLayout.WEST)

                // Name + role tag
                val nameInfo = JPanel()
                nameInfo.layout = BoxLayout(nameInfo, BoxLayout.Y_AXIS)
                nameInfo.isOpaque = false

                val nameLbl = JLabel(member.username)
                nameLbl.foreground = if (isMe) green else fg
                nameLbl.font = nameLbl.font.deriveFont(Font.BOLD, 11f)
                nameLbl.alignmentX = 0f

                val tag = when {
                    isMbrHost && isMe -> "host · you"
                    isMbrHost         -> "host"
                    isMe              -> "you"
                    else              -> null
                }
                nameInfo.add(nameLbl)
                if (tag != null) {
                    val tagLbl = JLabel(tag)
                    tagLbl.foreground = fgMuted
                    tagLbl.font = tagLbl.font.deriveFont(9f)
                    tagLbl.alignmentX = 0f
                    nameInfo.add(tagLbl)
                }

                mRow.add(avatarWrap, BorderLayout.WEST)
                mRow.add(nameInfo,   BorderLayout.CENTER)

                // Kick button — host only, not for self
                if (FocusRoomService.isHost && !isMe) {
                    val kickLbl = JLabel("Remove")
                    kickLbl.foreground = red
                    kickLbl.font = kickLbl.font.deriveFont(Font.PLAIN, 10f)
                    kickLbl.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    kickLbl.toolTipText = "Remove ${member.username} from the room"
                    kickLbl.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                FocusRoomService.kickPeer(member.username)
                            }
                            refreshPeersDialog()
                        }
                        override fun mouseEntered(e: MouseEvent) {
                            kickLbl.foreground = Color(255, 100, 100)
                        }
                        override fun mouseExited(e: MouseEvent) {
                            kickLbl.foreground = red
                        }
                    })
                    val kickWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
                    kickWrap.isOpaque = false
                    kickWrap.add(kickLbl)
                    mRow.add(kickWrap, BorderLayout.EAST)
                }

                memberPanel.add(mRow)
            }
            stack.add(memberPanel)
            stack.add(divider())

            // Leave / End button
            val actionRow = JPanel(BorderLayout())
            actionRow.isOpaque = false
            actionRow.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
            actionRow.minimumSize = Dimension(popupWidth, 0)
            actionRow.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)

            val actionLabel = if (FocusRoomService.isHost) "End Room" else "Leave Room"
            val leaveBtn = object : JLabel(actionLabel, SwingConstants.CENTER) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(red.red, red.green, red.blue, 18)
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                    g2.dispose()
                    super.paintComponent(g)
                }
            }
            leaveBtn.foreground = red
            leaveBtn.font = leaveBtn.font.deriveFont(Font.BOLD, 11f)
            leaveBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(red.red, red.green, red.blue, 80), 1, true),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)
            ))
            leaveBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            leaveBtn.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        FocusRoomService.leaveRoom()
                    }
                    dismissPeersDialog()
                    ApplicationManager.getApplication().invokeLater { panel.revalidate(); panel.repaint() }
                }
            })
            actionRow.add(leaveBtn, BorderLayout.CENTER)
            stack.add(actionRow)

        } else {
            // ── Nearby Focus Rooms (visible to non-members) ───────────────────────
            val nearbyList = FocusRoomService.getNearbyRooms()
            if (nearbyList.isNotEmpty()) {
                val nearbyHeader = JPanel(BorderLayout())
                nearbyHeader.isOpaque = false
                nearbyHeader.border = BorderFactory.createEmptyBorder(12, 14, 6, 14)
                nearbyHeader.minimumSize = Dimension(popupWidth, 0)
                nearbyHeader.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)

                val nearbyTitle = JLabel("Focus Room Nearby")
                nearbyTitle.foreground = fg
                nearbyTitle.font = nearbyTitle.font.deriveFont(Font.BOLD, 12f)

                val lockLbl = JLabel("invite only")
                lockLbl.foreground = fgMuted
                lockLbl.font = lockLbl.font.deriveFont(10f)

                nearbyHeader.add(nearbyTitle, BorderLayout.WEST)
                nearbyHeader.add(lockLbl, BorderLayout.EAST)
                stack.add(nearbyHeader)

                nearbyList.forEach { nRoom ->
                    val nRow = JPanel(BorderLayout(10, 0))
                    nRow.isOpaque = false
                    nRow.border = BorderFactory.createEmptyBorder(6, 14, 8, 14)
                    nRow.minimumSize = Dimension(popupWidth, 0)
                    nRow.maximumSize = Dimension(popupWidth, 46)

                    val aColor  = avatarPalette[Math.abs(nRoom.hostName.hashCode()) % avatarPalette.size]
                    val initial = nRoom.hostName.firstOrNull()?.uppercase() ?: "?"
                    val avatar = object : JComponent() {
                        override fun paintComponent(g: Graphics) {
                            val g2 = g.create() as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = aColor.darker()
                            g2.fillOval(0, 0, 28, 28)
                            g2.color = Color.WHITE
                            g2.font = g2.font.deriveFont(Font.BOLD, 11f)
                            val fmA = g2.fontMetrics
                            g2.drawString(initial, (28 - fmA.stringWidth(initial)) / 2, (28 - fmA.height) / 2 + fmA.ascent)
                            g2.dispose()
                        }
                        override fun getPreferredSize() = Dimension(30, 30)
                    }
                    val avatarWrap = JPanel(BorderLayout())
                    avatarWrap.isOpaque = false
                    avatarWrap.preferredSize = Dimension(38, 30)
                    avatarWrap.add(avatar, BorderLayout.WEST)

                    val info = JPanel(BorderLayout(0, 2))
                    info.isOpaque = false

                    val hostLbl = JLabel("${nRoom.hostName}'s room")
                    hostLbl.foreground = fg
                    hostLbl.font = hostLbl.font.deriveFont(Font.BOLD, 12f)

                    val memberCount = nRoom.seenMembers.size
                    val subLbl = JLabel("$memberCount ${if (memberCount == 1) "member" else "members"} · ask to be invited")
                    subLbl.foreground = fgMuted
                    subLbl.font = subLbl.font.deriveFont(10f)

                    info.add(hostLbl, BorderLayout.NORTH)
                    info.add(subLbl, BorderLayout.CENTER)

                    val nTimerLbl = JLabel(nRoom.formattedTime())
                    nTimerLbl.foreground = fgMuted
                    nTimerLbl.font = nTimerLbl.font.deriveFont(Font.BOLD, 11f)
                    nearbyTimerLabels.add(Pair(nRoom, nTimerLbl))

                    nRow.add(avatarWrap, BorderLayout.WEST)
                    nRow.add(info, BorderLayout.CENTER)
                    nRow.add(nTimerLbl, BorderLayout.EAST)
                    stack.add(nRow)
                }
                stack.add(divider())
            }

            // No active room — show "Start Focus Room" with duration buttons
            val startRow = JPanel(BorderLayout(8, 0))
            startRow.isOpaque = false
            startRow.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            startRow.minimumSize = Dimension(popupWidth, 0)
            startRow.maximumSize = Dimension(popupWidth, Int.MAX_VALUE)

            val startLbl = JLabel("Focus Room")
            startLbl.foreground = fgMuted
            startLbl.font = startLbl.font.deriveFont(12f)

            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
            btnPanel.isOpaque = false

            listOf(25, 45, 60).forEach { mins ->
                val pillColor = pillOff
                val btn = object : JLabel("${mins}m", SwingConstants.CENTER) {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = pillColor
                        g2.fillRoundRect(0, 0, width, height, height, height)
                        g2.dispose()
                        super.paintComponent(g)
                    }
                }
                btn.foreground = fg
                btn.font = btn.font.deriveFont(Font.BOLD, 10f)
                btn.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                btn.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        FocusRoomService.startRoom(mins)
                        panel.revalidate(); panel.repaint()
                        refreshPeersDialog()
                    }
                })
                btnPanel.add(btn)
            }

            startRow.add(startLbl, BorderLayout.WEST)
            startRow.add(btnPanel, BorderLayout.EAST)
            stack.add(startRow)
        }

        stack.add(divider())

        // ── Ghost mode toggle ─────────────────────────────────────────────────
        val inRoom      = FocusRoomService.isInRoom
        val ghostRow    = JPanel(BorderLayout(12, 0))
        ghostRow.isOpaque = false
        ghostRow.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ghostRow.cursor = if (inRoom) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        ghostRow.minimumSize = Dimension(popupWidth, 0)
        ghostRow.maximumSize = Dimension(popupWidth, 44)

        // Label — show reason when locked
        val ghostLblPanel = JPanel()
        ghostLblPanel.layout = BoxLayout(ghostLblPanel, BoxLayout.Y_AXIS)
        ghostLblPanel.isOpaque = false

        val ghostLbl = JLabel("Ghost mode")
        ghostLbl.foreground = if (inRoom) fgMuted else fg
        ghostLbl.font = ghostLbl.font.deriveFont(Font.PLAIN, 12f)
        ghostLbl.alignmentX = 0f
        ghostLblPanel.add(ghostLbl)

        if (inRoom) {
            val lockedHint = JLabel("Leave the room first")
            lockedHint.foreground = fgMuted
            lockedHint.font = lockedHint.font.deriveFont(9f)
            lockedHint.alignmentX = 0f
            ghostLblPanel.add(lockedHint)
        }

        val pill = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val on = NetworkDiscoveryService.isGhostMode()
                // Dim the pill when locked inside a room (live check so it reflects state changes)
                val alpha = if (FocusRoomService.isInRoom) 0.35f else 1.0f
                g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha)
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
        if (!inRoom) pill.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        ghostRow.add(ghostLblPanel, BorderLayout.CENTER)
        ghostRow.add(pill,          BorderLayout.EAST)

        val onToggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Hard runtime guard — re-check at click time, not just at dialog-open time
                if (FocusRoomService.isInRoom) return
                val newGhost    = !NetworkDiscoveryService.isGhostMode()
                NetworkDiscoveryService.setGhostMode(newGhost)
                refreshPeersDialog()
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
            // Force exact width — pack() can under-report on some platforms
            setSize(popupWidth, preferredSize.height)
            minimumSize = Dimension(popupWidth, 1)
        }

        val sz = peersDialog!!.size
        val refPoint = Point(buttonRightX, buttonTopY)
        val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(refPoint) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
        // Right-align popup to button's right edge; 12px gap between popup bottom and button top
        val xPos = (buttonRightX - sz.width)
            .coerceIn(screenBounds.x, screenBounds.x + screenBounds.width - sz.width)
        val yPos = (buttonTopY - sz.height - 12).coerceAtLeast(screenBounds.y)
        peersDialog!!.setLocation(xPos, yPos)
        peersDialog!!.isVisible = true

        // Close only when the user clicks outside into another window — not on internal interactions
        peersDialog!!.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {}
            override fun windowLostFocus(e: WindowEvent?) {
                // Delay slightly so clicks on internal components don't misfire
                Timer(150) {
                    ApplicationManager.getApplication().invokeLater {
                        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                        if (focused != peersDialog) dismissPeersDialog()
                    }
                }.apply { isRepeats = false; start() }
            }
        })

        // Live refresh — tick the room timer every second, refresh peers every 3s
        var refreshTick = 0
        val wasInRoom = room != null
        var peerSnapshot = peers.map { "${it.username}|${it.track}" }.joinToString()
        var memberSnapshot = FocusRoomService.currentRoom?.members?.keys?.sorted()?.joinToString() ?: ""
        var nearbySnapshot = FocusRoomService.getNearbyRooms()
            .joinToString(";") { "${it.roomId}:${it.seenMembers.size}" }
        popupRefreshTimer?.stop()
        popupRefreshTimer = Timer(1000) {
            ApplicationManager.getApplication().invokeLater {
                // If room state changed since popup opened, rebuild
                val nowInRoom = FocusRoomService.isInRoom
                if (wasInRoom != nowInRoom) {
                    refreshPeersDialog()
                    return@invokeLater
                }
                // Always tick the active room countdown
                FocusRoomService.currentRoom?.let { timerLbl.text = it.formattedTime() }
                // Tick nearby room countdowns every second
                nearbyTimerLabels.forEach { (nRoom, lbl) -> lbl.text = nRoom.formattedTime() }
                // Every 3 ticks refresh peer/member/nearby state
                if (++refreshTick % 3 == 0) {
                    val current = NetworkDiscoveryService.getActivePeers()
                    countLbl.text = if (current.isEmpty()) "0" else "${current.size}"
                    countLbl.foreground = if (current.isNotEmpty()) green else fgMuted

                    // Rebuild if any peer changed track
                    val currentPeerSnapshot = current.map { "${it.username}|${it.track}" }.joinToString()
                    if (currentPeerSnapshot != peerSnapshot) {
                        peerSnapshot = currentPeerSnapshot
                        refreshPeersDialog()
                        return@invokeLater
                    }

                    // Rebuild if room membership changed
                    val currentMemberSnapshot = FocusRoomService.currentRoom?.members?.keys?.sorted()?.joinToString() ?: ""
                    if (currentMemberSnapshot != memberSnapshot) {
                        memberSnapshot = currentMemberSnapshot
                        refreshPeersDialog()
                        return@invokeLater
                    }

                    // Rebuild if nearby rooms appeared, expired, or changed member count
                    val currentNearbySnapshot = FocusRoomService.getNearbyRooms()
                        .joinToString(";") { "${it.roomId}:${it.seenMembers.size}" }
                    if (currentNearbySnapshot != nearbySnapshot) {
                        nearbySnapshot = currentNearbySnapshot
                        refreshPeersDialog()
                    }
                }
            }
        }.apply { start() }
    }

    private fun dismissPeersDialog() {
        popupRefreshTimer?.stop()
        popupRefreshTimer = null
        peersDialog?.dispose()
        peersDialog = null
    }

    /** Rebuild the popup after a state change, re-anchored to the status bar button. */
    private fun refreshPeersDialog() {
        if (peersDialog == null) return
        dismissPeersDialog()
        showPeersDialog(lastPeersButtonRightX, lastPeersButtonScreenY)
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

    /** Rounded-rect hover for text-based buttons (peers / room timer). */
    private inner class PillButtonUI : BasicButtonUI() {
        init {
            UIManager.put("Button.paintShadow", false)
            UIManager.put("Button.rollover", true)
        }

        override fun paint(g: Graphics, c: JComponent) {
            if ((c as JButton).model.isRollover) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(128, 128, 128, 45)
                val arc = c.height - 4
                g2.fillRoundRect(2, 2, c.width - 4, c.height - 4, arc, arc)
                g2.dispose()
            }
            super.paint(g, c)
        }
    }

    private inner class CircularButtonUI(private val alwaysShowBackground: Boolean = false) : BasicButtonUI() {
        var progress: Float = 0f
        var isPlaying: Boolean = false

        init {
            UIManager.put("Button.paintShadow", false)
            UIManager.put("Button.rollover", true)
        }

        override fun paint(g: Graphics, c: JComponent) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val button = c as JButton
            val size = minOf(button.width, button.height) - 4
            val cx = (button.width - size) / 2
            val cy = (button.height - size) / 2

            if (alwaysShowBackground) {
                val strokeW = 2f
                val inset = strokeW / 2f + 0.5f
                val rx = cx + inset
                val ry = cy + inset
                val rw = size - inset * 2
                val rh = size - inset * 2

                // Theme-aware colors
                val faceBg    = if (isDarkTheme) Color(58, 58, 58)   else Color(210, 210, 215)
                val trackRing = if (isDarkTheme) Color(78, 78, 82)   else Color(185, 185, 192)
                val green     = if (isDarkTheme) Color(30, 215, 96)  else Color(18, 168, 74)
                val greenMuted= if (isDarkTheme) Color(30, 215, 96, 110) else Color(18, 168, 74, 130)

                // Button face
                g2.color = faceBg
                g2.fill(Ellipse2D.Float(rx, ry, rw, rh))

                // Full track ring
                g2.stroke = BasicStroke(strokeW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                g2.color = trackRing
                g2.draw(Ellipse2D.Float(rx, ry, rw, rh))

                // Progress arc
                if (progress > 0f) {
                    val arcAngle = -(progress * 360f)
                    g2.stroke = BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.color = if (isPlaying) green else greenMuted
                    g2.draw(Arc2D.Float(rx, ry, rw, rh, 90f, arcAngle, Arc2D.OPEN))
                }
            }

            if (button.model.isRollover) {
                val hoverColor = if (isDarkTheme) Color(255, 255, 255, 20) else Color(0, 0, 0, 18)
                val nonPrimaryHover = if (isDarkTheme) Color(255, 255, 255, 35) else Color(0, 0, 0, 20)
                g2.color = if (alwaysShowBackground) hoverColor else nonPrimaryHover
                g2.fill(Ellipse2D.Float(
                    (cx + 1).toFloat(), (cy + 1).toFloat(),
                    (size - 2).toFloat(), (size - 2).toFloat()
                ))
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
                    if (!OnboardingService.isSpotifyReady()) {
                        ApplicationManager.getApplication().invokeLater {
                            OnboardingService.showNow()
                        }
                        return@executeOnPooledThread
                    }
                    action()
                    Thread.sleep(300)
                    updateCurrentTrack()
                }
            }
        }
    }
}