package com.example.desktopfortress.utils

import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.DevelopType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface GameEvent

data class GameStateChanged(val state: GameState) : GameEvent
data class UserMessage(val message: String) : GameEvent
data class WaveCompleted(val levelId: Int, val waveNumber: Int) : GameEvent
data class LevelCompleted(
    val levelId: Int,
    val stars: Int,
    val bestStars: Int,
    val crystalCoreReward: Int,
) : GameEvent
data class LevelFailed(val levelId: Int) : GameEvent
data class GoldChanged(val previous: Int, val current: Int) : GameEvent
data class DevelopUpgraded(val type: DevelopType, val level: Int, val bonusRatio: Float) : GameEvent
data class CodexUnlocked(val id: String, val category: String) : GameEvent
data class AchievementUnlocked(val id: String, val name: String, val crystalReward: Int) : GameEvent

object EventBus {
    private val mutableEvents = MutableSharedFlow<GameEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GameEvent> = mutableEvents.asSharedFlow()

    suspend fun emit(event: GameEvent) = mutableEvents.emit(event)
    fun tryEmit(event: GameEvent): Boolean = mutableEvents.tryEmit(event)
}
