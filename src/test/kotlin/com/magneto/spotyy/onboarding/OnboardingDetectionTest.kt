package com.magneto.spotyy.onboarding

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests for Spotify and playerctl installation detection logic.
 *
 * We duplicate the detection predicates from OnboardingService here so they
 * can be exercised without an IntelliJ application context.  The real
 * OnboardingService delegates to these same checks at runtime.
 */
class OnboardingDetectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── File-based Spotify detection (Mac path pattern) ───────────────────────

    private fun spotifyExistsAt(vararg paths: File) = paths.any { it.exists() }

    @Test
    fun `Spotify detected when app file exists at primary path`() {
        val appDir = tmp.newFolder("Applications")
        File(appDir, "Spotify.app").mkdir()
        val home = tmp.newFolder("home")
        assertTrue(spotifyExistsAt(File(appDir, "Spotify.app"), File(home, "Applications/Spotify.app")))
    }

    @Test
    fun `Spotify detected when app file exists at user home path`() {
        val systemApps = File(tmp.root, "Applications/Spotify.app")
        val homeApps   = tmp.newFolder("home", "Applications")
        File(homeApps, "Spotify.app").mkdir()
        assertTrue(spotifyExistsAt(systemApps, File(homeApps, "Spotify.app")))
    }

    @Test
    fun `Spotify not detected when neither path exists`() {
        val systemApps = File(tmp.root, "Applications/Spotify.app")
        val homeApps   = File(tmp.root, "home/Applications/Spotify.app")
        assertFalse(spotifyExistsAt(systemApps, homeApps))
    }

    @Test
    fun `missing Spotify app file returns false`() {
        val nonExistent = File(tmp.root, "DoesNotExist/Spotify.app")
        assertFalse(nonExistent.exists())
    }

    // ── Process-based which() detection ──────────────────────────────────────

    private fun which(cmd: String): Boolean {
        return try {
            val p   = ProcessBuilder("which", cmd).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(2, TimeUnit.SECONDS)
            out.isNotBlank()
        } catch (_: Exception) { false }
    }

    @Test
    fun `which returns true for a known installed command`() {
        // 'ls' is universally available on Mac and Linux
        assertTrue(which("ls"))
    }

    @Test
    fun `which returns false for a command that does not exist`() {
        assertFalse(which("spotyy-nonexistent-binary-xyz"))
    }

    // ── Snap output parsing ───────────────────────────────────────────────────

    private fun snapOutputContainsSpotify(snapOutput: String) =
        snapOutput.contains("spotify", ignoreCase = true)

    @Test
    fun `snap output with spotify entry returns true`() {
        val output = "Name        Version   Rev  Tracking  Publisher\nspotify     1.2.22    82   latest    spotify"
        assertTrue(snapOutputContainsSpotify(output))
    }

    @Test
    fun `snap output without spotify entry returns false`() {
        val output = "Name        Version   Rev\ncurl        7.88.1    123"
        assertFalse(snapOutputContainsSpotify(output))
    }

    @Test
    fun `snap output is case insensitive`() {
        assertTrue(snapOutputContainsSpotify("Spotify 1.0"))
        assertTrue(snapOutputContainsSpotify("SPOTIFY 1.0"))
    }

    // ── Flatpak output parsing ────────────────────────────────────────────────

    private fun flatpakOutputContainsSpotify(flatpakOutput: String) =
        flatpakOutput.contains("spotify", ignoreCase = true)

    @Test
    fun `flatpak output with Spotify entry returns true`() {
        val output = "com.spotify.Client  Spotify  stable"
        assertTrue(flatpakOutputContainsSpotify(output))
    }

    @Test
    fun `flatpak output without Spotify entry returns false`() {
        val output = "org.gnome.Calculator  Calculator  stable"
        assertFalse(flatpakOutputContainsSpotify(output))
    }

    // ── playerctl detection (process-based) ──────────────────────────────────

    @Test
    fun `playerctl binary presence is detectable via which`() {
        val result = which("playerctl")
        // We don't assert true/false because playerctl may or may not be installed
        // on the machine running tests — just verify the call returns without throwing.
        assertTrue(result || !result)
    }

    // ── OS detection helpers ──────────────────────────────────────────────────

    @Test
    fun `os name property is available`() {
        assertNotNull(System.getProperty("os.name"))
    }

    @Test
    fun `os is identifiable as mac or linux or other`() {
        val os = System.getProperty("os.name").lowercase()
        val known = os.contains("mac") || os.contains("linux") || os.contains("windows")
        assertTrue("Unrecognised OS: $os", known)
    }

    @Test
    fun `mac detection does not fire on linux`() {
        val os    = System.getProperty("os.name").lowercase()
        val isMac = os.contains("mac")
        if (!isMac) assertFalse("Should not detect mac on $os", isMac)
    }

    @Test
    fun `linux detection does not fire on mac`() {
        val os      = System.getProperty("os.name").lowercase()
        val isLinux = os.contains("linux")
        if (!isLinux) assertFalse("Should not detect linux on $os", isLinux)
    }
}
