package com.example.desktopfortress.ui.game

import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.MonsterSnapshot
import com.example.desktopfortress.domain.model.MonsterType
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI

/** Procedural runtime visuals keyed by pool object ID, so recycle/spawn does not allocate ECS models. */
class MonsterScene(private val rememberResource: (AutoCloseable) -> Unit) {
    private data class Visual(val root: Entity, val healthFill: Entity, val marker: Entity)

    private lateinit var layer: Entity
    private val visuals = mutableMapOf<Int, Visual>()

    fun create(parent: Entity) {
        layer = Entity()
        parent.addChild(layer)
    }

    fun update(board: Board, snapshots: List<MonsterSnapshot>, debugGrounding: Boolean) {
        if (!::layer.isInitialized) return
        val activePoolIds = snapshots.mapTo(mutableSetOf()) { it.poolObjectId }
        visuals.forEach { (id, visual) ->
            if (id !in activePoolIds) visual.root.transform().setScaleVector(Vector3.ZERO)
        }
        snapshots.forEach { snapshot ->
            val visual = visuals.getOrPut(snapshot.poolObjectId) { createVisual(snapshot.type) }
            val local = board.worldToLocal(snapshot.worldPosition)
            visual.root.transform().apply {
                setPosition(Vector3(local.x, .025f, local.z))
                setEulerAngles(EulerAngles(0f, snapshot.facingYawRadians * 180f / PI.toFloat(), 0f))
                setScaleVector(Vector3(snapshot.visualScale))
            }
            visual.healthFill.transform().apply {
                setPosition(Vector3(-.031f * (1f - snapshot.healthRatio), .070f, 0f))
                setScaleVector(Vector3(snapshot.healthRatio.coerceIn(0f, 1f), 1f, 1f))
            }
            visual.marker.transform().setScaleVector(if (debugGrounding) Vector3.ONE else Vector3.ZERO)
        }
    }

    private fun createVisual(type: MonsterType): Visual {
        val root = Entity()
        val primary = color(type)
        when (type) {
            MonsterType.SMALL_BUG -> bugBody(root, primary, .026f)
            MonsterType.SWIFT_BUG -> {
                bugBody(root, primary, .020f)
                root.addChild(model(meshCone(.016f, .040f), primary, Vector3(0f, .022f, -.030f), EulerAngles(90f, 0f, 0f)))
            }
            MonsterType.ARMORED_BEETLE -> {
                bugBody(root, primary, .034f)
                root.addChild(model(meshBox(Vector3(.050f, .018f, .060f)), Color4(.24f, .28f, .32f, 1f), Vector3(0f, .028f, 0f)))
            }
            MonsterType.EXPLODING_WORM -> {
                repeat(3) { index ->
                    root.addChild(model(meshSphere(.021f), primary, Vector3(0f, .020f, (index - 1) * .030f)))
                }
                root.addChild(model(meshSphere(.008f), Color4(1f, .25f, .08f, 1f), Vector3(0f, .043f, -.032f)))
            }
            MonsterType.ACID_SPITTER -> {
                bugBody(root, primary, .030f)
                root.addChild(model(meshCone(.018f, .050f), Color4(.50f, 1f, .22f, 1f), Vector3(0f, .036f, -.036f), EulerAngles(90f, 0f, 0f)))
            }
            MonsterType.ELITE_GUARD -> {
                bugBody(root, primary, .038f)
                root.addChild(model(meshCylinder(.040f, .018f), Color4(.96f, .76f, .20f, 1f), Vector3(0f, .038f, 0f)))
            }
            MonsterType.BOSS -> {
                bugBody(root, primary, .046f)
                root.addChild(model(meshCone(.028f, .052f), Color4(1f, .72f, .15f, 1f), Vector3(-.025f, .065f, 0f)))
                root.addChild(model(meshCone(.028f, .052f), Color4(1f, .72f, .15f, 1f), Vector3(.025f, .065f, 0f)))
            }
        }
        root.addChild(model(meshBox(Vector3(.065f, .006f, .005f)), Color4(.16f, .05f, .05f, 1f), Vector3(0f, .070f, 0f)))
        val healthFill = model(
            meshBox(Vector3(.062f, .004f, .006f)),
            Color4(.24f, .95f, .35f, 1f),
            Vector3(0f, .070f, 0f),
        ).also(root::addChild)
        val marker = model(
            meshCylinder(.023f, .0015f),
            Color4.RED,
            Vector3(0f, -.024f, 0f),
        ).apply { transform().setScaleVector(Vector3.ZERO) }
        root.addChild(marker)
        layer.addChild(root)
        return Visual(root, healthFill, marker)
    }

    private fun bugBody(root: Entity, color: Color4, radius: Float) {
        root.addChild(model(meshSphere(radius), color, Vector3(0f, .022f, 0f)))
        root.addChild(model(meshSphere(radius * .65f), color, Vector3(0f, .020f, -.030f)))
        listOf(-1f, 1f).forEach { side ->
            repeat(3) { index ->
                root.addChild(
                    model(
                        meshBox(Vector3(.030f, .004f, .005f)),
                        color,
                        Vector3(side * .030f, .012f, (index - 1) * .018f),
                    ),
                )
            }
        }
    }

    private fun color(type: MonsterType): Color4 = when (type) {
        MonsterType.SMALL_BUG -> Color4(.52f, .80f, .28f, 1f)
        MonsterType.SWIFT_BUG -> Color4(.22f, .92f, .78f, 1f)
        MonsterType.ARMORED_BEETLE -> Color4(.40f, .46f, .52f, 1f)
        MonsterType.EXPLODING_WORM -> Color4(1f, .38f, .12f, 1f)
        MonsterType.ACID_SPITTER -> Color4(.48f, .86f, .20f, 1f)
        MonsterType.ELITE_GUARD -> Color4(.72f, .28f, .88f, 1f)
        MonsterType.BOSS -> Color4(.82f, .08f, .16f, 1f)
    }

    private fun model(mesh: MeshResource, color: Color4, position: Vector3, rotation: EulerAngles = EulerAngles()) =
        Entity().apply {
            transform().apply {
                setPosition(position)
                setEulerAngles(rotation)
                setScaleVector(Vector3.ONE)
            }
            components.set(ModelComponent(mesh, material(color)))
        }

    private fun material(color: Color4): UnlitMaterial =
        UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color) }.remember()

    private fun meshBox(size: Vector3) = MeshResource.createBox(size, .002f).remember()
    private fun meshSphere(radius: Float) = MeshResource.createSphere(radius).remember()
    private fun meshCylinder(radius: Float, height: Float) = MeshResource.createCylinder(radius, height).remember()
    private fun meshCone(radius: Float, height: Float) = MeshResource.createCone(radius, height).remember()
    private fun Entity.transform(): TransformComponent = requireNotNull(components[TransformComponent::class.java])
    private fun <T : AutoCloseable> T.remember(): T = also(rememberResource)

    fun destroy() {
        visuals.clear()
    }
}
