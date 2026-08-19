package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3

enum class TowerType(val displayName: String) {
    ARCHER("弓箭"),
    BALLISTA("弩炮"),
    EXPLOSIVE("爆破"),
    FROST("冰霜"),
}

enum class TowerQuality { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

enum class TowerTrait {
    RAPID_VOLLEY,
    CRITICAL_SHOT,
    PIERCE,
    STUN,
    EXPANDED_SPLASH,
    HEAVY_STUN,
    DEEP_SLOW,
    FREEZE,
}

enum class TowerConfigSource { USER_SPECIFIED_SMOOTH_PROGRESSION_V2 }

data class TowerConfig(
    val type: TowerType,
    val level: Int,
    val quality: TowerQuality,
    val damage: Float,
    /** Attacks per second. */
    val attackSpeed: Float,
    val attackRangeMeters: Float,
    val splashRadiusMeters: Float,
    val cost: Int,
    val exclusiveTraits: Set<TowerTrait>,
    val source: TowerConfigSource = TowerConfigSource.USER_SPECIFIED_SMOOTH_PROGRESSION_V2,
)

/** Mutable runtime entity intentionally reused by [com.example.desktopfortress.manager.TowerPool]. */
open class Tower internal constructor(
    val instanceId: Long,
    config: TowerConfig,
) {
    var config: TowerConfig = config
        private set
    var currentLevel: Int = config.level
        private set
    var worldPosition: Vector3 = Vector3.ZERO
    var coordinate: CellCoordinate? = null
    var attackTimerSeconds: Float = 0f
    var targetMonster: Monster? = null
    var facingYawRadians: Float = 0f
    var active: Boolean = false

    internal fun activate(config: TowerConfig, coordinate: CellCoordinate, worldPosition: Vector3) {
        this.config = config
        currentLevel = config.level
        this.coordinate = coordinate
        this.worldPosition = worldPosition
        attackTimerSeconds = 0f
        targetMonster = null
        facingYawRadians = 0f
        active = true
    }

    internal fun deactivate() {
        coordinate = null
        targetMonster = null
        attackTimerSeconds = 0f
        active = false
    }
}

enum class ProjectileKind { ARROW, BOLT, SHELL, FROST_SHARD }

class Projectile internal constructor(val poolId: Int) {
    var kind: ProjectileKind = ProjectileKind.ARROW
    var sourceTowerId: Long = -1L
    var position: Vector3 = Vector3.ZERO
    var velocity: Vector3 = Vector3.ZERO
    var damage: Float = 0f
    var splashRadiusMeters: Float = 0f
    var maxDistanceMeters: Float = 0f
    var travelledMeters: Float = 0f
    var targetMonsterId: Long? = null
    var remainingPierces: Int = 0
    val hitMonsterIds: MutableSet<Long> = linkedSetOf()
    var statusOnHit: MonsterStatus? = null
    var active: Boolean = false
    var sequence: Long = 0L

    internal fun deactivate() {
        active = false
        targetMonsterId = null
        hitMonsterIds.clear()
        statusOnHit = null
        travelledMeters = 0f
    }
}

data class TowerSnapshot(
    val id: Long,
    val type: TowerType,
    val level: Int,
    val coordinate: CellCoordinate,
    val worldPosition: Vector3,
    val facingYawRadians: Float,
)

data class ProjectileSnapshot(
    val poolId: Int,
    val kind: ProjectileKind,
    val worldPosition: Vector3,
)

enum class DragValidity { VALID_PLACE, VALID_MERGE, VALID_SELL, INVALID }

data class TowerSlotItem(
    val type: TowerType,
    val level: Int = 1,
)

data class TowerInventorySlot(
    val index: Int,
    val item: TowerSlotItem? = null,
)

data class TowerInventoryState(
    val slots: List<TowerInventorySlot> = List(6) { TowerInventorySlot(it) },
) {
    val isFull: Boolean get() = slots.all { it.item != null }
    val occupiedCount: Int get() = slots.count { it.item != null }
}

sealed interface TowerPurchaseResult {
    data class Stored(val slotIndex: Int, val item: TowerSlotItem, val cost: Int) : TowerPurchaseResult
    data class Rejected(val reason: String) : TowerPurchaseResult
}

sealed interface TowerDragSource {
    data class InventorySlot(val slotIndex: Int) : TowerDragSource
    data class Existing(val towerId: Long, val original: CellCoordinate) : TowerDragSource
}

data class TowerDragPreview(
    val source: TowerDragSource,
    val type: TowerType,
    val level: Int,
    val worldPosition: Vector3,
    val snappedCell: CellCoordinate?,
    val validity: DragValidity,
    val overSellZone: Boolean = false,
)

sealed interface TowerOperationResult {
    data class Placed(val towerId: Long, val coordinate: CellCoordinate) : TowerOperationResult
    data class Merged(val towerId: Long, val newLevel: Int, val coordinate: CellCoordinate) : TowerOperationResult
    data class Sold(val towerId: Long, val refund: Int) : TowerOperationResult
    data class SlotSold(val slotIndex: Int, val refund: Int) : TowerOperationResult
    data class Rejected(val reason: String) : TowerOperationResult
    data object Cancelled : TowerOperationResult
}
