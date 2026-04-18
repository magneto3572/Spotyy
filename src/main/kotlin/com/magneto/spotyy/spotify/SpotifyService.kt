package com.magneto.spotyy.spotify

interface SpotifyService {
    fun getCurrentTrack(): SpotifyState
    fun playPause()
    fun nextTrack()
    fun previousTrack()
    fun getVolume(): Int
    fun setVolume(volume: Int)
    fun increaseVolume(amount: Int = 10)
    fun decreaseVolume(amount: Int = 10)
}
