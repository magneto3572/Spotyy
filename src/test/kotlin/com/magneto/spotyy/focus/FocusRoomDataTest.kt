package com.magneto.spotyy.focus

import org.junit.Assert.*
import org.junit.Test

class FocusRoomDataTest {

    private fun room(durationSecs: Int, elapsedMs: Long = 0) = FocusRoom(
        roomId = "room-1",
        hostName = "alice",
        durationSeconds = durationSecs,
        startTimestamp = System.currentTimeMillis() - elapsedMs
    )

    private fun nearby(durationSecs: Int, elapsedMs: Long = 0) = NearbyRoom(
        roomId = "nearby-1",
        hostName = "bob",
        durationSeconds = durationSecs,
        startTimestamp = System.currentTimeMillis() - elapsedMs
    )

    // ── FocusRoom — expiry ────────────────────────────────────────────────────

    @Test
    fun `fresh room is not expired`() {
        assertFalse(room(25 * 60).isExpired)
    }

    @Test
    fun `room is expired when full duration has elapsed`() {
        assertTrue(room(durationSecs = 10, elapsedMs = 15_000).isExpired)
    }

    @Test
    fun `remaining seconds is zero after expiry`() {
        assertEquals(0L, room(durationSecs = 10, elapsedMs = 20_000).remainingSeconds)
    }

    @Test
    fun `remaining seconds is positive for active room`() {
        assertTrue(room(25 * 60).remainingSeconds > 0)
    }

    @Test
    fun `remaining seconds does not go negative`() {
        val r = room(durationSecs = 5, elapsedMs = 60_000)
        assertEquals(0L, r.remainingSeconds)
    }

    // ── FocusRoom — formattedTime ─────────────────────────────────────────────

    @Test
    fun `formattedTime returns MM_SS format`() {
        val time = room(25 * 60).formattedTime()
        assertTrue("Expected MM:SS, got $time", time.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `formattedTime is 00_00 for expired room`() {
        assertEquals("00:00", room(durationSecs = 5, elapsedMs = 30_000).formattedTime())
    }

    @Test
    fun `formattedTime pads single digit minutes and seconds`() {
        // Room with exactly 1 minute 5 seconds remaining
        val elapsed = ((25 * 60 - 65) * 1000).toLong()
        val r = room(durationSecs = 25 * 60, elapsedMs = elapsed)
        val remaining = r.remainingSeconds
        assertTrue("Remaining should be ~65s, was $remaining", remaining in 63..67)
        val time = r.formattedTime()
        assertTrue("Expected 01:0X format, got $time", time.startsWith("01:0"))
    }

    // ── FocusRoom — durations ─────────────────────────────────────────────────

    @Test
    fun `25 min room has 1500 duration seconds`() {
        assertEquals(1500L, room(25 * 60).remainingSeconds, 2L)
    }

    @Test
    fun `45 min room has 2700 duration seconds`() {
        assertEquals(2700L, room(45 * 60).remainingSeconds, 2L)
    }

    @Test
    fun `60 min room has 3600 duration seconds`() {
        assertEquals(3600L, room(60 * 60).remainingSeconds, 2L)
    }

    // ── NearbyRoom ────────────────────────────────────────────────────────────

    @Test
    fun `nearby room is not expired when fresh`() {
        assertFalse(nearby(25 * 60).isExpired)
    }

    @Test
    fun `nearby room is expired after duration`() {
        assertTrue(nearby(durationSecs = 5, elapsedMs = 10_000).isExpired)
    }

    @Test
    fun `nearby room remaining seconds does not go negative`() {
        assertEquals(0L, nearby(durationSecs = 5, elapsedMs = 60_000).remainingSeconds)
    }

    @Test
    fun `nearby room formattedTime matches MM_SS pattern`() {
        assertTrue(nearby(25 * 60).formattedTime().matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `nearby room starts with empty member map`() {
        assertTrue(nearby(25 * 60).seenMembers.isEmpty())
    }

    // ── RoomMember ────────────────────────────────────────────────────────────

    @Test
    fun `room member stores username correctly`() {
        val m = RoomMember("charlie", System.currentTimeMillis())
        assertEquals("charlie", m.username)
    }

    @Test
    fun `room member equality based on all fields`() {
        val ts = 1000L
        assertEquals(RoomMember("alice", ts), RoomMember("alice", ts))
    }

    @Test
    fun `room members with different usernames are not equal`() {
        val ts = System.currentTimeMillis()
        assertNotEquals(RoomMember("alice", ts), RoomMember("bob", ts))
    }

    @Test
    fun `member is stale when last seen beyond timeout`() {
        val timeout = 10_000L
        val member = RoomMember("alice", System.currentTimeMillis() - 15_000)
        assertTrue(System.currentTimeMillis() - member.lastSeen > timeout)
    }

    @Test
    fun `member is fresh when last seen within timeout`() {
        val timeout = 10_000L
        val member = RoomMember("alice", System.currentTimeMillis())
        assertTrue(System.currentTimeMillis() - member.lastSeen < timeout)
    }
}

private fun assertEquals(expected: Long, actual: Long, delta: Long) {
    assertTrue("Expected $actual to be within $delta of $expected", kotlin.math.abs(actual - expected) <= delta)
}
