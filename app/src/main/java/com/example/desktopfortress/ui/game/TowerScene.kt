package com.example.desktopfortress.ui.game

import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.DragValidity
import com.example.desktopfortress.domain.model.ProjectileKind
import com.example.desktopfortress.domain.model.ProjectileSnapshot
import com.example.desktopfortress.domain.model.TowerDragPreview
import com.example.desktopfortress.domain.model.TowerDragSource
import com.example.desktopfortress.domain.model.TowerInventoryState
import com.example.desktopfortress.domain.model.TowerSlotLayout
import com.example.desktopfortress.domain.model.TowerSnapshot
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.effect.TowerEffect
import com.example.desktopfortress.effect.TowerEffectSnapshot
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
import kotlin.math.sin

/** Runtime ECS presentation for pooled tower/projectile state. */
class TowerScene(private val rememberResource: (AutoCloseable) -> Unit) {
    private data class TowerVisual(val root: Entity, val level: Int, val marker: Entity, val bornAt: Long)
    private data class ProjectileVisual(val root: Entity, val variants: Map<ProjectileKind, Entity>)
    private enum class SlotRenderState { LOCKED, SELECTED, OCCUPIED, EMPTY }
    private data class SlotVisual(
        val tray: Entity,
        val trayMaterial: UnlitMaterial,
        var preview: Entity? = null,
        var previewType: TowerType? = null,
        var previewLevel: Int = 0,
        var renderState: SlotRenderState? = null,
    )
    private data class BoxMeshKey(val x: Float, val y: Float, val z: Float)
    private data class RadiusHeightKey(val radius: Float, val height: Float)
    private data class MaterialKey(
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
        val transparent: Boolean,
    )

    private lateinit var layer: Entity
    private lateinit var ghost: Entity
    private lateinit var ghostMaterial: UnlitMaterial
    private val towerVisuals = mutableMapOf<Long, TowerVisual>()
    private val projectileVisuals = mutableMapOf<Int, ProjectileVisual>()
    private val effectVisuals = mutableMapOf<Long, Entity>()
    private val slotVisuals = mutableMapOf<Int, SlotVisual>()
    private val slotPreviewOwners = mutableMapOf<Entity, Int>()
    private val boxMeshes = mutableMapOf<BoxMeshKey, MeshResource>()
    private val sphereMeshes = mutableMapOf<Float, MeshResource>()
    private val cylinderMeshes = mutableMapOf<RadiusHeightKey, MeshResource>()
    private val coneMeshes = mutableMapOf<RadiusHeightKey, MeshResource>()
    private val torusMeshes = mutableMapOf<RadiusHeightKey, MeshResource>()
    private val sharedMaterials = mutableMapOf<MaterialKey, UnlitMaterial>()
    private var sharedInteractionShape: ShapeResource? = null
    private var sharedSlotInteractionShape: ShapeResource? = null
    private var sharedInteractionPhysics: PhysicsMaterialResource? = null

    /** Collision and Interactable components live on this root, so target identity is stable. */
    fun towerIdForEntity(entity: Entity): Long? = findOwner(entity) { candidate ->
        towerVisuals.entries.firstOrNull { (_, visual) -> visual.root == candidate }?.key
    }

    /** Slot preview roots own their interaction collider, so a gesture can resolve the exact slot. */
    fun slotIndexForEntity(entity: Entity): Int? = findOwner(entity, slotPreviewOwners::get)

    /** Spatial hit targets may be a rendered child below the interactive model root. */
    private inline fun <T> findOwner(entity: Entity, owner: (Entity) -> T?): T? {
        var candidate: Entity? = entity
        repeat(MAX_TARGET_PARENT_DEPTH) {
            val current = candidate ?: return null
            owner(current)?.let { return it }
            candidate = runCatching { current.getParent() }.getOrNull()
        }
        return null
    }

    fun create(parent: Entity, board: Board) {
        layer = Entity()
        parent.addChild(layer)
        ghostMaterial = material(Color4(0.35f, 0.95f, 0.80f, .42f), true)
        ghost = Entity().apply {
            components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
            components.set(ModelComponent(meshCylinder(.045f, .095f), ghostMaterial))
        }
        layer.addChild(ghost)
        createInventoryRack(board)
    }

