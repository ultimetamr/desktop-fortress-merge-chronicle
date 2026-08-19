package com.example.desktopfortress.manager

import android.app.Application
import android.util.Log
import com.example.desktopfortress.domain.model.ObstacleBox
import com.example.desktopfortress.domain.model.Plane
import com.example.desktopfortress.domain.model.PlaneScanStatus
import com.example.desktopfortress.domain.model.SurfaceSemantic
import com.example.desktopfortress.utils.EventBus
import com.example.desktopfortress.utils.UserMessage
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.AnchorUpdateSubscriber
import com.pico.spatial.sense.base.SemanticLabelType
import com.pico.spatial.sense.base.TrackingState
import com.pico.spatial.sense.mesh.MeshAnchor
import com.pico.spatial.sense.mesh.MeshTrackingManager
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneOrientation
import com.pico.spatial.sense.plane.PlaneTrackingManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-scoped bridge between PICO Sense anchors and ground geometry. */
object SpatialManager : BaseManager() {
    private const val TAG = "SpatialManager"
    private const val SCAN_TIMEOUT_MS = 30_000L
    private const val ESTIMATED_EYE_HEIGHT_METERS = 1.6f
    private const val TRACKING_START_TIMEOUT_MS = 5_000L
    private const val TRACKING_STATE_POLL_MS = 100L
    private const val EMPTY_ANCHOR_RETRY_COUNT = 10
    private const val EMPTY_ANCHOR_RETRY_MS = 750L
    private const val LOGICAL_GROUND_SIZE_METERS = 8f

    fun interface ScanStatusCallback {
        fun onStatusChanged(status: PlaneScanStatus)
    }

    private lateinit var application: Application
    private val planeAnchors = linkedMapOf<UUID, PlaneAnchor>()
    private val meshAnchors = linkedMapOf<UUID, MeshAnchor>()
    private val anchorLock = Any()
    private val callbacks = CopyOnWriteArraySet<ScanStatusCallback>()
    private var planeSubscription: Cancellable? = null
    private var meshSubscription: Cancellable? = null
    private var timeoutJob: Job? = null
    private var anchorLoadJob: Job? = null
    private var initialized = false
    private var sensing = false
    @Volatile private var latestViewPosition = Vector3(0f, 1.6f, 0f)
    private var lastSelectionViewY = Float.NaN
    private var lastPlaneDiagnostic = ""
    @Volatile private var spatialViewRoot: Entity? = null

    private val mutableMainPlane = MutableStateFlow<Plane?>(null)
    val mainPlane: StateFlow<Plane?> = mutableMainPlane.asStateFlow()

    private val mutableScanStatus = MutableStateFlow<PlaneScanStatus>(PlaneScanStatus.Idle)
    val scanStatus: StateFlow<PlaneScanStatus> = mutableScanStatus.asStateFlow()

    private val mutableObstacles = MutableStateFlow<List<ObstacleBox>>(emptyList())
    val obstacles: StateFlow<List<ObstacleBox>> = mutableObstacles.asStateFlow()

    fun initialize(application: Application) {
        this.application = application
        initialize()
    }

    override fun initialize() {
        if (initialized) return
        check(::application.isInitialized) { "SpatialManager requires Application initialization" }
        recreateScopeIfNeeded()
        initialized = true
    }

    fun updateViewPosition(position: Vector3) {
        latestViewPosition = position
        if (mutableScanStatus.value == PlaneScanStatus.Scanning &&
            (!lastSelectionViewY.isFinite() || kotlin.math.abs(position.y - lastSelectionViewY) >= .05f)
        ) {
            lastSelectionViewY = position.y
            managerScope.launch { selectAndPublishGround() }
        }
    }

    /**
     * Sense anchors are reported in tracking-global coordinates. PICO requires
     * converting them through a stable SpatialView root before using them as
     * runtime ECS positions. The root must never be the moving board entity.
     */
    fun bindSpatialViewRoot(root: Entity) {
        spatialViewRoot = root
        managerScope.launch { selectAndPublishGround() }
    }

    fun unbindSpatialViewRoot(root: Entity) {
        if (spatialViewRoot === root) spatialViewRoot = null
    }

