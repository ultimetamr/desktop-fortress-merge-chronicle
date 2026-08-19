package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.TowerType
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

class TowerPoolTest {
    @Test
    fun recycledTowerIsReusedWithinSameTypeAndLevelBucket() {
        val pool = TowerPool()
        val first = pool.obtain(TowerType.ARCHER, 2, CellCoordinate(0, 0), Vector3.ZERO)
        val id = first.instanceId
        pool.recycle(first)
        assertEquals(1, pool.cachedCount(TowerType.ARCHER, 2))

        val reused = pool.obtain(TowerType.ARCHER, 2, CellCoordinate(0, 1), Vector3.ONE)
        assertEquals(id, reused.instanceId)
        assertEquals(CellCoordinate(0, 1), reused.coordinate)
    }

    @Test
    fun projectilePoolHardCapsActiveInstancesAtTwenty() {
        val pool = ProjectilePool(20)
        repeat(27) { pool.obtain() }
        assertEquals(20, pool.activeCount())
    }
}
