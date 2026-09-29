package com.vieneu.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateMachineTest {

    @Test
    fun lifecycleIdleToPlayToPauseToStop() {
        val sm = PlayerStateMachine()
        assertEquals(AudioPlayer.State.IDLE, sm.state)
        sm.onLoading()
        assertEquals(AudioPlayer.State.LOADING, sm.state)
        sm.onPlay()
        assertEquals(AudioPlayer.State.PLAYING, sm.state)
        sm.onPause()
        assertEquals(AudioPlayer.State.PAUSED, sm.state)
        sm.onPlay()
        assertEquals(AudioPlayer.State.PLAYING, sm.state)
        sm.onStop()
        assertEquals(AudioPlayer.State.STOPPED, sm.state)
        assertTrue(sm.canCancel())
    }

    @Test
    fun ignorePlayFromIdle() {
        val sm = PlayerStateMachine()
        sm.onPlay()
        assertEquals(AudioPlayer.State.IDLE, sm.state)
    }
}
