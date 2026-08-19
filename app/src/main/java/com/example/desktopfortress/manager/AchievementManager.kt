package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.AchievementCategory
import com.example.desktopfortress.domain.model.AchievementConfig
import com.example.desktopfortress.domain.model.AchievementState
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.utils.AchievementUnlocked
import com.example.desktopfortress.utils.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AchievementManager : BaseManager() {
    val catalog: List<AchievementConfig> = listOf(
        achievement("ach_growth_first_upgrade", AchievementCategory.GROWTH, "首次强化", "完成任意一次永久养成升级", 5),
        achievement("ach_growth_total_5", AchievementCategory.GROWTH, "初具规模", "永久养成总等级达到5级", 8),
        achievement("ach_growth_total_20", AchievementCategory.GROWTH, "体系成型", "永久养成总等级达到20级", 20),
        achievement("ach_growth_max_one", AchievementCategory.GROWTH, "专精大师", "任意养成项达到满级", 25),
        achievement("ach_growth_crystal_100", AchievementCategory.GROWTH, "晶核学徒", "累计获得100晶核", 10),
        achievement("ach_growth_crystal_500", AchievementCategory.GROWTH, "晶核专家", "累计获得500晶核", 30),

        achievement("ach_challenge_first_clear", AchievementCategory.CHALLENGE, "守住第一线", "首次通关", 8),
        achievement("ach_challenge_level_5", AchievementCategory.CHALLENGE, "新手毕业", "通关第5关", 15),
        achievement("ach_challenge_level_10", AchievementCategory.CHALLENGE, "久经沙场", "通关第10关", 25),
        achievement("ach_challenge_level_20", AchievementCategory.CHALLENGE, "桌面守护者", "通关第20关", 80),
        achievement("ach_challenge_first_three", AchievementCategory.CHALLENGE, "完美开端", "任意关卡获得3星", 12),
        achievement("ach_challenge_five_three", AchievementCategory.CHALLENGE, "五星战线", "5个关卡获得3星", 35),
        achievement("ach_challenge_perfect", AchievementCategory.CHALLENGE, "毫发无伤", "以满核心血量通关", 20),
        achievement("ach_challenge_kill_100", AchievementCategory.CHALLENGE, "百虫斩", "累计击杀100只怪物", 20),

        achievement("ach_collect_first_tower", AchievementCategory.COLLECTION, "第一座塔", "解锁任意塔图鉴", 5),
        achievement("ach_collect_four_lines", AchievementCategory.COLLECTION, "四线齐备", "解锁四条塔成长线的1阶塔", 15),
        achievement("ach_collect_all_towers", AchievementCategory.COLLECTION, "塔之百科", "解锁全部20个塔图鉴", 60),
        achievement("ach_collect_first_monster", AchievementCategory.COLLECTION, "初见异虫", "解锁任意怪物图鉴", 5),
        achievement("ach_collect_all_monsters", AchievementCategory.COLLECTION, "生态调查", "解锁全部7个怪物图鉴", 35),
        achievement("ach_collect_ten", AchievementCategory.COLLECTION, "博闻强识", "塔与怪物图鉴合计解锁10项", 18),
    )

    private val mutableAchievements = MutableStateFlow<List<AchievementState>>(emptyList())
    val achievements: StateFlow<List<AchievementState>> = mutableAchievements.asStateFlow()
    private var initialized = false

    init {
        require(catalog.size == 20)
        require(catalog.map { it.id }.distinct().size == 20)
    }

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        refresh()
        evaluateAll()
        initialized = true
    }

    fun evaluateAll() {
        var unlockedInPass: Boolean
        do {
            unlockedInPass = false
            val snapshot = PreferencesManager.snapshot.value
            catalog.filterNot { it.id in snapshot.completedAchievementIds }.forEach { config ->
                if (isSatisfied(config.id)) {
                    val reward = config.crystalReward
                    if (PreferencesManager.completeAchievement(config.id, reward)) {
                        EventBus.tryEmit(AchievementUnlocked(config.id, config.name, reward))
                        unlockedInPass = true
                    }
                }
            }
        } while (unlockedInPass)
        refresh()
    }

    fun progress(): Pair<Int, Int> = achievements.value.count(AchievementState::completed) to catalog.size

    fun refresh() {
        val completed = PreferencesManager.snapshot.value.completedAchievementIds
        mutableAchievements.value = catalog.map { AchievementState(it, it.id in completed) }
    }

    override fun destroy() {
        mutableAchievements.value = emptyList()
        initialized = false
        cancelScope()
    }

    private fun isSatisfied(id: String): Boolean {
        val save = PreferencesManager.snapshot.value
        val totalDevelop = DevelopType.entries.sumOf { save.developLevels[it] ?: 0 }
        val threeStarLevels = save.levels.values.count { it.bestStars >= 3 }
        val towerIds = save.unlockedTowerIds
        return when (id) {
            "ach_growth_first_upgrade" -> totalDevelop >= 1
            "ach_growth_total_5" -> totalDevelop >= 5
            "ach_growth_total_20" -> totalDevelop >= 20
            "ach_growth_max_one" -> save.developLevels.values.any { it >= 10 }
            "ach_growth_crystal_100" -> save.stats.totalCrystalsEarned >= 100
            "ach_growth_crystal_500" -> save.stats.totalCrystalsEarned >= 500
            "ach_challenge_first_clear" -> save.stats.totalClears >= 1
            "ach_challenge_level_5" -> save.player.highestClearedLevel >= 5
            "ach_challenge_level_10" -> save.player.highestClearedLevel >= 10
            "ach_challenge_level_20" -> save.player.highestClearedLevel >= 20
            "ach_challenge_first_three" -> threeStarLevels >= 1
            "ach_challenge_five_three" -> threeStarLevels >= 5
            "ach_challenge_perfect" -> save.stats.perfectClears >= 1
            "ach_challenge_kill_100" -> save.stats.totalKills >= 100
            "ach_collect_first_tower" -> towerIds.isNotEmpty()
            "ach_collect_four_lines" -> listOf("ARCHER_1", "BALLISTA_1", "EXPLOSIVE_1", "FROST_1").all(towerIds::contains)
            "ach_collect_all_towers" -> towerIds.size >= 20
            "ach_collect_first_monster" -> save.unlockedMonsterIds.isNotEmpty()
            "ach_collect_all_monsters" -> save.unlockedMonsterIds.size >= 7
            "ach_collect_ten" -> towerIds.size + save.unlockedMonsterIds.size >= 10
            else -> false
        }
    }

    private fun achievement(
        id: String,
        category: AchievementCategory,
        name: String,
        description: String,
        reward: Int,
    ) = AchievementConfig(id, category, name, description, reward)
}
