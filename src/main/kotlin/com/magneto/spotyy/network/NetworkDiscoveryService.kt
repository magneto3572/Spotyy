package com.magneto.spotyy.network

import com.intellij.ide.util.PropertiesComponent
import java.net.*
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class SpotyyPeer(val username: String, val track: String, val lastSeen: Long)

enum class VibeMatch { NONE, SAME_ARTIST, SAME_SONG }

object NetworkDiscoveryService {

    private const val PORT              = 57372
    private const val BROADCAST_ADDR   = "255.255.255.255"
    private const val PREFIX            = "SPOTYY"
    private const val PEER_TIMEOUT_MS  = 10_000L
    private const val GHOST_MODE_KEY   = "spotyy.ghost.mode"
    private const val VIBE_COUNT_KEY   = "spotyy.vibe.count"
    private const val VIBE_DATE_KEY    = "spotyy.vibe.date"

    val localUsername: String = System.getProperty("user.name") ?: "Someone"

    /** The track this user is currently playing — kept in sync by broadcast(). */
    @Volatile var localTrack: String = ""
        private set

    private val peers        = ConcurrentHashMap<String, SpotyyPeer>()
    private val recordedVibes = mutableSetOf<String>() // dedup: "peer|track"

    @Volatile private var listenerSocket: DatagramSocket? = null
    @Volatile private var running = false

    // ── Ghost mode ────────────────────────────────────────────────────────────

    fun isGhostMode(): Boolean =
        PropertiesComponent.getInstance().getBoolean(GHOST_MODE_KEY, false)

    fun setGhostMode(enabled: Boolean) {
        PropertiesComponent.getInstance().setValue(GHOST_MODE_KEY, enabled)
        if (enabled) {
            localTrack = ""
            sendPacket("$PREFIX|$localUsername||false")
        }
    }

    // ── Vibe match ────────────────────────────────────────────────────────────

    fun vibeMatch(peer: SpotyyPeer): VibeMatch {
        if (localTrack.isBlank() || peer.track.isBlank()) return VibeMatch.NONE
        val local = localTrack.lowercase()
        val other = peer.track.lowercase()
        if (local == other) return VibeMatch.SAME_SONG
        val localArtist = local.substringBefore(" - ").trim()
        val peerArtist  = other.substringBefore(" - ").trim()
        if (localArtist.isNotBlank() && localArtist == peerArtist) return VibeMatch.SAME_ARTIST
        return VibeMatch.NONE
    }

    /**
     * Records a vibe match. Deduped per session — same (peer, track) pair
     * is only counted once even though it's detected every 3 seconds.
     */
    fun recordVibeMatch(peerName: String, track: String) {
        val key = "$peerName|$track"
        if (!recordedVibes.add(key)) return // already recorded this session

        val props   = PropertiesComponent.getInstance()
        val today   = LocalDate.now().toString()
        val stored  = props.getValue(VIBE_DATE_KEY, "")
        val current = if (stored == today) props.getValue(VIBE_COUNT_KEY, "0").toIntOrNull() ?: 0 else 0
        props.setValue(VIBE_DATE_KEY, today)
        props.setValue(VIBE_COUNT_KEY, (current + 1).toString())
    }

    /** Today's total vibe match count (resets at midnight). */
    fun getTodayVibeCount(): Int {
        val props  = PropertiesComponent.getInstance()
        val today  = LocalDate.now().toString()
        val stored = props.getValue(VIBE_DATE_KEY, "")
        return if (stored == today) props.getValue(VIBE_COUNT_KEY, "0").toIntOrNull() ?: 0 else 0
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        startListenerThread()
    }

    fun stop() {
        running = false
        listenerSocket?.close()
        peers.clear()
    }

    // ── Broadcasting ──────────────────────────────────────────────────────────

    fun broadcast(track: String, isPlaying: Boolean) {
        if (isGhostMode()) return
        localTrack = if (isPlaying && track.isNotBlank()) track else ""
        val msg = if (localTrack.isNotBlank()) {
            "$PREFIX|$localUsername|$localTrack|true"
        } else {
            "$PREFIX|$localUsername||false"
        }
        sendPacket(msg)
    }

    private fun sendPacket(msg: String) {
        try {
            val data = msg.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { s ->
                s.broadcast = true
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT))
            }
        } catch (_: Exception) {}
    }

    // ── Peers ─────────────────────────────────────────────────────────────────

    fun getActivePeers(): List<SpotyyPeer> {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value.lastSeen > PEER_TIMEOUT_MS }
        return peers.values.sortedBy { it.username }
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    private fun startListenerThread() {
        Thread {
            while (running) {
                try {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast    = true
                        soTimeout    = 2000
                        bind(InetSocketAddress(PORT))
                    }
                    listenerSocket = socket
                    val buf = ByteArray(512)
                    while (running) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                            parseAndStore(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        } catch (_: SocketTimeoutException) {}
                          catch (_: SocketException)      { break }
                    }
                    socket.close()
                    listenerSocket = null
                    if (running) peers.clear()
                } catch (_: Exception) {}

                if (running) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                }
            }
        }.apply { isDaemon = true; name = "Spotyy-Discovery"; start() }
    }

    private fun parseAndStore(msg: String) {
        val parts = msg.split("|")
        if (parts.size < 4 || parts[0] != PREFIX) return
        val name      = parts[1].trim()
        val track     = parts[2].trim()
        val isPlaying = parts[3].trim() == "true"
        if (name == localUsername) return
        if (isPlaying && track.isNotBlank()) {
            peers[name] = SpotyyPeer(name, track, System.currentTimeMillis())
        } else {
            peers.remove(name)
        }
    }
}
