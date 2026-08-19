package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.BoardPreviewMode
import com.example.desktopfortress.domain.model.HeadPose
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCalibrationPreviewTest {
    @Test
    fun `calibration hides board and blocks confirmation until a surface is accepted`() {
        val pose = HeadPose(Vector3(0f, 1.6f, 0f), Vector3(0f, 0f, -1f))
        BoardManager.beginGroundCalibration(pose)

        assertEquals(false, BoardManager.placementSurfaceReady.value)
        assertEquals(false, BoardManager.lockPlacement())
        assertEquals(false, BoardManager.placePreviewInFront(pose))
    }

    @Test
    fun `calibration starts editable two meters ahead and can confirm directly`() {
        val pose = HeadPose(Vector3(.4f, 1.6f, .2f), Vector3(0f, 0f, -1f))
        BoardManager.resetForCalibration(pose)
        val initial = BoardManager.board.value.transform.worldCenter
        assertEquals(BoardPreviewMode.WORLD_LOCKED, BoardManager.previewMode.value)
        assertEquals(pose.position.x, initial.x, .0001f)
        assertEquals(2f, BoardManager.DIRECT_PLACEMENT_DISTANCE_METERS, .0001f)
        assertEquals(pose.position.z - BoardManager.DIRECT_PLACEMENT_DISTANCE_METERS, initial.z, .0001f)
        assertEquals(SpatialManager.getGroundHeight(), initial.y, .0001f)

        // Head updates no longer move a directly placeable preview.
        BoardManager.updateCalibrationPreview(.5f, HeadPose(Vector3(-2f, 2f, 2f), Vector3.FORWARD))
        assertEquals(initial, BoardManager.board.value.transform.worldCenter)

        BoardManager.dragByWorld(-.05f, 0f)
        assertTrue(BoardManager.board.value.transform.worldCenter.x < initial.x)
        assertTrue(BoardManager.lockPlacement())
    }

    @Test
    fun `view drag follows viewer axes when facing away from stage default`() {
        val pose = HeadPose(Vector3(0f, 1.6f, 0f), Vector3(1f, 0f, 0f))
        BoardManager.resetForCalibration(pose)
        val before = BoardManager.board.value.transform.worldCenter

        // Looking toward +X: screen-right is world +Z, screen-up is world +X.
        BoardManager.dragByViewMeters(.10f, -.12f, pose)
        val after = BoardManager.board.value.transform.worldCenter

        assertEquals(before.z + .10f, after.z, .0001f)
        assertEquals(before.x + .12f, after.x, .0001f)
    }

    @Test
    fun `player confirmation locks a ready board without obstacle rejection`() {
        BoardManager.resetForCalibration()

        assertTrue(BoardManager.placementSurfaceReady.value)
        assertTrue(BoardManager.lockPlacement())
        assertTrue(BoardManager.board.value.isLocked)
    }
}
