package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.HeadPose
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlinx.coroutines.flow.collectLatest

/**
 * A Stage looks down local -Z: Spatial SDK +Z points back toward the viewer.
 * Keep this explicit instead of using the ambiguously named Vector3.FORWARD (+Z).
 */
internal val LOCAL_STAGE_VIEW_FORWARD: Vector3 = Vector3.BACK

/** Kept outside UIManager so pure JVM UI state tests never initialize Android tracking classes. */
internal interface HeadPoseTracker {
    suspend fun collect(onPose: (HeadPose) -> Unit)
    fun stop()
}

internal class PicoHeadPoseTracker : HeadPoseTracker {
    private val provider = HMDTrackingProvider()

    override suspend fun collect(onPose: (HeadPose) -> Unit) {
        runCatching { provider.start() }
        provider.dataFlow.collectLatest { data ->
            val rawForward = data.hmdPose.rotation.rotateVector(LOCAL_STAGE_VIEW_FORWARD)
            val fullForward = if (rawForward.length() > .0001f) {
                rawForward.normalize()
            } else {
                LOCAL_STAGE_VIEW_FORWARD
            }
            val horizontal = Vector3(rawForward.x, 0f, rawForward.z)
            val normalized = if (horizontal.length() > .0001f) {
                horizontal.normalize()
            } else {
                LOCAL_STAGE_VIEW_FORWARD
            }
            onPose(HeadPose(data.hmdPose.position, normalized, fullForward))
        }
    }

    override fun stop() {
        runCatching { provider.stop() }
    }
}
