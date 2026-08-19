package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardTransformTest {
    @Test
    fun localWorldRoundTripPreservesCoordinates() {
        val transform = BoardTransform(Vector3(1f, 0.8f, -2f), yawRadians = 0.7f, scale = 1.3f)
        val local = Vector3(0.2f, 0.04f, -0.35f)
        val roundTrip = transform.worldToLocal(transform.localToWorld(local))
        assertEquals(local.x, roundTrip.x, 0.0001f)
        assertEquals(local.y, roundTrip.y, 0.0001f)
        assertEquals(local.z, roundTrip.z, 0.0001f)
    }

    @Test
    fun cellCentersAreCenteredAroundBoardOrigin() {
        val board = com.example.desktopfortress.domain.usecase.BuildBoardUseCase()(Vector3.ZERO)
        val first = board.cellLocalCenter(CellCoordinate(0, 0))
        val last = board.cellLocalCenter(CellCoordinate(5, 7))
        assertEquals(-first.x, last.x, 0.0001f)
        assertEquals(-first.z, last.z, 0.0001f)
    }
}
