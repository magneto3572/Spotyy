package com.magneto.spotyy.focus

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

class FocusRoomPacketTest {

    private val PREFIX = "SPOTYY_ROOM"

    // ── Reflection helpers ────────────────────────────────────────────────────

    private fun setField(name: String, value: Any?) {
        val f: Field = FocusRoomService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(FocusRoomService, value)
    }

    private fun getField(name: String): Any? {
        val f: Field = FocusRoomService::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(FocusRoomService)
    }

    private fun resetState() {
        setField("currentRoom", null)
        setField("isHost", false)
        @Suppress("UNCHECKED_CAST")
        (getField("nearbyRooms") as java.util.concurrent.ConcurrentHashMap<*, *>).clear()
        FocusRoomService.onRoomInvite = null
        FocusRoomService.onRoomEnded  = null
    }

    @Before fun setUp()    = resetState()
    @After  fun tearDown() = resetState()

    // ── Packet format helpers ─────────────────────────────────────────────────

    private fun makePacket(
        action: String,
        roomId: String = "room-abc",
        host: String   = "alice",
        durationSecs: Int = 1500,
        startTs: Long  = System.currentTimeMillis(),
        sender: String = "alice",
        target: String = ""
    ) = "$PREFIX|$action|$roomId|$host|$durationSecs|$startTs|$sender|$target"

    // ── Packet structure ──────────────────────────────────────────────────────

    @Test
    fun `packet has SPOTYY_ROOM prefix`() {
        val pkt = makePacket("PING")
        assertTrue(pkt.startsWith(PREFIX))
    }

    @Test
    fun `packet has at least 8 pipe-separated fields`() {
        val pkt = makePacket("PING")
        assertTrue(pkt.split("|").size >= 8)
    }

    @Test
    fun `action field is at index 1`() {
        val pkt = makePacket("INVITE", target = "bob")
        assertEquals("INVITE", pkt.split("|")[1])
    }

    @Test
    fun `roomId field is at index 2`() {
        val pkt = makePacket("PING", roomId = "my-room")
        assertEquals("my-room", pkt.split("|")[2])
    }

    @Test
    fun `host field is at index 3`() {
        val pkt = makePacket("PING", host = "alice")
        assertEquals("alice", pkt.split("|")[3])
    }

    @Test
    fun `duration field is parseable as Int`() {
        val pkt = makePacket("PING", durationSecs = 2700)
        val dur = pkt.split("|")[4].toIntOrNull()
        assertEquals(2700, dur)
    }

    @Test
    fun `start timestamp field is parseable as Long`() {
        val ts  = 1_700_000_000_000L
        val pkt = makePacket("PING", startTs = ts)
        val parsed = pkt.split("|")[5].toLongOrNull()
        assertEquals(ts, parsed)
    }

    @Test
    fun `sender field is at index 6`() {
        val pkt = makePacket("JOIN", sender = "bob")
        assertEquals("bob", pkt.split("|")[6])
    }

    @Test
    fun `target field is at index 7`() {
        val pkt = makePacket("INVITE", target = "charlie")
        assertEquals("charlie", pkt.split("|")[7])
    }

    // ── handleMessage — packet with fewer than 7 fields is ignored ────────────

    @Test
    fun `short packet is ignored without crash`() {
        FocusRoomService.handleMessage("SPOTYY_ROOM|PING|room1")
        assertNull(FocusRoomService.currentRoom)
    }

    @Test
    fun `non-SPOTYY_ROOM prefix is ignored`() {
        FocusRoomService.handleMessage("SPOTYY|alice|track|true")
        assertNull(FocusRoomService.currentRoom)
    }

    // ── handleMessage — INVITE ────────────────────────────────────────────────

    @Test
    fun `INVITE for this user fires onRoomInvite`() {
        val me = com.magneto.spotyy.network.NetworkDiscoveryService.localUsername
        var invited: FocusRoom? = null
        FocusRoomService.onRoomInvite = { invited = it }

        val ts  = System.currentTimeMillis()
        val pkt = makePacket("INVITE", host = "alice", startTs = ts, sender = "alice", target = me)
        FocusRoomService.handleMessage(pkt)

        assertNotNull(invited)
    }

    @Test
    fun `INVITE for a different user does not fire onRoomInvite`() {
        var invited = false
        FocusRoomService.onRoomInvite = { invited = true }

        val pkt = makePacket("INVITE", sender = "alice", target = "someoneElse")
        FocusRoomService.handleMessage(pkt)

        assertFalse(invited)
    }

    @Test
    fun `INVITE while already in a room is ignored`() {
        val me = com.magneto.spotyy.network.NetworkDiscoveryService.localUsername
        // Simulate being in a room already
        val existingRoom = FocusRoom("existing", "bob", 1500, System.currentTimeMillis())
        setField("currentRoom", existingRoom)

        var invited = false
        FocusRoomService.onRoomInvite = { invited = true }

        val pkt = makePacket("INVITE", sender = "alice", target = me)
        FocusRoomService.handleMessage(pkt)

        assertFalse(invited)
    }

