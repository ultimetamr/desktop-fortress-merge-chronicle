package com.example.desktopfortress.content

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.desktopfortress.GAME_STAGE_ID
import com.example.desktopfortress.MAIN_WINDOW_ID
import com.example.desktopfortress.data.local.PreferencesManager
import com.example.desktopfortress.domain.model.AchievementState
import com.example.desktopfortress.domain.model.CodexCategory
import com.example.desktopfortress.domain.model.CodexEntry
import com.example.desktopfortress.domain.model.LevelCatalog
import com.example.desktopfortress.domain.model.UiPanel
import com.example.desktopfortress.manager.AchievementManager
import com.example.desktopfortress.manager.CodexManager
import com.example.desktopfortress.manager.DevelopManager
import com.example.desktopfortress.manager.LevelManager
import com.example.desktopfortress.manager.UIManager
import com.example.desktopfortress.manager.GameManager
import com.example.desktopfortress.ui.components.FortressPanelSurface
import com.example.desktopfortress.ui.components.LayeredPanelText
import com.example.desktopfortress.ui.components.SpatialActionButton
import com.example.desktopfortress.ui.game.GameUiEvent
import com.example.desktopfortress.ui.game.GameViewModel
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch

private enum class CollectionSection { CODEX, ACHIEVEMENT }

@Composable
fun HomePage(gameViewModel: GameViewModel = viewModel()) {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val ui by UIManager.visibility.collectAsStateWithLifecycle()
    val save by PreferencesManager.snapshot.collectAsStateWithLifecycle()
    val developments by DevelopManager.configs.collectAsStateWithLifecycle()
    val towerCodex by CodexManager.towerEntries.collectAsStateWithLifecycle()
    val monsterCodex by CodexManager.monsterEntries.collectAsStateWithLifecycle()
    val achievements by AchievementManager.achievements.collectAsStateWithLifecycle()
    val recovery by GameManager.recovery.collectAsStateWithLifecycle()
    var codexPage by remember { mutableIntStateOf(0) }
    var achievementPage by remember { mutableIntStateOf(0) }
    var collectionSection by remember { mutableStateOf(CollectionSection.CODEX) }

    // DefaultWindowContainer retains Material.Regular. This is a shared inner frosted panel.
    FortressPanelSurface(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LayeredPanelText(
                text = "桌面堡垒：合成战记",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.displaySmall,
            )
            LayeredPanelText(
                text = "晶核 ${save.player.crystalCores} · 历史金币 ${save.player.lifetimeGold} · 最高通关 ${save.player.highestClearedLevel}",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge,
            )
            when (ui.activeModal) {
                UiPanel.LEVEL_SELECT -> LevelSelectPanel(
                    onBack = { GameManager.returnFromLevelSelect() },
                    onEnter = { levelId ->
                        LevelManager.selectLevel(levelId)
                        scope.launch {
                            if (openGameplayStage(navigator)) {
                                // Enter calibration only after the Stage exists and the
                                // Planar menu can no longer occlude Stage pointer events.
                                gameViewModel.onEvent(GameUiEvent.EnterCalibration)
                            }
                        }
                    },
                )
                UiPanel.DEVELOPMENT -> DevelopmentPanel(
                    developments = developments,
                    onBack = { UIManager.open(UiPanel.MAIN_MENU) },
                )
                UiPanel.CODEX_ACHIEVEMENT -> CollectionPanel(
                    section = collectionSection,
                    onSection = { collectionSection = it },
                    towerCodex = towerCodex,
                    monsterCodex = monsterCodex,
                    achievements = achievements,
                    codexPage = codexPage,
                    onCodexPage = { codexPage = it },
                    achievementPage = achievementPage,
                    onAchievementPage = { achievementPage = it },
                    onBack = { UIManager.open(UiPanel.MAIN_MENU) },
                )
                UiPanel.MAIN_MENU, null -> MainMenuPanel(
                    onLevelSelect = { GameManager.showLevelSelect() },
                    onDevelop = { UIManager.open(UiPanel.DEVELOPMENT) },
                    onCollection = { UIManager.open(UiPanel.CODEX_ACHIEVEMENT) },
                    hasRecovery = recovery != null,
                    onRecovery = {
                        scope.launch {
                            // Restore state after opening Full Space so the Planar Activity
                            // pause cannot pause an already-restored calibration session.
                            if (openGameplayStage(navigator)) {
                                if (!GameManager.restoreRecoveryCheckpoint()) {
                                    GameManager.onGameplayStageClosed()
                                    navigator.restoreWindowContainer(MAIN_WINDOW_ID, null)
                                    navigator.closeStage()
                                }
                            }
                        }
                    },
                )
                else -> Unit
            }
        }
    }
}

private suspend fun openGameplayStage(navigator: SpatialNavigator): Boolean {
    return when (
        val result = navigator.openStage(
            GAME_STAGE_ID,
            StageStyle.Mixed,
            Bundle.EMPTY,
            UpperLimbRenderMode.Visible,
        )
    ) {
        OpenStageResult.Allowed -> {
            GameManager.onGameplayStageOpened()
            val minimized = navigator.minimizeWindowContainer()
            Log.i(NAVIGATION_TAG, "GameStage opened; MainWindow minimized=$minimized")
            true
        }
        OpenStageResult.NotAllowed -> {
            Log.w(NAVIGATION_TAG, "GameStage open not allowed")
            false
        }
        is OpenStageResult.Error -> {
            Log.e(NAVIGATION_TAG, "GameStage open failed code=${result.code}: ${result.reason}")
            false
        }
    }
}

private const val NAVIGATION_TAG = "DesktopFortress/Nav"

