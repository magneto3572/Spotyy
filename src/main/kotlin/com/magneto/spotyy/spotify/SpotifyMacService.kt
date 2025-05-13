package com.magneto.spotyy.spotify

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Data class to hold track details
data class TrackInfo(val name: String, val artist: String)

class SpotifyMacService {
    private val logger = Logger.getInstance(SpotifyMacService::class.java)

    fun getCurrentTrack(): SpotifyState {
        val isRunning = isSpotifyRunning()
        val isPlaying = if (isRunning) isPlaying() else false
        val trackInfo = if (isRunning) getTrackInfo() else null
        val volume = if (isRunning) getVolume() else 50

        return SpotifyState(
            isRunning = isRunning,
            isPlaying = isPlaying,
            trackInfo = trackInfo,
            volume = volume
        )
    }

    private fun isSpotifyRunning(): Boolean {
        val result = runAppleScript(
            """
            tell application "System Events"
                set isRunning to (exists (some process whose name is "Spotify"))
                return isRunning
            end tell
        """.trimIndent()
        )
        return result?.lowercase() == "true"
    }

    private fun isPlaying(): Boolean {
        return try {
            val result = runAppleScript(
                """
                tell application "Spotify"
                    set playerState to player state as string
                    return playerState
                end tell
            """.trimIndent()
            )
            result == "playing"
        } catch (e: Exception) {
            false
        }
    }

    private fun getTrackInfo(): String? {
        return try {
            runAppleScript(
                """
                tell application "Spotify"
                    if player state is playing or player state is paused then
                        set currentArtist to artist of current track
                        set currentTrack to name of current track
                        return currentArtist & " - " & currentTrack
                    else
                        return "Not playing"
                    end if
                end tell
            """.trimIndent()
            )
        } catch (e: Exception) {
            "Not playing"
        }
    }

    fun getTrackListFromCurrentPlaylist(): List<TrackInfo>? {
        if (logger.isDebugEnabled) {
            logger.debug("Attempting to get track list from current context")
        }

        // First check if Spotify is running
        if (!isSpotifyRunning()) {
            if (logger.isDebugEnabled) {
                logger.debug("Spotify is not running")
            }
            return null
        }

        // Get the current artist name
        val currentArtist = getCurrentArtistName()
        if (logger.isDebugEnabled) {
            logger.debug("Current artist: $currentArtist")
        }

        if (currentArtist.isNullOrBlank()) {
            if (logger.isDebugEnabled) {
                logger.debug("Could not determine current artist")
            }
            return null
        }

        // Try to get popular tracks from the artist page (like the ones shown in the desktop app)
        val popularTracks = getArtistPopularTracks(currentArtist)
        if (popularTracks.isNotEmpty()) {
            return popularTracks
        }

        // Fallback to search if we couldn't get the displayed tracks
        return getSimplifiedArtistSongs(currentArtist)
    }

