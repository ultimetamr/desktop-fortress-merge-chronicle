package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3
import java.util.UUID

enum class SurfaceSemantic {
    FLOOR,
    UNKNOWN,
    OTHER,
}

/** SDK-independent plane snapshot exposed to game code. */
data class Plane(
    val id: UUID,
    val center: Vector3,
    val normal: Vector3,
    val boundary: List<Vector3>,
    val widthMeters: Float,
    val depthMeters: Float,
    val areaSquareMeters: Float,
    val flatnessMeters: Float,
    val isFallback: Boolean = false,
    val semantic: SurfaceSemantic = SurfaceSemantic.UNKNOWN,
)

data class ObstacleBox(
    val center: Vector3,
    val size: Vector3,
) {
    fun contains(x: Float, z: Float): Boolean =
        x in (center.x - size.x / 2f)..(center.x + size.x / 2f) &&
            z in (center.z - size.z / 2f)..(center.z + size.z / 2f)
}

sealed interface PlaneScanStatus {
    data object Idle : PlaneScanStatus
    data object Scanning : PlaneScanStatus
    data class Success(val plane: Plane) : PlaneScanStatus
    data class Failed(val reason: String, val fallback: Plane) : PlaneScanStatus
}
