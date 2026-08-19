package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterModelsTest {
    @Test
    fun tableDefinesExactlySevenTypedConfigurations() {
        assertEquals(7, MonsterConfigTable.all.size)
        assertEquals(MonsterType.entries.toSet(), MonsterConfigTable.all.map { it.type }.toSet())
    }

    @Test
    fun levelSpecificTemplatesMatchSmoothBalance() {
        val firstLevelBug = MonsterConfigTable.getForLevel(MonsterType.SMALL_BUG, 1)
        assertEquals(60f, firstLevelBug.baseHealth, 0f)
        assertEquals(.15f, firstLevelBug.movementSpeedMetersPerSecond, 0f)
        assertEquals(5, firstLevelBug.endpointDamage)
        assertEquals(5, firstLevelBug.killGold)

        assertEquals(60f, MonsterConfigTable.getForLevel(MonsterType.SMALL_BUG, 2).baseHealth, 0f)
        assertEquals(15, MonsterConfigTable.get(MonsterType.EXPLODING_WORM).endpointDamage)
        assertEquals(3, MonsterConfigTable.get(MonsterType.ACID_SPITTER).endpointDamage)
        assertEquals(2_000f, MonsterConfigTable.getForLevel(MonsterType.BOSS, 5).baseHealth, 0f)
        assertEquals(5_000f, MonsterConfigTable.getForLevel(MonsterType.BOSS, 10).baseHealth, 0f)
        assertEquals(9_000f, MonsterConfigTable.getForLevel(MonsterType.BOSS, 15).baseHealth, 0f)
        assertEquals(15_000f, MonsterConfigTable.getForLevel(MonsterType.BOSS, 20).baseHealth, 0f)
        assertEquals(listOf(5, 6, 12, 11, 15, 36, 120), MonsterConfigTable.all.map { it.killGold })
    }

    @Test
    fun movementIsSmoothAndYRemainsLockedToDesktop() {
        val monster = SmallBugMonster(1)
        monster.activate(
            instanceId = 1,
            config = MonsterConfigTable.get(MonsterType.SMALL_BUG),
            path = listOf(Vector3(0f, .74f, 0f), Vector3(.30f, .74f, 0f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = .74f,
        )

        monster.updateMovement(.5f)

        assertEquals(.075f, monster.worldPosition.x, .0001f)
        assertEquals(.74f, monster.worldPosition.y, 0f)
        assertTrue(monster.pathProgress in .24f..26f)
    }

    @Test
    fun freezeStopsAndSlowReducesMovementUntilBuffExpires() {
        val monster = SmallBugMonster(2)
        monster.activate(
            instanceId = 2,
            config = MonsterConfigTable.get(MonsterType.SMALL_BUG),
            path = listOf(Vector3.ZERO, Vector3(1f, 0f, 0f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = .8f,
        )
        monster.applyBuff(MonsterBuff(MonsterStatusType.FREEZE, 1f, 0f))
        monster.updateMovement(.5f)
        assertEquals(0f, monster.worldPosition.x, .0001f)

        monster.updateMovement(.6f)
        monster.applyBuff(MonsterBuff(MonsterStatusType.SLOW, 1f, .5f))
        monster.updateMovement(.5f)
        assertEquals(.1275f, monster.worldPosition.x, .0001f)
        assertEquals(.8f, monster.worldPosition.y, 0f)
    }

    @Test
    fun differentSlowSourcesMultiplyButNeverExceedSixtyPercentSlow() {
        val monster = SmallBugMonster(3)
        monster.activate(
            instanceId = 3,
            config = MonsterConfigTable.get(MonsterType.SMALL_BUG),
            path = listOf(Vector3.ZERO, Vector3(2f, 0f, 0f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = 0f,
        )
        monster.applyBuff(MonsterBuff(MonsterStatusType.SLOW, 2f, .50f, "FROST"))
        monster.applyBuff(MonsterBuff(MonsterStatusType.SLOW, 2f, .50f, "OTHER"))

        monster.updateMovement(1f)

        assertEquals(.06f, monster.worldPosition.x, .0001f)
    }

    @Test
    fun repeatedHardControlUsesFiftyPercentGateInsideThreeSeconds() {
        val monster = SmallBugMonster(4)
        monster.activate(
            instanceId = 4,
            config = MonsterConfigTable.get(MonsterType.SMALL_BUG),
            path = listOf(Vector3.ZERO, Vector3(2f, 0f, 0f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = 0f,
        )
        monster.applyBuff(MonsterBuff(MonsterStatusType.FREEZE, .1f, 0f))
        monster.applyBuff(MonsterBuff(MonsterStatusType.STUN, 1f, 0f)) // first repeat is rejected
        monster.updateMovement(.11f)
        val afterRejectedRepeat = monster.worldPosition.x
        assertTrue(afterRejectedRepeat > 0f)

        monster.applyBuff(MonsterBuff(MonsterStatusType.STUN, 1f, 0f)) // second repeat is accepted
        monster.updateMovement(.2f)
        assertEquals(afterRejectedRepeat, monster.worldPosition.x, .0001f)
    }

    @Test
    fun acidSpitterDealsThreeDamagePerOneSecondAttackCycle() {
        val monster = AcidSpitterMonster(5)
        monster.activate(
            instanceId = 5,
            config = MonsterConfigTable.get(MonsterType.ACID_SPITTER),
            path = listOf(Vector3.ZERO, Vector3(1f, 0f, 0f)),
            healthMultiplier = 1f,
            speedMultiplier = 1f,
            desktopHeight = 0f,
        )

        assertEquals(MonsterAction.RemoteEndpointAttack(3), monster.updateMovement(.01f))
        assertEquals(null, monster.updateMovement(.98f))
        assertEquals(MonsterAction.RemoteEndpointAttack(3), monster.updateMovement(.02f))
    }
}
