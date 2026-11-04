package com.stemify.mobile.playback

import com.stemify.mobile.plex.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens see of a player: this phone's [PlaybackManager], or another device's while
 * Stemify Connect controls it ([com.stemify.mobile.connect.RemotePlayer]). Main thread only.
 */
interface Controls {
    val queue: StateFlow<List<QueueItem>>
    val index: StateFlow<Int>
    val current: StateFlow<QueueItem?>
    val playWhenReady: StateFlow<Boolean>
    val buffering: StateFlow<Boolean>
    val shuffle: StateFlow<Boolean>
    val repeat: StateFlow<RepeatMode>
    val source: StateFlow<SourceKind>
    val stemNames: StateFlow<List<String>>
    val mix: StateFlow<Map<String, StemSetting>>
    val master: StateFlow<Float>
    val waveform: StateFlow<Waveform?>
    val durationMs: StateFlow<Long>

    fun positionMs(): Long

    /** The meters' levels for what's audible now, into [out] (one per stem); false when nothing is playing. */
    fun levelsNow(out: FloatArray): Boolean

    fun playTracks(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false)
    fun playNext(tracks: List<Track>)
    fun addToQueue(tracks: List<Track>)
    fun removeAt(i: Int)
    fun move(from: Int, to: Int)
    fun clearUpNext()
    fun toggleShuffle()
    fun cycleRepeat()

    fun play()
    fun pause()
    fun togglePlay()
    fun next()
    fun previous()
    fun skipTo(i: Int, positionMs: Long = 0)
    fun skipBy(deltaMs: Long)
    fun seekTo(ms: Long)

    fun setStemLevel(name: String, level: Float)
    fun toggleMute(name: String)
    fun toggleSolo(name: String)
    fun applyMix(m: Map<String, StemSetting>)
    fun setMaster(v: Float)
}