    // ── handleMessage — PING / JOIN (nearby room tracking) ───────────────────

    @Test
    fun `PING from a room we are not in adds it to nearbyRooms`() {
        val ts  = System.currentTimeMillis()
        val pkt = makePacket("PING", roomId = "room-xyz", host = "alice", startTs = ts, sender = "alice")
        FocusRoomService.handleMessage(pkt)

        val nearby = FocusRoomService.getNearbyRooms()
        assertTrue(nearby.any { it.roomId == "room-xyz" })
    }

    @Test
    fun `JOIN from a room we are not in adds it to nearbyRooms`() {
        val ts  = System.currentTimeMillis()
        val pkt = makePacket("JOIN", roomId = "room-join", host = "alice", startTs = ts, sender = "alice")
        FocusRoomService.handleMessage(pkt)

        val nearby = FocusRoomService.getNearbyRooms()
        assertTrue(nearby.any { it.roomId == "room-join" })
    }

    @Test
    fun `PING from our own room updates member list`() {
        val me   = com.magneto.spotyy.network.NetworkDiscoveryService.localUsername
        val room = FocusRoom("room-shared", "alice", 1500, System.currentTimeMillis())
        setField("currentRoom", room)

        val pkt = makePacket("PING", roomId = "room-shared", sender = "bob")
        FocusRoomService.handleMessage(pkt)

        assertTrue(room.members.containsKey("bob"))
    }

    // ── handleMessage — LEAVE ─────────────────────────────────────────────────

    @Test
    fun `LEAVE removes sender from current room member list`() {
        val room = FocusRoom("r1", "alice", 1500, System.currentTimeMillis())
        room.members["bob"] = RoomMember("bob", System.currentTimeMillis())
        setField("currentRoom", room)

        val pkt = makePacket("LEAVE", roomId = "r1", sender = "bob")
        FocusRoomService.handleMessage(pkt)

        assertFalse(room.members.containsKey("bob"))
    }

    // ── handleMessage — KICK ──────────────────────────────────────────────────

    @Test
    fun `KICK targeting this user clears currentRoom`() {
        val me   = com.magneto.spotyy.network.NetworkDiscoveryService.localUsername
        val room = FocusRoom("r2", "alice", 1500, System.currentTimeMillis())
        setField("currentRoom", room)

        var ended = false
        FocusRoomService.onRoomEnded = { ended = true }

        val pkt = makePacket("KICK", roomId = "r2", sender = "alice", target = me)
        FocusRoomService.handleMessage(pkt)

        assertNull(FocusRoomService.currentRoom)
        assertTrue(ended)
    }

    @Test
    fun `KICK targeting another user removes them from member list`() {
        val room = FocusRoom("r3", "alice", 1500, System.currentTimeMillis())
        room.members["charlie"] = RoomMember("charlie", System.currentTimeMillis())
        setField("currentRoom", room)

        val pkt = makePacket("KICK", roomId = "r3", sender = "alice", target = "charlie")
        FocusRoomService.handleMessage(pkt)

        assertFalse(room.members.containsKey("charlie"))
    }

    // ── handleMessage — END ───────────────────────────────────────────────────

    @Test
    fun `END clears currentRoom`() {
        val room = FocusRoom("r4", "alice", 1500, System.currentTimeMillis())
        setField("currentRoom", room)

        var ended = false
        FocusRoomService.onRoomEnded = { ended = true }

        val pkt = makePacket("END", roomId = "r4", sender = "alice")
        FocusRoomService.handleMessage(pkt)

        assertNull(FocusRoomService.currentRoom)
        assertTrue(ended)
    }

    @Test
    fun `END removes room from nearbyRooms`() {
        val ts    = System.currentTimeMillis()
        val pingPkt = makePacket("PING", roomId = "r5", host = "alice", startTs = ts, sender = "alice")
        FocusRoomService.handleMessage(pingPkt)
        assertTrue(FocusRoomService.getNearbyRooms().any { it.roomId == "r5" })

        val endPkt = makePacket("END", roomId = "r5", sender = "alice")
        FocusRoomService.handleMessage(endPkt)

        assertFalse(FocusRoomService.getNearbyRooms().any { it.roomId == "r5" })
    }

    // ── Self-packet guard ─────────────────────────────────────────────────────

    @Test
    fun `packet from self is ignored`() {
        val me = com.magneto.spotyy.network.NetworkDiscoveryService.localUsername
        var invited = false
        FocusRoomService.onRoomInvite = { invited = true }

        val pkt = makePacket("INVITE", sender = me, target = me)
        FocusRoomService.handleMessage(pkt)

        assertFalse(invited)
    }
}
