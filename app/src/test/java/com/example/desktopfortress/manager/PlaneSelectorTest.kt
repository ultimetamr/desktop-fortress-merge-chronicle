package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.Plane
import com.example.desktopfortress.domain.model.SurfaceSemantic
import com.pico.spatial.core.math.Vector3
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaneSelectorTest {
    @Test
    fun selectsLargestQualifiedUpwardPlaneInLowestGroundBand() {
        val small = plane(area = 0.6f)
        val large = plane(area = 1.2f)
        assertEquals(large.id, PlaneSelector.selectMainGround(listOf(small, large))?.id)
    }

    @Test
    fun rejectsLargeTableWhenQualifiedFloorIsLower() {
        val floor = plane(area = 1.2f, centerY = 0f)
        val largeTable = plane(area = 3f, centerY = .75f)
        assertEquals(floor.id, PlaneSelector.selectMainGround(listOf(largeTable, floor))?.id)
    }

    @Test
    fun doesNotPublishTableAsGroundBeforeFloorAppears() {
        val table = plane(area = 3f, centerY = .75f)
        assertNull(PlaneSelector.selectMainGround(listOf(table)))
    }

    @Test
    fun rejectsDeepPlaneAndKeepsStageFloor() {
        val staleSubfloor = plane(area = 8f, centerY = -1.4f)
        val floor = plane(area = 1.2f, centerY = 0f)
        assertEquals(floor.id, PlaneSelector.selectMainGround(listOf(staleSubfloor, floor))?.id)
        assertNull(PlaneSelector.selectMainGround(listOf(staleSubfloor)))
    }

    @Test
    fun preservesLocalizedSenseGroundHeight() {
        val noisyFloor = plane(area = 1.2f, centerY = -.08f)
        val selected = PlaneSelector.selectMainGround(listOf(noisyFloor))
        assertEquals(-.08f, selected?.center?.y ?: Float.NaN, .0001f)
    }

    @Test
    fun acceptsLocalizedFloorOffsetButRejectsDeepStaleAnchor() {
        assertEquals(
            -.2f,
            PlaneSelector.selectMainGround(listOf(plane(area = 1.2f, centerY = -.2f)))?.center?.y
                ?: Float.NaN,
            .0001f,
        )
        assertNull(PlaneSelector.selectMainGround(listOf(plane(area = 1.2f, centerY = -.8f))))
    }

    @Test
    fun selectsFloorRelativeToLocalizedHeadInsteadOfAssumingRootZero() {
        val floor = plane(area = 2f, centerY = -1.58f)
        val table = plane(area = 4f, centerY = -.72f)
        assertEquals(
            floor.id,
            PlaneSelector.selectMainGround(listOf(table, floor), expectedGroundY = -1.6f)?.id,
        )
    }

    @Test
    fun semanticFloorWinsEvenWhenUnknownPlaneIsLarger() {
        val floor = plane(area = 1.5f, centerY = -1.5f, semantic = SurfaceSemantic.FLOOR)
        val unknown = plane(area = 6f, centerY = -1.55f)
        assertEquals(
            floor.id,
            PlaneSelector.selectMainGround(listOf(unknown, floor), expectedGroundY = -1.6f)?.id,
        )
    }

    @Test
    fun semanticFloorProvidesHeightDespiteNoisyCapturedBoundary() {
        // Mirrors the real-device FLOOR anchor that was previously rejected:
        // y ~= 0.48 m, area ~= 218.9 m2, boundary variance ~= 0.058 m.
        val capturedFloor = plane(
            area = 218.89f,
            normal = Vector3(0.4f, 0.2f, 0f),
            flatness = 0.058f,
            centerY = 0.48f,
            semantic = SurfaceSemantic.FLOOR,
        )

        val selected = PlaneSelector.selectMainGround(
            listOf(capturedFloor),
            expectedGroundY = 0.45f,
        )

        assertEquals(capturedFloor.id, selected?.id)
        assertEquals(0.48f, selected?.center?.y ?: Float.NaN, 0.0001f)
    }

    @Test
    fun acceptsUnknownFloorHeightDespiteSmallTiltedOrUnevenGeometry() {
        val small = plane(area = 0.49f)
        val tilted = plane(area = 1f, normal = Vector3(0.5f, 0.5f, 0f))
        val uneven = plane(area = 1f, flatness = 0.02f)
        val selected = PlaneSelector.selectMainGround(listOf(small, tilted, uneven))
        assertEquals(1f, selected?.areaSquareMeters ?: Float.NaN, .0001f)
    }

    @Test
    fun acceptsMislabeledGroundWhenItsHeightMatches() {
        val mislabeledGround = plane(
            area = 8f,
            centerY = 0f,
            semantic = SurfaceSemantic.OTHER,
        )
        assertEquals(mislabeledGround.id, PlaneSelector.selectMainGround(listOf(mislabeledGround))?.id)
    }

    @Test
    fun choosesClosestUnknownHeightWithoutGeometryGates() {
        val noisyFloor = plane(
            area = .1f,
            normal = Vector3(1f, 0f, 0f),
            flatness = .2f,
            centerY = .04f,
        )
        val lowerCandidate = plane(area = 100f, centerY = -.4f)

        assertEquals(
            noisyFloor.id,
            PlaneSelector.selectMainGround(listOf(lowerCandidate, noisyFloor))?.id,
        )
    }

    private fun plane(
        area: Float,
        normal: Vector3 = Vector3.UP,
        flatness: Float = 0f,
        centerY: Float = 0f,
        semantic: SurfaceSemantic = SurfaceSemantic.UNKNOWN,
    ) = Plane(
        id = UUID.randomUUID(),
        center = Vector3(0f, centerY, 0f),
        normal = normal,
        boundary = emptyList(),
        widthMeters = area,
        depthMeters = 1f,
        areaSquareMeters = area,
        flatnessMeters = flatness,
        semantic = semantic,
    )
}
