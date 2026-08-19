package com.example.desktopfortress.audio

import android.media.AudioManager as AndroidAudioManager
import android.media.ToneGenerator
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.manager.BaseManager
import com.example.desktopfortress.data.local.PreferencesManager
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BgmPlaybackState { PLAYING, PAUSED, STOPPED }

object AudioManager : BaseManager() {
    private var toneGenerator: ToneGenerator? = null
    private var initialized = false
    private var lastAttackToneAt = 0L
    private val mutableBgmState = MutableStateFlow(BgmPlaybackState.STOPPED)
    val bgmState = mutableBgmState.asStateFlow()

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        val volume = (PreferencesManager.getSettings().effectsVolume * 100f).roundToInt().coerceIn(0, 100)
        toneGenerator = runCatching { ToneGenerator(AndroidAudioManager.STREAM_MUSIC, volume) }.getOrNull()
        initialized = true
    }

    fun playCalibrationSuccess() = play(ToneGenerator.TONE_PROP_ACK, 120)
    fun startBattleBgm() { mutableBgmState.value = BgmPlaybackState.PLAYING }
    fun pauseBattleBgm() { if (mutableBgmState.value == BgmPlaybackState.PLAYING) mutableBgmState.value = BgmPlaybackState.PAUSED }
    fun resumeBattleBgm() { if (mutableBgmState.value == BgmPlaybackState.PAUSED) mutableBgmState.value = BgmPlaybackState.PLAYING }
    fun stopBattleBgm() { mutableBgmState.value = BgmPlaybackState.STOPPED }
    fun playTowerPurchased() = play(ToneGenerator.TONE_PROP_PROMPT, 70)
    fun playTowerGrabbed() = play(ToneGenerator.TONE_PROP_BEEP2, 45)
    fun playTowerPlaced() = play(ToneGenerator.TONE_PROP_BEEP, 70)
    fun playTowerMerged() = play(ToneGenerator.TONE_PROP_ACK, 180)
    fun playTowerSold() = play(ToneGenerator.TONE_PROP_NACK, 90)
    fun playInteractionFailure() = play(ToneGenerator.TONE_PROP_NACK, 110)
    fun playAttack(type: TowerType) {
        val now = System.currentTimeMillis()
        if (now - lastAttackToneAt < 70L) return
        lastAttackToneAt = now
        val tone = when (type) {
            TowerType.ARCHER -> ToneGenerator.TONE_PROP_BEEP
            TowerType.BALLISTA -> ToneGenerator.TONE_DTMF_D
            TowerType.EXPLOSIVE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            TowerType.FROST -> ToneGenerator.TONE_DTMF_9
        }
        play(tone, 45)
    }

    override fun destroy() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        mutableBgmState.value = BgmPlaybackState.STOPPED
        initialized = false
        cancelScope()
    }

    private fun play(tone: Int, durationMs: Int) {
        if (!initialized) initialize()
        if (mutableBgmState.value == BgmPlaybackState.PAUSED) return
        runCatching { toneGenerator?.startTone(tone, durationMs) }
    }
}
