package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.usecase.BuildBoardUseCase
import com.example.desktopfortress.domain.model.Board
import com.example.desktopfortress.domain.model.CellCoordinate
import com.example.desktopfortress.domain.model.BoardCell
import com.example.desktopfortress.domain.model.TowerInstance
import com.example.desktopfortress.domain.model.BoardPreviewMode
import com.example.desktopfortress.domain.model.HeadPose
import com.example.desktopfortress.domain.model.Plane
import com.example.desktopfortress.domain.model.PlaneScanStatus
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.atan2

object BoardManager : BaseManager() {
    private val buildBoard = BuildBoardUseCase()
    private var configuredPath = BuildBoardUseCase.DEFAULT_PATH
    private val mutableBoard = MutableStateFlow(
        buildBoard(Vector3(0f, SpatialManager.getGroundHeight(), -0.8f), configuredPath),
    )
    val board: StateFlow<Board> = mutableBoard.asStateFlow()
    private val mutablePreviewMode = MutableStateFlow(BoardPreviewMode.FOLLOWING_GAZE)
    val previewMode: StateFlow<BoardPreviewMode> = mutablePreviewMode.asStateFlow()
    private val mutablePlacementSurfaceReady = MutableStateFlow(false)
    val placementSurfaceReady: StateFlow<Boolean> = mutablePlacementSurfaceReady.asStateFlow()
    private var awaitingDetectedGround = false
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        initialized = true
        managerScope.launch {
            SpatialManager.mainPlane.collect { plane ->
                val current = mutableBoard.value
                if (plane != null && mutablePlacementSurfaceReady.value && !current.isLocked) {
                    mutableBoard.value = current.copy(
                        transform = current.transform.copy(
                            worldCenter = Vector3(
                                current.transform.worldCenter.x,
                                plane.center.y,
                                current.transform.worldCenter.z,
                            ),
                        ),
                    )
                }
            }
        }
        managerScope.launch {
            SpatialManager.scanStatus.collect { status ->
                if (status is PlaneScanStatus.Success &&
                    !status.plane.isFallback &&
                    awaitingDetectedGround &&
                    !mutableBoard.value.isLocked
                ) {
                    placePreviewOnSurface(status.plane, UIManager.headPose.value)
                }
            }
        }
    }

    /** Starts calibration with no visible board until a real floor anchor is selected. */
    fun beginGroundCalibration(pose: HeadPose = UIManager.headPose.value) {
        awaitingDetectedGround = true
        mutablePlacementSurfaceReady.value = false
        val draft = buildBoard(Vector3(pose.position.x, 0f, pose.position.z), configuredPath)
        mutableBoard.value = draft.copy(
            transform = draft.transform.copy(yawRadians = facingPlayerYaw(pose)),
        )
        mutablePreviewMode.value = BoardPreviewMode.WORLD_LOCKED
    }

    fun resetForCalibration(pose: HeadPose = UIManager.headPose.value) {
        beginGroundCalibration(pose)
        val surface = SpatialManager.getMainGroundPlane()
            ?: Plane(
                id = java.util.UUID.nameUUIDFromBytes("board-manager-test-ground".toByteArray()),
                center = Vector3(0f, SpatialManager.getGroundHeight(), 0f),
                normal = Vector3.UP,
                boundary = emptyList(),
                widthMeters = 8f,
                depthMeters = 8f,
                areaSquareMeters = 64f,
                flatnessMeters = 0f,
                isFallback = true,
            )
        placePreviewOnSurface(surface, pose)
    }

    /** Explicit fallback acceptance; scan failure alone never makes the board visible. */
    fun useFallbackSurface(surface: Plane, pose: HeadPose = UIManager.headPose.value): Boolean {
        if (!surface.isFallback || mutableBoard.value.isLocked) return false
        return placePreviewOnSurface(surface, pose)
    }

    /** Places an editable preview directly on the ground in front of the player. */
    fun placePreviewInFront(
        pose: HeadPose,
        distanceMeters: Float = DIRECT_PLACEMENT_DISTANCE_METERS,
    ): Boolean {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value || current.isLocked ||
            !distanceMeters.isFinite() || distanceMeters <= 0f
        ) return false
        val desired = frontGroundPosition(pose, distanceMeters)
        mutableBoard.value = current.copy(
            transform = current.transform.copy(
                worldCenter = desired,
                yawRadians = facingPlayerYaw(pose),
            ),
            highlightedCell = null,
        )
        mutablePreviewMode.value = BoardPreviewMode.WORLD_LOCKED
        return true
    }

    /** Rebuilds an unlocked board with the selected level path. */
    fun configurePath(path: List<CellCoordinate>) {
        configuredPath = path.toList()
        val current = mutableBoard.value
        val center = SpatialManager.getMainGroundPlane()?.center ?: current.transform.worldCenter
        mutableBoard.value = buildBoard(center, configuredPath).copy(
            transform = current.transform.copy(worldCenter = center),
        )
    }

    /** Delta is in world meters and constrained to the detected ground X/Z footprint. */
    fun dragByWorld(deltaX: Float, deltaZ: Float) {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value || current.isLocked ||
            mutablePreviewMode.value != BoardPreviewMode.WORLD_LOCKED
        ) return
        val desired = current.transform.worldCenter + Vector3(deltaX, 0f, deltaZ)
        val constrained = constrainToGround(current, desired)
        mutableBoard.value = current.copy(transform = current.transform.copy(worldCenter = constrained))
    }

    /**
     * Maps Compose/View drag deltas onto the horizontal ground relative to the
     * current viewer. View +Y points down, so dragging upward moves forward.
     */
    fun dragByViewMeters(
        deltaHorizontalMeters: Float,
        deltaVerticalMeters: Float,
        pose: HeadPose = UIManager.headPose.value,
    ) {
        if (!deltaHorizontalMeters.isFinite() || !deltaVerticalMeters.isFinite()) return
        val forward = pose.horizontalForward.takeIf { it.length() > .0001f }?.normalize()
            ?: LOCAL_STAGE_VIEW_FORWARD
        val right = Vector3(-forward.z, 0f, forward.x)
        val worldDelta = right * deltaHorizontalMeters + forward * -deltaVerticalMeters
        dragByWorld(worldDelta.x, worldDelta.z)
    }

    fun movePreviewToWorld(world: Vector3) {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value || current.isLocked ||
            mutablePreviewMode.value != BoardPreviewMode.WORLD_LOCKED
        ) return
        val desired = Vector3(world.x, SpatialManager.getGroundHeight(), world.z)
        mutableBoard.value = current.copy(
            transform = current.transform.copy(worldCenter = constrainToGround(current, desired)),
        )
    }

    fun scaleBy(factor: Float) {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value || current.isLocked ||
            mutablePreviewMode.value != BoardPreviewMode.WORLD_LOCKED || !factor.isFinite()
        ) return
        val scale = (current.transform.scale * factor).coerceIn(Board.MIN_SCALE, Board.MAX_SCALE)
        mutableBoard.value = current.copy(transform = current.transform.copy(scale = scale))
        dragByWorld(0f, 0f)
    }

    fun previewCell(coordinate: CellCoordinate?) {
        val valid = coordinate?.takeIf { it.row in 0 until Board.ROWS && it.column in 0 until Board.COLUMNS }
        if (mutableBoard.value.highlightedCell == valid) return
        mutableBoard.value = mutableBoard.value.copy(highlightedCell = valid)
    }

    fun previewWorldPosition(world: Vector3) {
        previewCell(coordinateAtWorld(world))
    }

    fun coordinateAtWorld(world: Vector3): CellCoordinate? {
        val current = mutableBoard.value
        val local = current.worldToLocal(world)
        val column = floor((local.x + current.widthMeters / 2f) / current.cellSizeMeters).toInt()
        val row = floor((local.z + current.depthMeters / 2f) / current.cellSizeMeters).toInt()
        return CellCoordinate(row, column).takeIf {
            it.row in 0 until current.rows && it.column in 0 until current.columns
        }
    }

    fun cellAt(coordinate: CellCoordinate?): BoardCell? = coordinate?.let { target ->
        mutableBoard.value.cells.firstOrNull { it.coordinate == target }
    }

    fun cellWorldCenter(coordinate: CellCoordinate): Vector3 {
        val current = mutableBoard.value
        return current.localToWorld(current.cellLocalCenter(coordinate))
    }

    fun setTower(coordinate: CellCoordinate, tower: TowerInstance?): Boolean {
        val current = mutableBoard.value
        val index = current.cells.indexOfFirst { it.coordinate == coordinate }
        if (index < 0) return false
        val cells = current.cells.toMutableList()
        cells[index] = cells[index].copy(hasTower = tower != null, tower = tower)
        mutableBoard.value = current.copy(cells = cells)
        return true
    }

    fun lockPlacement(): Boolean {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value ||
            mutablePreviewMode.value != BoardPreviewMode.WORLD_LOCKED
        ) return false
        // Player confirmation is authoritative. Sense mesh obstacles can include
        // the physical floor, feet, or transient reconstruction fragments and
        // must not prevent a deliberate board placement.
        mutableBoard.value = current.copy(isLocked = true, highlightedCell = null)
        return true
    }

    fun lockPreview(): Boolean {
        val current = mutableBoard.value
        if (!mutablePlacementSurfaceReady.value || current.isLocked) return false
        mutablePreviewMode.value = BoardPreviewMode.WORLD_LOCKED
        val center = constrainToGround(
            current,
            Vector3(
                current.transform.worldCenter.x,
                SpatialManager.getGroundHeight(),
                current.transform.worldCenter.z,
            ),
        )
        mutableBoard.value = current.copy(transform = current.transform.copy(worldCenter = center))
        return true
    }

    fun resumePreviewFollow(): Boolean {
        if (!mutablePlacementSurfaceReady.value || mutableBoard.value.isLocked) return false
        mutablePreviewMode.value = BoardPreviewMode.FOLLOWING_GAZE
        return true
    }

    /** Slow gaze-relative preview. X/Z follow the head; Y always comes from the ground plane. */
    fun updateCalibrationPreview(deltaSeconds: Float, pose: HeadPose) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f ||
            !mutablePlacementSurfaceReady.value ||
            mutablePreviewMode.value != BoardPreviewMode.FOLLOWING_GAZE
        ) return
        val current = mutableBoard.value
        if (current.isLocked) return
        val desired = Vector3(
            pose.position.x + pose.horizontalForward.x * PREVIEW_DISTANCE_METERS,
            SpatialManager.getGroundHeight(),
            pose.position.z + pose.horizontalForward.z * PREVIEW_DISTANCE_METERS,
        )
        val target = constrainPreviewPointToGround(desired)
        val alpha = (1f - exp(-deltaSeconds / PREVIEW_LAG_SECONDS)).coerceIn(0f, 1f)
        val center = Vector3.lerp(current.transform.worldCenter, target, alpha).let {
            Vector3(it.x, SpatialManager.getGroundHeight(), it.z)
        }
        mutableBoard.value = current.copy(transform = current.transform.copy(worldCenter = center))
    }

    /** Explicit safety recenter; allowed even after placement has been world-locked. */
    fun recenterInFront(pose: com.example.desktopfortress.domain.model.HeadPose, distanceMeters: Float = .8f): Vector3 {
        val current = mutableBoard.value
        val desired = Vector3(
            pose.position.x + pose.horizontalForward.x * distanceMeters,
            SpatialManager.getGroundHeight(),
            pose.position.z + pose.horizontalForward.z * distanceMeters,
        )
        val plane = SpatialManager.getMainGroundPlane()
        val center = if (plane == null) desired else Vector3(
            clampOrCenter(
                desired.x,
                plane.center.x - plane.widthMeters / 2f + current.widthMeters * current.transform.scale / 2f,
                plane.center.x + plane.widthMeters / 2f - current.widthMeters * current.transform.scale / 2f,
                plane.center.x,
            ),
            plane.center.y,
            clampOrCenter(
                desired.z,
                plane.center.z - plane.depthMeters / 2f + current.depthMeters * current.transform.scale / 2f,
                plane.center.z + plane.depthMeters / 2f - current.depthMeters * current.transform.scale / 2f,
                plane.center.z,
            ),
        )
        mutableBoard.value = current.copy(
            transform = current.transform.copy(worldCenter = center),
            highlightedCell = null,
        )
        return center - current.transform.worldCenter
    }

    fun localToWorld(local: Vector3): Vector3 = mutableBoard.value.localToWorld(local)
    fun worldToLocal(world: Vector3): Vector3 = mutableBoard.value.worldToLocal(world)

    private fun clampOrCenter(value: Float, minimum: Float, maximum: Float, center: Float): Float =
        if (minimum <= maximum) value.coerceIn(minimum, maximum) else center

    private fun frontGroundPosition(pose: HeadPose, distanceMeters: Float): Vector3 = Vector3(
        pose.position.x + pose.horizontalForward.x * distanceMeters,
        SpatialManager.getGroundHeight(),
        pose.position.z + pose.horizontalForward.z * distanceMeters,
    )

    /** Rotates the board so its local +Z near edge always faces the player. */
    private fun facingPlayerYaw(pose: HeadPose): Float = atan2(
        pose.horizontalForward.x,
        -pose.horizontalForward.z,
    )

    private fun constrainToGround(board: Board, desired: Vector3): Vector3 {
        val plane = SpatialManager.getMainGroundPlane()
            ?: return Vector3(desired.x, SpatialManager.getGroundHeight(), desired.z)
        return Vector3(
            clampOrCenter(
                desired.x,
                plane.center.x - plane.widthMeters / 2f + board.widthMeters * board.transform.scale / 2f,
                plane.center.x + plane.widthMeters / 2f - board.widthMeters * board.transform.scale / 2f,
                plane.center.x,
            ),
            plane.center.y,
            clampOrCenter(
                desired.z,
                plane.center.z - plane.depthMeters / 2f + board.depthMeters * board.transform.scale / 2f,
                plane.center.z + plane.depthMeters / 2f - board.depthMeters * board.transform.scale / 2f,
                plane.center.z,
            ),
        )
    }

    private fun constrainPreviewPointToGround(desired: Vector3): Vector3 {
        val plane = SpatialManager.getMainGroundPlane()
            ?: return Vector3(desired.x, SpatialManager.getGroundHeight(), desired.z)
        return Vector3(
            desired.x.coerceIn(plane.center.x - plane.widthMeters / 2f, plane.center.x + plane.widthMeters / 2f),
            plane.center.y,
            desired.z.coerceIn(plane.center.z - plane.depthMeters / 2f, plane.center.z + plane.depthMeters / 2f),
        )
    }

    override fun destroy() {
        mutablePreviewMode.value = BoardPreviewMode.FOLLOWING_GAZE
        mutablePlacementSurfaceReady.value = false
        awaitingDetectedGround = false
        initialized = false
        cancelScope()
    }

    private fun placePreviewOnSurface(surface: Plane, pose: HeadPose): Boolean {
        if (mutableBoard.value.isLocked) return false
        val desired = Vector3(
            pose.position.x + pose.horizontalForward.x * DIRECT_PLACEMENT_DISTANCE_METERS,
            surface.center.y,
            pose.position.z + pose.horizontalForward.z * DIRECT_PLACEMENT_DISTANCE_METERS,
        )
        val draft = buildBoard(desired, configuredPath)
        val constrained = constrainToSurface(draft, desired, surface)
        mutableBoard.value = draft.copy(
            transform = draft.transform.copy(
                worldCenter = constrained,
                yawRadians = facingPlayerYaw(pose),
            ),
        )
        mutablePreviewMode.value = BoardPreviewMode.WORLD_LOCKED
        mutablePlacementSurfaceReady.value = true
        awaitingDetectedGround = false
        return true
    }

    private fun constrainToSurface(board: Board, desired: Vector3, surface: Plane): Vector3 = Vector3(
        clampOrCenter(
            desired.x,
            surface.center.x - surface.widthMeters / 2f + board.widthMeters * board.transform.scale / 2f,
            surface.center.x + surface.widthMeters / 2f - board.widthMeters * board.transform.scale / 2f,
            surface.center.x,
        ),
        surface.center.y,
        clampOrCenter(
            desired.z,
            surface.center.z - surface.depthMeters / 2f + board.depthMeters * board.transform.scale / 2f,
            surface.center.z,
            surface.center.z,
        ),
    )

    const val DIRECT_PLACEMENT_DISTANCE_METERS = 2f
    private const val PREVIEW_DISTANCE_METERS = DIRECT_PLACEMENT_DISTANCE_METERS
    private const val PREVIEW_LAG_SECONDS = UIManager.HUD_LAG_SECONDS
}
