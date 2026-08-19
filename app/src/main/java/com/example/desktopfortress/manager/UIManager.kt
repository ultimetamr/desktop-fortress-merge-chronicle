package com.example.desktopfortress.manager

import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.HeadPose
import com.example.desktopfortress.domain.model.PanelGroup
import com.example.desktopfortress.domain.model.PanelMaterialSpec
import com.example.desktopfortress.domain.model.PanelTransform
import com.example.desktopfortress.domain.model.UiPanel
import com.example.desktopfortress.domain.model.UiRuntimeState
import com.example.desktopfortress.domain.model.UiVisibilityState
import com.example.desktopfortress.domain.model.SpatialTrackingState
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of every spatial panel's visibility, group policy and pose.
 * Compose owns panel content; this manager owns only durable UI state and Stage transforms.
 */
object UIManager : BaseManager() {
    const val MODAL_DISTANCE_METERS = 1f
    const val HUD_DISTANCE_METERS = 1.2f
    const val CALIBRATION_DISTANCE_METERS = HUD_DISTANCE_METERS
    const val CALIBRATION_PANEL_WIDTH_METERS = .8f
    const val CALIBRATION_PANEL_HEIGHT_METERS = .6f
    const val MODAL_RECENTER_SECONDS = .3f
    const val HUD_LAG_SECONDS = .1f
    const val RECENTER_ANGLE_DEGREES = 90f
    const val HUD_RECENTER_ANGLE_DEGREES = 90f
    const val CALIBRATION_RECENTER_ANGLE_DEGREES = HUD_RECENTER_ANGLE_DEGREES
    const val RECENTER_DISTANCE_DEVIATION_METERS = .8f
    const val GAZE_CONFIRM_MILLIS = 2_000L

    val materialSpec = PanelMaterialSpec()

    private val DEFAULT_HEAD_POSE = HeadPose(Vector3(0f, 1.6f, 0f), LOCAL_STAGE_VIEW_FORWARD)

    private var headPoseTracker: HeadPoseTracker? = null
    private var trackingJob: Job? = null
    @Volatile private var spatialViewRoot: Entity? = null
    private var initialized = false
    private val stateLock = Any()
    private var recenterStart: PanelTransform? = null
    private var recenterTarget: PanelTransform? = null
    private var recenterElapsed = 0f
    private var hudAnchorForward: Vector3? = null
    private var calibrationAnchorForward: Vector3? = null

    private val mutableHeadPose = MutableStateFlow(DEFAULT_HEAD_POSE)
    val headPose: StateFlow<HeadPose> = mutableHeadPose.asStateFlow()
    private val mutableRawHeadPose = MutableStateFlow(DEFAULT_HEAD_POSE)
    private val mutableTrackingState = MutableStateFlow(SpatialTrackingState.IDLE)
    val trackingState: StateFlow<SpatialTrackingState> = mutableTrackingState.asStateFlow()
    private var secondsSinceTrackingSample = 0f

    private val mutableState = MutableStateFlow(UiRuntimeState())
    val state: StateFlow<UiRuntimeState> = mutableState.asStateFlow()

    private val mutableVisibility = MutableStateFlow(UiVisibilityState())
    val visibility: StateFlow<UiVisibilityState> = mutableVisibility.asStateFlow()

    override fun initialize() {
        if (initialized) return
        recreateScopeIfNeeded()
        initialized = true
    }

    fun startHeadTracking() {
        initialize()
        if (trackingJob?.isActive == true) return
        val tracker = headPoseTracker ?: PicoHeadPoseTracker().also { headPoseTracker = it }
        trackingJob = managerScope.launch {
            tracker.collect { pose ->
                mutableRawHeadPose.value = pose
                if (spatialViewRoot == null) {
                    mutableHeadPose.value = pose
                    SpatialManager.updateViewPosition(pose.position)
                }
                secondsSinceTrackingSample = 0f
                mutableTrackingState.value = SpatialTrackingState.TRACKING
            }
        }
    }

    fun bindSpatialViewRoot(root: Entity) {
        spatialViewRoot = root
    }

    fun unbindSpatialViewRoot(root: Entity) {
        if (spatialViewRoot === root) spatialViewRoot = null
    }

