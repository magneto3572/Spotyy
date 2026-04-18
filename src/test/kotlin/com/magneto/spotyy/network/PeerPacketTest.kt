package com.magneto.spotyy.network

import org.junit.Assert.*
import org.junit.Test

class PeerPacketTest {

    private val PREFIX = "SPOTYY"

    private data class ParsedPeer(val name: String, val track: String, val isPlaying: Boolean)

    private fun parsePeerPacket(msg: String): ParsedPeer? {
        if (msg.startsWith("SPOTYY_ROOM")) return null
        val parts = msg.split("|")
        if (parts.size < 4 || parts[0] != PREFIX) return null
        val name      = parts[1].trim()
        val track     = parts[2].trim()
        val isPlaying = parts[3].trim() == "true"
        return ParsedPeer(name, track, isPlaying)
    }

    // ── Valid packets ─────────────────────────────────────────────────────────

    @Test
    fun `playing packet parses username correctly`() {
        val p = parsePeerPacket("SPOTYY|alice|The Weeknd - Blinding Lights|true")
        assertEquals("alice", p!!.name)
    }

    @Test
    fun `playing packet parses track correctly`() {
        val p = parsePeerPacket("SPOTYY|alice|The Weeknd - Blinding Lights|true")
        assertEquals("The Weeknd - Blinding Lights", p!!.track)
    }

    @Test
    fun `playing packet has isPlaying true`() {
        val p = parsePeerPacket("SPOTYY|alice|The Weeknd - Blinding Lights|true")
        assertTrue(p!!.isPlaying)
    }

    @Test
    fun `paused packet has empty track`() {
        val p = parsePeerPacket("SPOTYY|bob||false")
        assertEquals("", p!!.track)
        assertFalse(p.isPlaying)
    }

    @Test
    fun `ghost mode packet has blank track and false`() {
        val p = parsePeerPacket("SPOTYY|charlie||false")
        assertNotNull(p)
        assertEquals("", p!!.track)
        assertFalse(p.isPlaying)
    }

    @Test
    fun `track with special characters parses correctly`() {
        val p = parsePeerPacket("SPOTYY|user|Artist's Song - Track #1|true")
        assertEquals("Artist's Song - Track #1", p!!.track)
    }

    // ── Invalid packets ───────────────────────────────────────────────────────

    @Test
    fun `wrong prefix is rejected`() {
        assertNull(parsePeerPacket("INVALID|alice|track|true"))
    }

    @Test
    fun `room packet is not parsed as peer packet`() {
        assertNull(parsePeerPacket("SPOTYY_ROOM|PING|room1|alice|1500|123|alice|*"))
    }

    @Test
    fun `packet with fewer than 4 fields is rejected`() {
        assertNull(parsePeerPacket("SPOTYY|alice|track"))
    }

    @Test
    fun `empty packet is rejected`() {
        assertNull(parsePeerPacket(""))
    }

    // ── Peer timeout logic ────────────────────────────────────────────────────

    @Test
    fun `peer is stale after 10 seconds`() {
        val timeout = 10_000L
        val peer = SpotyyPeer("alice", "Artist - Song", System.currentTimeMillis() - 15_000)
        assertTrue(System.currentTimeMillis() - peer.lastSeen > timeout)
    }

    @Test
    fun `peer is active within 10 seconds`() {
        val timeout = 10_000L
        val peer = SpotyyPeer("alice", "Artist - Song", System.currentTimeMillis() - 3_000)
        assertTrue(System.currentTimeMillis() - peer.lastSeen < timeout)
    }

    @Test
    fun `peer equality is based on all fields`() {
        val ts = 1000L
        assertEquals(SpotyyPeer("alice", "Song", ts), SpotyyPeer("alice", "Song", ts))
    }

    @Test
    fun `peers with different tracks are not equal`() {
        val ts = System.currentTimeMillis()
        assertNotEquals(SpotyyPeer("alice", "Song A", ts), SpotyyPeer("alice", "Song B", ts))
    }
}
