package com.simoscal.android

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The analysis canvas's coordinate math, in plain floats.
 *
 * Outside the `ui` package and free of Compose types so it can be tested on the
 * JVM, the same arrangement [BoostPlotGeometry] uses and for the same reason.
 * The difference between the two is what they are for: the boost plot inverts
 * pixels back into a *value someone is about to write to a bin*, so its
 * round-trip is a safety property. Nothing here is editable — these plots are a
 * readout — so the property that matters instead is that the axes tell the truth
 * about the data: a y range that quietly clipped a knock spike, or a zero line
 * that drifted off the plot, would hide the very thing the plot exists to show.
 */

/** Where one panel's plot area sits inside its canvas, in pixels. */
class AnalysisPlotGeometry(canvasWidth: Float, canvasHeight: Float) {

    /** Room for y labels left, x labels below, and a little air right. */
    val left: Float = 62f
    val top: Float = 16f
    val width: Float = max(1f, canvasWidth - left - 16f)
    val height: Float = max(1f, canvasHeight - top - 34f)
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * A closed numeric interval, and the tick ladder drawn along it.
 *
 * Built by [AnalysisAxis.of], which pads the data range outward to round
 * numbers. Padding outward rather than fitting tight is deliberate: a curve that
 * touches the frame reads as a curve that was cut off, and on a knock or
 * overshoot plot "did it go further than this?" is the first question a person
 * asks.
 */
data class AnalysisAxis(val min: Float, val max: Float, val ticks: List<Float>) {

    val span: Float get() = (max - min).takeIf { it > 1e-9f } ?: 1f

    /** Fraction of the way along the axis, 0 at [min] and 1 at [max]. */
    fun fraction(value: Float): Float = (value - min) / span

    companion object {
        /**
         * An axis covering [low]..[high] plus every value in [include].
         *
         * [include] is how a threshold line stays on screen. A watch line the
         * data never reached would otherwise be clipped away, and its absence
         * reads exactly like a line that was never crossed — the opposite of
         * what an off-scale threshold means.
         */
        fun of(low: Float, high: Float, include: List<Float> = emptyList()): AnalysisAxis {
            val values = (listOf(low, high) + include).filter { it.isFinite() }
            if (values.isEmpty()) return AnalysisAxis(0f, 1f, listOf(0f, 1f))

            var lo = values.min()
            var hi = values.max()
            if (hi - lo < 1e-6f) {
                // A flat series still deserves a readable frame rather than a
                // division by zero: give it a unit of room either side.
                val pad = max(abs(hi) * 0.05f, 0.5f)
                lo -= pad
                hi += pad
            }
            val step = niceStep(hi - lo)
            val paddedLo = floor(lo / step) * step
            val paddedHi = ceil(hi / step) * step
            return AnalysisAxis(paddedLo, paddedHi, ticksBetween(paddedLo, paddedHi, step))
        }

        /**
         * A 1/2/5×10ⁿ step giving roughly four or five intervals.
         *
         * The familiar ladder — 1, 2, 5, 10, 20, 50 — because axis labels are
         * read at a glance on a phone and an unrounded step turns every one of
         * them into a number to decode.
         */
        internal fun niceStep(range: Float): Float {
            if (!range.isFinite() || range <= 0f) return 1f
            // Divided by five, not four. The ladder below only ever rounds the
            // rough step *up*, so dividing by four lands on two-interval axes for
            // whole swathes of ranges (a span of 9 would take a step of 5). Five
            // keeps the result in the three-to-five range the doc promises.
            val rough = range / 5f
            val magnitude = 10f.pow(floor(log10(rough.toDouble())).toFloat())
            val normalised = rough / magnitude
            val step = when {
                normalised <= 1f -> 1f
                normalised <= 2f -> 2f
                normalised <= 5f -> 5f
                else -> 10f
            }
            return step * magnitude
        }

        private fun ticksBetween(low: Float, high: Float, step: Float): List<Float> {
            val ticks = mutableListOf<Float>()
            var value = low
            // Guarded rather than while(value <= high): float accumulation on a
            // step like 0.03 can otherwise overshoot or spin.
            var guard = 0
            while (value <= high + step * 1e-3f && guard < MAX_TICKS) {
                // Snap the accumulator back onto the ladder so a tick prints as
                // "0.06" rather than "0.060000002".
                ticks += if (abs(value) < step * 1e-6f) 0f else value
                value += step
                guard++
            }
            return ticks
        }

        private const val MAX_TICKS = 64
    }
}

/**
 * Value ↔ pixel mapping for one panel.
 *
 * Both axes map by *value*. On the boost editor the x axis is a fixed ladder of
 * breakpoints; here it is engine speed, sampled wherever the pull happened to
 * be, so nothing about the spacing can be assumed.
 */
class AnalysisPlotScale(val x: AnalysisAxis, val y: AnalysisAxis) {

