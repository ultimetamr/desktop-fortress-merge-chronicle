package com.example.desktopfortress.ui.game

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.PanelTransform
import com.example.desktopfortress.domain.model.UiPanel
import com.example.desktopfortress.domain.model.BoardPreviewMode
import com.example.desktopfortress.effect.EffectManager
import com.example.desktopfortress.manager.LevelManager
import com.example.desktopfortress.manager.MonsterManager
import com.example.desktopfortress.manager.TowerManager
import com.example.desktopfortress.manager.UIManager
import com.example.desktopfortress.manager.GameManager
import com.example.desktopfortress.manager.BoardManager
import com.example.desktopfortress.manager.SpatialManager
import com.example.desktopfortress.utils.CollisionDebug
import com.example.desktopfortress.utils.GroundingDebug
import com.pico.spatial.core.ecs.AttachmentPanelComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.panelSize
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialScaleGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager

private const val CALIBRATION_PANEL = "calibration_follow_panel"
private const val MODAL_PANEL = "stage_modal_panel"
private const val TOP_HUD_PANEL = "combat_top_hud"
private const val BOTTOM_HUD_PANEL = "bottom_action_hud"
private const val TOWER_INPUT_TAG = "TowerPlacementInput"

@Composable
fun GameStageScreen(gameViewModel: GameViewModel = viewModel()) {
    val state by gameViewModel.uiState.collectAsStateWithLifecycle()
    val board by BoardManager.board.collectAsStateWithLifecycle()
    val debugGrounding by GroundingDebug.enabled.collectAsStateWithLifecycle()
    val debugCollision by CollisionDebug.enabled.collectAsStateWithLifecycle()
    val towers by TowerManager.towers.collectAsStateWithLifecycle()
    val projectiles by TowerManager.projectiles.collectAsStateWithLifecycle()
    val monsters by MonsterManager.monsters.collectAsStateWithLifecycle()
    val dragPreview by TowerManager.dragPreview.collectAsStateWithLifecycle()
    val inventory by TowerManager.inventory.collectAsStateWithLifecycle()
    val effects by EffectManager.effectSnapshots.collectAsStateWithLifecycle()
    val previewMode by BoardManager.previewMode.collectAsStateWithLifecycle()
    val placementSurfaceReady by BoardManager.placementSurfaceReady.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spatialContainerState = LocalSpatialContainerStateManager.current
    val stageOnstage by spatialContainerState.isOnstage
    val converter = LocalPhysicalLengthConverter.current
    val metersFromPixels: (Float) -> Float = { pixels ->
        converter.dpToLength(with(converter) { pixels.toDp() }, LengthUnit.Meters)
    }
    val calibrationPanelSize = panelSize(
        UIManager.CALIBRATION_PANEL_WIDTH_METERS,
        UIManager.CALIBRATION_PANEL_HEIGHT_METERS,
        LengthUnit.Meters,
    )
    val scene = remember { BoardScene() }
    val spatialViewRoot = remember { Entity() }
    val panelScene = remember { StagePanelScene() }

    DisposableEffect(scene, panelScene) {
        UIManager.startHeadTracking()
        onDispose {
            UIManager.stopHeadTracking()
            SpatialManager.unbindSpatialViewRoot(spatialViewRoot)
            UIManager.unbindSpatialViewRoot(spatialViewRoot)
            panelScene.destroy()
            scene.destroy()
            runCatching { spatialViewRoot.destroy() }
        }
    }
    LaunchedEffect(state.gameState) { UIManager.syncGameState(state.gameState) }
    LaunchedEffect(stageOnstage) {
        GameManager.onGameplayStageOnstageChanged(stageOnstage)
    }
    LaunchedEffect(Unit) {
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val delta = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
            previous = now
            GameManager.update(delta)
            // UIManager transforms are intentionally not collected by Compose.
            // Apply them directly every frame so calibration visibility cannot be
            // stranded at the initial zero scale while waiting for recomposition.
            panelScene.update(UIManager.state.value)
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(board.isLocked, previewMode) {
                if (!board.isLocked && previewMode == BoardPreviewMode.WORLD_LOCKED) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = {},
                        onDragEnd = {},
                        onDragCancel = {},
                        onDrag = { gesture ->
                            gameViewModel.onEvent(
                                GameUiEvent.DragBoard(
                                    metersFromPixels(gesture.dragAmount.x),
                                    metersFromPixels(gesture.dragAmount.y),
                                ),
                            )
                        },
                    )
                }
            }
            .pointerInput(board.isLocked, previewMode) {
                if (!board.isLocked && previewMode == BoardPreviewMode.WORLD_LOCKED) {
                    detectSpatialScaleGesture(context) { gesture ->
                        gameViewModel.onEvent(GameUiEvent.ScaleBoard(gesture.scaleValue))
                    }
                }
            }
            .pointerInput(board.isLocked, previewMode) {
                if (!board.isLocked && previewMode == BoardPreviewMode.WORLD_LOCKED) {
                    detectSpatialTapGesture(context, targetedToEntity = TargetEntity.any()) { tap ->
                        gameViewModel.onEvent(GameUiEvent.PreviewAtWorld(tap.position.x, tap.position.y, tap.position.z))
                    }
                }
            }
            .pointerInput(state.gameState) {
                if (state.gameState == GameState.PREPARE || state.gameState == GameState.WAVE_PAUSE) {
                    detectSpatialTapGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                    ) { tap ->
                        val target = tap.targetEntity
                        val slotIndex = target?.let(scene::slotIndexForEntity)
                        val towerId = target?.let(scene::towerIdForEntity)
                        val coordinate = target?.let(scene::cellCoordinateForEntity)
                            ?: towerId?.let { id ->
                                TowerManager.towers.value.firstOrNull { it.id == id }?.coordinate
                            }
                        Log.i(
                            TOWER_INPUT_TAG,
                            "stage tap mapped slot=$slotIndex tower=$towerId cell=$coordinate " +
                                "selection=${TowerManager.dragPreview.value != null}",
                        )

                        when {
                            TowerManager.dragPreview.value != null && coordinate != null ->
                                TowerManager.confirmSelectionAtCell(coordinate)
                            slotIndex != null -> TowerManager.selectInventorySlot(slotIndex)
                            towerId != null -> TowerManager.beginExisting(towerId)
                        }
                    }
                }
            },
        attachments = {
            AttachmentPanel(
                id = CALIBRATION_PANEL,
                size = calibrationPanelSize,
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) { CalibrationFollowPanel(gameViewModel) }
            AttachmentPanel(
                id = MODAL_PANEL,
                size = IntSize(860, 460),
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) { StageModalPanel(gameViewModel) }
            AttachmentPanel(
                id = TOP_HUD_PANEL,
                size = IntSize(960, 132),
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) { CombatTopHud(gameViewModel) }
            AttachmentPanel(
                id = BOTTOM_HUD_PANEL,
                size = IntSize(1800, 340),
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) { BottomActionHud(gameViewModel) }
        },
        initial = { content, attachments ->
            spatialViewRoot.addChild(scene.create(board, placementSurfaceReady))
            content.addEntity(spatialViewRoot)
            SpatialManager.bindSpatialViewRoot(spatialViewRoot)
            UIManager.bindSpatialViewRoot(spatialViewRoot)
            panelScene.bind(
                calibration = requireNotNull(attachments.entity(CALIBRATION_PANEL)),
                modal = requireNotNull(attachments.entity(MODAL_PANEL)),
                top = requireNotNull(attachments.entity(TOP_HUD_PANEL)),
                bottom = requireNotNull(attachments.entity(BOTTOM_HUD_PANEL)),
            )
            panelScene.entities.forEach(content::addEntity)
        },
        update = { _, _ ->
            scene.update(
                board,
                towers,
                projectiles,
                monsters,
                inventory,
                dragPreview,
                effects,
                inventoryEditable = state.gameState == GameState.PREPARE || state.gameState == GameState.WAVE_PAUSE,
                boardVisible = placementSurfaceReady || board.isLocked,
                debugGrounding = debugGrounding,
                debugCollision = debugCollision,
            )
            panelScene.update(UIManager.state.value)
        },
    )
}

