package com.example.desktopfortress.domain.model

import kotlin.math.pow

/**
 * Single source of truth for all 20 tower tiers.
 *
 * User-specified smooth-progression balance. The project owns four implemented tower lines.
 * Tier statistics compound from level one: damage +15%, attack speed +5%, and range +3% per tier.
 */
object TowerBalanceTable {
    const val DAMAGE_GROWTH_PER_LEVEL = .15f
    const val ATTACK_SPEED_GROWTH_PER_LEVEL = .05f
    const val RANGE_GROWTH_PER_LEVEL = .03f

    val all: List<TowerConfig> = buildList {
        addLine(
            type = TowerType.ARCHER,
            baseDamage = 18f,
            baseAttackSpeed = 1.20f,
            baseRange = .45f,
            splashByLevel = floatArrayOf(0f, 0f, 0f, 0f, 0f),
            costs = intArrayOf(100, 180, 320, 560, 980),
            tierThreeTrait = TowerTrait.RAPID_VOLLEY,
            tierFiveTrait = TowerTrait.CRITICAL_SHOT,
        )
        addLine(
            TowerType.BALLISTA, 36f, .70f, .55f,
            floatArrayOf(0f, 0f, 0f, 0f, 0f), intArrayOf(120, 220, 390, 690, 1_200),
            TowerTrait.PIERCE, TowerTrait.STUN,
        )
        addLine(
            TowerType.EXPLOSIVE, 42f, .60f, .48f,
            floatArrayOf(.12f, .13f, .16f, .18f, .22f), intArrayOf(130, 240, 430, 760, 1_320),
            TowerTrait.EXPANDED_SPLASH, TowerTrait.HEAVY_STUN,
        )
        addLine(
            TowerType.FROST, 12f, 1.00f, .42f,
            floatArrayOf(0f, 0f, 0f, 0f, 0f), intArrayOf(110, 200, 360, 630, 1_100),
            TowerTrait.DEEP_SLOW, TowerTrait.FREEZE,
        )
    }

    private val byTier = all.associateBy { it.type to it.level }

    init {
        require(all.size == TowerType.entries.size * 5)
        require(all.groupBy { it.type }.values.all { tiers -> tiers.map { it.level } == (1..5).toList() })
    }

    fun get(type: TowerType, level: Int): TowerConfig =
        requireNotNull(byTier[type to level]) { "Missing tower config for $type level $level" }

    private fun MutableList<TowerConfig>.addLine(
        type: TowerType,
        baseDamage: Float,
        baseAttackSpeed: Float,
        baseRange: Float,
        splashByLevel: FloatArray,
        costs: IntArray,
        tierThreeTrait: TowerTrait,
        tierFiveTrait: TowerTrait,
    ) {
        require(splashByLevel.size == 5 && costs.size == 5)
        repeat(5) { index ->
            val level = index + 1
            val damageMultiplier = (1f + DAMAGE_GROWTH_PER_LEVEL).pow(index)
            val speedMultiplier = (1f + ATTACK_SPEED_GROWTH_PER_LEVEL).pow(index)
            val rangeMultiplier = (1f + RANGE_GROWTH_PER_LEVEL).pow(index)
            val traits = buildSet {
                if (level >= 3) add(tierThreeTrait)
                if (level == 5) add(tierFiveTrait)
            }
            add(
                TowerConfig(
                    type = type,
                    level = level,
                    quality = TowerQuality.entries[index],
                    damage = baseDamage * damageMultiplier,
                    attackSpeed = baseAttackSpeed * speedMultiplier,
                    attackRangeMeters = baseRange * rangeMultiplier,
                    splashRadiusMeters = splashByLevel[index],
                    cost = costs[index],
                    exclusiveTraits = traits,
                ),
            )
        }
    }
}
