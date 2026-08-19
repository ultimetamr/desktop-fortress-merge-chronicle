package com.example.desktopfortress.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class PicoHeadPoseTrackerTest {
    @Test
    fun `Stage view forward uses negative Z instead of SDK positive Z constant`() {
        assertEquals(0f, LOCAL_STAGE_VIEW_FORWARD.x, .0001f)
        assertEquals(0f, LOCAL_STAGE_VIEW_FORWARD.y, .0001f)
        assertEquals(-1f, LOCAL_STAGE_VIEW_FORWARD.z, .0001f)
    }
}
