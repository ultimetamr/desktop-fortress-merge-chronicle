package com.example.desktopfortress.domain.usecase

import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.CellType
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildBoardUseCaseTest {
    private val useCase = BuildBoardUseCase()

    @Test
    fun buildsSixByEightGridWithFortyEightCells() {
        val board = useCase(Vector3(1f, 0.75f, -2f))
        assertEquals(Board.ROWS, board.rows)
        assertEquals(Board.COLUMNS, board.columns)
        assertEquals(48, board.cells.size)
        assertEquals(0.15f, board.cellSizeMeters, 0.0001f)
    }

    @Test
    fun pathPointsFollowOnlyPathCells() {
        val board = useCase(Vector3.ZERO)
        val pathCoordinates = board.cells.filter { it.type == CellType.PATH }.map { it.coordinate }.toSet()
        assertTrue(board.pathPoints.isNotEmpty())
        assertTrue(board.pathPoints.all { it.coordinate in pathCoordinates })
        assertEquals(pathCoordinates.size, board.pathPoints.size)
    }
}
