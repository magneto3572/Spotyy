package com.magneto.spotyy.onboarding

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.*

object OnboardingService {

    private const val KEY = "spotyy.onboarding.shown"

    private val isMac   = System.getProperty("os.name").lowercase().contains("mac")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    fun isSpotifyReady(): Boolean = isSpotifyInstalled()

    fun showNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val spotifyOk   = isSpotifyInstalled()
            val playerctlOk = if (isLinux) isPlayerctlInstalled() else true
            ApplicationManager.getApplication().invokeLater {
                showDialog(spotifyOk, playerctlOk)
            }
        }
    }

    fun maybeShow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val spotifyOk   = isSpotifyInstalled()
            val playerctlOk = if (isLinux) isPlayerctlInstalled() else true
            val alreadyShownAndAllGood = PropertiesComponent.getInstance().getBoolean(KEY, false)

            // Skip if already completed setup successfully before
            if (alreadyShownAndAllGood) return@executeOnPooledThread

            // Always show if Spotify is missing — user may have clicked Get Started without installing
            if (!spotifyOk) {
                ApplicationManager.getApplication().invokeLater {
                    showDialog(spotifyOk, playerctlOk)
                }
                return@executeOnPooledThread
            }

            // Spotify is installed — show once if never shown, then permanently suppress
            val shown = PropertiesComponent.getInstance().getBoolean(KEY, false)
            if (!shown) {
                ApplicationManager.getApplication().invokeLater {
                    showDialog(spotifyOk, playerctlOk)
                    PropertiesComponent.getInstance().setValue(KEY, true)
                }
            }
        }
    }

    private fun isSpotifyInstalled(): Boolean = when {
        isMac   -> File("/Applications/Spotify.app").exists()
                || File("${System.getProperty("user.home")}/Applications/Spotify.app").exists()
        isLinux -> which("spotify") || isSnapSpotify() || isFlatpakSpotify()
        else    -> false
    }

    private fun isPlayerctlInstalled(): Boolean = which("playerctl")

    private fun which(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("which", cmd).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(2, TimeUnit.SECONDS)
            out.isNotBlank()
        } catch (_: Exception) { false }
    }

    private fun isSnapSpotify(): Boolean {
        return try {
            val p = ProcessBuilder("snap", "list", "spotify").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            out.contains("spotify", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun isFlatpakSpotify(): Boolean {
        return try {
            val p = ProcessBuilder("flatpak", "list").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            out.contains("spotify", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun showDialog(spotifyOk: Boolean, playerctlOk: Boolean) {
        val dark    = !JBColor.isBright()
        val bg      = if (dark) Color(26, 26, 30)    else Color(251, 251, 253)
        val borderC = if (dark) Color(48, 48, 54)    else Color(216, 216, 224)
        val fg      = if (dark) Color(228, 228, 234) else Color(18, 18, 24)
        val fgMuted = if (dark) Color(112, 112, 126) else Color(116, 116, 130)
        val green   = if (dark) Color(30, 215, 96)   else Color(18, 168, 74)
        val red     = if (dark) Color(220, 80, 80)   else Color(200, 50, 50)
        val amber   = if (dark) Color(255, 185, 50)  else Color(200, 140, 0)
        val cardBg  = if (dark) Color(34, 34, 40)    else Color(242, 242, 248)
        val codeBg  = if (dark) Color(22, 22, 26)    else Color(232, 232, 240)

        val dialog = JDialog()
        dialog.isUndecorated = true
        dialog.modalityType  = Dialog.ModalityType.APPLICATION_MODAL
        dialog.background    = Color(0, 0, 0, 0)

        val root = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.dispose()
            }
            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderC
                g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
                g2.dispose()
            }
            override fun isOpaque() = false
        }
        root.border = BorderFactory.createEmptyBorder(26, 26, 26, 26)

        val dialogW  = 480
        val padding  = 26
        val stackW   = dialogW - padding * 2   // 428px — content must stay within this

        val stack = JPanel()
        stack.layout      = BoxLayout(stack, BoxLayout.Y_AXIS)
        stack.isOpaque    = false
        stack.minimumSize = Dimension(stackW, 0)
        stack.maximumSize = Dimension(stackW, Int.MAX_VALUE)

        fun lbl(text: String, f: Font, color: Color) = JLabel(text).also {
            it.foreground = color; it.font = f; it.alignmentX = 0f
        }

        fun codeBlock(vararg lines: String): JPanel {
            val copyText = lines.filter { it.isNotBlank() }
                .joinToString("\n")
                .replace(" \\", "")   // collapse shell line continuations into one command

            val wrapper = JPanel(BorderLayout(0, 4))
            wrapper.isOpaque  = false
            wrapper.alignmentX = 0f
            wrapper.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

            // Code area — JTextArea wraps long lines at container width
            val codeArea = JTextArea(lines.joinToString("\n"))
            codeArea.isEditable      = false
            codeArea.isOpaque        = false
            codeArea.font            = Font(Font.MONOSPACED, Font.PLAIN, 11)
            codeArea.foreground      = if (dark) Color(180, 210, 255) else Color(40, 80, 160)
            codeArea.lineWrap        = true
            codeArea.wrapStyleWord   = false
            codeArea.border          = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            codeArea.alignmentX      = 0f
            codeArea.isFocusable     = false
            codeArea.cursor          = Cursor.getDefaultCursor()

            val codePanel = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = codeBg
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                    g2.dispose()
                }
                override fun isOpaque() = false
            }
            codePanel.alignmentX  = 0f
            codePanel.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            codePanel.add(codeArea, BorderLayout.CENTER)
            wrapper.add(codePanel, BorderLayout.CENTER)

            // Copy button
            val copyBtn = object : JLabel("Copy") {
                private var hovered  = false
                private var copied   = false
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val bgColor = when {
                        copied  -> if (dark) Color(30, 215, 96, 40)  else Color(18, 168, 74, 30)
                        hovered -> if (dark) Color(255,255,255, 18)  else Color(0, 0, 0, 12)
                        else    -> Color(0, 0, 0, 0)
                    }
                    g2.color = bgColor
                    g2.fillRoundRect(0, 0, width, height, 6, 6)
                    g2.dispose()
                    super.paintComponent(g)
                }
                init {
                    font        = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                    foreground  = if (dark) Color(140, 140, 160) else Color(110, 110, 130)
                    border      = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                    cursor      = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    horizontalAlignment = SwingConstants.CENTER
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent)  { hovered = true; repaint() }
                        override fun mouseExited(e: MouseEvent)   { hovered = false; repaint() }
                        override fun mouseClicked(e: MouseEvent)  {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(copyText), null)
                            copied = true
                            text   = "Copied!"
                            foreground = if (dark) Color(30, 215, 96) else Color(18, 168, 74)
                            repaint()
                            Timer(1800) {
                                copied     = false
                                text       = "Copy"
                                foreground = if (dark) Color(140, 140, 160) else Color(110, 110, 130)
                                repaint()
                            }.apply { isRepeats = false; start() }
                        }
                    })
                }
            }

            val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            btnRow.isOpaque   = false
            btnRow.alignmentX = 0f
            btnRow.add(copyBtn)
            wrapper.add(btnRow, BorderLayout.SOUTH)

            return wrapper
        }

        // ── Header ────────────────────────────────────────────────────────────
        val osName = if (isMac) "macOS" else "Linux"
        stack.add(lbl("Welcome to Spotyy", stack.font.deriveFont(Font.BOLD, 17f), fg))
        stack.add(Box.createVerticalStrut(6))
        stack.add(lbl("One-time setup for $osName.", stack.font.deriveFont(13f), fgMuted))
        stack.add(Box.createVerticalStrut(22))

        // ── Card builder ──────────────────────────────────────────────────────
        fun card(
            statusIcon: String,
            statusColor: Color,
            title: String,
            body: JPanel.() -> Unit
        ) {
            val card = object : JPanel(BorderLayout(14, 0)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = cardBg
                    g2.fillRoundRect(0, 0, width, height, 10, 10)
                    g2.dispose()
                }
                override fun isOpaque() = false
            }
            card.border     = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            card.alignmentX = 0f
            card.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

            // Status circle
            val circle = object : JComponent() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(statusColor.red, statusColor.green, statusColor.blue, 28)
                    g2.fillOval(0, 0, 28, 28)
                    g2.color = statusColor
                    g2.font  = g2.font.deriveFont(Font.BOLD, 12f)
                    val fm  = g2.fontMetrics
                    g2.drawString(statusIcon, (28 - fm.stringWidth(statusIcon)) / 2, (28 - fm.height) / 2 + fm.ascent)
                    g2.dispose()
                }
                override fun getPreferredSize() = Dimension(28, 28)
                override fun isOpaque()         = false
            }
            val circleWrap = JPanel(BorderLayout())
            circleWrap.isOpaque      = false
            circleWrap.preferredSize = Dimension(36, 28)
            circleWrap.add(circle, BorderLayout.NORTH)
            card.add(circleWrap, BorderLayout.WEST)

            val info = JPanel()
            info.layout   = BoxLayout(info, BoxLayout.Y_AXIS)
            info.isOpaque = false
            info.add(lbl(title, info.font.deriveFont(Font.BOLD, 12f), fg))
            info.add(Box.createVerticalStrut(6))
            info.body()
            card.add(info, BorderLayout.CENTER)

            stack.add(card)
            stack.add(Box.createVerticalStrut(12))
        }

        // ── macOS steps ───────────────────────────────────────────────────────
        if (isMac) {
            if (spotifyOk) {
                card("✓", green, "Spotify Desktop App") {
                    add(lbl("Found at /Applications/Spotify.app — ready to go.", font.deriveFont(11f), fgMuted))
                }
            } else {
                card("✗", red, "Spotify Desktop App — Not Installed") {
                    add(lbl("Download and install the Spotify desktop app:", font.deriveFont(11f), fgMuted))
                    add(Box.createVerticalStrut(8))
                    add(lbl("Step 1 — Open in browser:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("https://www.spotify.com/download/mac/"))
                    add(Box.createVerticalStrut(8))
                    add(lbl("Step 2 — Download the .dmg file and open it.", font.deriveFont(11f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(lbl("Step 3 — Drag Spotify into your Applications folder.", font.deriveFont(11f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(lbl("Step 4 — Open Spotify and log in.", font.deriveFont(11f), fgMuted))
                }
            }

            card("✓", green, "AppleScript — No Setup Needed") {
                add(lbl("Spotyy uses AppleScript to read track info and control", font.deriveFont(11f), fgMuted))
                add(lbl("playback. This works automatically on macOS.", font.deriveFont(11f), fgMuted))
            }

        // ── Linux steps ───────────────────────────────────────────────────────
        } else {
            if (spotifyOk) {
                card("✓", green, "Spotify Desktop App") {
                    add(lbl("Spotify is installed and ready.", font.deriveFont(11f), fgMuted))
                }
            } else {
                card("✗", red, "Spotify Desktop App — Not Installed") {
                    add(lbl("Choose one of the following install methods:", font.deriveFont(11f), fgMuted))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Option 1 — Official .deb (Ubuntu / Debian, recommended):", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock(
                        "curl -sS https://download.spotify.com/debian/pubkey_6224F9941A8AA6D1.gpg \\",
                        "  | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/spotify.gpg",
                        "",
                        "echo \"deb http://repository.spotify.com stable non-free\" \\",
                        "  | sudo tee /etc/apt/sources.list.d/spotify.list",
                        "",
                        "sudo apt update && sudo apt install spotify-client"
                    ))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Option 2 — Snap:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("sudo snap install spotify"))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Option 3 — Flatpak:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("flatpak install flathub com.spotify.Client"))
                }
            }

            if (playerctlOk) {
                card("✓", green, "playerctl") {
                    add(lbl("Installed — full playback control enabled.", font.deriveFont(11f), fgMuted))
                }
            } else {
                card("⚠", amber, "playerctl — Not Installed (Recommended)") {
                    add(lbl("playerctl gives Spotyy reliable playback control on Linux.", font.deriveFont(11f), fgMuted))
                    add(lbl("Without it, Spotyy falls back to D-Bus directly.", font.deriveFont(11f), fgMuted))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Ubuntu / Debian:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("sudo apt install playerctl"))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Fedora / RHEL:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("sudo dnf install playerctl"))
                    add(Box.createVerticalStrut(10))

                    add(lbl("Arch Linux:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("sudo pacman -S playerctl"))
                    add(Box.createVerticalStrut(10))

                    add(lbl("openSUSE:", font.deriveFont(Font.BOLD, 10f), fgMuted))
                    add(Box.createVerticalStrut(4))
                    add(codeBlock("sudo zypper install playerctl"))
                }
            }
        }

        // ── Get Started button ────────────────────────────────────────────────
        val btn = object : JLabel("Get Started", SwingConstants.CENTER) {
            private var hovered = false
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (hovered) green.darker() else green
                g2.fillRoundRect(0, 0, width, height, height, height)
                g2.dispose()
                super.paintComponent(g)
            }
            init {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent)  { hovered = true;  repaint() }
                    override fun mouseExited(e: MouseEvent)   { hovered = false; repaint() }
                    override fun mouseClicked(e: MouseEvent)  { dialog.dispose() }
                })
            }
        }
        btn.foreground  = Color.WHITE
        btn.font        = btn.font.deriveFont(Font.BOLD, 13f)
        btn.border      = BorderFactory.createEmptyBorder(11, 0, 11, 0)
        btn.cursor      = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.alignmentX  = 0f
        btn.maximumSize = Dimension(Int.MAX_VALUE, 44)
        stack.add(btn)

        // ── Scroll wrapper ────────────────────────────────────────────────────
        // viewport must be opaque with bg color — transparent viewport causes
        // the IDE background to bleed through during scroll repaints (flash effect)
        val scrollPane = JScrollPane(stack).apply {
            border                    = BorderFactory.createEmptyBorder()
            viewportBorder            = BorderFactory.createEmptyBorder() // clears Aqua L&F viewport stroke
            isOpaque                  = true
            background                = bg
            viewport.isOpaque         = true
            viewport.background       = bg
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.preferredSize = Dimension(4, 0)
        }

        // Cap dialog height to 80% of screen height
        val screen     = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        val maxHeight  = (screen.height * 0.80).toInt()

        root.add(scrollPane, BorderLayout.CENTER)
        dialog.contentPane       = root
        dialog.rootPane.isOpaque = false
        dialog.rootPane.border   = BorderFactory.createEmptyBorder()
        dialog.pack()
        dialog.setSize(dialogW, minOf(dialog.preferredSize.height + 4, maxHeight))

        dialog.setLocation(
            screen.x + (screen.width  - dialog.width)  / 2,
            screen.y + (screen.height - dialog.height) / 2
        )
        dialog.isVisible = true
    }
}
