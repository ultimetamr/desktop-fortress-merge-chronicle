package com.example.desktopfortress.manager

import android.util.Log
import com.example.desktopfortress.audio.AudioManager
import com.example.desktopfortress.data.repository.GameRepository
import com.example.desktopfortress.data.repository.InMemoryGameRepository
import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.CellType
import com.example.desktopfortress.domain.model.DragValidity
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.Monster
import com.example.desktopfortress.domain.model.MonsterStatus
import com.example.desktopfortress.domain.model.MonsterStatusType
import com.example.desktopfortress.domain.model.Projectile
import com.example.desktopfortress.domain.model.ProjectileKind
import com.example.desktopfortress.domain.model.ProjectileSnapshot
import com.example.desktopfortress.domain.model.Tower
import com.example.desktopfortress.domain.model.TowerBalanceTable
import com.example.desktopfortress.domain.model.TowerDragPreview
import com.example.desktopfortress.domain.model.TowerDragSource
import com.example.desktopfortress.domain.model.TowerInventorySlot
import com.example.desktopfortress.domain.model.TowerInventoryState
import com.example.desktopfortress.domain.model.TowerInstance
import com.example.desktopfortress.domain.model.TowerOperationResult
import com.example.desktopfortress.domain.model.TowerPurchaseResult
import com.example.desktopfortress.domain.model.TowerSnapshot
import com.example.desktopfortress.domain.model.TowerSlotItem
import com.example.desktopfortress.domain.model.TowerSlotLayout
import com.example.desktopfortress.domain.model.TowerSlotRecoveryItem
import com.example.desktopfortress.domain.model.TowerTrait
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.domain.model.TowerRecoveryItem
import com.example.desktopfortress.effect.EffectManager
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.UserMessage
import com.pico.spatial.core.math.Vector3
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TowerManager : BaseManager() {
    private const val TAG = "TowerPlacementInput"
    private const val SELECTION_GUIDANCE = "武器已拿起，请用射线点击棋盘格完成放置"
    const val INVENTORY_CAPACITY = 6
    const val MAX_PROJECTILES = 20
    const val EARLY_LEVEL_SELL_REFUND_RATIO = .70f
    const val LATE_LEVEL_SELL_REFUND_RATIO = .40f
    const val MAX_SELL_REFUND_RATIO = .90f
    private const val HIT_RADIUS_METERS = 0.045f

    private val repository: GameRepository = InMemoryGameRepository
    private val towerPool = TowerPool()
    private val projectilePool = ProjectilePool(MAX_PROJECTILES)
    private val towersById = linkedMapOf<Long, Tower>()
    private val mutableTowers = MutableStateFlow<List<TowerSnapshot>>(emptyList())
    val towers: StateFlow<List<TowerSnapshot>> = mutableTowers.asStateFlow()
    private val mutableProjectiles = MutableStateFlow<List<ProjectileSnapshot>>(emptyList())
    val projectiles: StateFlow<List<ProjectileSnapshot>> = mutableProjectiles.asStateFlow()
    private val mutableDragPreview = MutableStateFlow<TowerDragPreview?>(null)
    val dragPreview: StateFlow<TowerDragPreview?> = mutableDragPreview.asStateFlow()
    private var pointerTargetAuthoritative = false
    private val inventoryItems = MutableList<TowerSlotItem?>(INVENTORY_CAPACITY) { null }
    private val mutableInventory = MutableStateFlow(TowerInventoryState())
    val inventory: StateFlow<TowerInventoryState> = mutableInventory.asStateFlow()
    val gold: StateFlow<Int> = GoldManager.gold
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        initialized = true
    }

    /** Purchase is finalized into the first free slot; TowerScene renders its pooled Stage preview. */
    fun purchaseToSlot(type: TowerType, level: Int = 1): TowerPurchaseResult {
        if (!canEdit()) return rejectPurchase("战斗中无法购买或整理卡槽")
        if (level != 1) return rejectPurchase("购买栏仅提供 1 级基础塔")
        val config = TowerBalanceTable.get(type, level)
        if (GoldManager.getCurrentGold() < config.cost) return rejectPurchase("金币不足：需要 ${config.cost}")
        if (!GoldManager.costGold(config.cost)) return rejectPurchase("金币不足：需要 ${config.cost}")
        val slotIndex = inventoryItems.indexOfFirst { it == null }
        if (slotIndex < 0) {
            // Full-state races are transactionally safe: payment is immediately restored.
            GoldManager.refundGold(config.cost)
            return rejectPurchase("卡槽已满，请先放置或出售")
        }
        val item = TowerSlotItem(type, level)
        inventoryItems[slotIndex] = item
        publishInventory()
        AudioManager.playTowerPurchased()
        return TowerPurchaseResult.Stored(slotIndex, item, config.cost)
    }

    fun beginSlotDrag(slotIndex: Int): Boolean {
        if (!canEdit()) return reject("战斗中无法操作卡槽")
        val item = inventoryItems.getOrNull(slotIndex) ?: return false
        if (mutableDragPreview.value != null) cancelDrag()
        val board = BoardManager.board.value
        val startLocal = TowerSlotLayout.slotCenter(board, slotIndex)
        pointerTargetAuthoritative = false
        mutableDragPreview.value = TowerDragPreview(
            source = TowerDragSource.InventorySlot(slotIndex),
            type = item.type,
            level = item.level,
            worldPosition = board.localToWorld(startLocal),
            snappedCell = null,
            validity = DragValidity.INVALID,
        )
        AudioManager.playTowerGrabbed()
        return true
    }

    /**
     * Starts the first half of the two-tap placement flow. Repeated input on
     * the same slot is idempotent so controller trigger bounce cannot undo it.
     */
    fun selectInventorySlot(slotIndex: Int): Boolean {
        val selectedSource = mutableDragPreview.value?.source as? TowerDragSource.InventorySlot
        if (selectedSource?.slotIndex == slotIndex) {
            logInput("selection retained slot=$slotIndex")
            EventBus.tryEmit(UserMessage(SELECTION_GUIDANCE))
            return true
        }
        val selected = beginSlotDrag(slotIndex)
        logInput("slot selection slot=$slotIndex accepted=$selected state=${repository.gameState.value}")
        if (selected) EventBus.tryEmit(UserMessage(SELECTION_GUIDANCE))
        return selected
    }

    fun beginExisting(towerId: Long): Boolean {
        if (!canEdit()) return reject("战斗阶段塔已锁定")
        val tower = towersById[towerId] ?: return false
        val coordinate = tower.coordinate ?: return false
        BoardManager.previewCell(null)
        EffectManager.clearAt(tower.worldPosition)
        pointerTargetAuthoritative = false
        mutableDragPreview.value = TowerDragPreview(
            source = TowerDragSource.Existing(towerId, coordinate),
            type = tower.config.type,
            level = tower.currentLevel,
            worldPosition = tower.worldPosition,
            snappedCell = coordinate,
            validity = DragValidity.VALID_PLACE,
        )
        AudioManager.playTowerGrabbed()
        EventBus.tryEmit(UserMessage("棋盘武器已拿起：点击其他格移动，或点击出售按钮"))
        return true
    }

    /** Completes selection using the SDK-resolved cell entity, without ray reconstruction. */
    fun confirmSelectionAtCell(coordinate: CellCoordinate): TowerOperationResult {
        if (mutableDragPreview.value == null) return TowerOperationResult.Cancelled
        updateDragToCell(coordinate) ?: return TowerOperationResult.Cancelled
        return releaseDrag().also { result ->
            logInput("cell confirmation cell=$coordinate result=${result::class.simpleName}")
        }
    }

    private fun logInput(message: String) {
        // android.util.Log is unavailable in local JVM tests.
        runCatching { Log.i(TAG, message) }
    }

    /** Ray/gesture hit already arrives in Stage world meters. */
    fun updateDragWorld(worldPosition: Vector3, overSellZone: Boolean = false): TowerDragPreview? {
        val current = mutableDragPreview.value ?: return null
        if (!canEdit()) {
            cancelDrag()
            return null
        }
        val coordinate = if (overSellZone) null else BoardManager.coordinateAtWorld(worldPosition)
        val cell = BoardManager.cellAt(coordinate)
        val snapped = coordinate?.let(BoardManager::cellWorldCenter) ?: worldPosition
        val validity = when {
            overSellZone && current.source is TowerDragSource.Existing -> DragValidity.VALID_SELL
            cell == null || cell.type != CellType.PLACEABLE -> DragValidity.INVALID
            cell.tower == null -> DragValidity.VALID_PLACE
            cell.tower.kind == current.type.name && cell.tower.level == current.level && current.level < 5 -> {
                val sourceId = (current.source as? TowerDragSource.Existing)?.towerId
                if (cell.tower.id == sourceId?.toString()) DragValidity.VALID_PLACE else DragValidity.VALID_MERGE
            }
            else -> DragValidity.INVALID
        }
        val next = current.copy(
            worldPosition = snapped,
            snappedCell = coordinate,
            validity = validity,
            overSellZone = overSellZone,
        )
        if (next != current) mutableDragPreview.value = next
        BoardManager.previewCell(coordinate)
        return next
    }

    /** Applies a Stage ray hit while preserving the board-side sell-zone contract. */
    fun updateDragFromRay(worldPosition: Vector3): TowerDragPreview? {
        pointerTargetAuthoritative = true
        val board = BoardManager.board.value
        val overSellZone = TowerSlotLayout.isInSellZone(board, board.worldToLocal(worldPosition))
        return updateDragWorld(worldPosition, overSellZone)
    }

    /** Uses the SDK-resolved cell entity, avoiding device-pose axis assumptions. */
    fun updateDragToCell(coordinate: CellCoordinate): TowerDragPreview? =
        updateDragFromRay(BoardManager.cellWorldCenter(coordinate))

    /** Clears the last snap when a pointing ray no longer intersects the board. */
    fun invalidateDragTarget(): TowerDragPreview? {
        val current = mutableDragPreview.value ?: return null
        pointerTargetAuthoritative = true
        val next = current.copy(
            snappedCell = null,
            validity = DragValidity.INVALID,
            overSellZone = false,
        )
        if (next != current) mutableDragPreview.value = next
        BoardManager.previewCell(null)
        return next
    }

    /**
     * Applies physical view-space meters to the horizontal board plane.
     * View +Y points down, so a negative vertical drag moves toward board-local -Z.
     */
    fun updateDragByBoardViewMeters(
        deltaHorizontalMeters: Float,
        deltaVerticalMeters: Float,
        deltaDepthMeters: Float = 0f,
    ): TowerDragPreview? {
        val current = mutableDragPreview.value ?: return null
        if (pointerTargetAuthoritative) return current
        val board = BoardManager.board.value
        val currentLocal = board.worldToLocal(current.worldPosition)
        val inverseScale = 1f / board.transform.scale.coerceAtLeast(.01f)
        val nextLocal = Vector3(
            currentLocal.x + deltaHorizontalMeters * inverseScale,
            0f,
            currentLocal.z + (deltaVerticalMeters + deltaDepthMeters) * inverseScale,
        )
        val next = board.localToWorld(nextLocal)
        val local = board.worldToLocal(next)
        val overSell = TowerSlotLayout.isInSellZone(board, local)
        return updateDragWorld(next, overSell)
    }

    /** World-meter compatibility entry point used by tests and non-View callers. */
    fun updateDragByWorldDelta(deltaX: Float, deltaZ: Float): TowerDragPreview? =
        updateDragByBoardViewMeters(deltaX, deltaZ)

    fun releaseDrag(): TowerOperationResult {
        val preview = mutableDragPreview.value ?: return TowerOperationResult.Cancelled
        if (!canEdit()) return rejectAndRestore(preview, "当前阶段禁止移动或合成")
        return when (preview.validity) {
            DragValidity.VALID_SELL -> sell(preview)
            DragValidity.VALID_PLACE -> place(preview)
            DragValidity.VALID_MERGE -> merge(preview)
            DragValidity.INVALID -> rejectAndRestore(preview, "目标格不可放置，塔已返回原位")
        }
    }

    fun cancelDrag() {
        mutableDragPreview.value = null
        pointerTargetAuthoritative = false
        BoardManager.previewCell(null)
    }

    fun sellSlot(slotIndex: Int): TowerOperationResult {
        if (!canEdit()) return TowerOperationResult.Rejected("战斗中无法出售卡槽塔").also {
            EventBus.tryEmit(UserMessage(it.reason))
            AudioManager.playInteractionFailure()
        }
        val item = inventoryItems.getOrNull(slotIndex)
            ?: return TowerOperationResult.Rejected("卡槽为空")
        val refund = refundFor(item.type, item.level)
        if ((mutableDragPreview.value?.source as? TowerDragSource.InventorySlot)?.slotIndex == slotIndex) {
            cancelDrag()
        }
        inventoryItems[slotIndex] = null
        publishInventory()
        GoldManager.addGold(refund)
        AudioManager.playTowerSold()
        EventBus.tryEmit(UserMessage("已出售 ${item.type.displayName}，返还 $refund 金币"))
        return TowerOperationResult.SlotSold(slotIndex, refund)
    }

    /** Sells whichever inventory or board tower is currently selected. */
    fun sellSelectedWeapon(): TowerOperationResult {
        if (!canEdit()) return TowerOperationResult.Rejected("战斗中无法出售武器").also {
            EventBus.tryEmit(UserMessage(it.reason))
            AudioManager.playInteractionFailure()
        }
        val preview = mutableDragPreview.value ?: return TowerOperationResult.Cancelled
        return when (val source = preview.source) {
            is TowerDragSource.InventorySlot -> sellSlot(source.slotIndex)
            is TowerDragSource.Existing -> sell(preview)
        }
    }

    fun towerAtWorld(worldPosition: Vector3): TowerSnapshot? {
        val coordinate = BoardManager.coordinateAtWorld(worldPosition) ?: return null
        return mutableTowers.value.firstOrNull { it.coordinate == coordinate }
    }

    fun update(deltaSeconds: Float) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f) return
        if (repository.gameState.value == GameState.FIGHTING) updateTowers(deltaSeconds)
        else towersById.values.forEach { it.targetMonster = null }
        updateProjectiles(deltaSeconds)
        EffectManager.update(deltaSeconds)
        publishSnapshots()
    }

    fun addGold(amount: Int) {
        GoldManager.addGold(amount)
    }

    fun resetSession() {
        clearAll()
        MonsterManager.clear()
    }

    internal fun resetSession(startingGold: Int) {
        resetSession()
        GoldManager.resetForTesting(startingGold)
    }

    fun clearAll() {
        towersById.values.toList().forEach { tower ->
            tower.coordinate?.let { BoardManager.setTower(it, null) }
            towerPool.recycle(tower)
        }
        towersById.clear()
        inventoryItems.indices.forEach { inventoryItems[it] = null }
        publishInventory()
        projectilePool.clear()
        cancelDrag()
        publishSnapshots()
    }

    fun stopCombat() {
        towersById.values.forEach { it.targetMonster = null }
        projectilePool.clear()
        cancelDrag()
        publishSnapshots()
    }

    fun restoreCheckpointTowers(items: List<TowerRecoveryItem>) {
        clearAll()
        items.take(48).forEach { item ->
            if (item.level !in 1..5) return@forEach
            val coordinate = CellCoordinate(item.row, item.column)
            val cell = BoardManager.cellAt(coordinate)
            if (cell?.type != CellType.PLACEABLE || cell.tower != null) return@forEach
            val tower = towerPool.obtain(
                item.type,
                item.level,
                coordinate,
                BoardManager.cellWorldCenter(coordinate),
            )
            towersById[tower.instanceId] = tower
            BoardManager.setTower(coordinate, tower.toBoardInstance())
        }
        publishSnapshots()
    }

    fun recoveryInventorySlots(): List<TowerSlotRecoveryItem> = inventoryItems.mapIndexedNotNull { index, item ->
        item?.let { TowerSlotRecoveryItem(it.type, it.level, index) }
    }

    fun restoreCheckpointInventory(items: List<TowerSlotRecoveryItem>) {
        inventoryItems.indices.forEach { inventoryItems[it] = null }
        items.take(INVENTORY_CAPACITY).forEach { item ->
            if (item.slotIndex !in inventoryItems.indices || item.level !in 1..5) return@forEach
            if (inventoryItems[item.slotIndex] == null) {
                inventoryItems[item.slotIndex] = TowerSlotItem(item.type, item.level)
            }
        }
        publishInventory()
    }

    fun translateWorld(delta: Vector3) {
        towersById.values.forEach { it.worldPosition += delta }
        projectilePool.translateWorld(delta)
        publishSnapshots()
    }

    override fun destroy() {
        clearAll()
        towerPool.clear()
        initialized = false
        cancelScope()
    }

    private fun place(preview: TowerDragPreview): TowerOperationResult {
        val coordinate = requireNotNull(preview.snappedCell)
        val world = BoardManager.cellWorldCenter(coordinate)
        val tower = when (val source = preview.source) {
            is TowerDragSource.InventorySlot -> {
                if (!consumeSlot(source.slotIndex, preview.type, preview.level)) {
                    return rejectAndRestore(preview, "卡槽内容已变化，请重新抓取")
                }
                towerPool.obtain(preview.type, preview.level, coordinate, world).also { towersById[it.instanceId] = it }
            }
            is TowerDragSource.Existing -> {
                val existing = towersById[source.towerId] ?: return rejectAndRestore(preview, "原塔不存在")
                if (source.original != coordinate) BoardManager.setTower(source.original, null)
                existing.coordinate = coordinate
                existing.worldPosition = world
                existing
            }
        }
        BoardManager.setTower(coordinate, tower.toBoardInstance())
        CodexManager.unlockTower(tower.config.type, tower.currentLevel)
        finishDrag()
        AudioManager.playTowerPlaced()
        EffectManager.showPlacementPulse(world, tower.config.type)
        publishSnapshots()
        return TowerOperationResult.Placed(tower.instanceId, coordinate)
    }

    private fun merge(preview: TowerDragPreview): TowerOperationResult {
        val coordinate = requireNotNull(preview.snappedCell)
        val targetCell = requireNotNull(BoardManager.cellAt(coordinate))
        val targetId = targetCell.tower?.id?.toLongOrNull()
            ?: return rejectAndRestore(preview, "合成目标不存在")
        val targetTower = towersById[targetId] ?: return rejectAndRestore(preview, "合成目标已失效")
        if (targetTower.currentLevel >= 5) return rejectAndRestore(preview, "传奇塔已达到最高阶")

        when (val source = preview.source) {
            is TowerDragSource.InventorySlot -> {
                if (!consumeSlot(source.slotIndex, preview.type, preview.level)) {
                    return rejectAndRestore(preview, "卡槽内容已变化，请重新抓取")
                }
            }
            is TowerDragSource.Existing -> {
                val sourceTower = towersById.remove(source.towerId)
                    ?: return rejectAndRestore(preview, "原塔不存在")
                BoardManager.setTower(source.original, null)
                towerPool.recycle(sourceTower)
            }
        }
        towersById.remove(targetTower.instanceId)
        towerPool.recycle(targetTower)
        val nextLevel = preview.level + 1
        val world = BoardManager.cellWorldCenter(coordinate)
        val upgraded = towerPool.obtain(preview.type, nextLevel, coordinate, world)
        towersById[upgraded.instanceId] = upgraded
        BoardManager.setTower(coordinate, upgraded.toBoardInstance())
        CodexManager.unlockTower(upgraded.config.type, upgraded.currentLevel)
        finishDrag()
        AudioManager.playTowerMerged()
        EffectManager.showMergeBurst(world, preview.type, nextLevel)
        publishSnapshots()
        return TowerOperationResult.Merged(upgraded.instanceId, nextLevel, coordinate)
    }

    private fun sell(preview: TowerDragPreview): TowerOperationResult {
        val source = preview.source as? TowerDragSource.Existing
            ?: return rejectAndRestore(preview, "购买栏塔不可出售")
        val tower = towersById.remove(source.towerId)
            ?: return rejectAndRestore(preview, "出售目标不存在")
        val refund = refundFor(tower.config.type, tower.currentLevel)
        GoldManager.addGold(refund)
        BoardManager.setTower(source.original, null)
        val position = tower.worldPosition
        towerPool.recycle(tower)
        finishDrag()
        AudioManager.playTowerSold()
        EffectManager.showSell(position)
        publishSnapshots()
        return TowerOperationResult.Sold(source.towerId, refund)
    }

    private fun updateTowers(deltaSeconds: Float) {
        val monsters = MonsterManager.activeMonsters()
        towersById.values.forEach { tower ->
            tower.attackTimerSeconds -= deltaSeconds
            val target = selectTarget(tower, monsters)
            tower.targetMonster = target
            if (target == null) return@forEach
            val dx = target.worldPosition.x - tower.worldPosition.x
            val dz = target.worldPosition.z - tower.worldPosition.z
            tower.facingYawRadians = atan2(dx, dz)
            if (tower.attackTimerSeconds <= 0f) {
                fire(tower, target)
                val traitMultiplier = if (TowerTrait.RAPID_VOLLEY in tower.config.exclusiveTraits) .85f else 1f
                val attackSpeed = DevelopManager.applyBonus(
                    tower.config.attackSpeed,
                    com.example.desktopfortress.domain.model.DevelopType.TOWER_ATTACK_SPEED,
                )
                tower.attackTimerSeconds = (1f / attackSpeed.coerceAtLeast(.01f)) * traitMultiplier
            }
        }
    }

    private fun selectTarget(tower: Tower, monsters: List<Monster>): Monster? {
        val range = DevelopManager.applyBonus(
            tower.config.attackRangeMeters,
            com.example.desktopfortress.domain.model.DevelopType.TOWER_RANGE,
        )
        val rangeSquared = range * range
        return monsters.asSequence()
            .filter { distanceSquaredXZ(it.worldPosition, tower.worldPosition) <= rangeSquared }
            .maxByOrNull { it.pathProgress }
    }

    private fun fire(tower: Tower, target: Monster) {
        val projectile = projectilePool.obtain()
        val launchPosition = tower.worldPosition + Vector3(0f, 0.07f, 0f)
        val aimPosition = target.worldPosition + Vector3(0f, 0.03f, 0f)
        val direction = normalized(aimPosition - launchPosition)
        projectile.kind = when (tower.config.type) {
            TowerType.ARCHER -> ProjectileKind.ARROW
            TowerType.BALLISTA -> ProjectileKind.BOLT
            TowerType.EXPLOSIVE -> ProjectileKind.SHELL
            TowerType.FROST -> ProjectileKind.FROST_SHARD
        }
        projectile.sourceTowerId = tower.instanceId
        projectile.position = launchPosition
        projectile.velocity = direction * when (projectile.kind) {
            ProjectileKind.SHELL -> 1.20f
            ProjectileKind.FROST_SHARD -> 1.45f
            else -> 1.80f
        }
        projectile.damage = DevelopManager.applyBonus(
            tower.config.damage,
            com.example.desktopfortress.domain.model.DevelopType.TOWER_DAMAGE,
        ) *
            if (TowerTrait.CRITICAL_SHOT in tower.config.exclusiveTraits && projectile.sequence % 5L == 0L) 2f else 1f
        projectile.splashRadiusMeters = tower.config.splashRadiusMeters
        projectile.maxDistanceMeters = DevelopManager.applyBonus(
            tower.config.attackRangeMeters,
            com.example.desktopfortress.domain.model.DevelopType.TOWER_RANGE,
        ) * 1.25f
        projectile.targetMonsterId = target.id
        projectile.remainingPierces = if (TowerTrait.PIERCE in tower.config.exclusiveTraits) 2 else 0
        projectile.statusOnHit = statusFor(tower)
        AudioManager.playAttack(tower.config.type)
    }

    private fun statusFor(tower: Tower): MonsterStatus? = when {
        TowerTrait.FREEZE in tower.config.exclusiveTraits -> MonsterStatus(MonsterStatusType.FREEZE, .96f, 0f)
        TowerTrait.HEAVY_STUN in tower.config.exclusiveTraits -> MonsterStatus(MonsterStatusType.STUN, .80f, 0f)
        TowerTrait.STUN in tower.config.exclusiveTraits -> MonsterStatus(MonsterStatusType.STUN, .52f, 0f)
        tower.config.type == TowerType.FROST -> MonsterStatus(
            MonsterStatusType.SLOW,
            if (TowerTrait.DEEP_SLOW in tower.config.exclusiveTraits) 2.4f else 1.6f,
            if (TowerTrait.DEEP_SLOW in tower.config.exclusiveTraits) .60f else .75f,
            TowerType.FROST.name,
        )
        else -> null
    }

    private fun updateProjectiles(deltaSeconds: Float) {
        projectilePool.activeSnapshot().forEach { projectile ->
            val step = projectile.velocity * deltaSeconds
            projectile.position += step
            projectile.travelledMeters += length(step)
            val hits = MonsterManager.activeMonsters()
                .filter { it.id !in projectile.hitMonsterIds }
                .filter {
                    distanceSquared(it.worldPosition + Vector3(0f, .03f, 0f), projectile.position) <=
                        HIT_RADIUS_METERS * HIT_RADIUS_METERS
                }
                .sortedByDescending { it.pathProgress }
            when (projectile.kind) {
                ProjectileKind.BOLT -> hits.forEach { monster ->
                    hit(projectile, monster)
                    if (projectile.remainingPierces-- <= 0) {
                        projectilePool.recycle(projectile)
                        return@forEach
                    }
                }
                ProjectileKind.SHELL -> hits.firstOrNull()?.let { monster ->
                    val impact = monster.worldPosition
                    MonsterManager.activeMonsters()
                        .filter { distanceSquared(it.worldPosition, impact) <= projectile.splashRadiusMeters * projectile.splashRadiusMeters }
                        .forEach { hit(projectile, it) }
                    projectilePool.recycle(projectile)
                }
                else -> hits.firstOrNull()?.let { monster ->
                    hit(projectile, monster)
                    projectilePool.recycle(projectile)
                }
            }
            if (projectile.active && projectile.travelledMeters >= projectile.maxDistanceMeters) {
                projectilePool.recycle(projectile)
            }
        }
    }

    private fun hit(projectile: Projectile, monster: Monster) {
        if (!projectile.hitMonsterIds.add(monster.id)) return
        monster.applyDamage(projectile.damage)
        projectile.statusOnHit?.let { monster.applyStatus(it.copy()) }
        val type = when (projectile.kind) {
            ProjectileKind.ARROW -> TowerType.ARCHER
            ProjectileKind.BOLT -> TowerType.BALLISTA
            ProjectileKind.SHELL -> TowerType.EXPLOSIVE
            ProjectileKind.FROST_SHARD -> TowerType.FROST
        }
        EffectManager.showHit(monster.worldPosition, type)
    }

    private fun canEdit(): Boolean = repository.gameState.value in setOf(GameState.PREPARE, GameState.WAVE_PAUSE)

    private fun consumeSlot(slotIndex: Int, type: TowerType, level: Int): Boolean {
        val item = inventoryItems.getOrNull(slotIndex) ?: return false
        if (item.type != type || item.level != level) return false
        inventoryItems[slotIndex] = null
        publishInventory()
        return true
    }

    internal fun sellRefundRatioForLevel(levelId: Int): Float {
        val base = if (levelId in 1..3) {
            EARLY_LEVEL_SELL_REFUND_RATIO
        } else {
            LATE_LEVEL_SELL_REFUND_RATIO
        }
        return (base + DevelopManager.getSellRefundBonusRatio()).coerceAtMost(MAX_SELL_REFUND_RATIO)
    }

    private fun refundFor(type: TowerType, level: Int): Int = floor(
        TowerBalanceTable.get(type, level).cost *
            sellRefundRatioForLevel(LevelManager.runtimeState.value.levelId),
    ).toInt().coerceAtLeast(0)

    private fun publishInventory() {
        mutableInventory.value = TowerInventoryState(
            slots = inventoryItems.mapIndexed { index, item -> TowerInventorySlot(index, item) },
        )
    }

    private fun reject(message: String): Boolean {
        EventBus.tryEmit(UserMessage(message))
        AudioManager.playInteractionFailure()
        return false
    }

    private fun rejectPurchase(message: String): TowerPurchaseResult.Rejected {
        EventBus.tryEmit(UserMessage(message))
        AudioManager.playInteractionFailure()
        return TowerPurchaseResult.Rejected(message)
    }

    private fun rejectAndRestore(preview: TowerDragPreview, message: String): TowerOperationResult.Rejected {
        mutableDragPreview.value = preview.copy(validity = DragValidity.INVALID)
        EventBus.tryEmit(UserMessage(message))
        managerScope.launch {
            delay(320)
            if (mutableDragPreview.value == preview.copy(validity = DragValidity.INVALID)) finishDrag()
        }
        return TowerOperationResult.Rejected(message)
    }

    private fun finishDrag() {
        mutableDragPreview.value = null
        pointerTargetAuthoritative = false
        BoardManager.previewCell(null)
    }

    private fun publishSnapshots() {
        mutableTowers.value = towersById.values.filter(Tower::active).map { tower ->
            TowerSnapshot(
                id = tower.instanceId,
                type = tower.config.type,
                level = tower.currentLevel,
                coordinate = requireNotNull(tower.coordinate),
                worldPosition = tower.worldPosition,
                facingYawRadians = tower.facingYawRadians,
            )
        }
        mutableProjectiles.value = projectilePool.activeSnapshot().map {
            ProjectileSnapshot(it.poolId, it.kind, it.position)
        }
    }

    private fun Tower.toBoardInstance() = TowerInstance(instanceId.toString(), config.type.name, currentLevel)

    private fun distanceSquared(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun distanceSquaredXZ(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private fun length(v: Vector3): Float = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun normalized(v: Vector3): Vector3 {
        val length = length(v)
        return if (length <= .00001f) Vector3(0f, 0f, 1f) else v * (1f / length)
    }
}
