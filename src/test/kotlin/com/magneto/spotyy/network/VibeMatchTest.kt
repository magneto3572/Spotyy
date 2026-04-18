package com.magneto.spotyy.network

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VibeMatchTest {

    private fun setLocalTrack(track: String) {
        val field = NetworkDiscoveryService::class.java.getDeclaredField("localTrack")
        field.isAccessible = true
        field.set(NetworkDiscoveryService, track)
    }

    private fun peer(track: String) = SpotyyPeer("peer", track, System.currentTimeMillis())

    @Before fun setUp()    = setLocalTrack("")
    @After  fun tearDown() = setLocalTrack("")

    // ── Blank guards ──────────────────────────────────────────────────────────

    @Test
    fun `returns NONE when local track is blank`() {
        setLocalTrack("")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("Artist - Song")))
    }

    @Test
    fun `returns NONE when peer track is blank`() {
        setLocalTrack("Artist - Song")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("")))
    }

    @Test
    fun `returns NONE when both tracks are blank`() {
        setLocalTrack("")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("")))
    }

    // ── SAME_SONG ─────────────────────────────────────────────────────────────

    @Test
    fun `returns SAME_SONG for identical tracks`() {
        setLocalTrack("The Weeknd - Blinding Lights")
        assertEquals(VibeMatch.SAME_SONG, NetworkDiscoveryService.vibeMatch(peer("The Weeknd - Blinding Lights")))
    }

    @Test
    fun `SAME_SONG is case insensitive`() {
        setLocalTrack("the weeknd - blinding lights")
        assertEquals(VibeMatch.SAME_SONG, NetworkDiscoveryService.vibeMatch(peer("THE WEEKND - BLINDING LIGHTS")))
    }

    @Test
    fun `SAME_SONG takes precedence over SAME_ARTIST`() {
        setLocalTrack("Dua Lipa - Levitating")
        assertEquals(VibeMatch.SAME_SONG, NetworkDiscoveryService.vibeMatch(peer("Dua Lipa - Levitating")))
    }

    // ── SAME_ARTIST ───────────────────────────────────────────────────────────

    @Test
    fun `returns SAME_ARTIST when same artist different song`() {
        setLocalTrack("The Weeknd - Blinding Lights")
        assertEquals(VibeMatch.SAME_ARTIST, NetworkDiscoveryService.vibeMatch(peer("The Weeknd - Starboy")))
    }

    @Test
    fun `SAME_ARTIST is case insensitive`() {
        setLocalTrack("the weeknd - blinding lights")
        assertEquals(VibeMatch.SAME_ARTIST, NetworkDiscoveryService.vibeMatch(peer("THE WEEKND - STARBOY")))
    }

    // ── NONE ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns NONE for completely different tracks`() {
        setLocalTrack("Dua Lipa - Levitating")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("The Weeknd - Blinding Lights")))
    }

    @Test
    fun `returns NONE when tracks have no artist separator and differ`() {
        setLocalTrack("SongWithNoArtist")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("AnotherSongNoArtist")))
    }

    @Test
    fun `returns NONE when artists differ slightly`() {
        setLocalTrack("Drake - God's Plan")
        assertEquals(VibeMatch.NONE, NetworkDiscoveryService.vibeMatch(peer("Drake ft. Future - Jumpman")))
    }
}
