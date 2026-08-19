package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.BaseMonster
import com.example.desktopfortress.domain.model.LevelCatalog
import com.example.desktopfortress.domain.model.LevelConfig
import com.example.desktopfortress.domain.model.LevelRuntimeState
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.WaveRuntimeState
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.LevelCompleted
import com.example.desktopfortress.utils.LevelFailed
import com.example.desktopfortress.utils.WaveCompleted
import com.pico.spatial.core.math.Vector3
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LevelManager : BaseManager(), MonsterManager.Listener {
    interface FlowListener {
        fun onWaveCompleted(levelId: Int, waveNumber: Int, hasNextWave: Boolean)
        fun onLevelCompleted(levelId: Int)
        fun onLevelFailed(levelId: Int)
    }
    const val BASE_ENDPOINT_HEALTH = 10
    private const val BASE_WAVE_HEAL_RATIO = .05f
    private const val MAX_WAVE_HEAL_RATIO = .10f

    private val queuedTypes = ArrayDeque<MonsterType>()
    private val mutableRuntimeState = MutableStateFlow(LevelRuntimeState())
    val runtimeState: StateFlow<LevelRuntimeState> = mutableRuntimeState.asStateFlow()
    private var selectedLevel: LevelConfig = LevelCatalog.get(1)
    private var initialized = false
    private var permanentEndpointHealthBonus = 0
    private var spawnTimerSeconds = 0f
    private var pausedFrom = WaveRuntimeState.IDLE
    private var fightingElapsedSeconds = 0f
    private var invincible = false
    private var flowListener: FlowListener? = null

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        MonsterManager.initialize()
        MonsterManager.setListener(this)
        selectLevel(selectedLevel.levelId)
        initialized = true
    }

    fun selectLevel(levelId: Int): LevelConfig {
        require(PreferencesManager.getLevelData(levelId).unlocked) { "Level $levelId is locked" }
        selectedLevel = LevelCatalog.get(levelId)
        MonsterManager.configureActiveCap(selectedLevel.activeMonsterCap)
        BoardManager.configurePath(selectedLevel.pathCoordinates)
        val endpointMax = DevelopManager.applyBonus(BASE_ENDPOINT_HEALTH, DevelopType.CORE_HEALTH) +
            permanentEndpointHealthBonus
        mutableRuntimeState.value = LevelRuntimeState(
            levelId = selectedLevel.levelId,
            totalWaves = selectedLevel.waveCount,
            endpointHealth = endpointMax,
            maxEndpointHealth = endpointMax,
            bestStars = PreferencesManager.getLevelData(selectedLevel.levelId).bestStars,
            crystalCores = PreferencesManager.getCrystalCores(),
        )
        return selectedLevel
    }

    fun setPermanentEndpointHealthBonus(bonus: Int) {
        permanentEndpointHealthBonus = bonus.coerceAtLeast(0)
    }

    fun setFlowListener(listener: FlowListener?) {
        flowListener = listener
    }

    fun setInvincible(enabled: Boolean) {
        invincible = enabled
    }

    /** Creates a clean level session before desktop calibration begins. */
    fun prepareSelectedLevel() {
        queuedTypes.clear()
        MonsterManager.recycleAll()
        TowerManager.resetSession()
        GoldManager.initLevel(selectedLevel.levelId)
        MonsterManager.configureActiveCap(selectedLevel.activeMonsterCap)
        BoardManager.configurePath(selectedLevel.pathCoordinates)
        val developedCore = DevelopManager.applyBonus(BASE_ENDPOINT_HEALTH, DevelopType.CORE_HEALTH)
        val endpointMax = developedCore + permanentEndpointHealthBonus
        fightingElapsedSeconds = 0f
        mutableRuntimeState.value = LevelRuntimeState(
            levelId = selectedLevel.levelId,
            currentWave = 1,
            totalWaves = selectedLevel.waveCount,
            waveState = WaveRuntimeState.READY,
            endpointHealth = endpointMax,
            maxEndpointHealth = endpointMax,
            bestStars = PreferencesManager.getLevelData(selectedLevel.levelId).bestStars,
            crystalCores = PreferencesManager.getCrystalCores(),
        )
    }

    fun startOrResumeWave(): Boolean {
        val current = mutableRuntimeState.value
        when (current.waveState) {
            WaveRuntimeState.READY, WaveRuntimeState.BETWEEN_WAVES -> {
                enqueueWave(current.currentWave)
                mutableRuntimeState.value = current.copy(
                    waveState = WaveRuntimeState.SPAWNING,
                    queuedMonsters = queuedTypes.size,
                )
                spawnTimerSeconds = 0f
            }
            WaveRuntimeState.MANUAL_PAUSED -> {
                mutableRuntimeState.value = current.copy(waveState = pausedFrom)
            }
            else -> return false
        }
        return true
    }

    fun pauseWave(): Boolean {
        val current = mutableRuntimeState.value
        if (current.waveState !in setOf(WaveRuntimeState.SPAWNING, WaveRuntimeState.WAITING_CLEAR)) return false
        pausedFrom = current.waveState
        mutableRuntimeState.value = current.copy(waveState = WaveRuntimeState.MANUAL_PAUSED)
        return true
    }

    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || !deltaSeconds.isFinite()) return
        val state = mutableRuntimeState.value.waveState
        if (state !in setOf(WaveRuntimeState.SPAWNING, WaveRuntimeState.WAITING_CLEAR)) return

        fightingElapsedSeconds += deltaSeconds

        if (state == WaveRuntimeState.SPAWNING) updateSpawning(deltaSeconds)
        MonsterManager.update(deltaSeconds)
        val current = mutableRuntimeState.value
        mutableRuntimeState.value = current.copy(
            activeMonsters = MonsterManager.activeCount(),
            queuedMonsters = queuedTypes.size,
        )
        if (mutableRuntimeState.value.waveState == WaveRuntimeState.WAITING_CLEAR &&
            MonsterManager.activeCount() == 0
        ) completeCurrentWave()
    }

    override fun onMonsterKilled(monster: BaseMonster, goldReward: Int) {
        GoldManager.addGold(DevelopManager.applyBonus(goldReward, DevelopType.KILL_GOLD))
        PreferencesManager.incrementKills()
        AchievementManager.evaluateAll()
    }

    override fun onEndpointHit(monster: BaseMonster, damage: Int) {
        damageEndpoint(damage)
    }

    override fun onRemoteEndpointAttack(monster: BaseMonster, damage: Int) {
        damageEndpoint(damage)
    }

    fun calculateStars(
        endpointHealth: Int,
        maxEndpointHealth: Int,
        levelId: Int = mutableRuntimeState.value.levelId,
    ): Int {
        if (maxEndpointHealth <= 0 || endpointHealth <= 0) return 0
        val ratio = endpointHealth.toFloat() / maxEndpointHealth
        val config = LevelCatalog.get(levelId)
        return when {
            ratio >= config.threeStarHealthRatio -> 3
            ratio >= config.twoStarHealthRatio -> 2
            else -> 1
        }
    }

    override fun destroy() {
        queuedTypes.clear()
        MonsterManager.setListener(null)
        MonsterManager.recycleAll()
        flowListener = null
        invincible = false
        initialized = false
        cancelScope()
    }

    private fun enqueueWave(waveNumber: Int) {
        queuedTypes.clear()
        val wave = selectedLevel.waves.getOrNull(waveNumber - 1) ?: return
        wave.groups.forEach { group -> repeat(group.count) { queuedTypes.addLast(group.type) } }
    }

    private fun updateSpawning(deltaSeconds: Float) {
        spawnTimerSeconds -= deltaSeconds
        while (spawnTimerSeconds <= 0f && queuedTypes.isNotEmpty()) {
            val nextType = requireNotNull(queuedTypes.peekFirst())
            val spawned = MonsterManager.spawn(
                type = nextType,
                path = currentWorldPath(),
                healthMultiplier = selectedLevel.healthMultiplier,
                speedMultiplier = selectedLevel.speedMultiplier,
                desktopHeight = SpatialManager.getGroundHeight(),
                levelId = selectedLevel.levelId,
            )
            if (spawned == null) {
                spawnTimerSeconds = 0f
                break
            }
            queuedTypes.removeFirst()
            spawnTimerSeconds += selectedLevel.spawnIntervalSeconds
        }
        if (queuedTypes.isEmpty()) {
            mutableRuntimeState.value = mutableRuntimeState.value.copy(
                waveState = WaveRuntimeState.WAITING_CLEAR,
                queuedMonsters = 0,
            )
        }
    }

    private fun currentWorldPath(): List<Vector3> {
        val board = BoardManager.board.value
        return board.pathPoints.map { point ->
            val world = board.localToWorld(point.localPosition)
            Vector3(world.x, SpatialManager.getGroundHeight(), world.z)
        }
    }

    private fun damageEndpoint(damage: Int) {
        if (invincible || damage <= 0 || mutableRuntimeState.value.waveState == WaveRuntimeState.FAILED) return
        val health = (mutableRuntimeState.value.endpointHealth - damage).coerceAtLeast(0)
        mutableRuntimeState.value = mutableRuntimeState.value.copy(endpointHealth = health)
        if (health == 0) failLevel()
    }

    private fun completeCurrentWave() {
        val current = mutableRuntimeState.value
        GoldManager.addGold(selectedLevel.waveClearGold)
        val requestedHeal = waveHealAmount(current.maxEndpointHealth)
        val healed = current.copy(
            endpointHealth = (current.endpointHealth + requestedHeal)
                .coerceAtMost(current.maxEndpointHealth),
        )
        mutableRuntimeState.value = healed
        EventBus.tryEmit(WaveCompleted(current.levelId, current.currentWave))
        if (current.currentWave >= current.totalWaves) {
            completeLevel()
        } else {
            mutableRuntimeState.value = healed.copy(
                currentWave = current.currentWave + 1,
                waveState = WaveRuntimeState.BETWEEN_WAVES,
                activeMonsters = 0,
                queuedMonsters = 0,
            )
            flowListener?.onWaveCompleted(current.levelId, current.currentWave, true)
        }
    }

    private fun completeLevel() {
        val current = mutableRuntimeState.value
        val stars = calculateStars(current.endpointHealth, current.maxEndpointHealth)
        val previousBest = PreferencesManager.getLevelData(current.levelId).bestStars
        val saved = PreferencesManager.recordLevelCompletion(
            levelId = current.levelId,
            stars = stars,
            clearMillis = (fightingElapsedSeconds * 1000f).toLong().coerceAtLeast(1L),
            perfect = current.endpointHealth >= current.maxEndpointHealth,
        )
        val crystalReward = if (previousBest < 3 && stars == 3) {
            selectedLevel.fullStarCrystalReward
        } else 0
        if (crystalReward > 0) PreferencesManager.addCrystalCores(crystalReward)
        GoldManager.addGold(selectedLevel.completionGold)
        AchievementManager.evaluateAll()
        mutableRuntimeState.value = current.copy(
            waveState = WaveRuntimeState.COMPLETED,
            earnedStars = stars,
            bestStars = saved.bestStars,
            crystalCores = PreferencesManager.getCrystalCores(),
            activeMonsters = 0,
            queuedMonsters = 0,
        )
        EventBus.tryEmit(LevelCompleted(current.levelId, stars, saved.bestStars, crystalReward))
        flowListener?.onLevelCompleted(current.levelId)
    }

    internal fun waveHealAmount(maxEndpointHealth: Int): Int {
        if (maxEndpointHealth <= 0) return 0
        val healRatio = (BASE_WAVE_HEAL_RATIO + DevelopManager.getWaveHealBonusRatio())
            .coerceAtMost(MAX_WAVE_HEAL_RATIO)
        val requestedHeal = ceil(maxEndpointHealth * healRatio).toInt()
        val healCap = ceil(maxEndpointHealth * MAX_WAVE_HEAL_RATIO).toInt()
        return minOf(requestedHeal, healCap).coerceAtLeast(0)
    }

    private fun failLevel() {
        queuedTypes.clear()
        MonsterManager.recycleAll()
        val current = mutableRuntimeState.value
        mutableRuntimeState.value = current.copy(
            waveState = WaveRuntimeState.FAILED,
            activeMonsters = 0,
            queuedMonsters = 0,
            earnedStars = 0,
        )
        EventBus.tryEmit(LevelFailed(current.levelId))
        flowListener?.onLevelFailed(current.levelId)
    }

    fun restoreSafeCheckpoint(waveNumber: Int, endpointHealth: Int) {
        prepareSelectedLevel()
        val current = mutableRuntimeState.value
        mutableRuntimeState.value = current.copy(
            currentWave = waveNumber.coerceIn(1, current.totalWaves.coerceAtLeast(1)),
            waveState = WaveRuntimeState.BETWEEN_WAVES,
            endpointHealth = endpointHealth.coerceIn(1, current.maxEndpointHealth),
        )
    }

    fun debugCompleteLevel() {
        if (mutableRuntimeState.value.waveState in setOf(WaveRuntimeState.COMPLETED, WaveRuntimeState.FAILED)) return
        queuedTypes.clear()
        MonsterManager.recycleAll()
        completeLevel()
    }
}
