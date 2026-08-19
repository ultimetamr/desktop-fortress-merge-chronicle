package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.Plane
import com.example.desktopfortress.domain.model.SurfaceSemantic
import kotlin.math.abs

object PlaneSelector {
    const val MAX_EXPECTED_GROUND_DEVIATION_METERS = 0.65f

    /**
     * Ground placement only consumes an anchor height. PICO room captures do
     * not consistently label the physical floor as FLOOR, and their boundary,
     * normal, area and flatness values can be incomplete or noisy. Those fields
     * therefore never reject a candidate. Any anchor inside the viewer-relative
     * floor-height window is accepted; semantic labels only rank equal-height
     * candidates and never decide eligibility. Area is only a deterministic
     * tie-breaker and never an eligibility requirement.
     */
    fun selectMainGround(planes: Collection<Plane>, expectedGroundY: Float = 0f): Plane? {
        val heightCandidates = planes.asSequence()
            .filter { !it.isFallback }
            .filter { it.center.y.isFinite() }
            .filter {
                abs(it.center.y - expectedGroundY) <= MAX_EXPECTED_GROUND_DEVIATION_METERS
            }
            .toList()
        return heightCandidates.minWithOrNull(
            compareBy<Plane> {
                when (it.semantic) {
                    SurfaceSemantic.FLOOR -> 0
                    SurfaceSemantic.UNKNOWN -> 1
                    SurfaceSemantic.OTHER -> 2
                }
            }
                .thenBy { abs(it.center.y - expectedGroundY) }
                .thenByDescending { it.areaSquareMeters },
        )
    }

    /** Compatibility alias for older call sites; new placement code uses ground terminology. */
    fun selectMainDesktop(planes: Collection<Plane>): Plane? = selectMainGround(planes)
}
