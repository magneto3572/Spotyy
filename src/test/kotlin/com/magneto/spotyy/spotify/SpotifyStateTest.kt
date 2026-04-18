package com.magneto.spotyy.spotify

import org.junit.Assert.*
import org.junit.Test

class SpotifyStateTest {

    @Test
    fun `progress is zero when duration is zero`() {
        val state = SpotifyState(true, true, "Artist - Track", 50, positionMs = 1000, durationMs = 0)
        assertEquals(0f, state.progress)
    }

    @Test
    fun `progress is zero when position is zero`() {
        val state = SpotifyState(true, true, "Artist - Track", 50, positionMs = 0, durationMs = 240000)
        assertEquals(0f, state.progress)
    }

    @Test
    fun `progress is 0_5 at halfway point`() {
        val state = SpotifyState(true, true, "Artist - Track", 50, positionMs = 120000, durationMs = 240000)
        assertEquals(0.5f, state.progress, 0.001f)
    }

    @Test
    fun `progress is 1_0 at full duration`() {
        val state = SpotifyState(true, true, "Artist - Track", 50, positionMs = 240000, durationMs = 240000)
        assertEquals(1.0f, state.progress, 0.001f)
    }

    @Test
    fun `progress is clamped to 1_0 when position exceeds duration`() {
        val state = SpotifyState(true, true, "Artist - Track", 50, positionMs = 999999, durationMs = 240000)
        assertEquals(1.0f, state.progress, 0.001f)
    }

    @Test
    fun `progress defaults to 0 when no position or duration provided`() {
        val state = SpotifyState(true, true, "Artist - Track", 50)
        assertEquals(0f, state.progress)
    }

    @Test
    fun `isRunning is false when Spotify not open`() {
        val state = SpotifyState(false, false, null, 50)
        assertFalse(state.isRunning)
        assertFalse(state.isPlaying)
        assertNull(state.trackInfo)
    }

    @Test
    fun `volume is preserved correctly`() {
        val state = SpotifyState(true, true, "Artist - Track", 72)
        assertEquals(72, state.volume)
    }

    @Test
    fun `trackInfo is null when nothing is playing`() {
        val state = SpotifyState(true, false, null, 50)
        assertNull(state.trackInfo)
    }

    @Test
    fun `trackInfo contains artist and track name`() {
        val state = SpotifyState(true, true, "Dua Lipa - Levitating", 50)
        assertEquals("Dua Lipa - Levitating", state.trackInfo)
    }
}
