package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

enum class MonsterType(val displayName: String) {
    SMALL_BUG("小型虫"),
    SWIFT_BUG("疾行虫"),
    ARMORED_BEETLE("重甲甲虫"),
    EXPLODING_WORM("自爆蠕虫"),
    ACID_SPITTER("远程吐酸怪"),
    ELITE_GUARD("精英守卫"),
    BOSS("Boss"),
}

enum class MonsterConfigSource { USER_SPECIFIED_SMOOTH_PROGRESSION_V2 }

data class MonsterConfig(
    val type: MonsterType,
    val name: String,
    val baseHealth: Float,
    val movementSpeedMetersPerSecond: Float,
    val endpointDamage: Int,
    val killGold: Int,
    val modelResource: String,
    val source: MonsterConfigSource = MonsterConfigSource.USER_SPECIFIED_SMOOTH_PROGRESSION_V2,
)

object MonsterConfigTable {
    const val KILL_GOLD_SCALE = .60f
    private val bossBaseHealthByLevel = mapOf(5 to 2_000f, 10 to 5_000f, 15 to 9_000f, 20 to 15_000f)

    val all = listOf(
        MonsterConfig(MonsterType.SMALL_BUG, "小型虫", 60f, .15f, 5, 5, "procedural://monster/small_bug"),
        MonsterConfig(MonsterType.SWIFT_BUG, "疾行虫", 45f, .25f, 4, 6, "procedural://monster/swift_bug"),
        MonsterConfig(MonsterType.ARMORED_BEETLE, "重甲甲虫", 260f, .08f, 12, 12, "procedural://monster/armored_beetle"),
        MonsterConfig(MonsterType.EXPLODING_WORM, "自爆蠕虫", 120f, .12f, 15, 11, "procedural://monster/exploding_worm"),
        MonsterConfig(MonsterType.ACID_SPITTER, "远程吐酸怪", 150f, .10f, 3, 15, "procedural://monster/acid_spitter"),
        MonsterConfig(MonsterType.ELITE_GUARD, "精英守卫", 650f, .10f, 20, 36, "procedural://monster/elite_guard"),
        MonsterConfig(MonsterType.BOSS, "堡垒吞噬者", 2_000f, .07f, 40, 120, "procedural://monster/boss"),
    )
    private val byType = all.associateBy(MonsterConfig::type)

    init {
        require(all.size == MonsterType.entries.size)
    }

    fun get(type: MonsterType): MonsterConfig = requireNotNull(byType[type])

    fun getForLevel(type: MonsterType, levelId: Int): MonsterConfig {
        require(levelId in 1..20)
        val standard = get(type)
        return when {
            type == MonsterType.BOSS -> standard.copy(
                baseHealth = bossBaseHealthByLevel[levelId] ?: standard.baseHealth,
            )
            else -> standard
        }
    }
}

enum class MonsterStatusType { SLOW, FREEZE, STUN }

data class MonsterBuff(
    val type: MonsterStatusType,
    var remainingSeconds: Float,
    val movementMultiplier: Float = 1f,
    /** Same-key slows keep the strongest value; different keys multiply before the global cap. */
    val stackingKey: String = type.name,
)

typealias MonsterStatus = MonsterBuff
typealias Monster = BaseMonster

sealed interface MonsterAction {
    data class ReachedEndpoint(val damage: Int) : MonsterAction
    data class RemoteEndpointAttack(val damage: Int) : MonsterAction
}

/**
 * Poolable gameplay entity. Logical Y is always [desktopHeight] and never contains a visual bob.
 */
