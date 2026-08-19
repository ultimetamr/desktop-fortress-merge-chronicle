package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.CodexCategory
import com.example.desktopfortress.domain.model.CodexEntry
import com.example.desktopfortress.domain.model.CodexProgress
import com.example.desktopfortress.domain.model.MonsterConfigTable
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.TowerBalanceTable
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.utils.CodexUnlocked
import com.example.desktopfortress.utils.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CodexManager : BaseManager() {
    private val mutableTowerEntries = MutableStateFlow<List<CodexEntry>>(emptyList())
    val towerEntries: StateFlow<List<CodexEntry>> = mutableTowerEntries.asStateFlow()
    private val mutableMonsterEntries = MutableStateFlow<List<CodexEntry>>(emptyList())
    val monsterEntries: StateFlow<List<CodexEntry>> = mutableMonsterEntries.asStateFlow()
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        refresh()
        initialized = true
    }

    fun unlockTower(type: TowerType, level: Int): Boolean {
        val id = towerId(type, level)
        val unlocked = PreferencesManager.unlockTower(id)
        if (unlocked) {
            PreferencesManager.updateHighestTowerTier(level)
            refresh()
            EventBus.tryEmit(CodexUnlocked(id, CodexCategory.TOWER.name))
            AchievementManager.evaluateAll()
        }
        return unlocked
    }

    fun unlockMonster(type: MonsterType): Boolean {
        val id = type.name
        val unlocked = PreferencesManager.unlockMonster(id)
        if (unlocked) {
            refresh()
            EventBus.tryEmit(CodexUnlocked(id, CodexCategory.MONSTER.name))
            AchievementManager.evaluateAll()
        }
        return unlocked
    }

    fun getProgress(category: CodexCategory): CodexProgress {
        val entries = if (category == CodexCategory.TOWER) towerEntries.value else monsterEntries.value
        return CodexProgress(entries.count(CodexEntry::unlocked), entries.size)
    }

    fun getEntry(id: String): CodexEntry? =
        (towerEntries.value + monsterEntries.value).firstOrNull { it.id == id }

    fun refresh() {
        val snapshot = PreferencesManager.snapshot.value
        mutableTowerEntries.value = TowerBalanceTable.all.map { config ->
            val id = towerId(config.type, config.level)
            CodexEntry(
                id = id,
                category = CodexCategory.TOWER,
                name = "${config.type.displayName}塔 · ${config.level}阶",
                summary = "${config.quality.name} / 伤害 ${config.damage.toInt()} / 攻速 ${config.attackSpeed}",
                detail = "射程 ${config.attackRangeMeters}m，溅射 ${config.splashRadiusMeters}m，特性 ${config.exclusiveTraits.joinToString().ifBlank { "无" }}",
                unlocked = id in snapshot.unlockedTowerIds,
            )
        }
        mutableMonsterEntries.value = MonsterConfigTable.all.map { config ->
            CodexEntry(
                id = config.type.name,
                category = CodexCategory.MONSTER,
                name = config.name,
                summary = "生命 ${config.baseHealth.toInt()} / 移速 ${config.movementSpeedMetersPerSecond}m/s",
                detail = "终点伤害 ${config.endpointDamage}，击杀金币 ${config.killGold}，资源 ${config.modelResource}",
                unlocked = config.type.name in snapshot.unlockedMonsterIds,
            )
        }
    }

    override fun destroy() {
        mutableTowerEntries.value = emptyList()
        mutableMonsterEntries.value = emptyList()
        initialized = false
        cancelScope()
    }

    private fun towerId(type: TowerType, level: Int) = "${type.name}_${level.coerceIn(1, 5)}"
}
