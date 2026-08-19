package com.example.desktopfortress.domain.model

import com.pico.spatial.core.math.Vector3

enum class PanelGroup { A_MODAL, B_HUD }

enum class UiPanel(val group: PanelGroup) {
    MAIN_MENU(PanelGroup.A_MODAL),
    LEVEL_SELECT(PanelGroup.A_MODAL),
    DEVELOPMENT(PanelGroup.A_MODAL),
    CODEX_ACHIEVEMENT(PanelGroup.A_MODAL),
    CALIBRATION_GUIDE(PanelGroup.A_MODAL),
    SETTLEMENT(PanelGroup.A_MODAL),
    PAUSE(PanelGroup.A_MODAL),
    COMBAT_TOP_HUD(PanelGroup.B_HUD),
    BOTTOM_ACTION_HUD(PanelGroup.B_HUD),
}

/** Shared anti-flicker contract. Order values are kept explicit for render auditing. */
enum class PanelRenderLayer(val orderInLayer: Int, val depthMeters: Float) {
    BACKPLATE(10, 0f),
    DECORATION(20, .001f),
    BUTTON(30, .002f),
    TEXT(40, .003f),
}

enum class PanelBlendMode { ALPHA_BLEND }

data class PanelMaterialSpec(
    val zWrite: Boolean = false,
    val blendMode: PanelBlendMode = PanelBlendMode.ALPHA_BLEND,
    val cornerRadiusDp: Int = 28,
    val frostedAlpha: Float = .68f,
    val emissiveBorderColorArgb: Long = 0xFF36C8FFFF,
)

data class HeadPose(
    val position: Vector3,
    /** Horizontal, normalized look direction in Stage coordinates. */
    val horizontalForward: Vector3,
    /** Full normalized look direction. Calibration UI follows pitch but never roll. */
    val forward: Vector3 = horizontalForward,
)

data class PanelTransform(
    val position: Vector3,
    val yawDegrees: Float,
    val pitchDegrees: Float = 0f,
    val visible: Boolean = true,
)

data class UiRuntimeState(
    val activeModal: UiPanel? = UiPanel.MAIN_MENU,
    val visibleHuds: Set<UiPanel> = emptySet(),
    val modalTransform: PanelTransform? = null,
    val calibrationTransform: PanelTransform? = null,
    val topHudTransform: PanelTransform? = null,
    val bottomHudTransform: PanelTransform? = null,
)

data class UiVisibilityState(
    val activeModal: UiPanel? = UiPanel.MAIN_MENU,
    val visibleHuds: Set<UiPanel> = emptySet(),
)
