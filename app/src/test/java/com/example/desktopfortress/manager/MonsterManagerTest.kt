package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.data.local.MemoryPreferenceStore
import com.example.desktopfortress.data.local.PreferencesManager
import com.pico.spatial.core.math.Vector3
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterManagerTest {
    private val path = listOf(Vector3(0f, .7f, 0f), Vector3(1f, .7f, 0f))

    @Before
    fun setUp() {
        PreferencesManager.initializeForTesting(MemoryPreferenceStore())
        CodexManager.refresh()
        AchievementManager.refresh()
        DevelopManager.refresh()
        MonsterManager.configureActiveCap(MonsterManager.MAX_ACTIVE_MONSTERS)
    }

    @After
    fun tearDown() {
        MonsterManager.clear()
        MonsterManager.setListener(null)
    }

    @Test
    fun activeCapDefersTwentyFirstSpawn() {
        repeat(MonsterManager.MAX_ACTIVE_MONSTERS) {
            MonsterManager.spawn(MonsterType.SMALL_BUG, path, 1f, 1f, .7f)
        }

        assertEquals(20, MonsterManager.activeCount())
        assertNull(MonsterManager.spawn(MonsterType.SWIFT_BUG, path, 1f, 1f, .7f))
    }

    @Test
    fun recycledMonsterIsObtainedFromSameTypedPool() {
        val first = requireNotNull(MonsterManager.spawn(MonsterType.ARMORED_BEETLE, path, 1f, 1f, .7f))
        MonsterManager.recycleAll()
        val second = requireNotNull(MonsterManager.spawn(MonsterType.ARMORED_BEETLE, path, 1f, 1f, .7f))

        assertSame(first, second)
        assertEquals(first.poolObjectId, second.poolObjectId)
    }

    @Test
    fun spawnAppliesLevelHealthAndSpeedMultipliers() {
        val monster = requireNotNull(MonsterManager.spawn(MonsterType.SMALL_BUG, path, 2f, 1.5f, .7f))

        assertEquals(120f, monster.maxHealth, .0001f)
        assertEquals(.225f, monster.movementSpeed, .0001f)
        monster.updateMovement(1f)
        assertTrue(monster.worldPosition.x > .22f)
        assertEquals(.7f, monster.worldPosition.y, 0f)
    }
}
