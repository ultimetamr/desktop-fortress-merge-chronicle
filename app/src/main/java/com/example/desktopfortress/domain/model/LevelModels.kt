package com.example.desktopfortress.domain.model

import kotlin.math.pow

enum class LevelDifficulty { NOVICE, ADVANCED, HIGH }
enum class LevelConfigSource { USER_SPECIFIED_SMOOTH_PROGRESSION_V2 }

data class WaveMonsterGroup(val type: MonsterType, val count: Int)

data class WaveConfig(
    val index: Int,
    val groups: List<WaveMonsterGroup>,
)

data class LevelConfig(
    val levelId: Int,
    val difficulty: LevelDifficulty,
    val pathCoordinates: List<CellCoordinate>,
    val waveCount: Int,
    val waves: List<WaveConfig>,
    val healthMultiplier: Float,
    val speedMultiplier: Float,
    val initialGold: Int,
    val waveClearGold: Int,
    val completionGold: Int,
    val fullStarCrystalReward: Int,
    val spawnIntervalSeconds: Float,
    val activeMonsterCap: Int,
    val twoStarHealthRatio: Float,
    val threeStarHealthRatio: Float,
    val source: LevelConfigSource = LevelConfigSource.USER_SPECIFIED_SMOOTH_PROGRESSION_V2,
)

enum class WaveRuntimeState {
    IDLE,
    READY,
    SPAWNING,
    WAITING_CLEAR,
    MANUAL_PAUSED,
    BETWEEN_WAVES,
    COMPLETED,
    FAILED,
}

data class LevelRuntimeState(
    val levelId: Int = 1,
    val currentWave: Int = 0,
    val totalWaves: Int = 0,
    val waveState: WaveRuntimeState = WaveRuntimeState.IDLE,
    val endpointHealth: Int = 10,
    val maxEndpointHealth: Int = 10,
    val activeMonsters: Int = 0,
    val queuedMonsters: Int = 0,
    val earnedStars: Int = 0,
    val bestStars: Int = 0,
    val crystalCores: Int = 0,
)

object LevelCatalog {
    private val pathA = listOf(
        CellCoordinate(2, 0), CellCoordinate(2, 1), CellCoordinate(2, 2),
        CellCoordinate(3, 2), CellCoordinate(4, 2), CellCoordinate(4, 3),
        CellCoordinate(4, 4), CellCoordinate(3, 4), CellCoordinate(2, 4),
        CellCoordinate(1, 4), CellCoordinate(1, 5), CellCoordinate(1, 6),
        CellCoordinate(1, 7),
    )
    private val pathB = listOf(
        CellCoordinate(0, 0), CellCoordinate(1, 0), CellCoordinate(1, 1),
        CellCoordinate(1, 2), CellCoordinate(2, 2), CellCoordinate(2, 3),
        CellCoordinate(2, 4), CellCoordinate(3, 4), CellCoordinate(4, 4),
        CellCoordinate(4, 5), CellCoordinate(4, 6), CellCoordinate(5, 6),
        CellCoordinate(5, 7),
    )
    private val pathC = listOf(
        CellCoordinate(5, 0), CellCoordinate(4, 0), CellCoordinate(3, 0),
        CellCoordinate(3, 1), CellCoordinate(3, 2), CellCoordinate(2, 2),
        CellCoordinate(2, 3), CellCoordinate(1, 3), CellCoordinate(0, 3),
        CellCoordinate(0, 4), CellCoordinate(1, 4), CellCoordinate(2, 4),
        CellCoordinate(3, 4), CellCoordinate(4, 4), CellCoordinate(4, 5),
        CellCoordinate(4, 6), CellCoordinate(3, 6), CellCoordinate(2, 6),
        CellCoordinate(1, 6), CellCoordinate(0, 6), CellCoordinate(0, 7),
    )

    val all: List<LevelConfig> = (1..20).map(::buildLevel)
    private val byId = all.associateBy(LevelConfig::levelId)

