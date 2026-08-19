package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.Tower
import com.example.desktopfortress.domain.model.TowerBalanceTable
import com.example.desktopfortress.domain.model.TowerType
import com.pico.spatial.core.math.Vector3
import java.util.ArrayDeque

class TowerPool {
    private data class Key(val type: TowerType, val level: Int)

    private val cached = mutableMapOf<Key, ArrayDeque<Tower>>()
    private var nextId = 1L

    fun obtain(type: TowerType, level: Int, coordinate: CellCoordinate, worldPosition: Vector3): Tower {
        val config = TowerBalanceTable.get(type, level)
        val tower = cached.getOrPut(Key(type, level), ::ArrayDeque).pollFirst()
            ?: Tower(nextId++, config)
        tower.activate(config, coordinate, worldPosition)
        return tower
    }

    fun recycle(tower: Tower) {
        val key = Key(tower.config.type, tower.currentLevel)
        tower.deactivate()
        cached.getOrPut(key, ::ArrayDeque).addLast(tower)
    }

    fun cachedCount(type: TowerType, level: Int): Int = cached[Key(type, level)]?.size ?: 0

    fun clear() = cached.clear()
}