    fun stopHeadTracking() {
        trackingJob?.cancel()
        trackingJob = null
        headPoseTracker?.stop()
        headPoseTracker = null
        secondsSinceTrackingSample = 0f
        mutableTrackingState.value = SpatialTrackingState.IDLE
    }

    /** Opens one panel. Opening an A panel atomically replaces the previous A panel. */
    fun open(panel: UiPanel) = synchronized(stateLock) {
        when (panel.group) {
            PanelGroup.A_MODAL -> {
                recenterStart = null
                recenterTarget = null
                recenterElapsed = 0f
                if (panel == UiPanel.CALIBRATION_GUIDE) {
                    calibrationAnchorForward = normalizedFullForward(mutableHeadPose.value)
                    setState(mutableState.value.copy(
                        activeModal = panel,
                        modalTransform = null,
                        calibrationTransform = calibrationTargetTransform(
                            mutableHeadPose.value,
                            calibrationAnchorForward!!,
                        ),
                    ))
                } else {
                    calibrationAnchorForward = null
                    setState(mutableState.value.copy(
                        activeModal = panel,
                        modalTransform = obstacleAvoidedTransform(mutableHeadPose.value),
                        calibrationTransform = null,
                    ))
                }
            }
            PanelGroup.B_HUD -> {
                if (mutableState.value.visibleHuds.isEmpty()) {
                    hudAnchorForward = mutableHeadPose.value.horizontalForward
                }
                setState(mutableState.value.copy(
                    visibleHuds = mutableState.value.visibleHuds + panel,
                ))
            }
        }
    }

    fun close(panel: UiPanel) = synchronized(stateLock) {
        when (panel.group) {
            PanelGroup.A_MODAL -> if (mutableState.value.activeModal == panel) {
                if (panel == UiPanel.CALIBRATION_GUIDE) calibrationAnchorForward = null
                setState(mutableState.value.copy(
                    activeModal = null,
                    modalTransform = null,
                    calibrationTransform = null,
                ))
            }
            PanelGroup.B_HUD -> {
                val visible = mutableState.value.visibleHuds - panel
                if (visible.isEmpty()) hudAnchorForward = null
                setState(mutableState.value.copy(visibleHuds = visible))
            }
        }
    }

    fun closeAll() = synchronized(stateLock) {
        recenterStart = null
        recenterTarget = null
        hudAnchorForward = null
        calibrationAnchorForward = null
        setState(UiRuntimeState(activeModal = null))
    }

    fun syncGameState(gameState: GameState) {
        when (gameState) {
            GameState.MAIN_MENU -> showOnly(UiPanel.MAIN_MENU)
            GameState.LEVEL_SELECT -> showOnly(UiPanel.LEVEL_SELECT)
            GameState.CALIBRATING -> showOnly(UiPanel.CALIBRATION_GUIDE)
            GameState.SETTLE -> showOnly(UiPanel.SETTLEMENT)
            GameState.PAUSED -> showOnly(UiPanel.PAUSE)
            GameState.PREPARE, GameState.FIGHTING, GameState.WAVE_PAUSE -> synchronized(stateLock) {
                val pose = mutableHeadPose.value
                val current = mutableState.value
                if (current.visibleHuds.isEmpty() || hudAnchorForward == null) {
                    hudAnchorForward = pose.horizontalForward
                }
                setState(current.copy(
                    activeModal = null,
                    modalTransform = null,
                    calibrationTransform = null,
                    visibleHuds = setOf(UiPanel.COMBAT_TOP_HUD, UiPanel.BOTTOM_ACTION_HUD),
                    topHudTransform = current.topHudTransform
                        ?: targetTransform(pose, HUD_DISTANCE_METERS, TOP_HUD_OFFSET_METERS),
                    bottomHudTransform = current.bottomHudTransform
                        ?: targetTransform(pose, HUD_DISTANCE_METERS, BOTTOM_HUD_OFFSET_METERS),
                ))
                calibrationAnchorForward = null
            }
        }
    }

    internal fun setHeadPoseForTesting(pose: HeadPose) {
        mutableRawHeadPose.value = pose
        mutableHeadPose.value = pose
    }