    fun update(
        board: Board,
        towers: List<TowerSnapshot>,
        projectiles: List<ProjectileSnapshot>,
        inventory: TowerInventoryState,
        drag: TowerDragPreview?,
        effects: List<TowerEffectSnapshot>,
        inventoryEditable: Boolean,
        debugGrounding: Boolean,
    ) {
        if (!::layer.isInitialized) return
        syncInventory(board, inventory, drag, inventoryEditable)
        syncTowers(board, towers, debugGrounding)
        syncProjectiles(board, projectiles)
        syncGhost(board, drag)
        syncEffects(board, effects)
    }

    private fun syncTowers(board: Board, towers: List<TowerSnapshot>, debugGrounding: Boolean) {
        val ids = towers.mapTo(mutableSetOf()) { it.id }
        towerVisuals.keys.filterNot(ids::contains).toList().forEach { id ->
            towerVisuals.remove(id)?.root?.destroy()
        }
        val now = System.currentTimeMillis()
        towers.forEach { snapshot ->
            var visual = towerVisuals[snapshot.id]
            if (visual == null || visual.level != snapshot.level) {
                visual?.root?.destroy()
                visual = createTower(snapshot).also { towerVisuals[snapshot.id] = it }
            }
            val local = board.worldToLocal(snapshot.worldPosition)
            val age = ((now - visual.bornAt) / 320f).coerceIn(0f, 1f)
            val pop = .35f + .65f * (1f - (1f - age) * (1f - age))
            val levelScale = 1f + (snapshot.level - 1) * .055f
            visual.root.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(local.x, .026f, local.z))
                setEulerAngles(EulerAngles(0f, snapshot.facingYawRadians * 180f / PI.toFloat(), 0f))
                setScaleVector(Vector3(levelScale * pop))
            }
            visual.marker.components[TransformComponent::class.java]?.setScaleVector(
                if (debugGrounding) Vector3.ONE else Vector3.ZERO,
            )
        }
    }

    private fun createTower(snapshot: TowerSnapshot): TowerVisual {
        val root = createTowerModel(snapshot.type, snapshot.level, interactive = true)
        val marker = model(
            meshCylinder(.024f, .0015f),
            sharedMaterial(Color4.RED),
            Vector3(0f, .001f, 0f),
        ).apply { components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) }
        root.addChild(marker)
        layer.addChild(root)
        return TowerVisual(root, snapshot.level, marker, System.currentTimeMillis())
    }

    /** Builds the same recognizable silhouette for slot previews and placed towers. */
    private fun createTowerModel(type: TowerType, level: Int, interactive: Boolean): Entity {
        val root = Entity()
        if (interactive) {
            root.components.set(
                CollisionComponent(
                    listOf(interactionShape()),
                    interactionPhysics(),
                ),
            )
            root.components.set(InteractableComponent())
        }
        val color = colorFor(type)
        root.addChild(model(meshCylinder(.047f, .018f), sharedMaterial(Color4(.10f, .13f, .16f, 1f)), Vector3(0f, .009f, 0f)))
        when (type) {
            TowerType.ARCHER -> {
                root.addChild(model(meshCylinder(.012f, .058f), sharedMaterial(color), Vector3(0f, .046f, 0f)))
                root.addChild(model(meshBox(Vector3(.010f, .070f, .010f)), sharedMaterial(Color4(.80f, .54f, .18f, 1f)), Vector3(-.025f, .085f, 0f), EulerAngles(0f, 0f, 34f)))
                root.addChild(model(meshBox(Vector3(.010f, .070f, .010f)), sharedMaterial(Color4(.80f, .54f, .18f, 1f)), Vector3(.025f, .085f, 0f), EulerAngles(0f, 0f, -34f)))
                root.addChild(model(meshBox(Vector3(.006f, .006f, .105f)), sharedMaterial(Color4(.94f, .86f, .52f, 1f)), Vector3(0f, .085f, .012f)))
                root.addChild(model(meshCone(.012f, .026f), sharedMaterial(Color4(.94f, .86f, .52f, 1f)), Vector3(0f, .085f, -.052f), EulerAngles(90f, 0f, 0f)))
            }
            TowerType.BALLISTA -> {
                root.addChild(model(meshBox(Vector3(.022f, .055f, .022f)), sharedMaterial(color), Vector3(0f, .046f, 0f)))
                root.addChild(model(meshBox(Vector3(.095f, .014f, .020f)), sharedMaterial(color), Vector3(0f, .076f, 0f)))
                root.addChild(model(meshBox(Vector3(.010f, .010f, .095f)), sharedMaterial(Color4(.82f, .84f, .76f, 1f)), Vector3(0f, .082f, .010f)))
            }
            TowerType.EXPLOSIVE -> {
                root.addChild(model(meshCylinder(.032f, .060f), sharedMaterial(color), Vector3(0f, .048f, 0f)))
                root.addChild(model(meshSphere(.034f), sharedMaterial(color), Vector3(0f, .084f, 0f)))
                root.addChild(model(meshCylinder(.013f, .065f), sharedMaterial(Color4(.22f, .24f, .25f, 1f)), Vector3(0f, .087f, .025f), EulerAngles(90f, 0f, 0f)))
            }
            TowerType.FROST -> {
                root.addChild(model(meshCone(.035f, .095f), sharedMaterial(color), Vector3(0f, .064f, 0f)))
                root.addChild(model(meshSphere(.021f), sharedMaterial(Color4(.82f, .98f, 1f, 1f)), Vector3(0f, .113f, 0f)))
            }
        }
        repeat(level - 1) { index ->
            root.addChild(model(meshSphere(.006f), sharedMaterial(Color4(1f, .82f, .28f, 1f)), Vector3((index - 1.5f) * .013f, .020f, .043f)))
        }
        return root
    }

    private fun createInventoryRack(board: Board) {
        repeat(TowerSlotLayout.SLOT_COUNT) { slotIndex ->
            val center = TowerSlotLayout.slotCenter(board, slotIndex)
            val trayMaterial = material(Color4(.82f, .08f, .10f, .74f), true)
            val tray = Entity().apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(center.x, .022f, center.z))
                    setScaleVector(Vector3.ONE)
                }
                components.set(
                    ModelComponent(
                        meshBox(Vector3(TowerSlotLayout.slotTrayWidth(board), .012f, TowerSlotLayout.SLOT_DEPTH_METERS)),
                        trayMaterial,
                    ),
                )
                // The procedural tower silhouette is intentionally small. Make the
                // complete tray the stable ray target so a slightly off-center ray
                // still selects the purchased weapon instead of falling through to
                // the board root.
                components.set(
                    CollisionComponent(
                        listOf(slotInteractionShape(board)),
                        interactionPhysics(),
                    ),
                )
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
            }
            layer.addChild(tray)
            slotPreviewOwners[tray] = slotIndex
            slotVisuals[slotIndex] = SlotVisual(tray, trayMaterial)
        }
    }

    private fun syncInventory(
        board: Board,
        inventory: TowerInventoryState,
        drag: TowerDragPreview?,
        editable: Boolean,
    ) {
        val draggedSlot = (drag?.source as? TowerDragSource.InventorySlot)?.slotIndex
        inventory.slots.forEach { slot ->
            val visual = slotVisuals[slot.index] ?: return@forEach
            val occupied = slot.item != null
            val renderState = when {
                !editable -> SlotRenderState.LOCKED
                draggedSlot == slot.index -> SlotRenderState.SELECTED
                occupied -> SlotRenderState.OCCUPIED
                else -> SlotRenderState.EMPTY
            }
            if (visual.renderState != renderState) {
                val trayColor = when (renderState) {
                    SlotRenderState.LOCKED -> Color4(.28f, .30f, .32f, .58f)
                    SlotRenderState.SELECTED -> Color4(.12f, .92f, 1f, .96f)
                    SlotRenderState.OCCUPIED -> Color4(.10f, .50f, .94f, .82f)
                    SlotRenderState.EMPTY -> Color4(.82f, .08f, .10f, .74f)
                }
                visual.trayMaterial.setBaseColor(trayColor)
                visual.trayMaterial.setOpacity(trayColor.alpha)
                visual.renderState = renderState
            }
            val center = TowerSlotLayout.slotCenter(board, slot.index)
            val item = slot.item
            if (item == null) {
                visual.preview?.let { preview ->
                    slotPreviewOwners.remove(preview)
                    preview.destroy()
                }
                visual.preview = null
                visual.previewType = null
                visual.previewLevel = 0
            } else if (visual.preview == null || visual.previewType != item.type || visual.previewLevel != item.level) {
                visual.preview?.let { preview ->
                    slotPreviewOwners.remove(preview)
                    preview.destroy()
                }
                visual.preview = createTowerModel(item.type, item.level, interactive = true).also { preview ->
                    slotPreviewOwners[preview] = slot.index
                    layer.addChild(preview)
                }
                visual.previewType = item.type
                visual.previewLevel = item.level
            }
            val selected = item != null && draggedSlot == slot.index
            val visible = item != null
            visual.preview?.components?.get(TransformComponent::class.java)?.apply {
                setPosition(
                    if (visible) {
                        Vector3(center.x, center.y + if (selected) SELECTED_MODEL_LIFT_METERS else 0f, center.z)
                    } else {
                        Vector3(center.x, -5f, center.z)
                    },
                )
                val scale = TowerSlotLayout.PREVIEW_SCALE * if (selected) SELECTED_MODEL_SCALE else 1f
                setScaleVector(if (visible) Vector3(scale) else Vector3.ZERO)
            }
        }
    }

    private fun syncProjectiles(board: Board, snapshots: List<ProjectileSnapshot>) {
        val activeIds = snapshots.mapTo(mutableSetOf()) { it.poolId }
        projectileVisuals.forEach { (id, visual) ->
            if (id !in activeIds) visual.root.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
        }
        snapshots.forEach { snapshot ->
            val visual = projectileVisuals.getOrPut(snapshot.poolId) { createProjectileVisual() }
            val local = board.worldToLocal(snapshot.worldPosition)
            visual.root.components[TransformComponent::class.java]?.apply {
                setPosition(local)
                setScaleVector(Vector3.ONE)
            }
            visual.variants.forEach { (kind, entity) ->
                entity.components[TransformComponent::class.java]?.setScaleVector(
                    if (kind == snapshot.kind) Vector3.ONE else Vector3.ZERO,
                )
            }
        }
    }

    private fun createProjectileVisual(): ProjectileVisual {
        val root = Entity().apply { components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) }
        val variants = mapOf(
            ProjectileKind.ARROW to model(meshBox(Vector3(.008f, .008f, .050f)), sharedMaterial(Color4(.94f, .82f, .48f, 1f)), Vector3.ZERO),
            ProjectileKind.BOLT to model(meshBox(Vector3(.012f, .012f, .075f)), sharedMaterial(Color4(.84f, .86f, .82f, 1f)), Vector3.ZERO),
            ProjectileKind.SHELL to model(meshSphere(.018f), sharedMaterial(Color4(1f, .38f, .10f, 1f)), Vector3.ZERO),
            ProjectileKind.FROST_SHARD to model(meshCone(.014f, .045f), sharedMaterial(Color4(.42f, .92f, 1f, 1f)), Vector3.ZERO),
        )
        variants.values.forEach(root::addChild)
        layer.addChild(root)
        return ProjectileVisual(root, variants)
    }

    private fun syncGhost(board: Board, drag: TowerDragPreview?) {
        val waitingForBoardCell = drag?.source is TowerDragSource.InventorySlot && drag.snappedCell == null
        if (drag == null || waitingForBoardCell) {
            ghost.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
            return
        }
        val local = board.worldToLocal(drag.worldPosition)
        ghost.components[TransformComponent::class.java]?.apply {
            setPosition(Vector3(local.x, .074f, local.z))
            setScaleVector(Vector3(1f + (drag.level - 1) * .05f))
        }
        val color = if (drag.validity == DragValidity.INVALID) {
            Color4(1f, .12f, .12f, .48f)
        } else {
            val base = colorFor(drag.type)
            Color4(base.red, base.green, base.blue, .48f)
        }
        ghostMaterial.setBaseColor(color)
        ghostMaterial.setOpacity(color.alpha)
    }

    private fun syncEffects(board: Board, snapshots: List<TowerEffectSnapshot>) {
        val ids = snapshots.mapTo(mutableSetOf()) { it.id }
        effectVisuals.keys.filterNot(ids::contains).toList().forEach { id ->
            effectVisuals.remove(id)?.destroy()
        }
        snapshots.forEach { snapshot ->
            val entity = effectVisuals.getOrPut(snapshot.id) {
                val color = when (snapshot.effect) {
                    is TowerEffect.Merge -> Color4(1f, .84f, .22f, .82f)
                    is TowerEffect.Hit -> colorFor(snapshot.effect.type)
                    is TowerEffect.Placement -> Color4(.35f, 1f, .55f, .75f)
                    is TowerEffect.Sell -> Color4(.95f, .95f, .95f, .70f)
                }
                model(meshTorus(.040f, .006f), sharedMaterial(color, true), Vector3.ZERO).also(layer::addChild)
            }
            val local = board.worldToLocal(snapshot.effect.worldPosition)
            val pulse = when (snapshot.effect) {
                is TowerEffect.Merge -> 1f + .35f * sin(snapshot.progress * PI.toFloat())
                else -> .65f + snapshot.progress * .9f
            }
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(local.x, .012f + snapshot.progress * .045f, local.z))
                setScaleVector(Vector3(pulse))
            }
        }
    }

    private fun model(mesh: MeshResource, material: UnlitMaterial, position: Vector3, rotation: EulerAngles = EulerAngles()) =
        Entity().apply {
            components[TransformComponent::class.java]?.apply {
                setPosition(position)
                setEulerAngles(rotation)
                setScaleVector(Vector3.ONE)
            }
            components.set(ModelComponent(mesh, material))
        }

    private fun colorFor(type: TowerType): Color4 = when (type) {
        TowerType.ARCHER -> Color4(.22f, .82f, .38f, 1f)
        TowerType.BALLISTA -> Color4(.92f, .72f, .24f, 1f)
        TowerType.EXPLOSIVE -> Color4(1f, .30f, .12f, 1f)
        TowerType.FROST -> Color4(.22f, .72f, 1f, 1f)
    }

    private fun material(
        color: Color4,
        transparent: Boolean = false,
        persistent: Boolean = false,
    ): UnlitMaterial =
        UnlitMaterial.create(if (transparent) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(color)
            setOpacity(color.alpha)
            if (persistent) toGlobal()
        }.remember()

    private fun sharedMaterial(color: Color4, transparent: Boolean = false): UnlitMaterial {
        val key = MaterialKey(color.red, color.green, color.blue, color.alpha, transparent)
        return sharedMaterials.getOrPut(key) { material(color, transparent, persistent = true) }
    }

    private fun meshBox(size: Vector3): MeshResource =
        boxMeshes.getOrPut(BoxMeshKey(size.x, size.y, size.z)) {
            MeshResource.createBox(size, .002f).apply { toGlobal() }.remember()
        }

    private fun meshSphere(radius: Float): MeshResource = sphereMeshes.getOrPut(radius) {
        MeshResource.createSphere(radius).apply { toGlobal() }.remember()
    }

    private fun meshCylinder(radius: Float, height: Float): MeshResource =
        cylinderMeshes.getOrPut(RadiusHeightKey(radius, height)) {
            MeshResource.createCylinder(radius, height).apply { toGlobal() }.remember()
        }

    private fun meshCone(radius: Float, height: Float): MeshResource =
        coneMeshes.getOrPut(RadiusHeightKey(radius, height)) {
            MeshResource.createCone(radius, height).apply { toGlobal() }.remember()
        }

    private fun meshTorus(radius: Float, tube: Float): MeshResource =
        torusMeshes.getOrPut(RadiusHeightKey(radius, tube)) {
            MeshResource.createTorus(radius, tube).apply { toGlobal() }.remember()
        }

    private fun interactionShape(): ShapeResource = sharedInteractionShape
        ?: ShapeResource.createSphere(.06f).apply { toGlobal() }.remember().also { sharedInteractionShape = it }

    private fun slotInteractionShape(board: Board): ShapeResource = sharedSlotInteractionShape
        ?: ShapeResource.createBox(
            Vector3(
                TowerSlotLayout.slotTrayWidth(board),
                SLOT_INTERACTION_HEIGHT_METERS,
                TowerSlotLayout.SLOT_DEPTH_METERS,
            ),
        ).apply { toGlobal() }.remember().also { sharedSlotInteractionShape = it }

    private fun interactionPhysics(): PhysicsMaterialResource = sharedInteractionPhysics
        ?: PhysicsMaterialResource().apply { toGlobal() }.remember().also { sharedInteractionPhysics = it }

    private fun <T : AutoCloseable> T.remember(): T = also(rememberResource)

    fun destroy() {
        towerVisuals.clear()
        projectileVisuals.clear()
        effectVisuals.clear()
        slotVisuals.clear()
        slotPreviewOwners.clear()
        boxMeshes.clear()
        sphereMeshes.clear()
        cylinderMeshes.clear()
        coneMeshes.clear()
        torusMeshes.clear()
        sharedMaterials.clear()
        sharedInteractionShape = null
        sharedSlotInteractionShape = null
        sharedInteractionPhysics = null
    }

    private companion object {
        const val MAX_TARGET_PARENT_DEPTH = 8
        const val SLOT_INTERACTION_HEIGHT_METERS = .16f
        const val SELECTED_MODEL_LIFT_METERS = .10f
        const val SELECTED_MODEL_SCALE = 1.28f
    }
}
