package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.DevelopConfig
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.utils.DevelopUpgraded
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.UserMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DevelopManager : BaseManager() {
    private const val MAX_LEVEL = 10
    private val mutableConfigs = MutableStateFlow<List<DevelopConfig>>(emptyList())
    val configs: StateFlow<List<DevelopConfig>> = mutableConfigs.asStateFlow()
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        refresh()
        initialized = true
    }

    fun upgrade(type: DevelopType): Boolean {
        val config = getConfig(type)
        if (config.currentLevel >= config.maxLevel) return false
        val cost = config.nextUpgradeCost ?: return false
        if (!PreferencesManager.costCrystalCores(cost)) {
            EventBus.tryEmit(UserMessage("晶核不足：升级${config.name}需要 $cost"))
            return false
        }
        val nextLevel = config.currentLevel + 1
        PreferencesManager.setDevelopLevel(type, nextLevel)
        refresh()
        EventBus.tryEmit(DevelopUpgraded(type, nextLevel, getBonusRatio(type)))
        AchievementManager.evaluateAll()
        return true
    }

    fun getConfig(type: DevelopType): DevelopConfig =
        mutableConfigs.value.firstOrNull { it.type == type } ?: createConfig(type)

    fun getBonusRatio(type: DevelopType): Float =
        PreferencesManager.getDevelopLevel(type) * effectPerLevel(type)

    fun applyBonus(baseValue: Float, type: DevelopType): Float =
        (baseValue.coerceAtLeast(0f) * (1f + getBonusRatio(type))).coerceAtLeast(0f)

    fun applyBonus(baseValue: Int, type: DevelopType): Int =
        applyBonus(baseValue.toFloat(), type).roundToInt().coerceAtLeast(0)

    fun getStartingGoldBonusRatio() = getBonusRatio(DevelopType.STARTING_GOLD)
    fun getKillGoldBonusRatio() = getBonusRatio(DevelopType.KILL_GOLD)
    fun getTowerDamageBonusRatio() = getBonusRatio(DevelopType.TOWER_DAMAGE)
    fun getTowerAttackSpeedBonusRatio() = getBonusRatio(DevelopType.TOWER_ATTACK_SPEED)
    fun getCoreHealthBonusRatio() = getBonusRatio(DevelopType.CORE_HEALTH)
    fun getTowerRangeBonusRatio() = getBonusRatio(DevelopType.TOWER_RANGE)
    fun getWaveHealBonusRatio() = getBonusRatio(DevelopType.WAVE_GOLD)
    fun getSellRefundBonusRatio() = getBonusRatio(DevelopType.CRYSTAL_REWARD)

    fun refresh() {
        mutableConfigs.value = DevelopType.entries.map(::createConfig)
    }

    override fun destroy() {
        mutableConfigs.value = emptyList()
        initialized = false
        cancelScope()
    }

    private fun createConfig(type: DevelopType): DevelopConfig {
        val (name, description) = when (type) {
            DevelopType.STARTING_GOLD -> "战备资金" to "提高每关初始金币"
            DevelopType.KILL_GOLD -> "赏金协议" to "提高击杀怪物获得的金币"
            DevelopType.TOWER_DAMAGE -> "武器校准" to "提高全部防御塔伤害"
            DevelopType.TOWER_ATTACK_SPEED -> "自动装填" to "提高全部防御塔攻击速度"
            DevelopType.CORE_HEALTH -> "核心装甲" to "提高终点核心最大血量"
            DevelopType.TOWER_RANGE -> "观测阵列" to "提高全部防御塔攻击范围"
            DevelopType.WAVE_GOLD -> "战线修复" to "每级增加1%波次回血比例，最终受10%单次上限约束"
            DevelopType.CRYSTAL_REWARD -> "回收协议" to "每级增加5%出售返还比例，最高80%"
        }
        return DevelopConfig(
            type = type,
            name = name,
            description = description,
            currentLevel = PreferencesManager.getDevelopLevel(type),
            maxLevel = MAX_LEVEL,
            effectPerLevel = effectPerLevel(type),
            upgradeCosts = List(MAX_LEVEL) { level -> 20 + level * level * 8 + level * 12 },
        )
    }

    private fun effectPerLevel(type: DevelopType): Float = when (type) {
        DevelopType.STARTING_GOLD -> .05f
        DevelopType.KILL_GOLD, DevelopType.TOWER_RANGE, DevelopType.CORE_HEALTH -> .03f
        DevelopType.TOWER_DAMAGE -> .02f
        DevelopType.TOWER_ATTACK_SPEED, DevelopType.WAVE_GOLD -> .01f
        DevelopType.CRYSTAL_REWARD -> .05f
    }
}