@Composable
private fun MainMenuPanel(
    onLevelSelect: () -> Unit,
    onDevelop: () -> Unit,
    onCollection: () -> Unit,
    hasRecovery: Boolean,
    onRecovery: () -> Unit,
) {
    LayeredPanelText(
        "在真实地面上部署、合成并守住怪物路径。所有面板支持捏合、扳机与注视 2 秒确认。",
        color = PicoTheme.colorScheme.labelSecondary,
        style = PicoTheme.typography.bodyLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpatialActionButton("选择关卡", onLevelSelect, Modifier.width(240.dp))
        SpatialActionButton("永久养成", onDevelop)
        SpatialActionButton("图鉴成就", onCollection)
        if (hasRecovery) SpatialActionButton("恢复上次对局", onRecovery)
    }
    val tower = CodexManager.getProgress(CodexCategory.TOWER)
    val monster = CodexManager.getProgress(CodexCategory.MONSTER)
    val achievement = AchievementManager.progress()
    LayeredPanelText(
        "收集进度：塔 ${tower.unlocked}/${tower.total} · 怪物 ${monster.unlocked}/${monster.total} · 成就 ${achievement.first}/${achievement.second}",
        style = PicoTheme.typography.bodyMedium,
        color = PicoTheme.colorScheme.labelSecondary,
    )
}

@Composable
private fun LevelSelectPanel(onBack: () -> Unit, onEnter: (Int) -> Unit) {
    SectionHeader("关卡选择 · 20 个战场", onBack)
    LevelCatalog.all.chunked(5).forEach { rowLevels ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowLevels.forEach { level ->
                val record = PreferencesManager.getLevelData(level.levelId)
                SpatialActionButton(
                    label = if (record.unlocked) "${level.levelId}关 · ${record.bestStars}星" else "${level.levelId}关 · 未解锁",
                    onClick = { onEnter(level.levelId) },
                    enabled = record.unlocked,
                    modifier = Modifier.width(170.dp),
                )
            }
        }
    }
}

@Composable
private fun DevelopmentPanel(
    developments: List<com.example.desktopfortress.domain.model.DevelopConfig>,
    onBack: () -> Unit,
) {
    SectionHeader("永久养成 · 最终值 = 基础值 × (1 + 总加成比例)", onBack)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        developments.chunked(4).forEach { columnItems ->
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                columnItems.forEach { config ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LayeredPanelText(
                            "[${config.type.category.displayName}] ${config.name} Lv.${config.currentLevel}/${config.maxLevel}\n+${(config.currentBonusRatio * 100).toInt()}% · ${config.description}",
                            modifier = Modifier.width(315.dp),
                            style = PicoTheme.typography.bodyMedium,
                        )
                        SpatialActionButton(
                            label = config.nextUpgradeCost?.let { "升级 $it" } ?: "已满级",
                            onClick = { DevelopManager.upgrade(config.type) },
                            enabled = config.nextUpgradeCost != null,
                            modifier = Modifier.width(135.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionPanel(
    section: CollectionSection,
    onSection: (CollectionSection) -> Unit,
    towerCodex: List<CodexEntry>,
    monsterCodex: List<CodexEntry>,
    achievements: List<AchievementState>,
    codexPage: Int,
    onCodexPage: (Int) -> Unit,
    achievementPage: Int,
    onAchievementPage: (Int) -> Unit,
    onBack: () -> Unit,
) {
    SectionHeader("图鉴与成就", onBack)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SpatialActionButton("塔/怪物图鉴", { onSection(CollectionSection.CODEX) })
        SpatialActionButton("成就列表", { onSection(CollectionSection.ACHIEVEMENT) })
    }
    if (section == CollectionSection.CODEX) {
        val entries = towerCodex + monsterCodex
        val maxPage = ((entries.size - 1) / 5).coerceAtLeast(0)
        val page = codexPage.coerceIn(0, maxPage)
        entries.drop(page * 5).take(5).forEach { CodexRow(it) }
        PageButtons(page, maxPage, { onCodexPage(page - 1) }, { onCodexPage(page + 1) })
    } else {
        val maxPage = ((achievements.size - 1) / 5).coerceAtLeast(0)
        val page = achievementPage.coerceIn(0, maxPage)
        achievements.drop(page * 5).take(5).forEach { AchievementRow(it) }
        PageButtons(page, maxPage, { onAchievementPage(page - 1) }, { onAchievementPage(page + 1) })
    }
}

@Composable
private fun SectionHeader(title: String, onBack: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SpatialActionButton("返回", onBack)
        LayeredPanelText(title, style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
    }
}

@Composable
private fun CodexRow(entry: CodexEntry) {
    val state = if (entry.unlocked) "已解锁" else "未解锁"
    val detail = if (entry.unlocked) "${entry.summary} · ${entry.detail}" else "首次在对局中出现后显示详情"
    LayeredPanelText(
        "[$state/${entry.category.name}] ${entry.name} · $detail",
        style = PicoTheme.typography.bodyMedium,
        color = if (entry.unlocked) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
    )
}

@Composable
private fun AchievementRow(state: AchievementState) {
    LayeredPanelText(
        "[${if (state.completed) "已完成" else "未完成"}/${state.config.category.displayName}] ${state.config.name} · 奖励 ${state.config.crystalReward} 晶核 · ${state.config.description}",
        style = PicoTheme.typography.bodyMedium,
        color = if (state.completed) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
    )
}

@Composable
private fun PageButtons(page: Int, maxPage: Int, previous: () -> Unit, next: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SpatialActionButton("上一页", previous, enabled = page > 0)
        SpatialActionButton("下一页", next, enabled = page < maxPage)
        LayeredPanelText("第 ${page + 1}/${maxPage + 1} 页", style = PicoTheme.typography.labelMedium)
    }
}
