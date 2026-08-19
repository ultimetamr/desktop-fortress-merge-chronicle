package com.example.desktopfortress.ui.game

import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.PlaneScanStatus

data class GameUiState(
    val gameState: GameState = GameState.MAIN_MENU,
    val scanStatus: PlaneScanStatus = PlaneScanStatus.Idle,
    val message: String? = null,
    val permissionsGranted: Boolean = false,
)

sealed interface GameUiEvent {
    data object EnterCalibration : GameUiEvent
    data object ConfirmPlacement : GameUiEvent
    data object LockBoardPreview : GameUiEvent
    data object ResumeBoardPreviewFollow : GameUiEvent
    data object ResetBoardPreviewInFront : GameUiEvent
    data object UseFallback : GameUiEvent
    data class PermissionsResult(val granted: Boolean) : GameUiEvent
    data class DragBoard(
        val deltaHorizontalMeters: Float,
        val deltaVerticalMeters: Float,
    ) : GameUiEvent
    data class ScaleBoard(val factor: Float) : GameUiEvent
    data class PreviewAtWorld(val x: Float, val y: Float, val z: Float) : GameUiEvent
    data class MoveBoardPreviewToWorld(val x: Float, val y: Float, val z: Float) : GameUiEvent
    data class BuyTower(val type: com.example.desktopfortress.domain.model.TowerType) : GameUiEvent
    data object StartFight : GameUiEvent
    data object PauseWave : GameUiEvent
    data object ResumeFight : GameUiEvent
    data object PauseGame : GameUiEvent
    data object ResumeGame : GameUiEvent
    data object ReturnToMenu : GameUiEvent
    data object DismissMessage : GameUiEvent
}
