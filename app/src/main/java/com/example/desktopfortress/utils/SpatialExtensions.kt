package com.example.desktopfortress.utils

import com.example.desktopfortress.BuildConfig
import com.example.desktopfortress.manager.SpatialManager
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

fun Vector3.alignToGround(offsetMeters: Float = 0f): Vector3 =
    Vector3(x, SpatialManager.getGroundHeight() + offsetMeters, z)

/** Compatibility alias retained for existing entity call sites. */
fun Vector3.alignToDesktop(offsetMeters: Float = 0f): Vector3 = alignToGround(offsetMeters)

object GroundingDebug {
    private val mutableEnabled = MutableStateFlow(BuildConfig.GROUNDING_DEBUG_DEFAULT)
    val enabled = mutableEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
    }
}

object CollisionDebug {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
    }
}
