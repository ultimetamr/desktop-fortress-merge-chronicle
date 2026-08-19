package com.example.desktopfortress.manager

import android.app.Application
import android.util.Log
import com.example.desktopfortress.audio.AudioManager
import com.example.desktopfortress.data.local.GameRecoveryStore
import com.example.desktopfortress.data.repository.GameRepository
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.domain.model.GameDebugState
import com.example.desktopfortress.domain.model.GameRecoveryCheckpoint
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.PauseReason
import com.example.desktopfortress.domain.model.SpatialTrackingState
import com.example.desktopfortress.domain.model.StateTransitionResult
import com.example.desktopfortress.domain.model.TowerRecoveryItem
import com.example.desktopfortress.domain.model.TowerSlotRecoveryItem
import com.example.desktopfortress.domain.model.BoardPreviewMode
import com.example.desktopfortress.platform.NetworkStatusMonitor
import com.example.desktopfortress.utils.CollisionDebug
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.GameStateChanged
import com.example.desktopfortress.utils.UserMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sole owner of global state transitions, game-loop orchestration and runtime safety policy. */
object GameManager : BaseManager(), LevelManager.FlowListener {
    const val BOARD_OUT_OF_VIEW_SECONDS = 5f
    const val BOARD_HORIZONTAL_HALF_FOV_DEGREES = 55f

    private val repository: GameRepository = InMemoryGameRepository
    private val initialized = AtomicBoolean(false)
    private val gameplayStageOpen = AtomicBoolean(false)
    private val gameplayStageSeenOnstage = AtomicBoolean(false)
    private val mutableDebugState = MutableStateFlow(GameDebugState())
    val debugState: StateFlow<GameDebugState> = mutableDebugState.asStateFlow()
    private val mutableRecovery = MutableStateFlow<GameRecoveryCheckpoint?>(null)
    val recovery: StateFlow<GameRecoveryCheckpoint?> = mutableRecovery.asStateFlow()

    private var pausedFrom = GameState.PREPARE
    private var pauseReason = PauseReason.PLAYER
    private var pendingAfterCalibration = GameState.PREPARE
    private var recoveryStore: GameRecoveryStore? = null
    private var networkMonitor: NetworkStatusMonitor? = null
    private var networkAvailable = true
    private var boardOutOfViewSeconds = 0f
    private var debugElapsedSeconds = 0f
    private var debugFrameCount = 0
    private var trackingLostHandled = false
    private var pendingTowerRecovery: List<TowerRecoveryItem> = emptyList()
    private var pendingInventoryRecovery: List<TowerSlotRecoveryItem> = emptyList()

