package com.magneto.spotyy.spotify

object SpotifyServiceFactory {

    val instance: SpotifyService by lazy { create() }

    private fun create(): SpotifyService {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac")     -> SpotifyMacService()
            os.contains("linux")   -> SpotifyLinuxService()
            os.contains("windows") -> SpotifyWindowsService()
            else                   -> NoOpSpotifyService()
        }
    }
}

/** Returned on unsupported platforms. All controls are silent no-ops. */
private class NoOpSpotifyService : SpotifyService {
    override fun getCurrentTrack() = SpotifyState(false, false, null, 50)
    override fun playPause() = Unit
    override fun nextTrack() = Unit
    override fun previousTrack() = Unit
    override fun getVolume() = 50
    override fun setVolume(volume: Int) = Unit
    override fun increaseVolume(amount: Int) = Unit
    override fun decreaseVolume(amount: Int) = Unit
}
