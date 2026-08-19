package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3
import kotlin.math.cos
import kotlin.math.sin

data class CellCoordinate(val row: Int, val column: Int)

enum class CellType { PATH, PLACEABLE, OBSTACLE }

enum class BoardPreviewMode { FOLLOWING_GAZE, WORLD_LOCKED }

data class TowerInstance(
    val id: String,
    val kind: String,
    val level: Int,
)

data class BoardCell(
    val coordinate: CellCoordinate,
    val type: CellType,
    val hasTower: Boolean = false,
    val tower: TowerInstance? = null,
)

data class PathPoint(
    val coordinate: CellCoordinate,
    val localPosition: Vector3,
)

data class BoardTransform(
    val worldCenter: Vector3,
    val yawRadians: Float = 0f,
    val scale: Float = 1f,
) {
    fun localToWorld(local: Vector3): Vector3 {
        val sx = local.x * scale
        val sz = local.z * scale
        val c = cos(yawRadians)
        val s = sin(yawRadians)
        return Vector3(
            worldCenter.x + sx * c - sz * s,
            worldCenter.y + local.y * scale,
            worldCenter.z + sx * s + sz * c,
        )
    }

    fun worldToLocal(world: Vector3): Vector3 {
        val dx = world.x - worldCenter.x
        val dz = world.z - worldCenter.z
        val c = cos(yawRadians)
        val s = sin(yawRadians)
        return Vector3(
            (dx * c + dz * s) / scale,
            (world.y - worldCenter.y) / scale,
            (-dx * s + dz * c) / scale,
        )
    }
}

data class Board(
    val rows: Int = ROWS,
    val columns: Int = COLUMNS,
    val cellSizeMeters: Float = CELL_SIZE_METERS,
    val cells: List<BoardCell>,
    val pathPoints: List<PathPoint>,
    val transform: BoardTransform,
    val highlightedCell: CellCoordinate? = null,
    val isLocked: Boolean = false,
) {
    val widthMeters: Float get() = columns * cellSizeMeters
    val depthMeters: Float get() = rows * cellSizeMeters

    fun cellLocalCenter(coordinate: CellCoordinate): Vector3 = Vector3(
        (coordinate.column + 0.5f) * cellSizeMeters - widthMeters / 2f,
        0f,
        (coordinate.row + 0.5f) * cellSizeMeters - depthMeters / 2f,
    )

    fun localToWorld(local: Vector3): Vector3 = transform.localToWorld(local)
    fun worldToLocal(world: Vector3): Vector3 = transform.worldToLocal(world)

    companion object {
        const val ROWS = 6
        const val COLUMNS = 8
        const val CELL_SIZE_METERS = 0.15f
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 1.5f
    }
}
