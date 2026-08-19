package com.example.desktopfortress.data.local

import com.example.desktopfortress.domain.model.DevelopType
import com.example.desktopfortress.domain.model.GameSettings
import com.example.desktopfortress.domain.model.GraphicsQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {
    private lateinit var store: MemoryPreferenceStore

    @Before
    fun setUp() {
        store = MemoryPreferenceStore()
        PreferencesManager.initializeForTesting(store)
    }

    @Test
    fun defaultsUnlockOnlyFirstLevelAndContainEightDevelopItems() {
        assertTrue(PreferencesManager.getLevelData(1).unlocked)
        assertFalse(PreferencesManager.getLevelData(2).unlocked)
        assertEquals(8, PreferencesManager.snapshot.value.developLevels.size)
        assertEquals(0, PreferencesManager.getCrystalCores())
    }

    @Test
    fun persistsAllRequiredDataAndReloadsIt() {
        PreferencesManager.addCrystalCores(120)
        PreferencesManager.addLifetimeGold(450)
        PreferencesManager.setDevelopLevel(DevelopType.TOWER_DAMAGE, 3)
        PreferencesManager.recordLevelCompletion(1, 3, 12_500L, perfect = true)
        PreferencesManager.unlockTower("ARCHER_1")
        PreferencesManager.unlockMonster("SMALL_BUG")
        PreferencesManager.completeAchievement("ach_test", 7)
        PreferencesManager.updateSettings(GameSettings(.4f, .6f, GraphicsQuality.HIGH))

        PreferencesManager.reload()

        assertEquals(127, PreferencesManager.getCrystalCores())
        assertEquals(450L, PreferencesManager.getLifetimeGold())
        assertEquals(3, PreferencesManager.getDevelopLevel(DevelopType.TOWER_DAMAGE))
        assertEquals(3, PreferencesManager.getLevelData(1).bestStars)
        assertEquals(12_500L, PreferencesManager.getLevelData(1).fastestClearMillis)
        assertTrue(PreferencesManager.getLevelData(2).unlocked)
        assertTrue("ARCHER_1" in PreferencesManager.snapshot.value.unlockedTowerIds)
        assertTrue("SMALL_BUG" in PreferencesManager.snapshot.value.unlockedMonsterIds)
        assertTrue("ach_test" in PreferencesManager.snapshot.value.completedAchievementIds)
        assertEquals(GraphicsQuality.HIGH, PreferencesManager.getSettings().graphicsQuality)
    }

    @Test
    fun checksumMismatchRepairsCriticalDataToDefaults() {
        PreferencesManager.addCrystalCores(500)
        store.tamperString("player_crystals", "9999999")

        PreferencesManager.reload()

        assertEquals(0, PreferencesManager.getCrystalCores())
        assertEquals(0, PreferencesManager.getHighestClearedLevel())
        assertTrue(PreferencesManager.getLevelData(1).unlocked)
    }

    @Test
    fun invalidValuesAreClampedAndCannotCreateNegativeCurrency() {
        PreferencesManager.updateSettings(GameSettings(Float.NaN, 9f, GraphicsQuality.LOW))
        assertEquals(.8f, PreferencesManager.getSettings().masterVolume, .0001f)
        assertEquals(1f, PreferencesManager.getSettings().effectsVolume, .0001f)
        assertFalse(PreferencesManager.costCrystalCores(-1))
        assertEquals(0, PreferencesManager.getCrystalCores())
    }
}