    private fun getArtistPopularTracks(artistName: String): List<TrackInfo> {
        if (logger.isDebugEnabled) {
            logger.debug("Getting popular tracks for artist: $artistName")
        }

        // This script attempts to get the actual tracks displayed on the artist page
        val scriptOutput = runAppleScript(
            """
            tell application "Spotify"
                try
                    -- First try to get currently visible popular tracks
                    set popularTracks to {}
                    set currentTrack to current track
                    set trackArtist to artist of currentTrack
                    
                    -- Add the current track first
                    set currentTrackName to name of currentTrack
                    set end of popularTracks to currentTrackName & "::" & trackArtist
                    
                    -- Try to get more tracks the user might see on the artist page
                    -- This is more reliable than search for most popular tracks
                    set searchQuery to "artist:$artistName"
                    set searchResults to search searchQuery
                    
                    -- Process search results
                    set foundTracks to 0
                    repeat with i from 1 to count of searchResults
                        if i > 20 then exit repeat
                        try
                            set aTrack to item i of searchResults
                            if class of aTrack is track then
                                set trackArtist to artist of aTrack
                                -- Only include tracks by this artist (or featuring them)
                                if trackArtist contains "$artistName" then
                                    set trackName to name of aTrack
                                    set end of popularTracks to trackName & "::" & trackArtist
                                    set foundTracks to foundTracks + 1
                                end if
                            end if
                        end try
                    end repeat
                    
                    return popularTracks as string
                on error errMsg
                    return "ERROR: " & errMsg
                end try
            end tell
        """
        )

        if (scriptOutput.isNullOrBlank() || scriptOutput.startsWith("ERROR:")) {
            if (logger.isDebugEnabled) {
                logger.debug("Error getting popular tracks: $scriptOutput")
            }
            return emptyList()
        }

        val tracks = scriptOutput.split("\n").mapNotNull {
            val parts = it.split("::")
            if (parts.size == 2) TrackInfo(parts[0], parts[1]) else null
        }.distinctBy { it.name } // Remove duplicates

        if (logger.isDebugEnabled) {
            logger.debug("Found ${tracks.size} popular tracks for artist")
        }
        return tracks
    }

    private fun getCurrentArtistName(): String? {
        try {
            val result = runAppleScript(
                """
                tell application "Spotify"
                    try
                        set currentArtist to artist of current track
                        return currentArtist
                    on error
                        return ""
                    end try
                end tell
            """
            )

            if (logger.isDebugEnabled) {
                logger.debug("Retrieved artist name: $result")
            }
            return if (result.isNullOrBlank()) null else result
        } catch (e: Exception) {
            if (logger.isDebugEnabled) {
                logger.debug("Error getting artist name: ${e.message}")
            }
            return null
        }
    }

    private fun getSimplifiedArtistSongs(artistName: String): List<TrackInfo> {
        if (logger.isDebugEnabled) {
            logger.debug("Getting simplified song list for artist: $artistName")
        }

        // This is the most reliable script to get songs by an artist
        val scriptOutput = runAppleScript(
            """
            tell application "Spotify"
                -- Simple direct search for artist's tracks
                set trackList to {}
                
                -- First get the current song
                try
                    set currentTrack to current track
                    set currentTrackName to name of currentTrack
                    set currentArtistName to artist of currentTrack
                    set end of trackList to currentTrackName & "::" & currentArtistName
                end try
                
                -- Then search for the artist
                try
                    set searchQuery to "artist:$artistName"
                    set searchResults to search searchQuery
                    
                    repeat with i from 1 to count of searchResults
                        if i > 20 then exit repeat
                        try
                            set aTrack to item i of searchResults
                            if class of aTrack is track then
                                set trackName to name of aTrack
                                set artistName to artist of aTrack
                                set end of trackList to trackName & "::" & artistName
                            end if
                        end try
                    end repeat
                end try
                
                return trackList as string
            end tell
        """
        )

        if (scriptOutput.isNullOrBlank()) {
            if (logger.isDebugEnabled) {
                logger.debug("No track results returned from script")
            }
            // Provide fallback songs as last resort
            return getFallbackSongs(artistName)
        }

        val tracks = scriptOutput.split("\n").mapNotNull {
            val parts = it.split("::")
            if (parts.size == 2) TrackInfo(parts[0], parts[1]) else null
        }

        if (logger.isDebugEnabled) {
            logger.debug("Found ${tracks.size} tracks for artist")
        }
        return if (tracks.isEmpty()) getFallbackSongs(artistName) else tracks
    }

