package com.example.desktopfortress.manager

import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.CodexCategory
import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.TowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EconomyProgressionTest {
    @Before
    fun setUp() {
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        DevelopManager.refresh()
        GoldManager.resetForTesting()
        CodexManager.refresh()
        AchievementManager.refresh()
    }

    @Test
    fun goldNeverGoesNegativeAndCostsAtomically() {
        GoldManager.resetForTesting(100)
        assertTrue(GoldManager.costGold(60))
        assertEquals(40, GoldManager.getCurrentGold())
        assertFalse(GoldManager.costGold(41))
        assertEquals(40, GoldManager.getCurrentGold())
        assertFalse(GoldManager.costGold(-5))
    }

    @Test
    fun levelInitializationAppliesPermanentStartingGoldFormula() {
        PreferencesManager.setDevelopLevel(DevelopType.STARTING_GOLD, 2)
        DevelopManager.refresh()

        GoldManager.initLevel(1)

        assertEquals(165, GoldManager.getCurrentGold()) // 150 × (1 + 2 × 5%)
    }

    @Test
    fun developmentUpgradeCostsCrystalsAndUpdatesFormula() {
        PreferencesManager.addCrystalCores(100, countsAsEarned = false)
        DevelopManager.refresh()

        assertTrue(DevelopManager.upgrade(DevelopType.TOWER_DAMAGE))

        assertEquals(1, PreferencesManager.getDevelopLevel(DevelopType.TOWER_DAMAGE))
        assertEquals(.02f, DevelopManager.getTowerDamageBonusRatio(), .0001f)
        assertEquals(85, PreferencesManager.getCrystalCores()) // 100 - 20 + 首次强化成就5
    }

    @Test
    fun allEightDevelopmentRatiosRemainAtConfiguredProgressionValues() {
        val expected = mapOf(
            DevelopType.STARTING_GOLD to .05f,
            DevelopType.KILL_GOLD to .03f,
            DevelopType.TOWER_DAMAGE to .02f,
            DevelopType.TOWER_ATTACK_SPEED to .01f,
            DevelopType.TOWER_RANGE to .03f,
            DevelopType.CORE_HEALTH to .03f,
            DevelopType.WAVE_GOLD to .01f,
            DevelopType.CRYSTAL_REWARD to .05f,
        )
        expected.forEach { (type, perLevel) ->
            PreferencesManager.setDevelopLevel(type, 1)
            DevelopManager.refresh()
            assertEquals(perLevel, DevelopManager.getBonusRatio(type), .0001f)
            PreferencesManager.setDevelopLevel(type, 0)
        }
    }

    @Test
    fun firstAppearanceUnlocksCodexAndReportsProgress() {
        CodexManager.unlockTower(TowerType.ARCHER, 1)
        CodexManager.unlockMonster(MonsterType.SMALL_BUG)

        assertEquals(1, CodexManager.getProgress(CodexCategory.TOWER).unlocked)
        assertEquals(20, CodexManager.getProgress(CodexCategory.TOWER).total)
        assertEquals(1, CodexManager.getProgress(CodexCategory.MONSTER).unlocked)
        assertEquals(7, CodexManager.getProgress(CodexCategory.MONSTER).total)
    }

    @Test
    fun catalogHasTwentyAchievementsAndRewardsOnlyOnce() {
        assertEquals(20, AchievementManager.catalog.size)
        CodexManager.unlockTower(TowerType.ARCHER, 1)
        val afterFirst = PreferencesManager.getCrystalCores()

        AchievementManager.evaluateAll()

        assertTrue("ach_collect_first_tower" in PreferencesManager.snapshot.value.completedAchievementIds)
        assertEquals(afterFirst, PreferencesManager.getCrystalCores())
    }
}
