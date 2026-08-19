package com.example.desktopfortress.domain.usecase

import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.BoardCell
import com.example.desktopfortress.domain.model.BoardTransform
import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.CellType
import com.example.desktopfortress.domain.model.PathPoint
import com.pico.spatial.core.math.Vector3

class BuildBoardUseCase {
    private val obstacles = setOf(CellCoordinate(0, 2), CellCoordinate(5, 5))

    operator fun invoke(
        worldCenter: Vector3,
        pathCoordinates: List<CellCoordinate> = DEFAULT_PATH,
    ): Board {
        require(pathCoordinates.isNotEmpty())
        require(pathCoordinates.all { it.row in 0 until Board.ROWS && it.column in 0 until Board.COLUMNS })
        val pathSet = pathCoordinates.toSet()
        val cells = buildList {
            repeat(Board.ROWS) { row ->
                repeat(Board.COLUMNS) { column ->
                    val coordinate = CellCoordinate(row, column)
                    val type = when (coordinate) {
                        in pathSet -> CellType.PATH
                        in obstacles -> CellType.OBSTACLE
                        else -> CellType.PLACEABLE
                    }
                    add(BoardCell(coordinate = coordinate, type = type))
                }
            }
        }
        val board = Board(
            cells = cells,
            pathPoints = emptyList(),
            transform = BoardTransform(worldCenter = worldCenter),
        )
        return board.copy(
            pathPoints = pathCoordinates.map { PathPoint(it, board.cellLocalCenter(it)) },
        )
    }

    companion object {
        val DEFAULT_PATH = listOf(
            CellCoordinate(2, 0), CellCoordinate(2, 1), CellCoordinate(2, 2),
            CellCoordinate(3, 2), CellCoordinate(4, 2), CellCoordinate(4, 3),
            CellCoordinate(4, 4), CellCoordinate(3, 4), CellCoordinate(2, 4),
            CellCoordinate(1, 4), CellCoordinate(1, 5), CellCoordinate(1, 6),
            CellCoordinate(1, 7),
        )
    }
}