abstract class BaseMonster internal constructor(
    val poolObjectId: Int,
    val type: MonsterType,
) {
    var id: Long = -1L
        private set
    lateinit var config: MonsterConfig
        private set
    var currentHealth: Float = 0f
        private set
    var maxHealth: Float = 0f
        private set
    var movementSpeed: Float = 0f
        private set
    var worldPosition: Vector3 = Vector3.ZERO
        private set
    var pathIndex: Int = 0
        private set
    var pathProgress: Float = 0f
        internal set
    var facingYawRadians: Float = 0f
        private set
    var active: Boolean = false
        private set
    var desktopHeight: Float = 0f
        private set
    val health: Float get() = currentHealth
    val isAlive: Boolean get() = active && currentHealth > 0f
    val buffs: List<MonsterBuff> get() = buffByKey.values.toList()
    open val visualScale: Float = 1f
    protected open val rangedStopDistanceMeters: Float? = null
    protected open val rangedAttackIntervalSeconds: Float = 1.5f

    private val pathPoints = mutableListOf<Vector3>()
    private val buffByKey = linkedMapOf<String, MonsterBuff>()
    private var rangedAttackTimer = 0f
    private var totalPathLength = 0f
    private var distanceTravelled = 0f
    private var hardControlResistanceSeconds = 0f
    private var repeatedHardControlAttempt = 0

    internal fun activate(
        instanceId: Long,
        config: MonsterConfig,
        path: List<Vector3>,
        healthMultiplier: Float,
        speedMultiplier: Float,
        desktopHeight: Float,
    ) {
        require(path.isNotEmpty())
        id = instanceId
        this.config = config
        maxHealth = config.baseHealth * healthMultiplier
        currentHealth = maxHealth
        movementSpeed = config.movementSpeedMetersPerSecond * speedMultiplier
        this.desktopHeight = desktopHeight
        pathPoints.clear()
        pathPoints += path.map { Vector3(it.x, desktopHeight, it.z) }
        pathIndex = 0
        pathProgress = 0f
        worldPosition = pathPoints.first()
        facingYawRadians = 0f
        rangedAttackTimer = 0f
        totalPathLength = pathPoints.zipWithNext(::distanceXZ).sum()
        distanceTravelled = 0f
        buffByKey.clear()
        hardControlResistanceSeconds = 0f
        repeatedHardControlAttempt = 0
        active = true
    }

    fun updateMovement(deltaSeconds: Float): MonsterAction? {
        if (!active || deltaSeconds <= 0f || !deltaSeconds.isFinite()) return null
        tickBuffs(deltaSeconds)
        rangedStopDistanceMeters?.let { stopDistance ->
            if (remainingPathDistance() <= stopDistance) {
                rangedAttackTimer -= deltaSeconds
                if (rangedAttackTimer <= 0f) {
                    rangedAttackTimer += rangedAttackIntervalSeconds
                    return MonsterAction.RemoteEndpointAttack(config.endpointDamage)
                }
                return null
            }
        }
        val speedFactor = effectiveMovementMultiplier()
        if (speedFactor <= 0f) return null
        var travelBudget = movementSpeed * speedFactor * deltaSeconds
        while (travelBudget > 0f && pathIndex < pathPoints.lastIndex) {
            val target = pathPoints[pathIndex + 1]
            val dx = target.x - worldPosition.x
            val dz = target.z - worldPosition.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance <= .0001f) {
                pathIndex++
                continue
            }
            facingYawRadians = atan2(dx, dz)
            val step = minOf(travelBudget, distance)
            worldPosition = Vector3(
                worldPosition.x + dx / distance * step,
                desktopHeight,
                worldPosition.z + dz / distance * step,
            )
            travelBudget -= step
            distanceTravelled += step
            pathProgress = if (totalPathLength <= .0001f) {
                1f
            } else {
                (distanceTravelled / totalPathLength).coerceIn(0f, 1f)
            }
            if (step >= distance - .0001f) pathIndex++
        }
        worldPosition = Vector3(worldPosition.x, desktopHeight, worldPosition.z)
        return if (pathIndex >= pathPoints.lastIndex) onReachedEndpoint() else null
    }

    fun takeDamage(amount: Float): Boolean {
        if (!active || amount <= 0f) return false
        currentHealth = (currentHealth - amount).coerceAtLeast(0f)
        return currentHealth <= 0f
    }

    fun applyDamage(amount: Float) {
        takeDamage(amount)
    }

    fun applyBuff(buff: MonsterBuff) {
        if (!active || buff.remainingSeconds <= 0f) return
        val hardControl = buff.type == MonsterStatusType.FREEZE || buff.type == MonsterStatusType.STUN
        if (hardControl && hardControlResistanceSeconds > 0f) {
            repeatedHardControlAttempt++
            // Deterministic 50% gate avoids flaky frame-order-dependent random outcomes.
            if (repeatedHardControlAttempt % 2 != 0) return
        }
        if (hardControl) hardControlResistanceSeconds = HARD_CONTROL_RESISTANCE_SECONDS

        val normalized = if (buff.type == MonsterStatusType.SLOW) {
            buff.copy(movementMultiplier = buff.movementMultiplier.coerceIn(MIN_SLOW_MOVEMENT_MULTIPLIER, 1f))
        } else {
            buff.copy()
        }
        val key = if (normalized.type == MonsterStatusType.SLOW) {
            "${normalized.type}:${normalized.stackingKey}"
        } else {
            normalized.type.name
        }
        val current = buffByKey[key]
        if (current == null) {
            buffByKey[key] = normalized
        } else {
            buffByKey[key] = normalized.copy(
                remainingSeconds = maxOf(current.remainingSeconds, normalized.remainingSeconds),
                movementMultiplier = minOf(current.movementMultiplier, normalized.movementMultiplier),
            )
        }
    }

    fun applyStatus(status: MonsterStatus) = applyBuff(status)

    internal fun translateWorld(delta: Vector3) {
        worldPosition = Vector3(
            worldPosition.x + delta.x,
            desktopHeight,
            worldPosition.z + delta.z,
        )
        pathPoints.indices.forEach { index ->
            val point = pathPoints[index]
            pathPoints[index] = Vector3(point.x + delta.x, desktopHeight, point.z + delta.z)
        }
    }

    open fun die() {
        currentHealth = 0f
    }

    open fun recycle() {
        active = false
        currentHealth = 0f
        pathPoints.clear()
        buffByKey.clear()
        pathIndex = 0
        pathProgress = 0f
        rangedAttackTimer = 0f
        distanceTravelled = 0f
    }

    protected open fun onReachedEndpoint(): MonsterAction = MonsterAction.ReachedEndpoint(config.endpointDamage)

    private fun tickBuffs(deltaSeconds: Float) {
        hardControlResistanceSeconds = (hardControlResistanceSeconds - deltaSeconds).coerceAtLeast(0f)
        if (hardControlResistanceSeconds == 0f) repeatedHardControlAttempt = 0
        val iterator = buffByKey.iterator()
        while (iterator.hasNext()) {
            val buff = iterator.next().value
            buff.remainingSeconds -= deltaSeconds
            if (buff.remainingSeconds <= 0f) iterator.remove()
        }
    }

    private fun effectiveMovementMultiplier(): Float {
        if (buffByKey.values.any { it.type == MonsterStatusType.FREEZE || it.type == MonsterStatusType.STUN }) {
            return 0f
        }
        return buffByKey.values
            .asSequence()
            .filter { it.type == MonsterStatusType.SLOW }
            .fold(1f) { result, slow -> result * slow.movementMultiplier }
            .coerceIn(MIN_SLOW_MOVEMENT_MULTIPLIER, 1f)
    }

    private fun remainingPathDistance(): Float {
        if (pathIndex >= pathPoints.lastIndex) return 0f
        var result = distanceXZ(worldPosition, pathPoints[pathIndex + 1])
        for (index in pathIndex + 1 until pathPoints.lastIndex) {
            result += distanceXZ(pathPoints[index], pathPoints[index + 1])
        }
        return result
    }

    private fun distanceXZ(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private companion object {
        const val MIN_SLOW_MOVEMENT_MULTIPLIER = .40f
        const val HARD_CONTROL_RESISTANCE_SECONDS = 3f
    }
}

class SmallBugMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.SMALL_BUG)
class SwiftBugMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.SWIFT_BUG)
class ArmoredBeetleMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.ARMORED_BEETLE) {
    override val visualScale = 1.15f
}
class ExplodingWormMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.EXPLODING_WORM)
class AcidSpitterMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.ACID_SPITTER) {
    override val rangedStopDistanceMeters = 2f
    override val rangedAttackIntervalSeconds = 1f
}
class EliteGuardMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.ELITE_GUARD) {
    override val visualScale = 1.35f
}
class BossMonster internal constructor(poolId: Int) : BaseMonster(poolId, MonsterType.BOSS) {
    override val visualScale = 1.80f
}

data class MonsterSnapshot(
    val poolObjectId: Int,
    val instanceId: Long,
    val type: MonsterType,
    val worldPosition: Vector3,
    val facingYawRadians: Float,
    val healthRatio: Float,
    val visualScale: Float,
)