private class StagePanelScene {
    private lateinit var calibration: Entity
    private lateinit var modal: Entity
    private lateinit var top: Entity
    private lateinit var bottom: Entity
    val entities: List<Entity> get() = listOf(calibration, modal, top, bottom)

    fun bind(calibration: Entity, modal: Entity, top: Entity, bottom: Entity) {
        this.calibration = calibration
        this.modal = modal
        this.top = top
        this.bottom = bottom
        entities.forEach { entity ->
            entity.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO)
        }
    }

    fun update(state: com.example.desktopfortress.domain.model.UiRuntimeState) {
        if (!::modal.isInitialized) return
        applyTransform(
            calibration,
            state.calibrationTransform,
            state.activeModal == UiPanel.CALIBRATION_GUIDE,
        )
        applyTransform(
            modal,
            state.modalTransform,
            state.activeModal in setOf(UiPanel.SETTLEMENT, UiPanel.PAUSE),
        )
        applyTransform(
            top,
            state.topHudTransform,
            UiPanel.COMBAT_TOP_HUD in state.visibleHuds,
        )
        applyTransform(
            bottom,
            state.bottomHudTransform,
            UiPanel.BOTTOM_ACTION_HUD in state.visibleHuds,
        )
    }

    private fun applyTransform(entity: Entity, panel: PanelTransform?, visible: Boolean) {
        val transform = entity.components[TransformComponent::class.java] ?: return
        if (!visible || panel == null) {
            transform.setScaleVector(Vector3.ZERO)
            return
        }
        transform
            .setPosition(panel.position)
            .setEulerAngles(EulerAngles(panel.pitchDegrees, panel.yawDegrees, 0f))
            .setScaleVector(Vector3.ONE)
    }

    fun destroy() {
        if (::modal.isInitialized) entities.forEach { runCatching { it.destroy() } }
    }
}
