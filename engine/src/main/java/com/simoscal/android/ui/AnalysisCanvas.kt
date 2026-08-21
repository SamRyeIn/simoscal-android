package com.simoscal.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.simoscal.android.AnalysisPlotGeometry
import com.simoscal.android.AnalysisPlotScale
import com.simoscal.android.PlotPanel
import com.simoscal.android.PlotSeries
import com.simoscal.android.Segment
import com.simoscal.android.SeriesRole
import com.simoscal.android.Threshold
import com.simoscal.android.ThresholdTone
import com.simoscal.android.displayMeasured
import com.simoscal.android.thinForDisplay
import kotlin.math.abs

/**
 * The colours a pull's curve takes, by the engine-assigned colour slot.
 *
 * The first three are the palette's own; the last three are derived, and the
 * reason is the encoding on these particular plots. `warn` and `danger` are
 * spoken for here by the threshold lines — the watch and high levels are drawn
 * in exactly those two colours — so a *pull* drawn in the watch colour would
 * read as a limit rather than as a run. The categorical ramp therefore steps
 * around them, keeping the palette's meanings intact rather than borrowing them.
 *
 * Colour identifies the pull and nothing else; what a line *is* comes from its
 * style. That is the engine's rule (quantity = line style, pull = colour), and
 * it is why this list can be extended without changing what any plot means.
 */
private val PullColors: List<Color> = listOf(
    PromoPalette.Accent2,     // cool blue
    PromoPalette.Accent,      // simoscal orange
    PromoPalette.Good,        // green
    Color(0xFFB98CFF),        // violet
    Color(0xFF4FD8D0),        // teal
    Color(0xFFFF9EC4),        // pink
)

internal fun pullColor(ordinal: Int): Color =
    PullColors[((ordinal % PullColors.size) + PullColors.size) % PullColors.size]

/** The neutral ink a reference or secondary line takes — never a pull's colour. */
private val ReferenceInk = PromoPalette.TextDim
private val SecondaryInk = PromoPalette.TextFaint

internal fun thresholdColor(tone: ThresholdTone): Color = when (tone) {
    // The zero line is a datum, not a warning: it gets the frame's own ink.
    ThresholdTone.ZERO -> PromoPalette.TextFaint
    ThresholdTone.WATCH -> PromoPalette.Warn
    ThresholdTone.HIGH -> PromoPalette.Danger
}

