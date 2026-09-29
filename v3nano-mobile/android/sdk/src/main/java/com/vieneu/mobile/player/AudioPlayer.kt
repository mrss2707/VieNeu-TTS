package com.vieneu.mobile.player

/**
 * Playback bridge (04 §4.4) — AudioTrack streaming 24 kHz mono.
 * Interface thuần JVM để test queue/pipeline; impl Android nằm ở module app/SDK android source set.
 */
interface AudioPlayer {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, STOPPED }

    val state: State

    fun prepare(sampleRate: Int, channels: Int = 1)

    /** Enqueue PCM chunk để phát (thứ tự FIFO). */
    fun submit(pcm: FloatArray)

    fun play()
    fun pause()
    fun stop()

    /** Số chunk đang chờ phát trong buffer. */
    fun bufferedChunks(): Int

    fun setOnUnderrunListener(listener: (() -> Unit)?)
}

/**
 * PlayerState machine (01 §1.2) — tách khỏi AudioTrack để unit-test lifecycle.
 */
class PlayerStateMachine {
    var state: AudioPlayer.State = AudioPlayer.State.IDLE
        private set

    fun onLoading() {
        if (state == AudioPlayer.State.IDLE || state == AudioPlayer.State.STOPPED) {
            state = AudioPlayer.State.LOADING
        }
    }

    fun onPlay() {
        if (state == AudioPlayer.State.LOADING || state == AudioPlayer.State.PAUSED ||
            state == AudioPlayer.State.PLAYING
        ) {
            state = AudioPlayer.State.PLAYING
        }
    }

    fun onPause() {
        if (state == AudioPlayer.State.PLAYING) state = AudioPlayer.State.PAUSED
    }

    fun onStop() {
        state = AudioPlayer.State.STOPPED
    }

    fun canCancel(): Boolean = state != AudioPlayer.State.IDLE
}
