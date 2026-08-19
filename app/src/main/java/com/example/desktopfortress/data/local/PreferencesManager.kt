package com.example.desktopfortress.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.domain.model.GameSettings
import com.example.desktopfortress.domain.model.GraphicsQuality
import com.example.desktopfortress.domain.model.LevelSaveData
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.PlayerSaveData
import com.example.desktopfortress.domain.model.ProgressStats
import com.example.desktopfortress.domain.model.SaveSnapshot
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Versioned, checksummed SharedPreferences save facade and sole progression source of truth. */
object PreferencesManager {
    private const val FILE_NAME = "desktop_fortress_save_v2"
    private const val LEGACY_FILE_NAME = "desktop_fortress_level_progress"
    private const val SCHEMA_VERSION = "2"
    private const val CHECKSUM_SALT = "desktop-fortress-local-integrity-v2"
    private const val MAX_CRYSTALS = 10_000_000
    private const val MAX_LIFETIME_GOLD = 10_000_000_000L
    private const val MAX_STAT_COUNT = 10_000_000
    private const val MAX_CLEAR_MILLIS = 7L * 24 * 60 * 60 * 1000

    private lateinit var store: PreferenceStore
    private val mutableSnapshot = MutableStateFlow(SaveSnapshot())
    val snapshot: StateFlow<SaveSnapshot> = mutableSnapshot.asStateFlow()

