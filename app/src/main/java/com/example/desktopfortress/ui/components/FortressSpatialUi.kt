package com.example.desktopfortress.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.desktopfortress.domain.model.PanelRenderLayer
import com.example.desktopfortress.manager.UIManager
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.layout.offset as spatialOffset
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.delay

/** Inner panel surface. Window roots retain their system-provided Material.Regular glass. */
@Composable
fun FortressPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = UIManager.materialSpec
    val shape = RoundedCornerShape(spec.cornerRadiusDp.dp)
    val borderColor = Color(spec.emissiveBorderColorArgb).withVibrant(Vibrant.None)
    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(PicoTheme.colorScheme.fillSecondary.copy(alpha = spec.frostedAlpha), shape)
                .backgroundMaterial(style = Material.Thick),
        )
        Box(
            Modifier
                .fillMaxSize()
                .panelDepth(PanelRenderLayer.DECORATION)
                .clip(shape)
                .border(3.dp, borderColor, shape),
        )
        Box(Modifier.fillMaxSize().padding(24.dp), content = content)
    }
}

/**
 * PICO Button supplies pinch, controller-trigger, hover highlight, press animation and haptics.
 * This wrapper adds a non-consuming two-second spatial-pointer dwell path for eye-gaze input.
 */
@Composable
fun SpatialActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var gazeActive by remember { mutableStateOf(false) }
    var lastActivation by remember { mutableLongStateOf(0L) }
    val activate = remember(onClick, enabled) {
        {
            val now = android.os.SystemClock.uptimeMillis()
            if (enabled && now - lastActivation > 350L) {
                lastActivation = now
                gazeActive = false
                onClick()
            }
        }
    }
    LaunchedEffect(gazeActive, enabled) {
        if (gazeActive && enabled) {
            delay(UIManager.GAZE_CONFIRM_MILLIS)
            activate()
        }
    }

    Button(
        onClick = activate,
        enabled = enabled,
        modifier = modifier
            .panelDepth(PanelRenderLayer.BUTTON)
            .pointerInput(enabled) {
                if (enabled) {
                    detectSpatialPointerEvent(context) { pointers ->
                        gazeActive = pointers.any { pointer ->
                            !pointer.pressed && pointer.kind in GAZE_POINTER_KINDS
                        }
                        false
                    }
                }
            },
    ) {
        Text(
            text = if (gazeActive) "$label · 注视 2 秒" else label,
            modifier = Modifier.relativeTextDepth(),
        )
    }
}

@Composable
fun LayeredPanelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    if (style == null) {
        Text(text = text, modifier = modifier.panelDepth(PanelRenderLayer.TEXT), color = color)
    } else {
        Text(text = text, modifier = modifier.panelDepth(PanelRenderLayer.TEXT), color = color, style = style)
    }
}

@Composable
private fun Modifier.panelDepth(layer: PanelRenderLayer): Modifier {
    val converter = LocalPhysicalLengthConverter.current
    val depth = converter.lengthToDp(layer.depthMeters, LengthUnit.Meters)
    return spatialOffset(z = depth)
}

@Composable
private fun Modifier.relativeTextDepth(): Modifier {
    val converter = LocalPhysicalLengthConverter.current
    val relativeMeters = PanelRenderLayer.TEXT.depthMeters - PanelRenderLayer.BUTTON.depthMeters
    val depth: Dp = converter.lengthToDp(relativeMeters, LengthUnit.Meters)
    return spatialOffset(z = depth)
}

private val GAZE_POINTER_KINDS = setOf(InteractionKind.GazePinch)
