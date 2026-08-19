package com.example.desktopfortress.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothProgressionBalanceTest {
    @Test
    fun allNumericConfigurationIsFiniteNonNegativeAndBounded() {
        TowerBalanceTable.all.forEach { tower ->
            assertTrue(tower.damage.isFinite() && tower.damage > 0f)
            assertTrue(tower.attackSpeed.isFinite() && tower.attackSpeed > 0f)
            assertTrue(tower.attackRangeMeters.isFinite() && tower.attackRangeMeters > 0f)
            assertTrue(tower.splashRadiusMeters.isFinite() && tower.splashRadiusMeters >= 0f)
            assertTrue(tower.cost >= 0)
        }
        MonsterConfigTable.all.forEach { monster ->
            assertTrue(monster.baseHealth.isFinite() && monster.baseHealth > 0f)
            assertTrue(monster.movementSpeedMetersPerSecond.isFinite() && monster.movementSpeedMetersPerSecond >= 0f)
            assertTrue(monster.endpointDamage >= 0)
            assertTrue(monster.killGold >= 0)
        }
        LevelCatalog.all.forEach { level ->
            assertTrue(level.healthMultiplier.isFinite() && level.healthMultiplier > 0f)
            assertTrue(level.speedMultiplier.isFinite() && level.speedMultiplier > 0f)
            assertTrue(level.initialGold >= 0 && level.waveClearGold >= 0)
            assertEquals(20, level.activeMonsterCap)
            assertTrue(level.twoStarHealthRatio in 0f..1f)
            assertTrue(level.threeStarHealthRatio in level.twoStarHealthRatio..1f)
            assertTrue(level.waves.flatMap { it.groups }.all { it.count > 0 })
        }
    }

    @Test
    fun levelCurvesUseExactStageRatesAndFifteenGoldSteps() {
        val levels = LevelCatalog.all
        (2..3).forEach { levelId ->
            assertEquals(1.18f, levels[levelId - 1].healthMultiplier / levels[levelId - 2].healthMultiplier, .0001f)
            assertEquals(1.04f, levels[levelId - 1].speedMultiplier / levels[levelId - 2].speedMultiplier, .0001f)
        }
        (4..12).forEach { levelId ->
            assertEquals(1.16f, levels[levelId - 1].healthMultiplier / levels[levelId - 2].healthMultiplier, .0001f)
            assertEquals(1.04f, levels[levelId - 1].speedMultiplier / levels[levelId - 2].speedMultiplier, .0001f)
        }
        (13..20).forEach { levelId ->
            assertEquals(1.12f, levels[levelId - 1].healthMultiplier / levels[levelId - 2].healthMultiplier, .0001f)
            assertEquals(1.03f, levels[levelId - 1].speedMultiplier / levels[levelId - 2].speedMultiplier, .0001f)
        }
        assertTrue(levels.zipWithNext().all { (a, b) -> b.initialGold - a.initialGold == 15 })
    }

    @Test
    fun everyBossWaveContainsBossAndAccompanyingSmallBugs() {
        listOf(5, 10, 15, 20).forEach { levelId ->
            val finalGroups = LevelCatalog.get(levelId).waves.last().groups
            assertTrue(finalGroups.any { it.type == MonsterType.BOSS })
            assertTrue(finalGroups.any { it.type == MonsterType.SMALL_BUG && it.count > 0 })
        }
    }
}
