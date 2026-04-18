package com.magneto.spotyy.spotify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SpotifyLinuxService : SpotifyService {

    private val logger = Logger.getInstance(SpotifyLinuxService::class.java)

    /** Set to true the first time a D-Bus call is blocked by AppArmor (snap Spotify). */
    @Volatile var snapPermissionBlocked = false
        private set

    // True if playerctl is available on this system (checked once lazily)
    private val hasPlayerctl: Boolean by lazy {
        runCommand(listOf("which", "playerctl")) != null
    }

    override fun getCurrentTrack(): SpotifyState {
        if (!isSpotifyRunning()) return SpotifyState(false, false, null, 50)

        val status = getPlaybackStatus()
        val isPlaying = status == "Playing"
        val trackInfo = getTrackInfo()
        val volume = getVolume()
        val positionMs = getPositionMs()
        val durationMs = getDurationMs()

        return SpotifyState(isRunning = true, isPlaying = isPlaying, trackInfo = trackInfo, volume = volume,
            positionMs = positionMs, durationMs = durationMs)
    }

    override fun playPause() {
        if (!isSpotifyRunning()) {
            launchSpotifyAndPlay()
            return
        }
        if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "play-pause"))
        } else {
            dbusSend("org.mpris.MediaPlayer2.Player.PlayPause")
        }
    }

    private fun launchSpotifyAndPlay() {
        launchSpotify()
        if (!waitForSpotify(timeoutMs = 20_000)) {
            logger.warn("Spotify did not start within 20 seconds")
            return
        }
        // Extra delay for D-Bus/playerctl interface to register after the process appears
        Thread.sleep(1000)
        if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "play"))
        } else {
            dbusSend("org.mpris.MediaPlayer2.Player.Play")
        }
    }

    /**
     * Launches Spotify using the first method that works:
     * 1. xdg-open spotify: — works for native, snap, and flatpak via URI handler
     * 2. Direct binary: spotify / flatpak run / snap run
     */
    private fun launchSpotify() {
        // URI scheme is the most universal — handled by whatever Spotify install registered it
        if (runCommand(listOf("which", "xdg-open")) != null) {
            ProcessBuilder("xdg-open", "spotify:")
                .redirectErrorStream(true)
                .start()
            return
        }
        // Fallback: try direct binaries in order
        val candidates = listOf(
            listOf("spotify"),
            listOf("flatpak", "run", "com.spotify.Client"),
            listOf("snap", "run", "spotify")
        )
        for (cmd in candidates) {
            if (runCommand(listOf("which", cmd.first())) != null) {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
                return
            }
        }
        logger.warn("No Spotify launcher found (tried xdg-open, spotify, flatpak, snap)")
    }

    /**
     * Polls every 500 ms for Spotify to appear — checks both pgrep and playerctl
     * so it works across native, snap, and flatpak installs.
     */
    private fun waitForSpotify(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isSpotifyRunning() || isSpotifyVisibleToPlayerctl()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun isSpotifyVisibleToPlayerctl(): Boolean {
        if (!hasPlayerctl) return false
        val output = runCommand(listOf("playerctl", "-l")) ?: return false
        return output.contains("spotify", ignoreCase = true)
    }

    override fun nextTrack() {
        if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "next"))
        } else {
            dbusSend("org.mpris.MediaPlayer2.Player.Next")
        }
    }

    override fun previousTrack() {
        if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "previous"))
        } else {
            dbusSend("org.mpris.MediaPlayer2.Player.Previous")
        }
    }

    override fun getVolume(): Int {
        return if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "volume"))
                ?.trim()?.toDoubleOrNull()
                ?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 50
        } else {
            // dbus-send output: "   variant       double 0.5"
            val output = getDbusProperty("Volume") ?: return 50
            Regex("""double\s+([\d.]+)""").find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 50
        }
    }

    override fun setVolume(volume: Int) {
        val safeVolume = volume.coerceIn(0, 100)
        val linuxVolume = safeVolume / 100.0
        ApplicationManager.getApplication().executeOnPooledThread {
            if (hasPlayerctl) {
                runCommand(listOf("playerctl", "--player=spotify", "volume", "%.2f".format(linuxVolume)))
            } else {
                dbusSendWithArgs(
                    "org.freedesktop.DBus.Properties.Set",
                    "string:org.mpris.MediaPlayer2.Player",
                    "string:Volume",
                    "variant:double:%.2f".format(linuxVolume)
                )
            }
        }
    }

    override fun increaseVolume(amount: Int) {
        setVolume(getVolume() + amount)
    }

    override fun decreaseVolume(amount: Int) {
        setVolume(getVolume() - amount)
    }

    private fun isSpotifyRunning(): Boolean {
        val result = runCommand(listOf("pgrep", "-x", "spotify"))
            ?: runCommand(listOf("pgrep", "-x", "Spotify"))
        return !result.isNullOrBlank()
    }

    private fun getPlaybackStatus(): String? {
        return if (hasPlayerctl) {
            runCommand(listOf("playerctl", "--player=spotify", "status"))?.trim()
        } else {
            // dbus-send output: "   variant       string "Playing""
            val output = getDbusProperty("PlaybackStatus") ?: return null
            Regex("""string\s+"(Playing|Paused|Stopped)"""").find(output)?.groupValues?.get(1)
        }
    }

    private fun getTrackInfo(): String? {
        return if (hasPlayerctl) {
            val artist = runCommand(listOf("playerctl", "--player=spotify", "metadata", "xesam:artist"))?.trim()
            val title = runCommand(listOf("playerctl", "--player=spotify", "metadata", "xesam:title"))?.trim()
            if (!artist.isNullOrBlank() && !title.isNullOrBlank()) "$artist - $title"
            else title?.takeIf { it.isNotBlank() }
        } else {
            parseMetadataFromDbus()
        }
    }

    // Reads Metadata dict from D-Bus and extracts artist + title.
    // dbus-send output uses nested dict entries like:
    //   string "xesam:artist"
    //   variant   array [
    //         string "Artist Name"
    //      ]
    //   string "xesam:title"
    //   variant   string "Track Name"
    private fun parseMetadataFromDbus(): String? {
        val output = getDbusProperty("Metadata") ?: return null

        // Artist is wrapped in an array; find the first quoted string after "xesam:artist"
        // Use [\s\S] so the match crosses newlines, and limit lookahead to avoid runaway matching
        val artist = Regex(""""xesam:artist"[\s\S]{1,400}?"([^"]+)"""")
            .find(output)?.groupValues?.get(1)

        // Title is a direct string value after "xesam:title"
        val title = Regex(""""xesam:title"[\s\S]{1,400}?"([^"]+)"""")
            .find(output)?.groupValues?.get(1)

        return if (!artist.isNullOrBlank() && !title.isNullOrBlank()) "$artist - $title"
        else title?.takeIf { it.isNotBlank() }
    }

    private fun getPositionMs(): Long {
        return if (hasPlayerctl) {
            // playerctl position returns seconds as a float
            runCommand(listOf("playerctl", "--player=spotify", "position"))
                ?.trim()?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() } ?: 0L
        } else {
            // D-Bus Position is in microseconds
            val output = getDbusProperty("Position") ?: return 0L
            Regex("""int64\s+(\d+)""").find(output)
                ?.groupValues?.get(1)?.toLongOrNull()
                ?.let { it / 1000 } ?: 0L
        }
    }

    private fun getDurationMs(): Long {
        return if (hasPlayerctl) {
            // mpris:length is in microseconds
            runCommand(listOf("playerctl", "--player=spotify", "metadata", "mpris:length"))
                ?.trim()?.toLongOrNull()
                ?.let { it / 1000 } ?: 0L
        } else {
            // Metadata dict contains mpris:length as int64 microseconds
            val output = getDbusProperty("Metadata") ?: return 0L
            Regex(""""mpris:length"[\s\S]{1,100}?int64\s+(\d+)""")
                .find(output)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { it / 1000 } ?: 0L
        }
    }

    private fun getDbusProperty(property: String): String? {
        return runCommand(
            listOf(
                "dbus-send", "--print-reply", "--dest=org.mpris.MediaPlayer2.spotify",
                "/org/mpris/MediaPlayer2",
                "org.freedesktop.DBus.Properties.Get",
                "string:org.mpris.MediaPlayer2.Player",
                "string:$property"
            )
        )
    }

    private fun dbusSend(method: String) {
        runCommand(
            listOf(
                "dbus-send", "--print-reply", "--dest=org.mpris.MediaPlayer2.spotify",
                "/org/mpris/MediaPlayer2", method
            )
        )
    }

    private fun dbusSendWithArgs(method: String, vararg args: String) {
        runCommand(
            listOf(
                "dbus-send", "--print-reply", "--dest=org.mpris.MediaPlayer2.spotify",
                "/org/mpris/MediaPlayer2", method
            ) + args
        )
    }

    /**
     * Runs [cmd] and returns trimmed stdout, or null on failure/timeout.
     * Reads stderr separately so AppArmor/snap errors can be detected via [snapPermissionBlocked].
     */
    private fun runCommand(cmd: List<String>): String? {
        val future = CompletableFuture<String?>()

        val thread = Thread {
            try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error  = process.errorStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(3, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroy()
                    logger.warn("Command timed out: ${cmd.joinToString(" ")}")
                    future.complete(null)
                    return@Thread
                }
                if (error.contains("AppArmor") || error.contains("AccessDenied")) {
                    snapPermissionBlocked = true
                    logger.warn("Spotify D-Bus blocked by AppArmor (snap): $error")
                    future.complete(null)
                    return@Thread
                }
                future.complete(output.trim().takeIf { it.isNotBlank() })
            } catch (e: Exception) {
                logger.warn("Command failed: ${cmd.joinToString(" ")}", e)
                future.completeExceptionally(e)
            }
        }
        thread.isDaemon = true
        thread.start()

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Command timed out or interrupted: ${cmd.joinToString(" ")}", e)
            thread.interrupt()
            null
        }
    }
}
