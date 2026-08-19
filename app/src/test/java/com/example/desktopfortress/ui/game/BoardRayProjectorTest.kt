package com.example.desktopfortress.ui.game

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardRayProjectorTest {
    @Test
    fun rayHitsExactPointOnHorizontalBoard() {
        val hit = BoardRayProjector.intersectHorizontalPlane(
            origin = Vector3(0f, 1.6f, 0f),
            pointOnRay = Vector3(.5f, .6f, -1f),
            planeY = 0f,
        )
        assertEquals(.8f, hit?.x ?: Float.NaN, .0001f)
        assertEquals(0f, hit?.y ?: Float.NaN, .0001f)
        assertEquals(-1.6f, hit?.z ?: Float.NaN, .0001f)
    }

    @Test
    fun resultDoesNotDependOnPointerDepthAlongSameRay() {
        val origin = Vector3(.1f, 1.5f, .2f)
        val near = BoardRayProjector.intersectHorizontalPlane(origin, Vector3(.3f, 1f, -.3f), 0f)
        val far = BoardRayProjector.intersectHorizontalPlane(origin, Vector3(.9f, -.5f, -1.8f), 0f)
        assertEquals(near?.x ?: Float.NaN, far?.x ?: Float.NaN, .0001f)
        assertEquals(near?.z ?: Float.NaN, far?.z ?: Float.NaN, .0001f)
    }

    @Test
    fun rejectsParallelOrBackwardRay() {
        assertNull(
            BoardRayProjector.intersectHorizontalPlane(
                Vector3(0f, 1f, 0f),
                Vector3(1f, 1f, 0f),
                0f,
            ),
        )
        assertNull(
            BoardRayProjector.intersectHorizontalPlane(
                Vector3(0f, 1f, 0f),
                Vector3(0f, 2f, 0f),
                0f,
            ),
        )
    }
}
