package com.example.desktopfortress.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.desktopfortress.data.repository.GameRepository
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.manager.BoardManager
import com.example.desktopfortress.manager.SpatialManager
import com.example.desktopfortress.manager.TowerManager
import com.example.desktopfortress.manager.LevelManager
import com.example.desktopfortress.manager.GameManager
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameViewModel(
    private val repository: GameRepository = InMemoryGameRepository,
) : ViewModel() {
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutablePermissionsGranted = MutableStateFlow(false)

    val uiState = combine(
        repository.gameState,
        SpatialManager.scanStatus,
        mutableMessage,
        mutablePermissionsGranted,
    ) { gameState, scanStatus, message, permissions ->
        GameUiState(gameState, scanStatus, message, permissions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameUiState(),
    )

    init {
        BoardManager.initialize()
        LevelManager.initialize()
        GameManager.initialize()
        viewModelScope.launch {
            EventBus.events.collect { event ->
                if (event is UserMessage) mutableMessage.value = event.message
            }
        }
    }

    fun onEvent(event: GameUiEvent) {
        when (event) {
            GameUiEvent.EnterCalibration -> enterCalibration()
            GameUiEvent.ConfirmPlacement -> confirmPlacement()
            GameUiEvent.LockBoardPreview -> GameManager.lockBoardPreview()
            GameUiEvent.ResumeBoardPreviewFollow -> GameManager.resumeBoardPreviewFollow()
            GameUiEvent.ResetBoardPreviewInFront -> GameManager.resetBoardPreviewInFront()
            GameUiEvent.UseFallback -> useFallback()
            is GameUiEvent.PermissionsResult -> onPermissionsResult(event.granted)
            is GameUiEvent.DragBoard -> BoardManager.dragByViewMeters(
                event.deltaHorizontalMeters,
                event.deltaVerticalMeters,
            )
            is GameUiEvent.ScaleBoard -> BoardManager.scaleBy(event.factor)
            is GameUiEvent.PreviewAtWorld -> BoardManager.previewWorldPosition(
                com.pico.spatial.core.math.Vector3(event.x, event.y, event.z),
            )
            is GameUiEvent.MoveBoardPreviewToWorld -> BoardManager.movePreviewToWorld(
                com.pico.spatial.core.math.Vector3(event.x, event.y, event.z),
            )
            // Buying only stores configuration in the first free slot. A
            // separate ray/slot action is required to enter the picked-up state.
            is GameUiEvent.BuyTower -> TowerManager.purchaseToSlot(event.type)
            GameUiEvent.StartFight -> GameManager.startOrResumeFight()
            GameUiEvent.PauseWave -> GameManager.pause()
            GameUiEvent.ResumeFight -> GameManager.startOrResumeFight()
            GameUiEvent.PauseGame -> pauseGame()
            GameUiEvent.ResumeGame -> resumeGame()
            GameUiEvent.ReturnToMenu -> returnToMenu()
            GameUiEvent.DismissMessage -> dismissMessage()
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        mutablePermissionsGranted.value = granted
        if (!granted) {
            mutableMessage.value = "相机权限被拒绝，将使用地面高度兜底棋盘"
            BoardManager.useFallbackSurface(SpatialManager.useFallbackNow("缺少相机权限"))
        }
    }

    fun enterCalibration() {
        GameManager.enterCalibration()
    }

    fun confirmPlacement() {
        if (GameManager.confirmBoardPlacement()) {
            mutableMessage.value = null
        } else {
            mutableMessage.value = if (!BoardManager.placementSurfaceReady.value) {
                "正在识别地面，请识别成功后再放置棋盘"
            } else {
                "棋盘当前无法锁定，请重试"
            }
        }
    }

    fun useFallback() {
        val fallback = SpatialManager.useFallbackNow("玩家选择地面高度兜底棋盘")
        BoardManager.useFallbackSurface(fallback)
    }

    fun pauseGame() {
        GameManager.pause()
    }

    fun resumeGame() {
        GameManager.resume()
    }

    fun returnToMenu() {
        GameManager.returnToMainMenu()
    }
    fun dismissMessage() { mutableMessage.value = null }

}
