package com.example.desktopfortress.domain.model

enum class DevelopCategory(val displayName: String) {
    ECONOMY("经济"),
    OFFENSE("进攻"),
    DEFENSE("防御"),
    REWARD("奖励"),
}

enum class DevelopType(val category: DevelopCategory) {
    STARTING_GOLD(DevelopCategory.ECONOMY),
    KILL_GOLD(DevelopCategory.ECONOMY),
    TOWER_DAMAGE(DevelopCategory.OFFENSE),
    TOWER_ATTACK_SPEED(DevelopCategory.OFFENSE),
    CORE_HEALTH(DevelopCategory.DEFENSE),
    TOWER_RANGE(DevelopCategory.DEFENSE),
    /** Persisted save key retained; gameplay meaning is wave healing. */
    WAVE_GOLD(DevelopCategory.DEFENSE),
    /** Persisted save key retained; gameplay meaning is tower sale refund. */
    CRYSTAL_REWARD(DevelopCategory.ECONOMY),
}

enum class DevelopConfigSource { USER_SPECIFIED_SMOOTH_PROGRESSION_V2 }

data class DevelopConfig(
    val type: DevelopType,
    val name: String,
    val description: String,
    val currentLevel: Int,
    val maxLevel: Int,
    /** Additive ratio unlocked by each level; final = base × (1 + level × ratio). */
    val effectPerLevel: Float,
    val upgradeCosts: List<Int>,
    val source: DevelopConfigSource = DevelopConfigSource.USER_SPECIFIED_SMOOTH_PROGRESSION_V2,
) {
    val currentBonusRatio: Float get() = currentLevel * effectPerLevel
    val nextUpgradeCost: Int? get() = upgradeCosts.getOrNull(currentLevel)
}

enum class GraphicsQuality { LOW, MEDIUM, HIGH }

data class GameSettings(
    val masterVolume: Float = .8f,
    val effectsVolume: Float = .8f,
    val graphicsQuality: GraphicsQuality = GraphicsQuality.MEDIUM,
)

data class LevelSaveData(
    val unlocked: Boolean = false,
    val bestStars: Int = 0,
    val fastestClearMillis: Long? = null,
)

data class PlayerSaveData(
    val crystalCores: Int = 0,
    val lifetimeGold: Long = 0L,
    val highestClearedLevel: Int = 0,
)

data class ProgressStats(
    val totalKills: Int = 0,
    val totalClears: Int = 0,
    val perfectClears: Int = 0,
    val totalCrystalsEarned: Int = 0,
    val highestTowerTier: Int = 0,
)

data class SaveSnapshot(
    val player: PlayerSaveData = PlayerSaveData(),
    val developLevels: Map<DevelopType, Int> = DevelopType.entries.associateWith { 0 },
    val levels: Map<Int, LevelSaveData> = (1..20).associateWith { level ->
        LevelSaveData(unlocked = level == 1)
    },
    val unlockedTowerIds: Set<String> = emptySet(),
    val unlockedMonsterIds: Set<String> = emptySet(),
    val completedAchievementIds: Set<String> = emptySet(),
    val settings: GameSettings = GameSettings(),
    val stats: ProgressStats = ProgressStats(),
)

enum class CodexCategory { TOWER, MONSTER }

data class CodexEntry(
    val id: String,
    val category: CodexCategory,
    val name: String,
    val summary: String,
    val detail: String,
    val unlocked: Boolean,
)

data class CodexProgress(
    val unlocked: Int,
    val total: Int,
) {
    val ratio: Float get() = if (total <= 0) 0f else unlocked.toFloat() / total
}

enum class AchievementCategory(val displayName: String) {
    GROWTH("成长"),
    CHALLENGE("挑战"),
    COLLECTION("收集"),
}

enum class AchievementConfigSource { ENGINEERING_DEFAULT_PENDING_DESIGN }

data class AchievementConfig(
    val id: String,
    val category: AchievementCategory,
    val name: String,
    val description: String,
    val crystalReward: Int,
    val source: AchievementConfigSource = AchievementConfigSource.ENGINEERING_DEFAULT_PENDING_DESIGN,
)

data class AchievementState(
    val config: AchievementConfig,
    val completed: Boolean,
)
