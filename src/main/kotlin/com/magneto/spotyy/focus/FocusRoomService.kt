package com.magneto.spotyy.focus

import com.intellij.openapi.project.Project
import com.magneto.spotyy.network.NetworkDiscoveryService
import java.util.concurrent.ConcurrentHashMap

data class RoomMember(val username: String, val lastSeen: Long)

/** A Focus Room detected on the network that this user has NOT joined. */
data class NearbyRoom(
    val roomId: String,
    val hostName: String,
    val durationSeconds: Int,
    val startTimestamp: Long,
    val seenMembers: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
) {
    val remainingSeconds: Long
        get() = maxOf(0L, durationSeconds - (System.currentTimeMillis() - startTimestamp) / 1000)
    val isExpired: Boolean get() = remainingSeconds <= 0L
    fun formattedTime(): String {
        val r = remainingSeconds
        return "%02d:%02d".format(r / 60, r % 60)
    }
}

data class FocusRoom(
    val roomId: String,
    val hostName: String,
    val durationSeconds: Int,
    val startTimestamp: Long,
    val members: ConcurrentHashMap<String, RoomMember> = ConcurrentHashMap()
) {
    val remainingSeconds: Long
        get() = maxOf(0L, durationSeconds - (System.currentTimeMillis() - startTimestamp) / 1000)

    val isExpired: Boolean get() = remainingSeconds <= 0L

    fun formattedTime(): String {
        val r = remainingSeconds
        return "%02d:%02d".format(r / 60, r % 60)
    }
}

object FocusRoomService {

    private const val PREFIX           = "SPOTYY_ROOM"
    private const val MEMBER_TIMEOUT   = 10_000L

    @Volatile var currentRoom: FocusRoom? = null
        private set
    @Volatile var isHost: Boolean = false
        private set

    val isInRoom: Boolean get() = currentRoom != null

    /** Rooms seen on the network that this user has not joined. Keyed by roomId. */
    private val nearbyRooms = ConcurrentHashMap<String, NearbyRoom>()

    /**
     * Returns active Focus Rooms on the network that this user is NOT a member of.
     * Expired rooms and stale members are pruned before returning.
     */
    fun getNearbyRooms(): List<NearbyRoom> {
        val now = System.currentTimeMillis()
        nearbyRooms.entries.removeIf { it.value.isExpired }
        nearbyRooms.values.forEach { r ->
            r.seenMembers.entries.removeIf { now - it.value > MEMBER_TIMEOUT }
        }
        return nearbyRooms.values.toList()
    }

    /** Set by MyProjectActivity so invite notifications have a project reference. */
    var project: Project? = null

    /** Called on EDT when another user starts a room and we are not already in one. */
    var onRoomInvite: ((FocusRoom) -> Unit)? = null

    /** Called on EDT when the active room expires (host-side auto-end). */
    var onRoomEnded: (() -> Unit)? = null

    // ── Actions ───────────────────────────────────────────────────────────────

    fun startRoom(durationMinutes: Int) {
        val me    = NetworkDiscoveryService.localUsername
        val room  = FocusRoom(
            roomId          = System.currentTimeMillis().toString(36),
            hostName        = me,
            durationSeconds = durationMinutes * 60,
            startTimestamp  = System.currentTimeMillis()
        )
        room.members[me] = RoomMember(me, System.currentTimeMillis())
        currentRoom = room
        isHost      = true
        // Room is invite-only — no broadcast. Use invitePeer() to invite specific people.
    }

    /** Send a direct invite to one peer by username. Only that peer will see the notification. */
    fun invitePeer(targetUsername: String) {
        val room = currentRoom ?: return
        send("INVITE", room, target = targetUsername)
    }

    /** Host removes a member from the room. The kicked peer's room is cleared. */
    fun kickPeer(targetUsername: String) {
        val room = currentRoom ?: return
        send("KICK", room, target = targetUsername)
        room.members.remove(targetUsername)
    }