    init {
        require(all.size == 20)
        require(listOf(5, 10, 15, 20).all { levelId ->
            get(levelId).waves.last().groups.any { it.type == MonsterType.BOSS }
        })
    }

    fun get(levelId: Int): LevelConfig = requireNotNull(byId[levelId]) { "Unknown level $levelId" }

    private fun buildLevel(levelId: Int): LevelConfig {
        val difficulty = when (levelId) {
            in 1..5 -> LevelDifficulty.NOVICE
            in 6..12 -> LevelDifficulty.ADVANCED
            else -> LevelDifficulty.HIGH
        }
        val waveCount = when (difficulty) {
            LevelDifficulty.NOVICE -> 3 + levelId / 2
            LevelDifficulty.ADVANCED -> 5 + (levelId - 6) / 2
            LevelDifficulty.HIGH -> 8 + (levelId - 13) / 2
        }
        val healthMultiplier = healthMultiplier(levelId)
        val speedMultiplier = speedMultiplier(levelId)
        val path = when (levelId % 3) {
            1 -> pathA
            2 -> pathB
            else -> pathC
        }
        return LevelConfig(
            levelId = levelId,
            difficulty = difficulty,
            pathCoordinates = path.toList(),
            waveCount = waveCount,
            waves = List(waveCount) { waveIndex -> buildWave(levelId, waveIndex, waveCount) },
            healthMultiplier = healthMultiplier,
            speedMultiplier = speedMultiplier,
            initialGold = 150 + (levelId - 1) * 15,
            waveClearGold = 20,
            completionGold = 160 + levelId * 25,
            fullStarCrystalReward = 10 + levelId * 2,
            spawnIntervalSeconds = .60f,
            activeMonsterCap = 20,
            twoStarHealthRatio = when (levelId) {
                in 1..3 -> .80f
                in 4..12 -> .60f
                else -> .40f
            },
            threeStarHealthRatio = when (levelId) {
                in 1..3 -> .95f
                in 4..12 -> .85f
                else -> .70f
            },
        )
    }

    private fun healthMultiplier(levelId: Int): Float = when (levelId) {
        1 -> 1f
        in 2..3 -> 1.18f.pow(levelId - 1)
        in 4..12 -> 1.18f.pow(2) * 1.16f.pow(levelId - 3)
        else -> 1.18f.pow(2) * 1.16f.pow(9) * 1.12f.pow(levelId - 12)
    }

    private fun speedMultiplier(levelId: Int): Float = when (levelId) {
        1 -> 1f
        in 2..12 -> 1.04f.pow(levelId - 1)
        else -> 1.04f.pow(11) * 1.03f.pow(levelId - 12)
    }

    private fun buildWave(levelId: Int, waveIndex: Int, totalWaves: Int): WaveConfig {
        val lastWave = waveIndex == totalWaves - 1
        val groups = buildList {
            add(WaveMonsterGroup(MonsterType.SMALL_BUG, 3 + levelId / 2 + waveIndex))
            if (levelId >= 2) add(WaveMonsterGroup(MonsterType.SWIFT_BUG, 1 + (levelId + waveIndex) / 4))
            if (levelId >= 4 && waveIndex >= 1) add(WaveMonsterGroup(MonsterType.ARMORED_BEETLE, 1 + levelId / 8))
            if (levelId >= 6 && waveIndex >= 2) add(WaveMonsterGroup(MonsterType.EXPLODING_WORM, 1 + waveIndex / 3))
            if (levelId >= 8 && waveIndex >= 2) add(WaveMonsterGroup(MonsterType.ACID_SPITTER, 1 + levelId / 10))
            if (levelId >= 12 && waveIndex >= totalWaves / 2) add(WaveMonsterGroup(MonsterType.ELITE_GUARD, 1 + levelId / 15))
            if (levelId % 5 == 0 && lastWave) add(WaveMonsterGroup(MonsterType.BOSS, 1))
        }
        return WaveConfig(waveIndex + 1, groups)
    }
}
