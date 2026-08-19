package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.AcidSpitterMonster
import com.example.desktopfortress.domain.model.ArmoredBeetleMonster
import com.example.desktopfortress.domain.model.BaseMonster
import com.example.desktopfortress.domain.model.BossMonster
import com.example.desktopfortress.domain.model.EliteGuardMonster
import com.example.desktopfortress.domain.model.ExplodingWormMonster
import com.example.desktopfortress.domain.model.MonsterType
import com.example.desktopfortress.domain.model.SmallBugMonster
import com.example.desktopfortress.domain.model.SwiftBugMonster
import java.util.ArrayDeque

internal class MonsterPool {
    private val cachedByType = MonsterType.entries.associateWith { ArrayDeque<BaseMonster>() }
    private var nextPoolObjectId = 1

    fun obtain(type: MonsterType): BaseMonster = cachedByType.getValue(type).pollFirst()
        ?: create(type, nextPoolObjectId++)

    fun recycle(monster: BaseMonster) {
        monster.recycle()
        cachedByType.getValue(monster.type).addLast(monster)
    }

    fun cachedCount(type: MonsterType? = null): Int = if (type == null) {
        cachedByType.values.sumOf(ArrayDeque<BaseMonster>::size)
    } else {
        cachedByType.getValue(type).size
    }

    fun clear() = cachedByType.values.forEach(ArrayDeque<BaseMonster>::clear)

    private fun create(type: MonsterType, poolObjectId: Int): BaseMonster = when (type) {
        MonsterType.SMALL_BUG -> SmallBugMonster(poolObjectId)
        MonsterType.SWIFT_BUG -> SwiftBugMonster(poolObjectId)
        MonsterType.ARMORED_BEETLE -> ArmoredBeetleMonster(poolObjectId)
        MonsterType.EXPLODING_WORM -> ExplodingWormMonster(poolObjectId)
        MonsterType.ACID_SPITTER -> AcidSpitterMonster(poolObjectId)
        MonsterType.ELITE_GUARD -> EliteGuardMonster(poolObjectId)
        MonsterType.BOSS -> BossMonster(poolObjectId)
    }
}
