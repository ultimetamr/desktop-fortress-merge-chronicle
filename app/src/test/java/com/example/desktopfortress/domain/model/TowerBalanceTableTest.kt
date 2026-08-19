package com.example.desktopfortress.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TowerBalanceTableTest {
    @Test
    fun containsExactlyFiveTiersForEachOfFourLines() {
        assertEquals(20, TowerBalanceTable.all.size)
        TowerType.entries.forEach { type ->
            assertEquals((1..5).toList(), TowerBalanceTable.all.filter { it.type == type }.map { it.level })
        }
    }

    @Test
    fun levelThreeAndFiveTraitsArePresent() {
        TowerType.entries.forEach { type ->
            assertTrue(TowerBalanceTable.get(type, 3).exclusiveTraits.isNotEmpty())
            assertTrue(TowerBalanceTable.get(type, 5).exclusiveTraits.size >= 2)
        }
    }

    @Test
    fun everyRowIsMarkedAsUserSpecifiedSmoothProgression() {
        assertTrue(TowerBalanceTable.all.all {
            it.source == TowerConfigSource.USER_SPECIFIED_SMOOTH_PROGRESSION_V2
        })
    }

    @Test
    fun everyLineUsesCompoundedFifteenFiveThreeGrowth() {
        TowerType.entries.forEach { type ->
            val base = TowerBalanceTable.get(type, 1)
            (2..5).forEach { level ->
                val tier = TowerBalanceTable.get(type, level)
                val steps = level - 1
                assertEquals(base.damage * 1.15f.pow(steps), tier.damage, .0001f)
                assertEquals(base.attackSpeed * 1.05f.pow(steps), tier.attackSpeed, .0001f)
                assertEquals(base.attackRangeMeters * 1.03f.pow(steps), tier.attackRangeMeters, .0001f)
            }
            val dpsRatio = with(TowerBalanceTable.get(type, 5)) { damage * attackSpeed } /
                (base.damage * base.attackSpeed)
            assertEquals(2.126f, dpsRatio, .002f)
        }
    }

    @Test
    fun firstLevelEconomyCanBuyArcherAndKeepFiftyGold() {
        assertEquals(100, TowerBalanceTable.get(TowerType.ARCHER, 1).cost)
        assertEquals(50, 150 - TowerBalanceTable.get(TowerType.ARCHER, 1).cost)
    }

    @Test
    fun mergeGrowthCompressesBoardSpaceButDoesNotMultiplyRawDpsByInputsConsumed() {
        TowerType.entries.forEach { type ->
            val levelOne = TowerBalanceTable.get(type, 1)
            val levelTwo = TowerBalanceTable.get(type, 2)
            val levelThree = TowerBalanceTable.get(type, 3)
            val baseDps = levelOne.damage * levelOne.attackSpeed
            assertTrue(levelTwo.damage * levelTwo.attackSpeed < baseDps * 2f)
            assertTrue(levelThree.damage * levelThree.attackSpeed < baseDps * 4f)
        }
    }
}