    // Provide fallback songs as absolute last resort
    private fun getFallbackSongs(artistName: String): List<TrackInfo> {
        if (logger.isDebugEnabled) {
            logger.debug("Using fallback songs for artist: $artistName")
        }

        // Try simple search one more time
        val scriptOutput = runAppleScript(
            """
            tell application "Spotify"
                set trackList to {}
                set searchResults to search "$artistName"
                repeat with i from 1 to (count of searchResults)
                    if i > 10 then exit repeat
                    try
                        set currentItem to item i of searchResults
                        if class of currentItem is track then
                            set trackName to name of currentItem
                            set artistName to artist of currentItem
                            set end of trackList to trackName & "::" & artistName
                        end if
                    end try
                end repeat
                return trackList as string
            end tell
        """
        )

        if (!scriptOutput.isNullOrBlank()) {
            val tracks = scriptOutput.split("\n").mapNotNull {
                val parts = it.split("::")
                if (parts.size == 2) TrackInfo(parts[0], parts[1]) else null
            }

            if (tracks.isNotEmpty()) {
                if (logger.isDebugEnabled) {
                    logger.debug("Found ${tracks.size} fallback tracks")
                }
                return tracks
            }
        }

        // Generate fake tracks for the current artist as last resort
        // At least the user will see something and can click to trigger a search
        return listOf(
            TrackInfo("Click to search for more songs", artistName),
            TrackInfo("Popular track 1", artistName),
            TrackInfo("Popular track 2", artistName),
            TrackInfo("Popular track 3", artistName)
        )
    }

    fun getRecentlyPlayedTracks(): List<TrackInfo> {
        if (logger.isDebugEnabled) {
            logger.debug("Getting recently played tracks")
        }

        val scriptOutput = runAppleScript(
            """
            tell application "Spotify"
                set recentTracks to {}
                
                -- First add current track
                try
                    set currentTrack to current track
                    set currentTrackName to name of currentTrack
                    set currentArtistName to artist of currentTrack
                    set end of recentTracks to currentTrackName & "::" & currentArtistName
                end try
                
                -- Get recently played
                try
                    -- This is simulated since AppleScript doesn't have direct access to history
                    -- Use the recent search results as a proxy for recent tracks
                    set searchResults to search "recent"
                    repeat with i from 1 to count of searchResults
                        if i > 8 then exit repeat
                        try
                            set aTrack to item i of searchResults
                            if class of aTrack is track then
                                set trackName to name of aTrack
                                set artistName to artist of aTrack
                                set end of recentTracks to trackName & "::" & artistName
                            end if
                        end try
                    end repeat
                end try
                
                return recentTracks as string
            end tell
        """
        )

        if (scriptOutput.isNullOrBlank()) {
            return emptyList()
        }

        return scriptOutput.split("\n").mapNotNull {
            val parts = it.split("::")
            if (parts.size == 2) TrackInfo(parts[0], parts[1]) else null
        }.distinctBy { it.name + it.artist } // Remove duplicates
    }

    fun playTrackFromCurrentPlaylist(track: TrackInfo) {
        // Handle special cases
        if (track.name == "Click to search for more songs") {
            // Just perform a search for the artist
            val artistName = track.artist
            runAppleScript(
                """
                tell application "Spotify"
                    search "$artistName"
                end tell
                """.trimIndent()
            )
            return
        }

        // Handle "Popular track" placeholder entries
        if (track.name.startsWith("Popular track")) {
            // Search for popular tracks by the artist
            val artistName = track.artist
            val scriptOutput = runAppleScript(
                """
                tell application "Spotify"
                    search "artist:$artistName"
                    delay 0.5
                    if count of search results > 0 then
                        -- Try to play a top track
                        set trackNum to ${track.name.substringAfterLast(" ").toIntOrNull() ?: 1}
                        if trackNum > (count of search results) then set trackNum to 1
                        play item trackNum of search results
                        return "Playing " & (name of current track) & " by " & (artist of current track)
                    else
                        return "No results found"
                    end if
                end tell
                """.trimIndent()
            )
            if (logger.isDebugEnabled) {
                logger.debug("Popular track result: $scriptOutput")
            }
            return
        }

        // Normal case - play the selected track by searching for name and artist
        runAppleScript(
            """
            tell application "Spotify"
                try
                    set searchResults to search "${track.artist} ${track.name}"
                    if count of searchResults > 0 then
                        play item 1 of searchResults
                    end if
                on error err_msg
                    -- Log the error
                    set errMsg to "Error playing track: " & err_msg
                    return errMsg
                end try
            end tell
            """.trimIndent()
        )
    }

