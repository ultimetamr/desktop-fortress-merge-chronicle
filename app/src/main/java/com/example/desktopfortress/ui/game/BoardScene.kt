package com.example.desktopfortress.ui.game

import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.CellType
import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.DragValidity
import com.example.desktopfortress.domain.model.ProjectileSnapshot
import com.example.desktopfortress.domain.model.MonsterSnapshot
import com.example.desktopfortress.domain.model.TowerDragPreview
import com.example.desktopfortress.domain.model.TowerDragSource
import com.example.desktopfortress.domain.model.TowerInventoryState
import com.example.desktopfortress.domain.model.TowerSlotLayout
import com.example.desktopfortress.domain.model.TowerSnapshot
import com.example.desktopfortress.effect.TowerEffectSnapshot
import com.example.desktopfortress.utils.alignToGround
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI

class BoardScene {
    lateinit var root: Entity
        private set
    private lateinit var highlight: Entity
    private lateinit var highlightMaterial: UnlitMaterial
    private lateinit var collisionOverlay: Entity
    private val towerCollisionBoxes = linkedMapOf<Long, Entity>()
    private val monsterCollisionBoxes = linkedMapOf<Long, Entity>()
    private val groundingMarkers = mutableListOf<Entity>()
    private val cellOwners = mutableMapOf<Entity, CellCoordinate>()
    private val cellMaterials = mutableMapOf<CellCoordinate, UnlitMaterial>()
    private val cellRenderStates = mutableMapOf<CellCoordinate, CellRenderState>()
    private val resources = mutableListOf<AutoCloseable>()
    private val towerScene = TowerScene { resources += it }
    private val monsterScene = MonsterScene { resources += it }

    fun towerIdForEntity(entity: Entity): Long? = towerScene.towerIdForEntity(entity)
    fun slotIndexForEntity(entity: Entity): Int? = towerScene.slotIndexForEntity(entity)
    fun cellCoordinateForEntity(entity: Entity?): CellCoordinate? {
        var candidate = entity
        repeat(MAX_TARGET_PARENT_DEPTH) {
            val current = candidate ?: return null
            cellOwners[current]?.let { return it }
            candidate = runCatching { current.getParent() }.getOrNull()
        }
        return null
    }