    private fun showOnly(panel: UiPanel) = synchronized(stateLock) {
        recenterStart = null
        recenterTarget = null
        hudAnchorForward = null
        if (panel == UiPanel.CALIBRATION_GUIDE) {
            calibrationAnchorForward = normalizedFullForward(mutableHeadPose.value)
            setState(mutableState.value.copy(
                activeModal = panel,
                modalTransform = null,
                calibrationTransform = calibrationTargetTransform(
                    mutableHeadPose.value,
                    calibrationAnchorForward!!,
                ),
                visibleHuds = emptySet(),
            ))
        } else {
            calibrationAnchorForward = null
            setState(mutableState.value.copy(
                activeModal = panel,
                modalTransform = obstacleAvoidedTransform(mutableHeadPose.value),
                calibrationTransform = null,
                visibleHuds = emptySet(),
            ))
        }
    }

    /** Calibration uses the HUD's 0.1 s filter and 90-degree reset threshold on yaw and pitch. */
    fun update(deltaSeconds: Float) = synchronized(stateLock) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f) return
        localizeHeadPose()
        if (mutableTrackingState.value == SpatialTrackingState.TRACKING) {
            secondsSinceTrackingSample += deltaSeconds
            if (secondsSinceTrackingSample >= TRACKING_LOST_TIMEOUT_SECONDS) {
                mutableTrackingState.value = SpatialTrackingState.LOST
            }
        }
        val pose = mutableHeadPose.value
        var current = mutableState.value
        val alpha = (1f - exp(-deltaSeconds / HUD_LAG_SECONDS)).coerceIn(0f, 1f)
        if (current.activeModal == UiPanel.CALIBRATION_GUIDE) {
            recenterStart = null
            recenterTarget = null
            recenterElapsed = 0f
            var anchorForward = calibrationAnchorForward ?: normalizedFullForward(pose)
            val exceedsYawThreshold = current.calibrationTransform?.let {
                abs(horizontalYawDeltaDegrees(it, pose)) > CALIBRATION_RECENTER_ANGLE_DEGREES
            } ?: false
            val exceedsPitchThreshold = current.calibrationTransform?.let {
                verticalAngleDegrees(it, pose) > CALIBRATION_RECENTER_ANGLE_DEGREES
            } ?: false
            val exceedsAngleThreshold = exceedsYawThreshold || exceedsPitchThreshold
            if (exceedsAngleThreshold) {
                anchorForward = normalizedFullForward(pose)
                calibrationAnchorForward = anchorForward
            }
            val target = calibrationTargetTransform(pose, anchorForward)
            current = current.copy(
                calibrationTransform = followHud(
                    current.calibrationTransform,
                    target,
                    alpha,
                    exceedsAngleThreshold,
                ),
                modalTransform = null,
            )
        } else {
            current.modalTransform?.let { modal ->
                if (recenterTarget == null && needsRecenter(modal, pose)) {
                    recenterStart = modal
                    recenterTarget = obstacleAvoidedTransform(pose, MODAL_DISTANCE_METERS)
                    recenterElapsed = 0f
                }
                val target = recenterTarget
                val start = recenterStart
                if (target != null && start != null) {
                    recenterElapsed += deltaSeconds
                    val t = min(1f, recenterElapsed / MODAL_RECENTER_SECONDS)
                    current = current.copy(modalTransform = interpolate(start, target, smoothStep(t)))
                    if (t >= 1f) {
                        recenterStart = null
                        recenterTarget = null
                        recenterElapsed = 0f
                    }
                }
            }
        }

        if (current.visibleHuds.isNotEmpty()) {
            var anchorForward = hudAnchorForward ?: pose.horizontalForward
            val angleReference = when {
                UiPanel.COMBAT_TOP_HUD in current.visibleHuds -> current.topHudTransform
                UiPanel.BOTTOM_ACTION_HUD in current.visibleHuds -> current.bottomHudTransform
                else -> null
            }
            val exceedsYawThreshold = angleReference?.let {
                abs(horizontalYawDeltaDegrees(it, pose)) > HUD_RECENTER_ANGLE_DEGREES
            } ?: false
            if (exceedsYawThreshold) {
                anchorForward = pose.horizontalForward
                hudAnchorForward = anchorForward
            }
            val topTarget = targetTransform(
                pose,
                HUD_DISTANCE_METERS,
                TOP_HUD_OFFSET_METERS,
                anchorForward,
            )
            val bottomTarget = targetTransform(
                pose,
                HUD_DISTANCE_METERS,
                BOTTOM_HUD_OFFSET_METERS,
                anchorForward,
            )
            current = current.copy(
                topHudTransform = followHud(current.topHudTransform, topTarget, alpha, exceedsYawThreshold),
                bottomHudTransform = followHud(
                    current.bottomHudTransform,
                    bottomTarget,
                    alpha,
                    exceedsYawThreshold,
                ),
            )
        }
        setState(current)
    }

    fun needsRecenter(transform: PanelTransform, pose: HeadPose): Boolean {
        val delta = Vector3(
            transform.position.x - pose.position.x,
            0f,
            transform.position.z - pose.position.z,
        )
        val distance = delta.length()
        if (abs(distance - MODAL_DISTANCE_METERS) > RECENTER_DISTANCE_DEVIATION_METERS) return true
        if (distance <= .0001f) return true
        return horizontalAngleDegrees(transform, pose) > RECENTER_ANGLE_DEGREES
    }

    fun obstacleAvoidedTransform(
        pose: HeadPose,
        preferredDistance: Float = MODAL_DISTANCE_METERS,
        obstacleQuery: (Float, Float) -> Boolean = SpatialManager::isPositionInObstacle,
    ): PanelTransform {
        var distance = preferredDistance
        while (distance >= MIN_MODAL_DISTANCE_METERS) {
            val candidate = targetTransform(pose, distance, 0f)
            if (!panelFootprintBlocked(candidate, obstacleQuery)) return candidate
            distance -= OBSTACLE_BACKOFF_STEP_METERS
        }
        return targetTransform(pose, MIN_MODAL_DISTANCE_METERS, 0f)
    }

    override fun destroy() {
        stopHeadTracking()
        mutableHeadPose.value = DEFAULT_HEAD_POSE
        mutableRawHeadPose.value = DEFAULT_HEAD_POSE
        spatialViewRoot = null
        hudAnchorForward = null
        calibrationAnchorForward = null
        mutableTrackingState.value = SpatialTrackingState.IDLE
        setState(UiRuntimeState())
        initialized = false
        cancelScope()
    }

    private fun targetTransform(
        pose: HeadPose,
        distance: Float,
        verticalOffset: Float,
        horizontalDirection: Vector3 = pose.horizontalForward,
    ): PanelTransform {
        val forward = horizontalDirection.takeIf { it.length() > .0001f }?.normalize()
            ?: pose.horizontalForward
        val position = Vector3(
            pose.position.x + forward.x * distance,
            pose.position.y + verticalOffset,
            pose.position.z + forward.z * distance,
        )
        return PanelTransform(position, yawDegrees(forward))
    }

    private fun calibrationTargetTransform(pose: HeadPose, forwardDirection: Vector3): PanelTransform {
        val forward = forwardDirection.takeIf { it.length() > .0001f }?.normalize()
            ?: normalizedFullForward(pose)
        val horizontal = Vector3(forward.x, 0f, forward.z).takeIf { it.length() > .0001f }
            ?.normalize() ?: pose.horizontalForward
        val position = pose.position + forward * CALIBRATION_DISTANCE_METERS
        val pitch = asin(forward.y.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
        return PanelTransform(
            position = position,
            yawDegrees = yawDegrees(horizontal),
            pitchDegrees = pitch,
        )
    }

    private fun panelFootprintBlocked(
        transform: PanelTransform,
        obstacleQuery: (Float, Float) -> Boolean,
    ): Boolean {
        val yaw = transform.yawDegrees * PI.toFloat() / 180f
        val rightX = cos(yaw)
        val rightZ = -sin(yaw)
        return FOOTPRINT_SAMPLES.any { offset ->
            obstacleQuery(
                transform.position.x + rightX * offset,
                transform.position.z + rightZ * offset,
            )
        }
    }

    private fun interpolate(from: PanelTransform, to: PanelTransform, t: Float): PanelTransform {
        val yawDelta = shortestAngleDegrees(from.yawDegrees, to.yawDegrees)
        return PanelTransform(
            position = Vector3.lerp(from.position, to.position, t),
            yawDegrees = normalizeDegrees(from.yawDegrees + yawDelta * t),
            pitchDegrees = from.pitchDegrees + (to.pitchDegrees - from.pitchDegrees) * t,
            visible = from.visible || to.visible,
        )
    }

    /**
     * Body translation keeps the shared 0.1 s lag while yaw stays anchored.
     * Once either left/right yaw boundary is exceeded, both HUDs reset directly
     * to the new current-front target.
     */
    private fun followHud(
        current: PanelTransform?,
        target: PanelTransform,
        alpha: Float,
        resetToFront: Boolean,
    ): PanelTransform {
        val start = current ?: return target
        return if (resetToFront) target else interpolate(start, target, alpha)
    }

    internal fun horizontalAngleDegrees(transform: PanelTransform, pose: HeadPose): Float {
        return abs(horizontalYawDeltaDegrees(transform, pose))
    }

    internal fun horizontalYawDeltaDegrees(transform: PanelTransform, pose: HeadPose): Float {
        val delta = Vector3(
            transform.position.x - pose.position.x,
            0f,
            transform.position.z - pose.position.z,
        )
        if (delta.length() <= .0001f) return 180f
        val forward = Vector3(
            pose.horizontalForward.x,
            0f,
            pose.horizontalForward.z,
        )
        if (forward.length() <= .0001f) return 180f
        val direction = delta.normalize()
        val normalizedForward = forward.normalize()
        val dot = Vector3.dot(normalizedForward, direction).coerceIn(-1f, 1f)
        val crossY = normalizedForward.z * direction.x - normalizedForward.x * direction.z
        return atan2(crossY, dot) * 180f / PI.toFloat()
    }

    internal fun verticalAngleDegrees(transform: PanelTransform, pose: HeadPose): Float {
        val panelDirection = transform.position - pose.position
        if (panelDirection.length() <= .0001f) return 180f
        val normalizedPanel = panelDirection.normalize()
        val panelHorizontal = Vector3(normalizedPanel.x, 0f, normalizedPanel.z)
        if (panelHorizontal.length() <= .0001f) return 180f
        val horizontalBasis = panelHorizontal.normalize()
        val gaze = normalizedFullForward(pose)
        val panelPitch = atan2(normalizedPanel.y, Vector3.dot(normalizedPanel, horizontalBasis))
        val gazePitch = atan2(gaze.y, Vector3.dot(gaze, horizontalBasis))
        return abs(shortestAngleDegrees(
            panelPitch * 180f / PI.toFloat(),
            gazePitch * 180f / PI.toFloat(),
        ))
    }

    private fun normalizedFullForward(pose: HeadPose): Vector3 =
        pose.forward.takeIf { it.length() > .0001f }?.normalize() ?: pose.horizontalForward

    private fun yawDegrees(forward: Vector3): Float =
        atan2(-forward.x, -forward.z) * 180f / PI.toFloat()

    private fun shortestAngleDegrees(from: Float, to: Float): Float {
        var delta = normalizeDegrees(to) - normalizeDegrees(from)
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    private fun setState(newState: UiRuntimeState) {
        mutableState.value = newState
        val visibility = UiVisibilityState(newState.activeModal, newState.visibleHuds)
        if (mutableVisibility.value != visibility) mutableVisibility.value = visibility
    }

    /** Converts HMD tracking-global pose into the same root-local space as Sense anchors. */
    private fun localizeHeadPose() {
        val root = spatialViewRoot ?: return
        val raw = mutableRawHeadPose.value
        val localPosition = root.convertPositionFrom(raw.position, null)
        val localForwardPoint = root.convertPositionFrom(raw.position + raw.forward, null)
        val fullForward = (localForwardPoint - localPosition).let { converted ->
            if (converted.length() > .0001f) converted.normalize() else LOCAL_STAGE_VIEW_FORWARD
        }
        val horizontal = Vector3(fullForward.x, 0f, fullForward.z).let { converted ->
            if (converted.length() > .0001f) converted.normalize() else LOCAL_STAGE_VIEW_FORWARD
        }
        mutableHeadPose.value = HeadPose(localPosition, horizontal, fullForward)
        SpatialManager.updateViewPosition(localPosition)
    }

    private const val TOP_HUD_OFFSET_METERS = .34f
    private const val BOTTOM_HUD_OFFSET_METERS = -.28f
    private const val MIN_MODAL_DISTANCE_METERS = .45f
    private const val OBSTACLE_BACKOFF_STEP_METERS = .1f
    private const val TRACKING_LOST_TIMEOUT_SECONDS = 1.5f
    private val FOOTPRINT_SAMPLES = floatArrayOf(-.34f, 0f, .34f)
}