    fun px(geometry: AnalysisPlotGeometry, value: Float): Float =
        geometry.left + x.fraction(value) * geometry.width

    fun py(geometry: AnalysisPlotGeometry, value: Float): Float =
        geometry.bottom - y.fraction(value) * geometry.height

    /** True when a value falls inside the drawn frame — a clipped point is skipped, not clamped. */
    fun containsY(value: Float): Boolean = value >= y.min && value <= y.max

    companion object {
        /**
         * Fit a panel's own data.
         *
         * Every panel scales to itself rather than to a range shared across the
         * plot. Stacked panels here hold different quantities — kPa over psi,
         * percent over percent — so a shared y axis would flatten one of them
         * into a straight line for no gain.
         */
        fun of(panel: PlotPanel): AnalysisPlotScale {
            val xs = mutableListOf<Float>()
            val ys = mutableListOf<Float>()
            panel.series.forEach { series ->
                series.segments.forEach { segment ->
                    for (i in 0 until segment.size) {
                        val xv = segment.x[i]
                        val yv = segment.y[i]
                        if (xv.isFinite()) xs += xv
                        if (yv.isFinite()) ys += yv
                    }
                }
            }
            if (xs.isEmpty() || ys.isEmpty()) {
                return AnalysisPlotScale(AnalysisAxis.of(0f, 1f), AnalysisAxis.of(0f, 1f))
            }
            return AnalysisPlotScale(
                x = AnalysisAxis.of(xs.min(), xs.max()),
                // Threshold values join the y range so a line at the watch level
                // is visible whether or not the data reached it.
                y = AnalysisAxis.of(ys.min(), ys.max(), panel.thresholds.map { it.value }),
            )
        }
    }
}

/**
 * Thin a segment down to at most [limit] points for drawing.
 *
 * A three-minute log can put several thousand samples behind one curve, which is
 * more points than a phone-width canvas has pixels — so most of them land on a
 * pixel already painted, and the cost is paid for nothing.
 *
 * The thinning keeps **every local extreme**, not every nth point. Stride
 * sampling is what would make this dishonest: a single-sample knock spike or a
 * one-frame overshoot is exactly the feature these plots exist to show, and it
 * is exactly what a stride drops. Each output bucket contributes its minimum and
 * its maximum, so the drawn envelope still touches the real extremes.
 */
fun thinForDisplay(segment: Segment, limit: Int = DEFAULT_POINT_LIMIT): Segment {
    val n = segment.size
    if (n <= limit || limit < 4) return segment

    val buckets = limit / 2
    val outX = ArrayList<Float>(limit)
    val outY = ArrayList<Float>(limit)
    for (bucket in 0 until buckets) {
        val start = (bucket.toLong() * n / buckets).toInt()
        val end = ((bucket + 1).toLong() * n / buckets).toInt().coerceAtMost(n)
        if (start >= end) continue
        var minIndex = -1
        var maxIndex = -1
        for (i in start until end) {
            val value = segment.y[i]
            if (!value.isFinite()) continue
            if (minIndex < 0 || value < segment.y[minIndex]) minIndex = i
            if (maxIndex < 0 || value > segment.y[maxIndex]) maxIndex = i
        }
        if (minIndex < 0) continue
        // Emit in index order so the curve still sweeps one way in x.
        val first = minOf(minIndex, maxIndex)
        val second = maxOf(minIndex, maxIndex)
        outX += segment.x[first]; outY += segment.y[first]
        if (second != first) {
            outX += segment.x[second]; outY += segment.y[second]
        }
    }
    return Segment(outX.toFloatArray(), outY.toFloatArray())
}

/** Points per drawn segment, above which [thinForDisplay] starts bucketing. */
const val DEFAULT_POINT_LIMIT: Int = 600