    fun create(board: Board, boardVisible: Boolean): Entity {
        root = Entity().apply {
            components.set(
                CollisionComponent(
                    listOf(
                        ShapeResource.createBox(Vector3(board.widthMeters, 0.03f, board.depthMeters))
                            .rememberResource(),
                    ),
                    PhysicsMaterialResource().rememberResource(),
                ),
            )
            components.set(InteractableComponent())
        }

        root.addChild(
            boxEntity(
                name = "board_backplate",
                size = Vector3(board.widthMeters + 0.02f, 0.018f, board.depthMeters + 0.02f),
                position = Vector3(0f, 0.009f, 0f),
                color = Color4(0.035f, 0.07f, 0.09f, 0.64f),
                transparent = true,
            ),
        )
        root.addChild(
            boxEntity(
                name = "tower_sell_zone",
                size = Vector3(
                    TowerSlotLayout.SELL_ZONE_SIZE_METERS,
                    .009f,
                    TowerSlotLayout.SELL_ZONE_SIZE_METERS,
                ),
                position = TowerSlotLayout.sellZoneCenter(board),
                color = Color4(.80f, .10f, .12f, .72f),
                transparent = true,
            ),
        )
        board.cells.forEach { cell ->
            val color = baseCellColor(cell.type)
            val cellMaterial = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color)
                setOpacity(color.alpha)
            }.rememberResource()
            cellMaterials[cell.coordinate] = cellMaterial
            cellRenderStates[cell.coordinate] = CellRenderState.BASE
            val local = board.cellLocalCenter(cell.coordinate)
            val cellEntity = boxEntity(
                    name = "cell_${cell.coordinate.row}_${cell.coordinate.column}",
                    size = Vector3(board.cellSizeMeters - 0.006f, 0.008f, board.cellSizeMeters - 0.006f),
                    position = Vector3(local.x, 0.022f, local.z),
                    color = color,
                    transparent = true,
                    materialOverride = cellMaterial,
                ).apply {
                    components.set(
                        CollisionComponent(
                            listOf(cellInteractionShape(board)),
                            cellInteractionPhysics(),
                        ),
                    )
                    components.set(InteractableComponent())
                    components.set(HoverEffectComponent())
                }
            cellOwners[cellEntity] = cell.coordinate
            root.addChild(cellEntity)
            groundingMarkers += markerEntity(Vector3(local.x, 0.001f, local.z)).also(root::addChild)
        }

        highlightMaterial = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(Color4(.34f, 1f, .48f, .90f))
            setOpacity(.90f)
        }.rememberResource()
        highlight = Entity().apply {
            components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(0f, .032f, 0f))
                setScaleVector(Vector3.ZERO)
            }
            components.set(
                ModelComponent(
                    MeshResource.createBox(
                        Vector3(board.cellSizeMeters - .012f, .006f, board.cellSizeMeters - .012f),
                        .003f,
                    ).rememberResource(),
                    highlightMaterial,
                ),
            )
        }
        root.addChild(highlight)
        collisionOverlay = boxEntity(
            name = "board_collision_debug",
            size = Vector3(board.widthMeters + .03f, .045f, board.depthMeters + .03f),
            position = Vector3(0f, .022f, 0f),
            color = Color4(1f, .05f, .05f, .18f),
            transparent = true,
        ).apply { components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) }
        root.addChild(collisionOverlay)
        towerScene.create(root, board)
        monsterScene.create(root)
        update(
            board,
            emptyList(),
            emptyList(),
            emptyList(),
            TowerInventoryState(),
            null,
            emptyList(),
            inventoryEditable = false,
            boardVisible = boardVisible,
            debugGrounding = false,
            debugCollision = false,
        )
        return root
    }

    fun update(
        board: Board,
        towers: List<TowerSnapshot>,
        projectiles: List<ProjectileSnapshot>,
        monsters: List<MonsterSnapshot>,
        inventory: TowerInventoryState,
        drag: TowerDragPreview?,
        effects: List<TowerEffectSnapshot>,
        inventoryEditable: Boolean,
        boardVisible: Boolean,
        debugGrounding: Boolean,
        debugCollision: Boolean,
    ) {
        if (!::root.isInitialized) return
        val center = board.transform.worldCenter.alignToGround(GROUND_RENDER_CLEARANCE_METERS)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(center)
            setEulerAngles(EulerAngles(0f, board.transform.yawRadians * 180f / PI.toFloat(), 0f))
            setScaleVector(if (boardVisible) Vector3(board.transform.scale) else Vector3.ZERO)
        }
        syncCellFeedback(board, drag, inventoryEditable)
        board.highlightedCell?.let { coordinate ->
            val local = board.cellLocalCenter(coordinate)
            val highlightColor = when (drag?.validity) {
                DragValidity.VALID_PLACE -> Color4(.34f, 1f, .48f, .90f)
                DragValidity.VALID_MERGE -> Color4(.25f, .78f, 1f, .94f)
                else -> Color4(1f, .12f, .12f, .90f)
            }
            highlightMaterial.setBaseColor(highlightColor)
            highlightMaterial.setOpacity(highlightColor.alpha)
            highlight.components[TransformComponent::class.java]?.setPosition(Vector3(local.x, 0.034f, local.z))
            highlight.components[TransformComponent::class.java]?.setScaleVector(Vector3.ONE)
        } ?: run {
            highlight.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
        }
        groundingMarkers.forEach {
            it.components[TransformComponent::class.java]?.setScaleVector(
                if (debugGrounding) Vector3.ONE else Vector3.ZERO,
            )
        }
        collisionOverlay.components[TransformComponent::class.java]?.setScaleVector(
            if (debugCollision) Vector3.ONE else Vector3.ZERO,
        )
        updateCollisionBoxes(board, towers, monsters, debugCollision)
        towerScene.update(board, towers, projectiles, inventory, drag, effects, inventoryEditable, debugGrounding)
        monsterScene.update(board, monsters, debugGrounding)
    }

    private fun updateCollisionBoxes(
        board: Board,
        towers: List<TowerSnapshot>,
        monsters: List<MonsterSnapshot>,
        visible: Boolean,
    ) {
        towers.forEach { snapshot ->
            val entity = towerCollisionBoxes.getOrPut(snapshot.id) {
                boxEntity(
                    name = "tower_collision_${snapshot.id}",
                    size = Vector3(.11f, .16f, .11f),
                    position = Vector3.ZERO,
                    color = Color4(1f, .05f, .05f, .20f),
                    transparent = true,
                ).also(root::addChild)
            }
            val local = board.worldToLocal(snapshot.worldPosition)
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(local.x, .08f, local.z))
                setScaleVector(if (visible) Vector3.ONE else Vector3.ZERO)
            }
        }
        monsters.forEach { snapshot ->
            val entity = monsterCollisionBoxes.getOrPut(snapshot.instanceId) {
                boxEntity(
                    name = "monster_collision_${snapshot.instanceId}",
                    size = Vector3(.10f, .10f, .10f),
                    position = Vector3.ZERO,
                    color = Color4(1f, .05f, .05f, .20f),
                    transparent = true,
                ).also(root::addChild)
            }
            val local = board.worldToLocal(snapshot.worldPosition)
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(local.x, .05f, local.z))
                setScaleVector(if (visible) Vector3.ONE else Vector3.ZERO)
            }
        }
        val activeTowerIds = towers.mapTo(hashSetOf()) { it.id }
        towerCollisionBoxes.filterKeys { it !in activeTowerIds }.values.forEach {
            it.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
        }
        val activeMonsterIds = monsters.mapTo(hashSetOf()) { it.instanceId }
        monsterCollisionBoxes.filterKeys { it !in activeMonsterIds }.values.forEach {
            it.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
        }
    }

    private fun boxEntity(
        name: String,
        size: Vector3,
        position: Vector3,
        color: Color4,
        transparent: Boolean,
        materialOverride: UnlitMaterial? = null,
    ): Entity {
        val blending = if (transparent) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE
        val material = materialOverride ?: UnlitMaterial.create(blending).apply {
                setBaseColor(color)
                setOpacity(color.alpha)
            }.rememberResource()
        return Entity().apply {
            components[TransformComponent::class.java]?.apply {
                setPosition(position)
                setEulerAngles(EulerAngles())
                setScaleVector(Vector3.ONE)
            }
            components.set(ModelComponent(MeshResource.createBox(size, 0.003f).rememberResource(), material))
        }
    }

    private fun markerEntity(position: Vector3): Entity = boxEntity(
        name = "grounding_debug_marker",
        size = Vector3(0.025f, 0.0015f, 0.025f),
        position = position,
        color = Color4.RED,
        transparent = false,
    ).apply { components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) }

    fun destroy() {
        if (::root.isInitialized) root.destroy()
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
        groundingMarkers.clear()
        towerCollisionBoxes.clear()
        monsterCollisionBoxes.clear()
        cellOwners.clear()
        cellMaterials.clear()
        cellRenderStates.clear()
        cellShape = null
        cellPhysics = null
        towerScene.destroy()
        monsterScene.destroy()
    }

    private fun <T : AutoCloseable> T.rememberResource(): T = also(resources::add)

    private var cellShape: ShapeResource? = null
    private var cellPhysics: PhysicsMaterialResource? = null

    private fun cellInteractionShape(board: Board): ShapeResource = cellShape
        ?: ShapeResource.createBox(
            Vector3(board.cellSizeMeters - .006f, .012f, board.cellSizeMeters - .006f),
        ).apply { toGlobal() }.rememberResource().also { cellShape = it }

    private fun cellInteractionPhysics(): PhysicsMaterialResource = cellPhysics
        ?: PhysicsMaterialResource().apply { toGlobal() }.rememberResource().also { cellPhysics = it }

    private fun syncCellFeedback(board: Board, drag: TowerDragPreview?, editable: Boolean) {
        board.cells.forEach { cell ->
            val state = when {
                drag == null || !editable -> CellRenderState.BASE
                cell.type != CellType.PLACEABLE -> CellRenderState.INVALID
                cell.tower == null -> CellRenderState.PLACE
                cell.tower.kind == drag.type.name &&
                    cell.tower.level == drag.level && drag.level < 5 -> {
                    val sourceId = (drag.source as? TowerDragSource.Existing)?.towerId
                    if (cell.tower.id == sourceId?.toString()) CellRenderState.PLACE else CellRenderState.MERGE
                }
                else -> CellRenderState.INVALID
            }
            if (cellRenderStates[cell.coordinate] == state) return@forEach
            val color = when (state) {
                CellRenderState.BASE -> baseCellColor(cell.type)
                CellRenderState.PLACE -> Color4(.20f, .82f, .36f, .76f)
                CellRenderState.MERGE -> Color4(.22f, .70f, 1f, .82f)
                CellRenderState.INVALID -> Color4(.92f, .16f, .18f, .68f)
            }
            cellMaterials[cell.coordinate]?.apply {
                setBaseColor(color)
                setOpacity(color.alpha)
            }
            cellRenderStates[cell.coordinate] = state
        }
    }

    private fun baseCellColor(type: CellType): Color4 = when (type) {
        CellType.PATH -> Color4(0.88f, 0.52f, 0.16f, 0.76f)
        CellType.PLACEABLE -> Color4(0.12f, 0.48f, 0.49f, 0.62f)
        CellType.OBSTACLE -> Color4(0.42f, 0.16f, 0.18f, 0.82f)
    }

    private enum class CellRenderState { BASE, PLACE, MERGE, INVALID }

    companion object {
        /** Keeps the visual board above the physical floor without changing logical gameplay Y. */
        const val GROUND_RENDER_CLEARANCE_METERS = 0.006f
        const val CELL_SURFACE_TOP_LOCAL_METERS = 0.026f
        private const val MAX_TARGET_PARENT_DEPTH = 8

        fun interactionSurfaceWorldY(board: Board): Float =
            board.transform.worldCenter.y + GROUND_RENDER_CLEARANCE_METERS +
                CELL_SURFACE_TOP_LOCAL_METERS * board.transform.scale
    }
}
