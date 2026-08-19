package com.example.desktopfortress.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.desktopfortress.MAIN_WINDOW_ID
import com.example.desktopfortress.domain.model.GameState
import com.example.desktopfortress.domain.model.PlaneScanStatus
import com.example.desktopfortress.domain.model.TowerBalanceTable
import com.example.desktopfortress.domain.model.TowerDragSource
import com.example.desktopfortress.domain.model.TowerInventorySlot
import com.example.desktopfortress.domain.model.TowerType
import com.example.desktopfortress.domain.model.UiPanel
import com.example.desktopfortress.manager.LevelManager
import com.example.desktopfortress.manager.BoardManager
import com.example.desktopfortress.manager.TowerManager
import com.example.desktopfortress.manager.UIManager
import com.example.desktopfortress.manager.GameManager
import com.example.desktopfortress.ui.components.FortressPanelSurface
import com.example.desktopfortress.ui.components.LayeredPanelText
import com.example.desktopfortress.ui.components.SpatialActionButton
import com.example.desktopfortress.utils.GroundingDebug
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.foundation.layout.offset as spatialOffset
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import kotlinx.coroutines.launch

@Composable
fun CalibrationFollowPanel(viewModel: GameViewModel) {
    val ui by UIManager.visibility.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val surfaceReady by BoardManager.placementSurfaceReady.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    if (ui.activeModal != UiPanel.CALIBRATION_GUIDE) return
    FortressPanelSurface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LayeredPanelText(
                "地面棋盘校准",
                style = PicoTheme.typography.titleLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            LayeredPanelText(scanCopy(state.scanStatus), style = PicoTheme.typography.bodyLarge)
            LayeredPanelText(
                if (surfaceReady) {
                    "已按识别到的真实地面高度生成棋盘，位于正前方 2 米；可拖拽微调或缩放至 0.7–1.5 倍。"
                } else {
                    "扫描期间棋盘保持隐藏。请缓慢环视脚下与前方地面，识别成功后才会生成棋盘。"
                },
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
            state.message?.let {
                LayeredPanelText(it, color = PicoTheme.colorScheme.alert, style = PicoTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpatialActionButton("重置到前方 2 米", {
                    viewModel.onEvent(GameUiEvent.ResetBoardPreviewInFront)
                }, enabled = surfaceReady)
                SpatialActionButton("缩小", { viewModel.onEvent(GameUiEvent.ScaleBoard(.9f)) }, enabled = surfaceReady)
                SpatialActionButton("放大", { viewModel.onEvent(GameUiEvent.ScaleBoard(1.1f)) }, enabled = surfaceReady)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpatialActionButton(
                    "确认放置棋盘",
                    viewModel::confirmPlacement,
                    modifier = Modifier.width(220.dp),
                    enabled = surfaceReady,
                )
                SpatialActionButton("使用地面兜底", viewModel::useFallback)
                SpatialActionButton("返回菜单", {
                    viewModel.onEvent(GameUiEvent.ReturnToMenu)
                    scope.launch { closeGameplayStage(navigator) }
                })
            }
        }
    }
}

@Composable
fun StageModalPanel(viewModel: GameViewModel) {
    val ui by UIManager.visibility.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val level by LevelManager.runtimeState.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    if (ui.activeModal !in WORLD_LOCKED_STAGE_MODALS) return

    FortressPanelSurface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (ui.activeModal) {
                UiPanel.SETTLEMENT -> {
                    val passed = level.earnedStars > 0
                    LayeredPanelText(
                        if (passed) "关卡完成" else "核心失守",
                        style = PicoTheme.typography.titleLarge,
                        color = if (passed) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.alert,
                    )
                    LayeredPanelText(
                        if (passed) {
                            "第 ${level.levelId} 关 · ${level.earnedStars} 星 · 历史最高 ${level.bestStars} 星 · 晶核 ${level.crystalCores}"
                        } else {
                            "第 ${level.levelId} 关失败，调整塔线与合成节奏后再试。"
                        },
                        style = PicoTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpatialActionButton("再次挑战", {
                            viewModel.onEvent(GameUiEvent.EnterCalibration)
                        })
                        SpatialActionButton("返回菜单", {
                            viewModel.onEvent(GameUiEvent.ReturnToMenu)
                            scope.launch { closeGameplayStage(navigator) }
                        })
                    }
                }
                UiPanel.PAUSE -> {
                    LayeredPanelText("游戏已暂停", style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
                    LayeredPanelText("怪物、投射物与波次计时均已停止。", style = PicoTheme.typography.bodyLarge)
                    state.message?.let {
                        LayeredPanelText(it, color = PicoTheme.colorScheme.alert, style = PicoTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpatialActionButton("继续游戏", { viewModel.onEvent(GameUiEvent.ResumeGame) })
                        SpatialActionButton("返回菜单", {
                            viewModel.onEvent(GameUiEvent.ReturnToMenu)
                            scope.launch { closeGameplayStage(navigator) }
                        })
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
fun CombatTopHud(viewModel: GameViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val level by LevelManager.runtimeState.collectAsStateWithLifecycle()
    val gold by TowerManager.gold.collectAsStateWithLifecycle()
    if (state.gameState !in HUD_STATES) return
    FortressPanelSurface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LayeredPanelText(
                "第 ${level.levelId} 关 · 波次 ${level.currentWave}/${level.totalWaves} · 血量 ${level.endpointHealth}/${level.maxEndpointHealth} · 金币 $gold · 怪物 ${level.activeMonsters}+${level.queuedMonsters}",
                modifier = Modifier.width(690.dp),
                style = PicoTheme.typography.titleMedium,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            SpatialActionButton("暂停", { viewModel.onEvent(GameUiEvent.PauseGame) })
        }
    }
}

@Composable
fun BottomActionHud(viewModel: GameViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inventory by TowerManager.inventory.collectAsStateWithLifecycle()
    val dragPreview by TowerManager.dragPreview.collectAsStateWithLifecycle()
    val gold by TowerManager.gold.collectAsStateWithLifecycle()
    val debugGrounding by GroundingDebug.enabled.collectAsStateWithLifecycle()
    val debug by GameManager.debugState.collectAsStateWithLifecycle()
    val disabledAlpha = LocalDisableAlpha.current
    val editable = state.gameState == GameState.PREPARE || state.gameState == GameState.WAVE_PAUSE
    val draggingSlot = (dragPreview?.source as? TowerDragSource.InventorySlot)?.slotIndex
    val draggingBoardTower = dragPreview?.source is TowerDragSource.Existing
    if (state.gameState !in HUD_STATES) return
    FortressPanelSurface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width(730.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TowerType.entries.forEach { type ->
                        val config = TowerBalanceTable.get(type, 1)
                        val purchaseReady = !inventory.isFull && gold >= config.cost
                        SpatialActionButton(
                            label = "购买 ${type.displayName} ${config.cost}",
                            onClick = { viewModel.onEvent(GameUiEvent.BuyTower(type)) },
                            modifier = Modifier
                                .width(146.dp)
                                .alpha(if (purchaseReady) 1f else disabledAlpha),
                            // Keep the editable button routable so full/insufficient attempts can play failure audio.
                            enabled = editable,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    inventory.slots.forEach { slot ->
                        TowerSlotCard(
                            slot = slot,
                            enabled = editable,
                            dragging = draggingSlot == slot.index,
                        )
                    }
                }
            }
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SpatialActionButton(
                    label = if (state.gameState == GameState.PREPARE) "开始游戏" else "下一波",
                    onClick = { viewModel.onEvent(GameUiEvent.StartFight) },
                    modifier = Modifier
                        .testTag("start-fight-button")
                        .width(300.dp),
                    enabled = editable,
                )
                SpatialActionButton(
                    label = "出售已拿起武器",
                    onClick = TowerManager::sellSelectedWeapon,
                    modifier = Modifier.width(300.dp),
                    enabled = editable && dragPreview != null,
                )
                LayeredPanelText(
                    if (state.gameState == GameState.PREPARE) "放置完成后从这里开战" else "准备完成后进入下一波",
                    modifier = Modifier.width(300.dp),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.labelMedium,
                )
            }
            Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LayeredPanelText(
                    when {
                        !editable -> "战斗中无法操作 · 卡槽已锁定"
                        inventory.isFull -> "卡槽已满 · 请先放置或出售已拿起武器"
                        draggingBoardTower -> "棋盘武器已拿起 · 点击其他格移动，或点击出售按钮"
                        draggingSlot != null -> "武器已拿起 · 用射线点击棋盘可放置格；绿色高亮后确认"
                        else -> "购买后存入卡槽；再次点击卡槽武器才会拿起"
                    },
                    modifier = Modifier.width(660.dp),
                    color = if (!editable || inventory.isFull) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = debugGrounding, onCheckedChange = GroundingDebug::setEnabled)
                    LayeredPanelText("贴地标记", style = PicoTheme.typography.labelMedium)
                    SpatialActionButton(
                        if (debug.panelVisible) "关闭调试" else "打开调试",
                        { GameManager.setDebugPanelVisible(!debug.panelVisible) },
                    )
                }
                if (debug.panelVisible) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LayeredPanelText(
                            "${debug.framesPerSecond} FPS · 怪 ${debug.monsterCount} · 塔 ${debug.towerCount} · ${debug.usedMemoryMegabytes} MB",
                            modifier = Modifier.width(250.dp),
                            style = PicoTheme.typography.labelMedium,
                        )
                        SpatialActionButton(if (debug.invincible) "关闭无敌" else "无敌", {
                            GameManager.setInvincible(!debug.invincible)
                        })
                        SpatialActionButton("满金币", GameManager::fillGoldForDebug)
                        SpatialActionButton("跳关", GameManager::skipCurrentLevelForDebug)
                        SpatialActionButton(if (debug.collisionBoxesVisible) "隐藏碰撞" else "显示碰撞", {
                            GameManager.setCollisionBoxesVisible(!debug.collisionBoxesVisible)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TowerSlotCard(
    slot: TowerInventorySlot,
    enabled: Boolean,
    dragging: Boolean,
) {
    val shape = RoundedCornerShape(18.dp)
    val disabledAlpha = LocalDisableAlpha.current
    val emptyRed = Color(0xFFFF4D57) // design-style: fixed-figma-color user-required empty-slot red
    val occupiedBlue = Color(0xFF36C8FF) // design-style: fixed-figma-color inherited fortress emissive blue
    val selectedCyan = Color(0xFF67F5FF) // design-style: fixed-figma-color selected weapon emphasis
    val item = slot.item
    val borderColor = when {
        dragging -> selectedCyan
        item == null -> emptyRed.copy(alpha = .88f)
        else -> occupiedBlue
    }
    val fillColor = when {
        item == null -> emptyRed.copy(alpha = .18f)
        dragging -> selectedCyan.copy(alpha = .48f)
        else -> PicoTheme.colorScheme.fillSecondary.copy(alpha = .58f)
    }

    Box(
        Modifier
            .testTag("tower-slot-${slot.index}")
            .size(width = 112.dp, height = 116.dp)
            .alpha(if (enabled) 1f else disabledAlpha)
            .clip(shape),
    ) {
        Box(Modifier.fillMaxSize().background(fillColor, shape))
        Box(
            Modifier
                .fillMaxSize()
                .slotDepth(.01f)
                .border(3.dp, borderColor, shape),
        )
        Column(
            Modifier
                .fillMaxSize()
                .slotDepth(.02f)
                .padding(6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (item == null) {
                LayeredPanelText("空槽 ${slot.index + 1}", color = emptyRed, style = PicoTheme.typography.labelMedium)
                LayeredPanelText("待存入", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelSmall)
            } else {
                LayeredPanelText(
                    "${towerGlyph(item.type)} ${item.type.displayName}",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.labelMedium,
                )
                SpatialActionButton(
                    label = if (dragging) "已拿起" else "拿起",
                    onClick = { TowerManager.selectInventorySlot(slot.index) },
                    modifier = Modifier.width(100.dp),
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun Modifier.slotDepth(meters: Float): Modifier {
    val converter = LocalPhysicalLengthConverter.current
    return spatialOffset(z = converter.lengthToDp(meters, LengthUnit.Meters))
}

private fun towerGlyph(type: TowerType): String = when (type) {
    TowerType.ARCHER -> "➶"
    TowerType.BALLISTA -> "➹"
    TowerType.EXPLOSIVE -> "✹"
    TowerType.FROST -> "❄"
}

private fun scanCopy(status: PlaneScanStatus): String = when (status) {
    PlaneScanStatus.Idle -> "等待空间感知初始化"
    PlaneScanStatus.Scanning -> "正在扫描水平地面…请缓慢环视可用地面区域"
    is PlaneScanStatus.Success -> if (status.plane.isFallback) "地面高度兜底预览已生成" else "地面识别成功，半透明棋盘预览已生成"
    is PlaneScanStatus.Failed -> "${status.reason}；可选择“使用地面兜底”，或继续环视等待真实地面"
}

private suspend fun closeGameplayStage(navigator: SpatialNavigator) {
    // The Planar root is minimized while Full Space is active so it cannot
    // occlude Stage pointer events. Restore it before closing the Stage.
    GameManager.onGameplayStageClosed()
    navigator.restoreWindowContainer(MAIN_WINDOW_ID, null)
    navigator.closeStage()
}

private val WORLD_LOCKED_STAGE_MODALS = setOf(UiPanel.SETTLEMENT, UiPanel.PAUSE)
private val HUD_STATES = setOf(GameState.PREPARE, GameState.FIGHTING, GameState.WAVE_PAUSE)
