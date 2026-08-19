package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.HeadPose
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.UiPanel
import com.example.desktopfortress.domain.model.PanelGroup
import com.example.desktopfortress.domain.model.PanelRenderLayer
import com.example.desktopfortress.domain.model.PanelBlendMode
import com.pico.spatial.core.math.Vector3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UIManagerTest {
    @Before fun setUp() {
        UIManager.destroy()
        UIManager.initialize()
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(0f, 1.6f, 0f), Vector3(0f, 0f, -1f)))
    }

    @After fun tearDown() = UIManager.destroy()

    @Test fun `A panels are mutually exclusive and open one meter ahead`() {
        UIManager.open(UiPanel.MAIN_MENU)
        UIManager.open(UiPanel.LEVEL_SELECT)
        val state = UIManager.state.value
        assertEquals(UiPanel.LEVEL_SELECT, state.activeModal)
        assertEquals(-1f, state.modalTransform!!.position.z, .0001f)
        assertTrue(state.visibleHuds.isEmpty())
    }

    @Test fun `A panel remains locked until a recenter threshold is exceeded`() {
        UIManager.open(UiPanel.SETTLEMENT)
        val locked = UIManager.state.value.modalTransform!!
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(.1f, 1.6f, 0f), Vector3(0f, 0f, -1f)))
        UIManager.update(.2f)
        assertEquals(locked.position, UIManager.state.value.modalTransform!!.position)

        UIManager.setHeadPoseForTesting(HeadPose(Vector3(2f, 1.6f, 0f), Vector3(0f, 0f, -1f)))
        UIManager.update(.15f)
        val midway = UIManager.state.value.modalTransform!!.position
        assertTrue(midway.x > locked.position.x)
        UIManager.update(.15f)
        val complete = UIManager.state.value.modalTransform!!.position
        assertEquals(2f, complete.x, .02f)
        assertEquals(-1f, complete.z, .02f)
    }

    @Test fun `calibration panel shares HUD distance smoothing and threshold policy`() {
        assertEquals(1.2f, UIManager.CALIBRATION_DISTANCE_METERS, .0001f)
        assertEquals(.8f, UIManager.CALIBRATION_PANEL_WIDTH_METERS, .0001f)
        assertEquals(.6f, UIManager.CALIBRATION_PANEL_HEIGHT_METERS, .0001f)
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val first = UIManager.state.value.calibrationTransform!!
        assertEquals(-1.2f, first.position.z, .0001f)
        assertEquals(null, UIManager.state.value.modalTransform)

        UIManager.setHeadPoseForTesting(HeadPose(Vector3(1f, 1.6f, 0f), Vector3(0f, 0f, -1f)))
        UIManager.update(.016f)
        val second = UIManager.state.value.calibrationTransform!!
        assertTrue(second.position.x > first.position.x)
        assertTrue(second.position.x < 1f)
        assertEquals(null, UIManager.state.value.modalTransform)
    }

    @Test fun `calibration panel resets to current front beyond the HUD ninety degree threshold`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val initial = UIManager.state.value.calibrationTransform!!
        assertEquals(-1.2f, initial.position.z, .0001f)

        UIManager.setHeadPoseForTesting(
            HeadPose(Vector3(0f, 1.6f, 0f), Vector3(.9848077f, 0f, .1736482f)),
        )
        assertTrue(
            UIManager.horizontalAngleDegrees(initial, UIManager.headPose.value) >
                UIManager.HUD_RECENTER_ANGLE_DEGREES,
        )
        UIManager.update(.016f)

        val reset = UIManager.state.value.calibrationTransform!!
        assertEquals(.9848077f * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.x, .0001f)
        assertEquals(.1736482f * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.z, .0001f)
    }

    @Test fun `calibration panel applies the same threshold when turning left`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val leftForward = Vector3(-.9848077f, 0f, .1736482f)
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(0f, 1.6f, 0f), leftForward))
        UIManager.update(.016f)

        val reset = UIManager.state.value.calibrationTransform!!
        assertEquals(leftForward.x * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.x, .0001f)
        assertEquals(leftForward.z * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.z, .0001f)
    }

    @Test fun `calibration panel keeps its anchor at exactly ninety degrees`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val initial = UIManager.state.value.calibrationTransform!!
        UIManager.setHeadPoseForTesting(
            HeadPose(Vector3(0f, 1.6f, 0f), Vector3(1f, 0f, 0f)),
        )
        assertEquals(
            90f,
            UIManager.horizontalAngleDegrees(initial, UIManager.headPose.value),
            .001f,
        )
        UIManager.update(.016f)

        val anchored = UIManager.state.value.calibrationTransform!!
        assertEquals(initial.position.x, anchored.position.x, .0001f)
        assertEquals(initial.position.z, anchored.position.z, .0001f)
    }

    @Test fun `calibration panel resets upward only after vertical angle exceeds ninety degrees`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val initial = UIManager.state.value.calibrationTransform!!
        val upwardBeyondNinety = Vector3(0f, .9848077f, .1736482f)
        UIManager.setHeadPoseForTesting(
            HeadPose(
                Vector3(0f, 1.6f, 0f),
                Vector3(0f, 0f, -1f),
                upwardBeyondNinety,
            ),
        )
        assertTrue(
            UIManager.verticalAngleDegrees(initial, UIManager.headPose.value) >
                UIManager.CALIBRATION_RECENTER_ANGLE_DEGREES,
        )
        UIManager.update(.016f)

        val reset = UIManager.state.value.calibrationTransform!!
        assertEquals(1.6f + upwardBeyondNinety.y * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.y, .0001f)
        assertEquals(upwardBeyondNinety.z * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.z, .0001f)
    }

    @Test fun `calibration panel resets downward only after vertical angle exceeds ninety degrees`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val initial = UIManager.state.value.calibrationTransform!!
        val downwardBeyondNinety = Vector3(0f, -.9848077f, .1736482f)
        UIManager.setHeadPoseForTesting(
            HeadPose(
                Vector3(0f, 1.6f, 0f),
                Vector3(0f, 0f, -1f),
                downwardBeyondNinety,
            ),
        )
        assertTrue(
            UIManager.verticalAngleDegrees(initial, UIManager.headPose.value) >
                UIManager.CALIBRATION_RECENTER_ANGLE_DEGREES,
        )
        UIManager.update(.016f)

        val reset = UIManager.state.value.calibrationTransform!!
        assertEquals(1.6f + downwardBeyondNinety.y * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.y, .0001f)
        assertEquals(downwardBeyondNinety.z * UIManager.CALIBRATION_DISTANCE_METERS, reset.position.z, .0001f)
    }

    @Test fun `calibration panel keeps its pitch anchor at exactly ninety degrees`() {
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val initial = UIManager.state.value.calibrationTransform!!
        UIManager.setHeadPoseForTesting(
            HeadPose(
                Vector3(0f, 1.6f, 0f),
                Vector3(0f, 0f, -1f),
                Vector3(0f, 1f, 0f),
            ),
        )
        assertEquals(
            90f,
            UIManager.verticalAngleDegrees(initial, UIManager.headPose.value),
            .001f,
        )
        UIManager.update(.016f)

        val anchored = UIManager.state.value.calibrationTransform!!
        assertEquals(initial.position.y, anchored.position.y, .0001f)
        assertEquals(initial.position.z, anchored.position.z, .0001f)
    }

    @Test fun `calibration state replaces stale planar menu visibility`() {
        UIManager.open(UiPanel.MAIN_MENU)
        UIManager.syncGameState(GameState.CALIBRATING)
        assertEquals(UiPanel.CALIBRATION_GUIDE, UIManager.state.value.activeModal)
        assertEquals(UiPanel.CALIBRATION_GUIDE, UIManager.visibility.value.activeModal)
        assertTrue(UIManager.state.value.calibrationTransform != null)
        assertEquals(null, UIManager.state.value.modalTransform)
    }

    @Test fun `calibration follows pitch while roll remains zero by contract`() {
        val fullForward = Vector3(0f, -.5f, -.8660254f)
        UIManager.setHeadPoseForTesting(
            HeadPose(Vector3(0f, 1.6f, 0f), Vector3(0f, 0f, -1f), fullForward),
        )
        UIManager.open(UiPanel.CALIBRATION_GUIDE)
        val transform = UIManager.state.value.calibrationTransform!!
        assertEquals(1f, transform.position.y, .001f)
        assertEquals(-30f, transform.pitchDegrees, .05f)
    }

    @Test fun `obstacle avoidance backs modal toward the player`() {
        val transform = UIManager.obstacleAvoidedTransform(
            pose = HeadPose(Vector3(0f, 1.6f, 0f), Vector3(0f, 0f, -1f)),
            obstacleQuery = { _, z -> z < -.75f },
        )
        assertTrue(transform.position.z > -.75f)
        assertTrue(transform.position.z <= -.45f)
    }

    @Test fun `B HUD follows smoothly instead of snapping`() {
        UIManager.open(UiPanel.COMBAT_TOP_HUD)
        UIManager.update(.016f)
        val first = UIManager.state.value.topHudTransform!!
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(1f, 1.6f, 0f), Vector3(0f, 0f, -1f)))
        UIManager.update(.016f)
        val second = UIManager.state.value.topHudTransform!!
        assertTrue(second.position.x > first.position.x)
        assertTrue(second.position.x < 1f)
        assertFalse(second.position.x == 1f)
    }

    @Test fun `B HUD resets to current front when horizontal angle exceeds ninety degrees`() {
        UIManager.syncGameState(GameState.PREPARE)
        val initialTop = UIManager.state.value.topHudTransform!!
        assertEquals(-1.2f, initialTop.position.z, .0001f)

        UIManager.setHeadPoseForTesting(
            HeadPose(Vector3(0f, 1.6f, 0f), Vector3(0f, 0f, 1f)),
        )
        assertTrue(
            UIManager.horizontalAngleDegrees(initialTop, UIManager.headPose.value) >
                UIManager.HUD_RECENTER_ANGLE_DEGREES,
        )
        UIManager.update(.016f)

        val state = UIManager.state.value
        assertEquals(1.2f, state.topHudTransform!!.position.z, .0001f)
        assertEquals(1.2f, state.bottomHudTransform!!.position.z, .0001f)
    }

    @Test fun `B HUD keeps smooth lag at exactly ninety degrees`() {
        UIManager.syncGameState(GameState.PREPARE)
        val initialTop = UIManager.state.value.topHudTransform!!
        UIManager.setHeadPoseForTesting(
            HeadPose(Vector3(0f, 1.6f, 0f), Vector3(1f, 0f, 0f)),
        )
        assertEquals(
            90f,
            UIManager.horizontalAngleDegrees(initialTop, UIManager.headPose.value),
            .001f,
        )
        UIManager.update(.016f)

        val moved = UIManager.state.value.topHudTransform!!.position
        assertEquals(initialTop.position.x, moved.x, .0001f)
        assertEquals(initialTop.position.z, moved.z, .0001f)
    }

    @Test fun `B HUD right turn beyond ninety degrees resets to right front`() {
        UIManager.syncGameState(GameState.PREPARE)
        val rightForward = Vector3(.9848077f, 0f, .1736482f)
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(0f, 1.6f, 0f), rightForward))
        UIManager.update(.016f)

        val moved = UIManager.state.value.topHudTransform!!.position
        assertEquals(rightForward.x * UIManager.HUD_DISTANCE_METERS, moved.x, .0001f)
        assertEquals(rightForward.z * UIManager.HUD_DISTANCE_METERS, moved.z, .0001f)
    }

    @Test fun `B HUD left turn beyond ninety degrees resets to left front`() {
        UIManager.syncGameState(GameState.PREPARE)
        val leftForward = Vector3(-.9848077f, 0f, .1736482f)
        UIManager.setHeadPoseForTesting(HeadPose(Vector3(0f, 1.6f, 0f), leftForward))
        UIManager.update(.016f)

        val moved = UIManager.state.value.bottomHudTransform!!.position
        assertEquals(leftForward.x * UIManager.HUD_DISTANCE_METERS, moved.x, .0001f)
        assertEquals(leftForward.z * UIManager.HUD_DISTANCE_METERS, moved.z, .0001f)
    }

    @Test fun `panel catalog contains seven A modals and two B HUDs`() {
        assertEquals(7, UiPanel.entries.count { it.group == PanelGroup.A_MODAL })
        assertEquals(2, UiPanel.entries.count { it.group == PanelGroup.B_HUD })
    }

    @Test fun `render contract has one millimeter depth steps and alpha blend`() {
        assertEquals(listOf(10, 20, 30, 40), PanelRenderLayer.entries.map { it.orderInLayer })
        assertEquals(listOf(0f, .001f, .002f, .003f), PanelRenderLayer.entries.map { it.depthMeters })
        assertFalse(UIManager.materialSpec.zWrite)
        assertEquals(PanelBlendMode.ALPHA_BLEND, UIManager.materialSpec.blendMode)
    }
}
