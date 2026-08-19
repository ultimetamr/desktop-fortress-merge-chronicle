package com.example.desktopfortress.domain.model

enum class PauseReason {
    PLAYER,
    APP_BACKGROUND,
    TRACKING_LOST,
}

sealed interface StateTransitionResult {
    data class Changed(val from: GameState, val to: GameState) : StateTransitionResult
    data class Rejected(val from: GameState, val requested: GameState, val reason: String) : StateTransitionResult
    data class Unchanged(val state: GameState) : StateTransitionResult
}

enum class SpatialTrackingState { IDLE, TRACKING, LOST }

data class GameDebugState(
    val panelVisible: Boolean = false,
    val framesPerSecond: Int = 0,
    val monsterCount: Int = 0,
    val towerCount: Int = 0,
    val usedMemoryMegabytes: Long = 0,
    val invincible: Boolean = false,
    val collisionBoxesVisible: Boolean = false,
    val networkAvailable: Boolean = true,
    val trackingState: SpatialTrackingState = SpatialTrackingState.IDLE,
)

/** Safe wave-boundary checkpoint. Spatial coordinates are intentionally recalibrated after restart. */
data class GameRecoveryCheckpoint(
    val levelId: Int,
    val waveNumber: Int,
    val endpointHealth: Int,
    val gold: Int,
    val savedAtEpochMillis: Long,
    val towers: List<TowerRecoveryItem> = emptyList(),
    val inventorySlots: List<TowerSlotRecoveryItem> = emptyList(),
)

data class TowerRecoveryItem(
    val type: TowerType,
    val level: Int,
    val row: Int,
    val column: Int,
)

data class TowerSlotRecoveryItem(
    val type: TowerType,
    val level: Int,
    val slotIndex: Int,
)