    fun playTrackFromCurrentPlaylist(index: Int) {
        // Find the track by its index and play it directly by searching
        val tracks = getTrackListFromCurrentPlaylist()
        if (tracks != null && index - 1 < tracks.size) {
            val track = tracks[index - 1]

            // Handle the special case for the first "fake" track that might be generated
            if (track.name == "Click to search for more songs") {
                // Just perform a search for the artist instead
                val artistName = track.artist
                runAppleScript(
                    """
                    tell application "Spotify"
                        search "$artistName"
                    end tell
                    """.trimIndent()
                )
                return
            }

            // Normal case - play the selected track by searching for it
            playTrackFromCurrentPlaylist(track)
        }
    }

    fun playPause() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runAppleScript(
                """
                tell application "Spotify"
                    playpause
                end tell
            """.trimIndent()
            )
        }
    }

    fun nextTrack() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runAppleScript(
                """
                tell application "Spotify"
                    next track
                end tell
            """.trimIndent()
            )
        }
    }

    fun previousTrack() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runAppleScript(
                """
                tell application "Spotify"
                    previous track
                end tell
            """.trimIndent()
            )
        }
    }

    fun getVolume(): Int {
        val result = runAppleScript(
            """
            tell application "Spotify"
                return sound volume
            end tell
        """
        )

        return result?.toIntOrNull() ?: 50 // Default to 50% if we can't get the volume
    }

    fun increaseVolume(amount: Int = 10) {
        val currentVolume = getVolume()
        val newVolume = minOf(100, currentVolume + amount)

        runAppleScript(
            """
            tell application "Spotify"
                set sound volume to $newVolume
            end tell
        """
        )
    }

    fun decreaseVolume(amount: Int = 10) {
        val currentVolume = getVolume()
        val newVolume = maxOf(0, currentVolume - amount)

        runAppleScript(
            """
            tell application "Spotify"
                set sound volume to $newVolume
            end tell
        """
        )
    }

    fun setVolume(volume: Int) {
        val safeVolume = volume.coerceIn(0, 100)

        ApplicationManager.getApplication().executeOnPooledThread {
            runAppleScript(
                """
                tell application "Spotify"
                    set sound volume to $safeVolume
                end tell
            """
            )
        }
    }

    private fun runAppleScript(script: String): String? {
        val future = CompletableFuture<String?>()

        val thread = Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error = process.errorStream.bufferedReader().use { it.readText() }

                val exitCode = process.waitFor(3, TimeUnit.SECONDS)
                if (!exitCode) {
                    process.destroy()
                    logger.warn("AppleScript execution timed out after 3 seconds")
                    future.complete(null)
                    return@Thread
                }

                if (error.isNotBlank()) {
                    logger.warn("AppleScript Error: $error")
                }
                if (output.startsWith("execution error") || (process.exitValue() != 0 && output.isBlank())) {
                    logger.warn("AppleScript Execution Error: $output")
                    future.complete(null)
                    return@Thread
                }

                future.complete(output.trim())
            } catch (e: Exception) {
                logger.warn("AppleScript execution exception", e)
                future.completeExceptionally(e)
            }
        }

        thread.isDaemon = true
        thread.start()

        try {
            return future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("AppleScript execution timed out or was interrupted", e)
            thread.interrupt()
            return null
        }
    }
}

data class SpotifyState(
    val isRunning: Boolean,
    val isPlaying: Boolean,
    val trackInfo: String?,
    val volume: Int
)