/**
 * One panel of an evidence plot.
 *
 * Draws exactly what the engine sent and nothing it did not: the samples come
 * from `simoscal.analysis.series`, already masked to loaded-WOT, split at the
 * holes in that mask, and sorted along rpm. Nothing here filters, smooths, or
 * interpolates — a curve on this canvas is the same curve the library's own PNG
 * report draws, which is the property the whole feature turns on.
 *
 * The caller owns the height. A stack of panels shares a screen with prose and a
 * findings list, and how much of it one panel deserves is a decision for the
 * screen that can see the whole column.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun AnalysisCanvas(panel: PlotPanel, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val scale = remember(panel) { AnalysisPlotScale.of(panel) }
    // Thinned once per panel rather than on every frame: a long pull carries
    // thousands of samples and the bucketing walks all of them.
    val thinned = remember(panel) {
        panel.series.map { series -> series to series.segments.map { thinForDisplay(it) } }
    }

    Canvas(modifier = modifier.fillMaxWidth()) {
        val geometry = AnalysisPlotGeometry(size.width, size.height)

        drawGrid(geometry, scale, measurer)
        panel.thresholds.forEach { threshold -> drawThreshold(geometry, scale, threshold, measurer) }

        // Painted back to front: context first, measurement last. A settled
        // curve must never be hidden under the transients it deliberately excludes.
        val order = listOf(
            SeriesRole.TRANSIENT,
            SeriesRole.REFERENCE,
            SeriesRole.SECONDARY,
            SeriesRole.PRIMARY,
        )
        order.forEach { role ->
            thinned.filter { (series, _) -> series.role == role }
                .forEach { (series, segments) -> drawSeries(geometry, scale, series, segments) }
        }

        drawFrame(geometry)
    }
}

// -------------------------------------------------------------------- drawing

private fun DrawScope.drawSeries(
    geometry: AnalysisPlotGeometry,
    scale: AnalysisPlotScale,
    series: PlotSeries,
    segments: List<Segment>,
) {
    when (series.role) {
        SeriesRole.TRANSIENT -> segments.forEach { segment ->
            for (i in 0 until segment.size) {
                drawCircle(
                    color = PromoPalette.TextFaint.copy(alpha = 0.35f),
                    radius = 2.5f,
                    center = Offset(
                        scale.px(geometry, segment.x[i]),
                        scale.py(geometry, segment.y[i]),
                    ),
                )
            }
        }

        SeriesRole.PRIMARY -> segments.forEach { segment ->
            drawPolyline(geometry, scale, segment, pullColor(series.ordinal), 3f, null)
        }

        SeriesRole.REFERENCE -> segments.forEach { segment ->
            drawPolyline(
                geometry, scale, segment, ReferenceInk, 2f,
                PathEffect.dashPathEffect(floatArrayOf(11f, 8f)),
            )
        }

        SeriesRole.SECONDARY -> segments.forEach { segment ->
            drawPolyline(
                geometry, scale, segment, SecondaryInk, 2f,
                PathEffect.dashPathEffect(floatArrayOf(10f, 5f, 2f, 5f)),
            )
        }
    }
}

private fun DrawScope.drawPolyline(
    geometry: AnalysisPlotGeometry,
    scale: AnalysisPlotScale,
    segment: Segment,
    color: Color,
    width: Float,
    effect: PathEffect?,
) {
    if (segment.size < 2) {
        // A one-sample run is still a measurement and still gets drawn — as the
        // point it is. Dropping it would silently hide a pull that the mask
        // admitted for a single frame.
        if (segment.size == 1) {
            drawCircle(
                color,
                radius = width,
                center = Offset(
                    scale.px(geometry, segment.x[0]),
                    scale.py(geometry, segment.y[0]),
                ),
            )
        }
        return
    }
    val path = Path()
    var started = false
    for (i in 0 until segment.size) {
        val x = scale.px(geometry, segment.x[i])
        val y = scale.py(geometry, segment.y[i])
        if (!x.isFinite() || !y.isFinite()) continue
        if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
    }
    if (started) drawPath(path, color, style = Stroke(width = width, pathEffect = effect))
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawThreshold(
    geometry: AnalysisPlotGeometry,
    scale: AnalysisPlotScale,
    threshold: Threshold,
    measurer: TextMeasurer,
) {
    val y = scale.py(geometry, threshold.value)
    if (y < geometry.top || y > geometry.bottom) return
    val color = thresholdColor(threshold.tone)
    val zero = threshold.tone == ThresholdTone.ZERO
    drawLine(
        color = color,
        start = Offset(geometry.left, y),
        end = Offset(geometry.right, y),
        strokeWidth = if (zero) 1.5f else 2f,
        // The zero line is solid because it is a datum; a watch or high line is
        // dashed because it is a level someone drew, not a thing that was measured.
        pathEffect = if (zero) null else PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
    )
    if (threshold.label.isNotEmpty()) {
        val layout = measurer.measure(threshold.label, axisLabelStyle.copy(color = color))
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(geometry.right - layout.size.width - 4f, y - layout.size.height - 2f),
        )
    }
}

private val axisLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = PromoPalette.TextFaint,
    fontFamily = FontFamily.Monospace,
)

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawGrid(
    geometry: AnalysisPlotGeometry,
    scale: AnalysisPlotScale,
    measurer: TextMeasurer,
) {
    val gridColor = PromoPalette.Rule.copy(alpha = 0.45f)

    scale.y.ticks.forEach { value ->
        val y = scale.py(geometry, value)
        if (y < geometry.top - 1f || y > geometry.bottom + 1f) return@forEach
        drawLine(gridColor, Offset(geometry.left, y), Offset(geometry.right, y), 1f)
        val layout = measurer.measure(value.toDouble().displayMeasured(), axisLabelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(geometry.left - layout.size.width - 6f, y - layout.size.height / 2f),
        )
    }

    // Only the ends and the middle of the x ladder get a label. A phone-width
    // canvas cannot carry six rpm figures without them running together, and the
    // shape of the curve is what is being read here, not an exact abscissa.
    val xTicks = scale.x.ticks
    val labelled = listOfNotNull(
        xTicks.firstOrNull(),
        xTicks.getOrNull(xTicks.size / 2),
        xTicks.lastOrNull(),
    ).distinct()
    xTicks.forEach { value ->
        val x = scale.px(geometry, value)
        if (x < geometry.left - 1f || x > geometry.right + 1f) return@forEach
        drawLine(gridColor, Offset(x, geometry.top), Offset(x, geometry.bottom), 1f)
        if (value in labelled) {
            val layout = measurer.measure(value.toDouble().displayMeasured(), axisLabelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    (x - layout.size.width / 2f)
                        .coerceIn(geometry.left, geometry.right - layout.size.width),
                    geometry.bottom + 6f,
                ),
            )
        }
    }
}

private fun DrawScope.drawFrame(geometry: AnalysisPlotGeometry) {
    drawLine(
        PromoPalette.Rule,
        Offset(geometry.left, geometry.top),
        Offset(geometry.left, geometry.bottom),
        1.5f,
    )
    drawLine(
        PromoPalette.Rule,
        Offset(geometry.left, geometry.bottom),
        Offset(geometry.right, geometry.bottom),
        1.5f,
    )
}