    override fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        recreateScopeIfNeeded()
        LevelManager.initialize()
        LevelManager.setFlowListener(this)
        UIManager.initialize()
        managerScope.launch {
            UIManager.trackingState.collect { state ->
                mutableDebugState.value = mutableDebugState.value.copy(trackingState = state)
                when (state) {
                    SpatialTrackingState.LOST -> handleTrackingLost()
                    SpatialTrackingState.TRACKING -> handleTrackingRecovered()
                    SpatialTrackingState.IDLE -> Unit
                }
            }
        }
        syncState(repository.gameState.value)
        log("initialized in ${repository.gameState.value}")
    }

    fun initialize(application: Application) {
        initialize()
        if (recoveryStore == null) {
            recoveryStore = GameRecoveryStore(application).also { mutableRecovery.value = it.read() }
        }
        if (networkMonitor == null) {
            networkMonitor = NetworkStatusMonitor(application) { available -> onNetworkChanged(available) }
                .also(NetworkStatusMonitor::start)
        }
    }

    fun requestTransition(target: GameState): StateTransitionResult {
        initialize()
        val current = repository.gameState.value
        if (current == target) return StateTransitionResult.Unchanged(current)
        if (target !in LEGAL_TRANSITIONS.getValue(current)) {
            val reason = "非法状态跳转：$current → $target"
            log(reason, warning = true)
            EventBus.tryEmit(UserMessage(reason))
            return StateTransitionResult.Rejected(current, target, reason)
        }
        if (!prepareTransition(current, target)) {
            val reason = "状态入口条件未满足：$current → $target"
            EventBus.tryEmit(UserMessage(reason))
            return StateTransitionResult.Rejected(current, target, reason)
        }
        repository.updateGameState(target)
        syncState(target)
        EventBus.tryEmit(GameStateChanged(target))
        persistCheckpointFor(target)
        log("state $current -> $target")
        return StateTransitionResult.Changed(current, target)
    }

    fun showLevelSelect() = requestTransition(GameState.LEVEL_SELECT)

    fun returnFromLevelSelect() = requestTransition(GameState.MAIN_MENU)

    fun enterCalibration(): StateTransitionResult {
        pendingAfterCalibration = GameState.PREPARE
        return requestTransition(GameState.CALIBRATING)
    }

    fun confirmBoardPlacement(): Boolean {
        if (repository.gameState.value != GameState.CALIBRATING) return false
        if (BoardManager.previewMode.value != BoardPreviewMode.WORLD_LOCKED) {
            if (!BoardManager.lockPreview()) return false
        }
        if (!BoardManager.lockPlacement()) {
            EventBus.tryEmit(UserMessage("棋盘尚未完成地面识别，暂时无法锁定"))
            return false
        }
        if (pendingTowerRecovery.isNotEmpty() || pendingInventoryRecovery.isNotEmpty()) {
            TowerManager.restoreCheckpointTowers(pendingTowerRecovery)
            TowerManager.restoreCheckpointInventory(pendingInventoryRecovery)
            pendingTowerRecovery = emptyList()
            pendingInventoryRecovery = emptyList()
        }
        AudioManager.playCalibrationSuccess()
        return requestTransition(pendingAfterCalibration) is StateTransitionResult.Changed
    }

    fun lockBoardPreview(): Boolean {
        if (repository.gameState.value != GameState.CALIBRATING) return false
        val locked = BoardManager.lockPreview()
        if (locked) EventBus.tryEmit(UserMessage("预览已锁定，可拖拽或缩放微调"))
        return locked
    }

    fun resumeBoardPreviewFollow(): Boolean {
        if (repository.gameState.value != GameState.CALIBRATING) return false
        val following = BoardManager.resumePreviewFollow()
        if (following) EventBus.tryEmit(UserMessage("棋盘预览已恢复视线跟随"))
        return following
    }

    fun resetBoardPreviewInFront(): Boolean {
        if (repository.gameState.value != GameState.CALIBRATING) return false
        val placed = BoardManager.placePreviewInFront(UIManager.headPose.value)
        if (placed) EventBus.tryEmit(UserMessage("棋盘已重置到正前方 2 米，可直接确认放置"))
        return placed
    }

    fun startOrResumeFight(): Boolean {
        val current = repository.gameState.value
        if (current !in setOf(GameState.PREPARE, GameState.WAVE_PAUSE)) return false
        return requestTransition(GameState.FIGHTING) is StateTransitionResult.Changed
    }

    fun pause(reason: PauseReason = PauseReason.PLAYER): Boolean {
        val current = repository.gameState.value
        if (current == GameState.PAUSED || current == GameState.SETTLE) return false
        pausedFrom = current
        pauseReason = reason
        if (current in setOf(GameState.PREPARE, GameState.WAVE_PAUSE)) persistCheckpointFor(current)
        if (current == GameState.FIGHTING) LevelManager.pauseWave()
        return requestTransition(GameState.PAUSED) is StateTransitionResult.Changed
    }

    fun resume(): Boolean {
        if (repository.gameState.value != GameState.PAUSED) return false
        if (pauseReason == PauseReason.TRACKING_LOST && UIManager.trackingState.value == SpatialTrackingState.LOST) {
            EventBus.tryEmit(UserMessage("空间跟踪尚未恢复，暂时无法继续"))
            return false
        }
        return requestTransition(pausedFrom) is StateTransitionResult.Changed
    }

    fun returnToMainMenu(): StateTransitionResult {
        val current = repository.gameState.value
        if (current == GameState.MAIN_MENU) return StateTransitionResult.Unchanged(current)
        SpatialManager.stopSpatialPerception()
        MonsterManager.recycleAll()
        TowerManager.cancelDrag()
        AudioManager.stopBattleBgm()
        clearRecoveryCheckpoint()
        onGameplayStageClosed()
        pausedFrom = GameState.PREPARE
        pauseReason = PauseReason.PLAYER
        return requestTransition(GameState.MAIN_MENU)
    }

    fun onAppBackgrounded() {
        // MainActivity is the Planar MainWindow host. PICO pauses it whenever the
        // Mixed GameStage takes focus, which is not an application background
        // event. The Stage's own isOnstage signal handles real visibility loss.
        if (gameplayStageOpen.get()) {
            log("ignored MainWindow pause while GameStage is open")
            return
        }
        if (repository.gameState.value in ACTIVE_STAGE_STATES) pause(PauseReason.APP_BACKGROUND)
    }

    fun onGameplayStageOpened() {
        gameplayStageOpen.set(true)
        gameplayStageSeenOnstage.set(false)
        log("GameStage opened")
    }

    fun onGameplayStageOnstageChanged(isOnstage: Boolean) {
        if (isOnstage) {
            gameplayStageOpen.set(true)
            gameplayStageSeenOnstage.set(true)
            return
        }
        if (
            gameplayStageOpen.get() &&
            gameplayStageSeenOnstage.get() &&
            repository.gameState.value in ACTIVE_STAGE_STATES
        ) {
            pause(PauseReason.APP_BACKGROUND)
            log("GameStage left onstage; gameplay paused")
        }
    }

    fun onGameplayStageClosed() {
        gameplayStageOpen.set(false)
        gameplayStageSeenOnstage.set(false)
        log("GameStage closed")
    }

    /** Foreground never auto-resumes; the player confirms from the pause panel. */
    fun onAppForegrounded() {
        if (repository.gameState.value == GameState.PAUSED) {
            EventBus.tryEmit(UserMessage("游戏保持暂停，请确认空间安全后继续"))
        }
    }

    fun update(deltaSeconds: Float) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f) return
        UIManager.update(deltaSeconds)
        if (repository.gameState.value == GameState.CALIBRATING) {
            BoardManager.updateCalibrationPreview(deltaSeconds, UIManager.headPose.value)
        }
        if (repository.gameState.value == GameState.FIGHTING) {
            LevelManager.update(deltaSeconds)
        }
        if (repository.gameState.value in setOf(GameState.PREPARE, GameState.FIGHTING, GameState.WAVE_PAUSE)) {
            // TowerManager guards combat logic by state but always advances
            // short-lived placement/merge/sell visuals. Without this call in
            // editable phases, ground pulse rings never expired.
            TowerManager.update(deltaSeconds)
        }
        updateBoardVisibility(deltaSeconds)
        updateDebug(deltaSeconds)
    }

    fun setDebugPanelVisible(visible: Boolean) {
        mutableDebugState.value = mutableDebugState.value.copy(panelVisible = visible)
    }

    fun setInvincible(enabled: Boolean) {
        LevelManager.setInvincible(enabled)
        mutableDebugState.value = mutableDebugState.value.copy(invincible = enabled)
        log("debug invincible=$enabled")
    }

    fun fillGoldForDebug() {
        GoldManager.fillForDebug()
        log("debug gold filled")
    }

    fun skipCurrentLevelForDebug() {
        if (repository.gameState.value in ACTIVE_STAGE_STATES) {
            LevelManager.debugCompleteLevel()
            log("debug level skipped")
        }
    }

    fun setCollisionBoxesVisible(visible: Boolean) {
        CollisionDebug.setEnabled(visible)
        mutableDebugState.value = mutableDebugState.value.copy(collisionBoxesVisible = visible)
    }

    fun restoreRecoveryCheckpoint(): Boolean {
        val checkpoint = mutableRecovery.value ?: return false
        return runCatching {
            LevelManager.selectLevel(checkpoint.levelId)
            LevelManager.restoreSafeCheckpoint(checkpoint.waveNumber, checkpoint.endpointHealth)
            GoldManager.restoreCheckpoint(checkpoint.gold)
            BoardManager.beginGroundCalibration()
            pendingTowerRecovery = checkpoint.towers
            pendingInventoryRecovery = checkpoint.inventorySlots
            pendingAfterCalibration = GameState.WAVE_PAUSE
            val current = repository.gameState.value
            if (current != GameState.MAIN_MENU) return@runCatching false
            requestTransition(GameState.LEVEL_SELECT)
            requestTransition(GameState.CALIBRATING)
            EventBus.tryEmit(UserMessage("已载入第 ${checkpoint.levelId} 关安全检查点，请重新校准地面"))
            true
        }.getOrElse {
            log("recovery failed", it, warning = true)
            clearRecoveryCheckpoint()
            false
        }
    }

    fun flushRecoveryCheckpoint() {
        if (repository.gameState.value in setOf(GameState.PREPARE, GameState.WAVE_PAUSE)) {
            persistCheckpointFor(repository.gameState.value)
        } else {
            mutableRecovery.value?.let { recoveryStore?.write(it) }
        }
    }

    override fun onWaveCompleted(levelId: Int, waveNumber: Int, hasNextWave: Boolean) {
        log("wave completed level=$levelId wave=$waveNumber hasNext=$hasNextWave")
        if (hasNextWave) requestTransition(GameState.WAVE_PAUSE)
    }

    override fun onLevelCompleted(levelId: Int) {
        log("level completed level=$levelId")
        requestTransition(GameState.SETTLE)
    }

    override fun onLevelFailed(levelId: Int) {
        log("level failed level=$levelId", warning = true)
        requestTransition(GameState.SETTLE)
    }

    override fun destroy() {
        networkMonitor?.stop()
        networkMonitor = null
        LevelManager.setFlowListener(null)
        gameplayStageOpen.set(false)
        gameplayStageSeenOnstage.set(false)
        pausedFrom = GameState.PREPARE
        pauseReason = PauseReason.PLAYER
        initialized.set(false)
        cancelScope()
    }

    private fun prepareTransition(from: GameState, to: GameState): Boolean {
        when (to) {
            GameState.CALIBRATING -> {
                if (pendingAfterCalibration == GameState.PREPARE) LevelManager.prepareSelectedLevel()
                BoardManager.beginGroundCalibration()
                SpatialManager.startSpatialPerception()
            }
            GameState.FIGHTING -> {
                if (!LevelManager.startOrResumeWave()) return false
                TowerManager.cancelDrag()
                if (from == GameState.PAUSED) AudioManager.resumeBattleBgm() else AudioManager.startBattleBgm()
            }
            GameState.PAUSED -> {
                TowerManager.cancelDrag()
                AudioManager.pauseBattleBgm()
            }
            GameState.WAVE_PAUSE -> AudioManager.pauseBattleBgm()
            GameState.SETTLE -> {
                AudioManager.stopBattleBgm()
                MonsterManager.recycleAll()
                TowerManager.stopCombat()
                clearRecoveryCheckpoint()
            }
            GameState.MAIN_MENU -> AudioManager.stopBattleBgm()
            else -> Unit
        }
        return true
    }

    private fun syncState(state: GameState) {
        UIManager.syncGameState(state)
    }

    private fun updateBoardVisibility(deltaSeconds: Float) {
        if (repository.gameState.value !in ACTIVE_STAGE_STATES || !BoardManager.board.value.isLocked ||
            UIManager.trackingState.value != SpatialTrackingState.TRACKING
        ) {
            boardOutOfViewSeconds = 0f
            return
        }
        val pose = UIManager.headPose.value
        val center = BoardManager.board.value.transform.worldCenter
        val dx = center.x - pose.position.x
        val dz = center.z - pose.position.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)
        val dot = if (length <= .001f) 1f else
            (dx / length) * pose.horizontalForward.x + (dz / length) * pose.horizontalForward.z
        val visible = dot >= cos(Math.toRadians(BOARD_HORIZONTAL_HALF_FOV_DEGREES.toDouble())).toFloat()
        boardOutOfViewSeconds = if (visible) 0f else boardOutOfViewSeconds + deltaSeconds
        if (boardOutOfViewSeconds >= BOARD_OUT_OF_VIEW_SECONDS) {
            val delta = BoardManager.recenterInFront(pose)
            TowerManager.translateWorld(delta)
            MonsterManager.translateWorld(delta)
            boardOutOfViewSeconds = 0f
            EventBus.tryEmit(UserMessage("棋盘离开视野超过 5 秒，已复位到正前方"))
            log("board safety recenter")
        }
    }

    private fun updateDebug(deltaSeconds: Float) {
        debugElapsedSeconds += deltaSeconds
        debugFrameCount++
        if (debugElapsedSeconds < .5f) return
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        mutableDebugState.value = mutableDebugState.value.copy(
            framesPerSecond = (debugFrameCount / debugElapsedSeconds).toInt().coerceAtLeast(0),
            monsterCount = MonsterManager.activeCount(),
            towerCount = TowerManager.towers.value.size,
            usedMemoryMegabytes = usedMb.coerceAtLeast(0L),
            networkAvailable = networkAvailable,
            trackingState = UIManager.trackingState.value,
        )
        debugElapsedSeconds = 0f
        debugFrameCount = 0
    }

    private fun handleTrackingLost() {
        if (trackingLostHandled) return
        trackingLostHandled = true
        if (repository.gameState.value in ACTIVE_STAGE_STATES) {
            pause(PauseReason.TRACKING_LOST)
            EventBus.tryEmit(UserMessage("空间跟踪丢失，游戏已暂停"))
            log("tracking lost", warning = true)
        }
    }

    private fun handleTrackingRecovered() {
        if (!trackingLostHandled) return
        trackingLostHandled = false
        EventBus.tryEmit(UserMessage("空间跟踪已恢复，可手动继续游戏"))
        log("tracking recovered")
    }

    private fun onNetworkChanged(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        mutableDebugState.value = mutableDebugState.value.copy(networkAvailable = available)
        EventBus.tryEmit(UserMessage(if (available) "网络已恢复" else "网络不可用；本地对局不受影响"))
        log("network available=$available", warning = !available)
    }

    private fun persistCheckpointFor(state: GameState) {
        if (state !in CHECKPOINT_STATES) return
        val level = LevelManager.runtimeState.value
        if (level.currentWave <= 0 || level.endpointHealth <= 0) return
        val checkpoint = GameRecoveryCheckpoint(
            levelId = level.levelId,
            waveNumber = level.currentWave,
            endpointHealth = level.endpointHealth,
            gold = GoldManager.getCurrentGold(),
            savedAtEpochMillis = System.currentTimeMillis(),
            towers = TowerManager.towers.value.map {
                TowerRecoveryItem(it.type, it.level, it.coordinate.row, it.coordinate.column)
            },
            inventorySlots = TowerManager.recoveryInventorySlots(),
        )
        mutableRecovery.value = checkpoint
        recoveryStore?.write(checkpoint)
    }

    private fun clearRecoveryCheckpoint() {
        mutableRecovery.value = null
        recoveryStore?.clear()
    }

    private fun log(message: String, throwable: Throwable? = null, warning: Boolean = false) {
        runCatching {
            when {
                throwable != null -> Log.e(TAG, message, throwable)
                warning -> Log.w(TAG, message)
                else -> Log.i(TAG, message)
            }
        }
    }

    private const val TAG = "DesktopFortress/Game"
    private val ACTIVE_STAGE_STATES = setOf(
        GameState.CALIBRATING,
        GameState.PREPARE,
        GameState.FIGHTING,
        GameState.WAVE_PAUSE,
    )
    private val CHECKPOINT_STATES = setOf(GameState.PREPARE, GameState.FIGHTING, GameState.WAVE_PAUSE)
    private val LEGAL_TRANSITIONS = mapOf(
        GameState.MAIN_MENU to setOf(GameState.LEVEL_SELECT, GameState.PAUSED),
        GameState.LEVEL_SELECT to setOf(GameState.MAIN_MENU, GameState.CALIBRATING, GameState.PAUSED),
        GameState.CALIBRATING to setOf(GameState.PREPARE, GameState.WAVE_PAUSE, GameState.PAUSED, GameState.MAIN_MENU),
        GameState.PREPARE to setOf(GameState.FIGHTING, GameState.PAUSED, GameState.MAIN_MENU),
        GameState.FIGHTING to setOf(GameState.WAVE_PAUSE, GameState.SETTLE, GameState.PAUSED, GameState.MAIN_MENU),
        GameState.WAVE_PAUSE to setOf(GameState.FIGHTING, GameState.SETTLE, GameState.PAUSED, GameState.MAIN_MENU),
        GameState.SETTLE to setOf(GameState.CALIBRATING, GameState.LEVEL_SELECT, GameState.MAIN_MENU, GameState.PAUSED),
        GameState.PAUSED to GameState.entries.filterTo(linkedSetOf()) { it != GameState.PAUSED },
    )
}
