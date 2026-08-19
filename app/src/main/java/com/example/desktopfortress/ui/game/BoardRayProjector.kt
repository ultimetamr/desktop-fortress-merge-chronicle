package com.example.desktopfortress.ui.game

import com.example.desktopfortress.domain.model.Board
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.geometry.Offset3D

/** Reconstructs the pointer ray and intersects it with the horizontal board surface. */
object BoardRayProjector {
    private var content: SpatialViewContent? = null
    private var spatialViewRoot: Entity? = null

    fun bind(content: SpatialViewContent, spatialViewRoot: Entity) {
        this.content = content
        this.spatialViewRoot = spatialViewRoot
    }

    fun unbind() {
        content = null
        spatialViewRoot = null
    }

    fun projectPointerRay(
        pointerPosition: Offset3D,
        inputDevicePosition: Offset3D,
        fallbackOrigin: Vector3,
        board: Board,
    ): Vector3? {
        val spatialContent = content ?: return null
        val root = spatialViewRoot ?: return null
        if (!pointerPosition.x.isFinite() || !pointerPosition.y.isFinite() || !pointerPosition.z.isFinite()) return null
        val pointOnRay = spatialContent.convertPosition(
            Vector3(pointerPosition.x, pointerPosition.y, pointerPosition.z),
            ViewCoordinateSpace.Local,
            spatialContent.localSpatialCoordinateSpace,
        )
        if (!pointOnRay.isFinite()) return null

        val rawDevice = Vector3(inputDevicePosition.x, inputDevicePosition.y, inputDevicePosition.z)
        val localizedDevice = rawDevice
            .takeIf { it.isFinite() && it.length() > MIN_VECTOR_LENGTH_METERS }
            ?.let { root.convertPositionFrom(it, null) }
            ?.takeIf(Vector3::isFinite)

        val surfaceY = BoardScene.interactionSurfaceWorldY(board)
        return listOfNotNull(localizedDevice, fallbackOrigin.takeIf(Vector3::isFinite))
            .firstNotNullOfOrNull { origin ->
                intersectHorizontalPlane(origin, pointOnRay, surfaceY)
            }
    }

    /** Depth-invariant ray/plane intersection used by controller, hand and gaze pointers. */
    fun intersectHorizontalPlane(origin: Vector3, pointOnRay: Vector3, planeY: Float): Vector3? {
        if (!origin.isFinite() || !pointOnRay.isFinite() || !planeY.isFinite()) return null
        val direction = pointOnRay - origin
        if (direction.length() <= MIN_VECTOR_LENGTH_METERS || kotlin.math.abs(direction.y) <= MIN_VERTICAL_DIRECTION) {
            return null
        }
        val distanceFactor = (planeY - origin.y) / direction.y
        if (!distanceFactor.isFinite() || distanceFactor < 0f) return null
        val hit = origin + direction * distanceFactor
        val distance = (hit - origin).length()
        return hit.takeIf { it.isFinite() && distance <= MAX_RAY_DISTANCE_METERS }
    }

    private const val MIN_VECTOR_LENGTH_METERS = .0001f
    private const val MIN_VERTICAL_DIRECTION = .00001f
    private const val MAX_RAY_DISTANCE_METERS = 20f
}