    fun joinRoom(room: FocusRoom) {
        val me = NetworkDiscoveryService.localUsername
        room.members[me] = RoomMember(me, System.currentTimeMillis())
        currentRoom = room
        isHost      = false
        nearbyRooms.remove(room.roomId) // no longer an observer — now a member
        send("JOIN", room)
    }

    fun leaveRoom() {
        currentRoom?.let { send("LEAVE", it) }
        currentRoom = null
        isHost      = false
    }

    /** Called every 3 seconds from the widget timer (background thread). */
    fun ping() {
        val room = currentRoom ?: return
        if (room.isExpired) {
            if (isHost) send("END", room)
            currentRoom = null
            isHost      = false
            onRoomEnded?.invoke()
            return
        }
        // Prune members we haven't heard from
        val now = System.currentTimeMillis()
        room.members.entries.removeIf { (name, m) ->
            name != NetworkDiscoveryService.localUsername && now - m.lastSeen > MEMBER_TIMEOUT
        }
        // Keep our own lastSeen fresh
        room.members[NetworkDiscoveryService.localUsername] =
            RoomMember(NetworkDiscoveryService.localUsername, now)
        send("PING", room)
    }

    // ── Incoming message handler ───────────────────────────────────────────────

    fun handleMessage(msg: String) {
        val parts = msg.split("|")
        if (parts.size < 7 || parts[0] != PREFIX) return

        val action       = parts[1]
        val roomId       = parts[2]
        val hostName     = parts[3]
        val durationSecs = parts[4].toIntOrNull() ?: return
        val startTs      = parts[5].toLongOrNull() ?: return
        val sender       = parts[6].trim()
        val target       = if (parts.size > 7) parts[7].trim() else ""

        val me = NetworkDiscoveryService.localUsername
        if (sender == me) return

        when (action) {
            "INVITE" -> {
                // Only the named recipient shows the invite notification
                if (target == me && currentRoom == null) {
                    val room = FocusRoom(
                        roomId          = roomId,
                        hostName        = hostName,
                        durationSeconds = durationSecs,
                        startTimestamp  = startTs
                    )
                    room.members[hostName] = RoomMember(hostName, System.currentTimeMillis())
                    onRoomInvite?.invoke(room)
                }
            }
            "PING", "JOIN" -> {
                val room = currentRoom
                if (room != null && room.roomId == roomId) {
                    // We are in this room — update the member list
                    room.members[sender] = RoomMember(sender, System.currentTimeMillis())
                } else if (currentRoom == null) {
                    // We are not in any room — track this as a nearby room so the UI can show it
                    val nearby = nearbyRooms.getOrPut(roomId) {
                        NearbyRoom(roomId, hostName, durationSecs, startTs)
                    }
                    nearby.seenMembers[sender]   = System.currentTimeMillis()
                    nearby.seenMembers[hostName] = System.currentTimeMillis()
                }
            }
            "LEAVE" -> {
                currentRoom?.takeIf { it.roomId == roomId }?.members?.remove(sender)
                nearbyRooms[roomId]?.seenMembers?.remove(sender)
            }
            "KICK" -> {
                if (target == me) {
                    // We were kicked by the host
                    currentRoom = null
                    isHost      = false
                    onRoomEnded?.invoke()
                } else {
                    // Another member was kicked — update our member list and nearby view
                    currentRoom?.takeIf { it.roomId == roomId }?.members?.remove(target)
                    nearbyRooms[roomId]?.seenMembers?.remove(target)
                }
            }
            "END" -> {
                if (currentRoom?.roomId == roomId) {
                    currentRoom = null
                    isHost      = false
                    onRoomEnded?.invoke()
                }
                nearbyRooms.remove(roomId) // room gone — remove from nearby list
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun send(action: String, room: FocusRoom, target: String = "") {
        val me  = NetworkDiscoveryService.localUsername
        val msg = "$PREFIX|$action|${room.roomId}|${room.hostName}|${room.durationSeconds}|${room.startTimestamp}|$me|$target"
        NetworkDiscoveryService.sendRoomPacket(msg)
    }
}
