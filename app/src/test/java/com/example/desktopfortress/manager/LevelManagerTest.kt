package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.LevelCatalog
import com.example.desktopfortress.domain.model.LevelDifficulty
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.WaveRuntimeState
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LevelManagerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        DevelopManager.refresh()
        CodexManager.refresh()
        AchievementManager.refresh()
        BoardManager.initialize()
        TowerManager.initialize()
        MonsterManager.initialize()
        LevelManager.initialize()
        LevelManager.setFlowListener(null)
        LevelManager.selectLevel(1)
        LevelManager.prepareSelectedLevel()
    }

    @After
    fun tearDown() {
        MonsterManager.clear()
        InMemoryGameRepository.updateGameState(GameState.MAIN_MENU)
        Dispatchers.resetMain()
    }
    @Test
    fun catalogContainsTwentyIndependentDifficultyTieredLevels() {
        assertEquals(20, LevelCatalog.all.size)
        assertTrue(LevelCatalog.all.take(5).all { it.difficulty == LevelDifficulty.NOVICE })
        assertTrue(LevelCatalog.all.slice(5..11).all { it.difficulty == LevelDifficulty.ADVANCED })
        assertTrue(LevelCatalog.all.drop(12).all { it.difficulty == LevelDifficulty.HIGH })
        assertTrue(LevelCatalog.all.zipWithNext().all { (a, b) -> a.pathCoordinates !== b.pathCoordinates })
    }

    @Test
    fun everyFifthLevelEndsWithBossWave() {
        listOf(5, 10, 15, 20).forEach { levelId ->
            assertTrue(LevelCatalog.get(levelId).waves.last().groups.any { it.type == MonsterType.BOSS })
        }
    }

    @Test
    fun starsFollowEndpointHealthThresholds() {
        assertEquals(0, LevelManager.calculateStars(0, 100))
        assertEquals(1, LevelManager.calculateStars(1, 100))
        assertEquals(1, LevelManager.calculateStars(79, 100, 1))
        assertEquals(2, LevelManager.calculateStars(80, 100, 1))
        assertEquals(3, LevelManager.calculateStars(95, 100, 1))
        assertEquals(2, LevelManager.calculateStars(60, 100, 4))
        assertEquals(3, LevelManager.calculateStars(85, 100, 4))
        assertEquals(2, LevelManager.calculateStars(40, 100, 13))
        assertEquals(3, LevelManager.calculateStars(70, 100, 13))
    }

    @Test
    fun waveSpawnsImmediatelyThenAtPointSixSecondIntervals() {
        assertTrue(LevelManager.startOrResumeWave())
        LevelManager.update(.01f)
        assertEquals(1, MonsterManager.activeCount())
        assertEquals(60f, MonsterManager.activeMonsters().single().maxHealth, 0f)
        assertEquals(.15f, MonsterManager.activeMonsters().single().movementSpeed, 0f)

        LevelManager.update(.58f)
        assertEquals(1, MonsterManager.activeCount())

        LevelManager.update(.02f)
        assertEquals(2, MonsterManager.activeCount())
    }

    @Test
    fun levelOneKeepsOriginalWavePlanWithUpdatedEconomy() {
        val level = LevelCatalog.get(1)
        assertEquals(listOf(3, 4, 5), level.waves.map { it.groups.single().count })
        assertTrue(level.waves.flatMap { it.groups }.all { it.type == MonsterType.SMALL_BUG })
        assertEquals(150, level.initialGold)
        assertEquals(20, level.waveClearGold)
        assertEquals(.60f, level.spawnIntervalSeconds, 0f)
        assertEquals(20, level.activeMonsterCap)
        assertEquals(10, LevelManager.runtimeState.value.maxEndpointHealth)
    }

    @Test
    fun levelDifficultyMultipliersAndEconomyFollowSmoothCurve() {
        assertEquals(1.18f, LevelCatalog.get(2).healthMultiplier, .0001f)
        assertEquals(1.18f * 1.18f, LevelCatalog.get(3).healthMultiplier, .0001f)
        assertEquals(1.18f * 1.18f * 1.16f, LevelCatalog.get(4).healthMultiplier, .0001f)
        assertEquals(1.04f * 1.04f * 1.04f, LevelCatalog.get(4).speedMultiplier, .0001f)
        assertEquals(165, LevelCatalog.get(2).initialGold)
        assertEquals(435, LevelCatalog.get(20).initialGold)
        assertEquals(20, LevelCatalog.get(20).waveClearGold)
        assertEquals(.60f, LevelCatalog.get(20).spawnIntervalSeconds, 0f)
        assertEquals(20, LevelCatalog.get(20).activeMonsterCap)
        assertTrue(LevelCatalog.get(2).waves.flatMap { it.groups }.any { it.type == MonsterType.SWIFT_BUG })
        assertTrue(LevelCatalog.get(3).waves.flatMap { it.groups }.none { it.type == MonsterType.ARMORED_BEETLE })
        assertTrue(LevelCatalog.get(4).waves.flatMap { it.groups }.any { it.type == MonsterType.ARMORED_BEETLE })
    }

    @Test
    fun waveHealingStartsAtFivePercentAndCapsAtTenPercent() {
        assertEquals(5, LevelManager.waveHealAmount(100))
        PreferencesManager.setDevelopLevel(com.example.desktopfortress.domain.model.DevelopType.WAVE_GOLD, 10)
        DevelopManager.refresh()
        assertEquals(10, LevelManager.waveHealAmount(100))
    }

    @Test
    fun endpointAtZeroFailsAndRecyclesWholeWave() {
        var failedLevel = 0
        LevelManager.setFlowListener(object : LevelManager.FlowListener {
            override fun onWaveCompleted(levelId: Int, waveNumber: Int, hasNextWave: Boolean) = Unit
            override fun onLevelCompleted(levelId: Int) = Unit
            override fun onLevelFailed(levelId: Int) { failedLevel = levelId }
        })
        LevelManager.startOrResumeWave()
        LevelManager.update(.01f)
        val monster = MonsterManager.activeMonsters().first()

        LevelManager.onEndpointHit(monster, 100)

        assertEquals(0, LevelManager.runtimeState.value.endpointHealth)
        assertEquals(WaveRuntimeState.FAILED, LevelManager.runtimeState.value.waveState)
        assertEquals(0, MonsterManager.activeCount())
        assertEquals(1, failedLevel)
    }
}
