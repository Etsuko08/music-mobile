/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.component

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.convx.music.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private const val TOTAL_MS = 1200f
private const val ZOOM_START = 720f
private const val ZOOM_END = 1100f
private const val REVEAL_START = 1000f
private const val RIPPLE_MS = 450f
private const val PULSE_MS = 180f

private val BEATS_MS = listOf(400f, 620f)

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun inCubic(x: Float) = x * x * x
private fun outCubic(x: Float): Float { val f = 1f - x; return 1f - f * f * f }
private fun outBack(x: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f
    val f = x - 1f
    return 1f + c3 * f * f * f + c1 * f * f
}
private fun pulse(dt: Float): Float {
    if (dt !in 0f..PULSE_MS) return 0f
    return sin(dt / PULSE_MS * PI.toFloat())
}

/**
 * Butter-smooth 120Hz cold-start intro for Etsuko:
 * Driven by an Animatable running on the display frame rate.
 * Renders the official Etsuko split-disc logo in Bone white over Ink background,
 * pulses with tactile haptic beats, and sweeps into the live app.
 */
@Composable
fun ConvxIntro(onFinished: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val clock = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            var lastTickMs = 0f
            clock.animateTo(
                targetValue = TOTAL_MS,
                animationSpec = tween(durationMillis = TOTAL_MS.toInt(), easing = LinearEasing),
            ) {
                val current = value
                if (BEATS_MS.any { it in lastTickMs..current && it != lastTickMs }) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                lastTickMs = current
            }
        }
        onFinished()
    }
    if (reducedMotion) return

    val logoVector = ImageVector.vectorResource(R.drawable.convx_logo)
    val logo = remember(logoVector) {
        Path().apply {
            fillType = PathFillType.EvenOdd
            logoVector.root.filterIsInstance<VectorPath>().forEach { addPath(it.pathData.toPath()) }
        }
    }
    val logoViewport = logoVector.viewportWidth
    val hole = remember { Path() }

    val inkColor = Color(0xFF0E0E10)
    val boneColor = Color(0xFFF3F0E9)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        val t = clock.value
        val c = center
        val r0 = 54.dp.toPx()
        val cover = hypot(size.width, size.height) / 2f * 1.05f

        val holeR = cover * outCubic(phase(t, REVEAL_START, TOTAL_MS))
        hole.reset()
        if (holeR > 0f) hole.addOval(Rect(c, holeR))
        clipPath(hole, ClipOp.Difference) {
            drawRect(inkColor)

            BEATS_MS.forEach { beat ->
                val x = (t - beat) / RIPPLE_MS
                if (x in 0f..1f) {
                    drawCircle(
                        color = boneColor.copy(alpha = 0.5f * (1f - x)),
                        radius = r0 * (1f + 2.2f * outCubic(x)),
                        center = c,
                        style = Stroke(width = 2.5.dp.toPx() * (1f - 0.6f * x)),
                    )
                }
            }

            val beat = BEATS_MS.sumOf { pulse(t - it).toDouble() }.toFloat()
            val zoom = inCubic(phase(t, ZOOM_START, ZOOM_END))
            val discIn = outBack(phase(t, 0f, 350f))
            val discR = r0 * discIn * (1f + 0.08f * beat) + (cover - r0) * zoom
            drawCircle(boneColor, radius = discR.coerceAtLeast(0f), center = c)

            val glyphIn = phase(t, 80f, 420f)
            val glyph = r0 * 2f * 0.62f * outBack(glyphIn) * (1f + 0.08f * beat) *
                (1f + 15f * zoom.pow(1.6f))
            val spin = -120f * (1f - outCubic(glyphIn)) + 30f * zoom
            if (glyph > 0f) {
                rotate(spin, pivot = c) {
                    translate(c.x - glyph / 2f, c.y - glyph / 2f) {
                        scale(glyph / logoViewport, pivot = Offset.Zero) { drawPath(logo, inkColor) }
                    }
                }
            }
        }
    }
}