    fun startSpatialPerception() {
        initialize()
        timeoutJob?.cancel()
        anchorLoadJob?.cancel()
        mutableMainPlane.value = null
        dispatchStatus(PlaneScanStatus.Scanning)
        if (!sensing) {
            synchronized(anchorLock) {
                planeAnchors.clear()
                meshAnchors.clear()
            }
            mutableObstacles.value = emptyList()
            lastPlaneDiagnostic = ""
            sensing = true
            planeSubscription = PlaneTrackingManager.subscribeAnchorUpdate(
                AnchorUpdateSubscriber { update -> handlePlaneUpdate(update) },
            )
            meshSubscription = MeshTrackingManager.subscribeAnchorUpdate(
                AnchorUpdateSubscriber { update -> handleMeshUpdate(update) },
            )
            PlaneTrackingManager.start()
            MeshTrackingManager.start()
            Log.i(TAG, "Sense providers started in gameplay Stage")
        }
        anchorLoadJob = managerScope.launch { preloadAnchorsWhenTrackingRuns() }
        timeoutJob = managerScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (mutableMainPlane.value == null) publishFallback("未在 30 秒内识别到合格地面")
        }
    }

    fun stopSpatialPerception() {
        if (!sensing) return
        sensing = false
        timeoutJob?.cancel()
        anchorLoadJob?.cancel()
        anchorLoadJob = null
        planeSubscription?.cancel()
        meshSubscription?.cancel()
        planeSubscription = null
        meshSubscription = null
        PlaneTrackingManager.stop()
        MeshTrackingManager.stop()
    }

    fun useFallbackNow(reason: String = "空间感知不可用"): Plane {
        val fallback = fallbackGroundPlane()
        publishFallback(reason, fallback)
        return fallback
    }

    fun getMainDesktopPlane(): Plane? = mutableMainPlane.value

    fun getDesktopHeight(): Float =
        getGroundHeight()

    fun getMainGroundPlane(): Plane? = mutableMainPlane.value

    fun getGroundHeight(): Float =
        mutableMainPlane.value?.center?.y ?: fallbackGroundPlane().center.y

    fun isPositionInObstacle(x: Float, z: Float): Boolean =
        mutableObstacles.value.any { it.contains(x, z) }

    fun addScanStatusCallback(callback: ScanStatusCallback) {
        callbacks += callback
        callback.onStatusChanged(mutableScanStatus.value)
    }

    fun removeScanStatusCallback(callback: ScanStatusCallback) {
        callbacks -= callback
    }

    override fun destroy() {
        stopSpatialPerception()
        synchronized(anchorLock) {
            planeAnchors.clear()
            meshAnchors.clear()
        }
        callbacks.clear()
        mutableMainPlane.value = null
        mutableObstacles.value = emptyList()
        mutableScanStatus.value = PlaneScanStatus.Idle
        spatialViewRoot = null
        lastSelectionViewY = Float.NaN
        lastPlaneDiagnostic = ""
        initialized = false
        cancelScope()
    }

    private fun handlePlaneUpdate(update: AnchorUpdate<PlaneAnchor>) {
        synchronized(anchorLock) {
            when (update.event) {
                AnchorUpdate.Event.REMOVED -> planeAnchors.remove(update.anchor.anchorUUID)
                else -> planeAnchors[update.anchor.anchorUUID] = update.anchor
            }
        }
        managerScope.launch { selectAndPublishGround() }
    }

    private fun handleMeshUpdate(update: AnchorUpdate<MeshAnchor>) {
        synchronized(anchorLock) {
            when (update.event) {
                AnchorUpdate.Event.REMOVED -> meshAnchors.remove(update.anchor.anchorUUID)
                else -> meshAnchors[update.anchor.anchorUUID] = update.anchor
            }
        }
        managerScope.launch { publishObstacles() }
    }

    /**
     * PICO Sense returns an empty array from loadAllAnchors while its manager is
     * not RUNNING. start() is asynchronous, so loading immediately after start
     * can lose the initial room-capture snapshot and leave only future updates.
     */
    private suspend fun preloadAnchorsWhenTrackingRuns() {
        val planeReady = awaitTrackingState("plane") {
            PlaneTrackingManager.state == TrackingState.RUNNING
        }
        if (!planeReady || !sensing) {
            Log.w(TAG, "Plane provider did not reach RUNNING; state=${PlaneTrackingManager.state}")
            return
        }

        for (attempt in 1..EMPTY_ANCHOR_RETRY_COUNT) {
            if (!sensing) return
            val loaded = runCatching { PlaneTrackingManager.loadAllAnchors() }
                .onFailure { Log.w(TAG, "Unable to preload plane anchors", it) }
                .getOrDefault(emptyArray())
            synchronized(anchorLock) {
                loaded.forEach { planeAnchors[it.anchorUUID] = it }
            }
            Log.i(
                TAG,
                "Plane snapshot attempt=$attempt state=${PlaneTrackingManager.state} anchors=${loaded.size}",
            )
            selectAndPublishGround()
            if (loaded.isNotEmpty()) break
            delay(EMPTY_ANCHOR_RETRY_MS)
        }

        val meshReady = awaitTrackingState("mesh") {
            MeshTrackingManager.state == TrackingState.RUNNING
        }
        if (meshReady && sensing) {
            val loaded = runCatching { MeshTrackingManager.loadAllAnchors() }
                .onFailure { Log.w(TAG, "Unable to preload mesh anchors", it) }
                .getOrDefault(emptyArray())
            synchronized(anchorLock) {
                loaded.forEach { meshAnchors[it.anchorUUID] = it }
            }
            Log.i(TAG, "Mesh snapshot state=${MeshTrackingManager.state} anchors=${loaded.size}")
            publishObstacles()
        } else {
            Log.w(TAG, "Mesh provider did not reach RUNNING; state=${MeshTrackingManager.state}")
        }
    }

    private suspend fun awaitTrackingState(name: String, isRunning: () -> Boolean): Boolean {
        val polls = (TRACKING_START_TIMEOUT_MS / TRACKING_STATE_POLL_MS).toInt()
        repeat(polls) { poll ->
            if (!sensing) return false
            if (isRunning()) {
                Log.i(TAG, "$name provider reached RUNNING after ${poll * TRACKING_STATE_POLL_MS}ms")
                return true
            }
            delay(TRACKING_STATE_POLL_MS)
        }
        return isRunning()
    }

    private suspend fun selectAndPublishGround() {
        val anchors = synchronized(anchorLock) { planeAnchors.values.toList() }
        val root = withContext(Dispatchers.Main.immediate) { spatialViewRoot } ?: return
        val planes = withContext(Dispatchers.Main.immediate) {
            anchors.map { anchor -> toDomainPlane(anchor, root) }
        }
        val expectedGroundY = latestViewPosition.y - ESTIMATED_EYE_HEIGHT_METERS
        val selected = PlaneSelector.selectMainGround(planes, expectedGroundY)
            ?.let(::toHeightOnlyGround)
        logPlaneSelection(planes, expectedGroundY, selected)
        selected ?: return
        withContext(Dispatchers.Main.immediate) {
            mutableMainPlane.value = selected
            dispatchStatus(PlaneScanStatus.Success(selected))
        }
        publishObstacles()
    }

    /**
     * Placement only needs the detected floor height. The captured polygon is
     * intentionally replaced with a stable logical surface around the viewer so
     * boundary noise or incomplete room scans cannot clamp the board elsewhere.
     */
    private fun toHeightOnlyGround(source: Plane): Plane {
        val center = Vector3(latestViewPosition.x, source.center.y, latestViewPosition.z)
        return source.copy(
            center = center,
            normal = Vector3.UP,
            boundary = rectangleBoundary(
                center,
                LOGICAL_GROUND_SIZE_METERS,
                LOGICAL_GROUND_SIZE_METERS,
            ),
            widthMeters = LOGICAL_GROUND_SIZE_METERS,
            depthMeters = LOGICAL_GROUND_SIZE_METERS,
            areaSquareMeters = LOGICAL_GROUND_SIZE_METERS * LOGICAL_GROUND_SIZE_METERS,
            flatnessMeters = 0f,
            semantic = SurfaceSemantic.FLOOR,
        )
    }

    private fun toDomainPlane(anchor: PlaneAnchor, root: Entity): Plane {
        val transform = anchor.transform
        val localPosition = root.convertPositionFrom(transform.position, null)
        val localRotation = root.convertRotationFrom(transform.quaternion, null)
        val localVertices = anchor.vertices.map { localPosition + localRotation.rotateVector(it) }
        val boundary = localVertices.ifEmpty {
            rectangleBoundary(localPosition, anchor.boundingBoxSize.x, anchor.boundingBoxSize.y)
        }
        val meanY = boundary.map { it.y }.average().toFloat()
        val flatness = if (boundary.isEmpty()) 0f else sqrt(
            boundary.sumOf { vertex ->
                val delta = (vertex.y - meanY).toDouble()
                delta * delta
            }.div(boundary.size),
        ).toFloat()
        val normal = if (anchor.planeOrientation == PlaneOrientation.HORIZONTAL_UPWARD) {
            Vector3.UP
        } else {
            localRotation.rotateVector(Vector3.UP).normalize()
        }
        return Plane(
            id = anchor.anchorUUID,
            // PlaneAnchor.transform.position is the SDK's localized anchor
            // height. Boundary vertices are useful diagnostics, but averaging
            // their Y values here allowed noisy/incomplete polygons to move the
            // entire board above or below the physical floor.
            center = Vector3(localPosition.x, localPosition.y, localPosition.z),
            normal = normal,
            boundary = boundary,
            widthMeters = anchor.boundingBoxSize.x,
            depthMeters = anchor.boundingBoxSize.y,
            areaSquareMeters = anchor.boundingBoxSize.x * anchor.boundingBoxSize.y,
            flatnessMeters = flatness,
            semantic = when (anchor.semantics) {
                SemanticLabelType.FLOOR -> SurfaceSemantic.FLOOR
                SemanticLabelType.UNKNOWN -> SurfaceSemantic.UNKNOWN
                else -> SurfaceSemantic.OTHER
            },
        )
    }

    private suspend fun publishObstacles() {
        val ground = mutableMainPlane.value ?: return
        val anchors = synchronized(anchorLock) { meshAnchors.values.toList() }
        val root = withContext(Dispatchers.Main.immediate) { spatialViewRoot } ?: return
        val candidates = withContext(Dispatchers.Main.immediate) {
            anchors.mapNotNull { anchor ->
                val center = root.convertPositionFrom(anchor.transform.position, null)
                val size = anchor.boundingBoxSize
                val bottom = center.y - size.y / 2f
                val top = center.y + size.y / 2f
                if (top < ground.center.y + 0.01f || bottom > ground.center.y + 0.5f) null
                else ObstacleBox(center, size)
            }
        }
        withContext(Dispatchers.Main.immediate) { mutableObstacles.value = candidates }
    }

    private fun publishFallback(reason: String, fallback: Plane = fallbackGroundPlane()) {
        managerScope.launch(Dispatchers.Main.immediate) {
            mutableMainPlane.value = fallback
            dispatchStatus(PlaneScanStatus.Failed(reason, fallback))
            EventBus.tryEmit(UserMessage("$reason；棋盘保持隐藏，可手动选择地面兜底"))
        }
    }

    private fun fallbackGroundPlane(): Plane {
        val center = Vector3(
            latestViewPosition.x,
            latestViewPosition.y - ESTIMATED_EYE_HEIGHT_METERS,
            latestViewPosition.z,
        )
        return Plane(
            id = UUID.nameUUIDFromBytes("desktop-fortress-ground-fallback".toByteArray()),
            center = center,
            normal = Vector3.UP,
            boundary = rectangleBoundary(center, LOGICAL_GROUND_SIZE_METERS, LOGICAL_GROUND_SIZE_METERS),
            widthMeters = LOGICAL_GROUND_SIZE_METERS,
            depthMeters = LOGICAL_GROUND_SIZE_METERS,
            areaSquareMeters = LOGICAL_GROUND_SIZE_METERS * LOGICAL_GROUND_SIZE_METERS,
            flatnessMeters = 0f,
            isFallback = true,
            semantic = SurfaceSemantic.FLOOR,
        )
    }

    private fun logPlaneSelection(planes: List<Plane>, expectedGroundY: Float, selected: Plane?) {
        val summary = buildString {
            append("anchors=").append(planes.size)
            append(" expectedY=").append("%.3f".format(expectedGroundY))
            append(" selected=").append(selected?.id ?: "none")
            planes.sortedBy { it.center.y }.take(12).forEach { plane ->
                append(" | ").append(plane.id.toString().take(8))
                append(':').append(plane.semantic)
                append(" y=").append("%.3f".format(plane.center.y))
                append(" area=").append("%.2f".format(plane.areaSquareMeters))
                append(" flat=").append("%.4f".format(plane.flatnessMeters))
            }
        }
        if (summary != lastPlaneDiagnostic) {
            lastPlaneDiagnostic = summary
            Log.i(TAG, "Ground selection: $summary")
        }
    }

    private fun rectangleBoundary(center: Vector3, width: Float, depth: Float) = listOf(
        Vector3(center.x - width / 2f, center.y, center.z - depth / 2f),
        Vector3(center.x + width / 2f, center.y, center.z - depth / 2f),
        Vector3(center.x + width / 2f, center.y, center.z + depth / 2f),
        Vector3(center.x - width / 2f, center.y, center.z + depth / 2f),
    )

    private fun dispatchStatus(status: PlaneScanStatus) {
        mutableScanStatus.value = status
        callbacks.forEach { callback -> runCatching { callback.onStatusChanged(status) } }
    }
}