    fun initialize(context: Context) {
        if (::store.isInitialized) return
        val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        store = SharedPreferencesStore(preferences)
        if (store.getString(KEY_CHECKSUM) == null) {
            val legacy = context.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE)
            val migrated = migrateLegacy(legacy)
            mutableSnapshot.value = migrated
            persist(migrated)
        } else {
            loadAndRepair()
        }
    }

    internal fun initializeForTesting(testStore: PreferenceStore) {
        store = testStore
        if (store.getString(KEY_CHECKSUM) == null) persist(SaveSnapshot())
        loadAndRepair()
    }

    fun reload() {
        ensureInitialized()
        loadAndRepair()
    }

    fun getCrystalCores(): Int = mutableSnapshot.value.player.crystalCores
    fun getLifetimeGold(): Long = mutableSnapshot.value.player.lifetimeGold
    fun getHighestClearedLevel(): Int = mutableSnapshot.value.player.highestClearedLevel
    fun getDevelopLevel(type: DevelopType): Int = mutableSnapshot.value.developLevels[type] ?: 0
    fun getLevelData(levelId: Int): LevelSaveData = mutableSnapshot.value.levels[levelId] ?: LevelSaveData()
    fun getSettings(): GameSettings = mutableSnapshot.value.settings

    fun addCrystalCores(amount: Int, countsAsEarned: Boolean = true): Int {
        if (amount <= 0) return getCrystalCores()
        update { current ->
            current.copy(
                player = current.player.copy(
                    crystalCores = safeAdd(current.player.crystalCores, amount, MAX_CRYSTALS),
                ),
                stats = if (countsAsEarned) current.stats.copy(
                    totalCrystalsEarned = safeAdd(current.stats.totalCrystalsEarned, amount, MAX_STAT_COUNT),
                ) else current.stats,
            )
        }
        return getCrystalCores()
    }

    fun costCrystalCores(amount: Int): Boolean {
        if (amount < 0) return false
        if (amount == 0) return true
        val current = getCrystalCores()
        if (current < amount) return false
        update { it.copy(player = it.player.copy(crystalCores = current - amount)) }
        return true
    }

    fun addLifetimeGold(amount: Int) {
        if (amount <= 0) return
        update { current ->
            current.copy(
                player = current.player.copy(
                    lifetimeGold = (current.player.lifetimeGold + amount.toLong())
                        .coerceIn(0L, MAX_LIFETIME_GOLD),
                ),
            )
        }
    }

    fun setDevelopLevel(type: DevelopType, level: Int) {
        update { current ->
            current.copy(developLevels = current.developLevels + (type to level.coerceIn(0, 10)))
        }
    }

    fun recordLevelCompletion(
        levelId: Int,
        stars: Int,
        clearMillis: Long,
        perfect: Boolean,
    ): LevelSaveData {
        if (levelId !in 1..20) return LevelSaveData()
        update { current ->
            val previous = current.levels.getValue(levelId)
            val sanitizedMillis = clearMillis.coerceIn(1L, MAX_CLEAR_MILLIS)
            val updated = previous.copy(
                unlocked = true,
                bestStars = maxOf(previous.bestStars, stars.coerceIn(0, 3)),
                fastestClearMillis = previous.fastestClearMillis?.let { minOf(it, sanitizedMillis) }
                    ?: sanitizedMillis,
            )
            val levels = current.levels.toMutableMap().apply {
                put(levelId, updated)
                if (levelId < 20) put(levelId + 1, getValue(levelId + 1).copy(unlocked = true))
            }
            current.copy(
                player = current.player.copy(highestClearedLevel = maxOf(current.player.highestClearedLevel, levelId)),
                levels = levels,
                stats = current.stats.copy(
                    totalClears = safeAdd(current.stats.totalClears, 1, MAX_STAT_COUNT),
                    perfectClears = safeAdd(current.stats.perfectClears, if (perfect) 1 else 0, MAX_STAT_COUNT),
                ),
            )
        }
        return getLevelData(levelId)
    }

    fun incrementKills(amount: Int = 1) {
        if (amount <= 0) return
        update { it.copy(stats = it.stats.copy(totalKills = safeAdd(it.stats.totalKills, amount, MAX_STAT_COUNT))) }
    }

    fun updateHighestTowerTier(tier: Int) {
        update { it.copy(stats = it.stats.copy(highestTowerTier = maxOf(it.stats.highestTowerTier, tier.coerceIn(0, 5)))) }
    }

    fun unlockTower(id: String): Boolean = addToSet(id, mutableSnapshot.value.unlockedTowerIds) { current, updated ->
        current.copy(unlockedTowerIds = updated)
    }

    fun unlockMonster(id: String): Boolean = addToSet(id, mutableSnapshot.value.unlockedMonsterIds) { current, updated ->
        current.copy(unlockedMonsterIds = updated)
    }

    /** Atomically marks completion and grants its reward once. */
    fun completeAchievement(id: String, crystalReward: Int): Boolean {
        val current = mutableSnapshot.value
        if (id in current.completedAchievementIds) return false
        val reward = crystalReward.coerceAtLeast(0)
        update {
            it.copy(
                completedAchievementIds = it.completedAchievementIds + id,
                player = it.player.copy(
                    crystalCores = safeAdd(it.player.crystalCores, reward, MAX_CRYSTALS),
                ),
                stats = it.stats.copy(
                    totalCrystalsEarned = safeAdd(it.stats.totalCrystalsEarned, reward, MAX_STAT_COUNT),
                ),
            )
        }
        return true
    }

    fun updateSettings(settings: GameSettings) {
        update { it.copy(settings = settings) }
    }

    private fun addToSet(
        id: String,
        currentSet: Set<String>,
        transform: (SaveSnapshot, Set<String>) -> SaveSnapshot,
    ): Boolean {
        if (id.isBlank() || id in currentSet) return false
        update { transform(it, currentSet + id) }
        return true
    }

    @Synchronized
    private fun update(transform: (SaveSnapshot) -> SaveSnapshot) {
        ensureInitialized()
        val repaired = sanitize(transform(mutableSnapshot.value))
        mutableSnapshot.value = repaired
        persist(repaired)
    }

    private fun loadAndRepair() {
        val loaded = parseStore()
        val storedChecksum = store.getString(KEY_CHECKSUM)
        val valid = storedChecksum != null && storedChecksum == checksum(canonical(loaded))
        val repaired = if (valid) sanitize(loaded) else SaveSnapshot()
        mutableSnapshot.value = repaired
        persist(repaired)
    }

    private fun parseStore(): SaveSnapshot {
        val player = PlayerSaveData(
            crystalCores = store.getString(KEY_CRYSTALS)?.toIntOrNull() ?: 0,
            lifetimeGold = store.getString(KEY_LIFETIME_GOLD)?.toLongOrNull() ?: 0L,
            highestClearedLevel = store.getString(KEY_HIGHEST_LEVEL)?.toIntOrNull() ?: 0,
        )
        val develops = DevelopType.entries.associateWith { type ->
            parseMap(store.getString(KEY_DEVELOP_LEVELS))[type.name]?.toIntOrNull() ?: 0
        }
        val levelTokens = parseMap(store.getString(KEY_LEVELS))
        val levels = (1..20).associateWith { levelId ->
            val parts = levelTokens[levelId.toString()]?.split(':').orEmpty()
            LevelSaveData(
                unlocked = parts.getOrNull(0)?.toBooleanStrictOrNull() ?: (levelId == 1),
                bestStars = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                fastestClearMillis = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
        val settings = GameSettings(
            masterVolume = store.getString(KEY_MASTER_VOLUME)?.toFloatOrNull() ?: .8f,
            effectsVolume = store.getString(KEY_EFFECTS_VOLUME)?.toFloatOrNull() ?: .8f,
            graphicsQuality = runCatching {
                GraphicsQuality.valueOf(store.getString(KEY_GRAPHICS_QUALITY).orEmpty())
            }.getOrDefault(GraphicsQuality.MEDIUM),
        )
        val statMap = parseMap(store.getString(KEY_STATS))
        return SaveSnapshot(
            player = player,
            developLevels = develops,
            levels = levels,
            unlockedTowerIds = store.getStringSet(KEY_TOWER_CODEX),
            unlockedMonsterIds = store.getStringSet(KEY_MONSTER_CODEX),
            completedAchievementIds = store.getStringSet(KEY_ACHIEVEMENTS),
            settings = settings,
            stats = ProgressStats(
                totalKills = statMap["kills"]?.toIntOrNull() ?: 0,
                totalClears = statMap["clears"]?.toIntOrNull() ?: 0,
                perfectClears = statMap["perfect"]?.toIntOrNull() ?: 0,
                totalCrystalsEarned = statMap["crystals"]?.toIntOrNull() ?: 0,
                highestTowerTier = statMap["tier"]?.toIntOrNull() ?: 0,
            ),
        )
    }

    private fun sanitize(source: SaveSnapshot): SaveSnapshot {
        val levels = (1..20).associateWith { levelId ->
            val raw = source.levels[levelId] ?: LevelSaveData()
            LevelSaveData(
                unlocked = raw.unlocked || levelId == 1,
                bestStars = raw.bestStars.coerceIn(0, 3),
                fastestClearMillis = raw.fastestClearMillis?.takeIf { it in 1..MAX_CLEAR_MILLIS },
            )
        }
        val validTower = Regex("(ARCHER|BALLISTA|EXPLOSIVE|FROST)_[1-5]")
        val validMonsterIds = MonsterType.entries.mapTo(mutableSetOf()) { it.name }
        return source.copy(
            player = source.player.copy(
                crystalCores = source.player.crystalCores.coerceIn(0, MAX_CRYSTALS),
                lifetimeGold = source.player.lifetimeGold.coerceIn(0L, MAX_LIFETIME_GOLD),
                highestClearedLevel = source.player.highestClearedLevel.coerceIn(0, 20),
            ),
            developLevels = DevelopType.entries.associateWith { source.developLevels[it]?.coerceIn(0, 10) ?: 0 },
            levels = levels,
            unlockedTowerIds = source.unlockedTowerIds.filterTo(linkedSetOf()) { validTower.matches(it) },
            unlockedMonsterIds = source.unlockedMonsterIds.filterTo(linkedSetOf()) { it in validMonsterIds },
            completedAchievementIds = source.completedAchievementIds.filterTo(linkedSetOf()) {
                it.matches(Regex("ach_[a-z0-9_]+"))
            },
            settings = source.settings.copy(
                masterVolume = source.settings.masterVolume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .8f,
                effectsVolume = source.settings.effectsVolume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .8f,
            ),
            stats = source.stats.copy(
                totalKills = source.stats.totalKills.coerceIn(0, MAX_STAT_COUNT),
                totalClears = source.stats.totalClears.coerceIn(0, MAX_STAT_COUNT),
                perfectClears = source.stats.perfectClears.coerceIn(0, MAX_STAT_COUNT),
                totalCrystalsEarned = source.stats.totalCrystalsEarned.coerceIn(0, MAX_STAT_COUNT),
                highestTowerTier = source.stats.highestTowerTier.coerceIn(0, 5),
            ),
        )
    }

    private fun persist(snapshot: SaveSnapshot) {
        val scalar = linkedMapOf(
            KEY_SCHEMA to SCHEMA_VERSION,
            KEY_CRYSTALS to snapshot.player.crystalCores.toString(),
            KEY_LIFETIME_GOLD to snapshot.player.lifetimeGold.toString(),
            KEY_HIGHEST_LEVEL to snapshot.player.highestClearedLevel.toString(),
            KEY_DEVELOP_LEVELS to snapshot.developLevels.entries.sortedBy { it.key.name }
                .joinToString(",") { "${it.key.name}=${it.value}" },
            KEY_LEVELS to snapshot.levels.entries.sortedBy { it.key }.joinToString(",") { (id, data) ->
                "$id=${data.unlocked}:${data.bestStars}:${data.fastestClearMillis ?: -1L}"
            },
            KEY_MASTER_VOLUME to snapshot.settings.masterVolume.toString(),
            KEY_EFFECTS_VOLUME to snapshot.settings.effectsVolume.toString(),
            KEY_GRAPHICS_QUALITY to snapshot.settings.graphicsQuality.name,
            KEY_STATS to listOf(
                "kills=${snapshot.stats.totalKills}",
                "clears=${snapshot.stats.totalClears}",
                "perfect=${snapshot.stats.perfectClears}",
                "crystals=${snapshot.stats.totalCrystalsEarned}",
                "tier=${snapshot.stats.highestTowerTier}",
            ).joinToString(","),
        )
        scalar[KEY_CHECKSUM] = checksum(canonical(snapshot))
        store.put(
            strings = scalar,
            sets = mapOf(
                KEY_TOWER_CODEX to snapshot.unlockedTowerIds,
                KEY_MONSTER_CODEX to snapshot.unlockedMonsterIds,
                KEY_ACHIEVEMENTS to snapshot.completedAchievementIds,
            ),
        )
    }

    private fun canonical(snapshot: SaveSnapshot): String = buildString {
        append(SCHEMA_VERSION).append('|')
        append(snapshot.player.crystalCores).append('|')
        append(snapshot.player.lifetimeGold).append('|')
        append(snapshot.player.highestClearedLevel).append('|')
        DevelopType.entries.forEach { append(it.name).append('=').append(snapshot.developLevels[it] ?: 0).append(';') }
        append('|')
        (1..20).forEach { id ->
            val level = snapshot.levels.getValue(id)
            append(id).append('=').append(level.unlocked).append(':').append(level.bestStars)
                .append(':').append(level.fastestClearMillis ?: -1L).append(';')
        }
        append('|').append(snapshot.unlockedTowerIds.sorted().joinToString(","))
        append('|').append(snapshot.unlockedMonsterIds.sorted().joinToString(","))
        append('|').append(snapshot.completedAchievementIds.sorted().joinToString(","))
        append('|').append(String.format(Locale.US, "%.4f", snapshot.settings.masterVolume))
        append('|').append(String.format(Locale.US, "%.4f", snapshot.settings.effectsVolume))
        append('|').append(snapshot.settings.graphicsQuality.name)
        append('|').append(snapshot.stats.totalKills)
        append('|').append(snapshot.stats.totalClears)
        append('|').append(snapshot.stats.perfectClears)
        append('|').append(snapshot.stats.totalCrystalsEarned)
        append('|').append(snapshot.stats.highestTowerTier)
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest((CHECKSUM_SALT + value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun migrateLegacy(legacy: SharedPreferences): SaveSnapshot {
        val crystals = legacy.getInt("crystal_cores", 0).coerceAtLeast(0)
        val levels = (1..20).associateWith { id ->
            val stars = legacy.getInt("level_${id}_best_stars", 0).coerceIn(0, 3)
            LevelSaveData(unlocked = id == 1 || legacy.getInt("level_${id - 1}_best_stars", 0) > 0, bestStars = stars)
        }
        val highest = levels.filterValues { it.bestStars > 0 }.keys.maxOrNull() ?: 0
        return sanitize(
            SaveSnapshot(
                player = PlayerSaveData(crystalCores = crystals, highestClearedLevel = highest),
                levels = levels,
            ),
        )
    }

    private fun parseMap(serialized: String?): Map<String, String> = serialized.orEmpty()
        .split(',')
        .mapNotNull { token ->
            val index = token.indexOf('=')
            if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
        }.toMap()

    private fun safeAdd(current: Int, amount: Int, maximum: Int): Int =
        (current.toLong() + amount.toLong()).coerceIn(0L, maximum.toLong()).toInt()

    private fun ensureInitialized() = check(::store.isInitialized) { "PreferencesManager.initialize must run first" }

    private const val KEY_SCHEMA = "schema"
    private const val KEY_CHECKSUM = "checksum"
    private const val KEY_CRYSTALS = "player_crystals"
    private const val KEY_LIFETIME_GOLD = "player_lifetime_gold"
    private const val KEY_HIGHEST_LEVEL = "player_highest_level"
    private const val KEY_DEVELOP_LEVELS = "develop_levels"
    private const val KEY_LEVELS = "level_records"
    private const val KEY_TOWER_CODEX = "codex_towers"
    private const val KEY_MONSTER_CODEX = "codex_monsters"
    private const val KEY_ACHIEVEMENTS = "achievements"
    private const val KEY_MASTER_VOLUME = "settings_master_volume"
    private const val KEY_EFFECTS_VOLUME = "settings_effects_volume"
    private const val KEY_GRAPHICS_QUALITY = "settings_graphics_quality"
    private const val KEY_STATS = "progress_stats"
}

internal interface PreferenceStore {
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>
    fun put(strings: Map<String, String>, sets: Map<String, Set<String>>)
}

private class SharedPreferencesStore(private val preferences: SharedPreferences) : PreferenceStore {
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun getStringSet(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty().toSet()
    override fun put(strings: Map<String, String>, sets: Map<String, Set<String>>) {
        preferences.edit().apply {
            strings.forEach(::putString)
            sets.forEach { (key, value) -> putStringSet(key, value) }
        }.apply()
    }
}
