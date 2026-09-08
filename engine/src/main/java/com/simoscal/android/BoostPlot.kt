package com.simoscal.android

import kotlin.math.abs
import kotlin.math.max

/**
 * The boost canvas's coordinate math, in plain floats.
 *
 * Deliberately outside the `ui` package and free of Compose types so it can be
 * tested on the JVM. This is where a fingertip becomes a boost number: [psiAt]
 * is the inverse of [y], and if the two ever disagreed a drag would set a value
 * other than the one the curve was drawn at — a silent mismatch between what a
 * person sees and what gets written to a bin.
 */

/** Where the plot sits inside the canvas, in pixels. */
class BoostPlotGeometry(canvasWidth: Float, canvasHeight: Float) {

    /** Room for psi labels left, rpm labels below, slot labels right. */
    val left: Float = 56f
    val top: Float = 14f
    val width: Float = max(1f, canvasWidth - left - 40f)
    val height: Float = max(1f, canvasHeight - top - 34f)
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** The vertical step that keeps two converging slot labels legible. */
private const val SLOT_LABEL_GAP = 26f

/** Roughly one line of the 11sp label face — the room a label needs below its y. */
private const val SLOT_LABEL_LINE = 14f

/**
 * Where each slot's right-hand label is drawn, nudged apart where curves converge.
 *
 * Pure, and deliberately not inlined into the drawing, because the nudge can run
 * off the bottom of the canvas: a short plot clamps [BoostPlotGeometry.height] to
 * its 1px floor, every curve then ends at the same y, and a fixed [SLOT_LABEL_GAP]
 * step marches the ladder down past the edge. Text there measures against a
 * negative height and throws rather than clipping, so a label that will not fit is
 * dropped here and the drawing code never asks for it.
 *
 * [ends] is (slot, the y its curve ends at); the result is (slot, the y to draw at)
 * for the labels that fit, in ladder order.
 */
fun slotLabelLadder(ends: List<Pair<Int, Float>>, canvasHeight: Float): List<Pair<Int, Float>> {
    val lowest = canvasHeight - SLOT_LABEL_LINE
    val placed = mutableListOf<Pair<Int, Float>>()
    var previousY = Float.NEGATIVE_INFINITY
    for ((slot, endY) in ends.sortedBy { it.second }) {
        val y = max(endY - 8f, previousY + SLOT_LABEL_GAP)
        // Sorted ascending, so once the ladder is off the canvas it stays off.
        if (y > lowest) break
        previousY = y
        placed += slot to y
    }
    return placed
}

/**
 * Value ↔ pixel mapping for the plot.
 *
 * The rpm axis maps by *value*, not by index: the slot breakpoints are not evenly
 * spaced, and drawing them as if they were would misplace every curve against the
 * base ceiling — the one comparison this screen exists to make.
 */
class BoostPlotScale(
    val rpmMin: Double,
    val rpmMax: Double,
    val psiMax: Double,
    val rpmAxis: List<Double>,
) {

    fun x(geometry: BoostPlotGeometry, rpm: Double): Float {
        val span = (rpmMax - rpmMin).takeIf { it > 1e-9 } ?: 1.0
        return geometry.left + ((rpm - rpmMin) / span).toFloat() * geometry.width
    }

    fun y(geometry: BoostPlotGeometry, psi: Double): Float {
        val span = psiMax.takeIf { it > 1e-9 } ?: 1.0
        return geometry.bottom - (psi / span).toFloat() * geometry.height
    }

    /** Invert [y] — the psi a fingertip at this pixel is asking for. */
    fun psiAt(geometry: BoostPlotGeometry, pixelY: Float): Double {
        val fraction = (geometry.bottom - pixelY) / geometry.height
        return fraction.toDouble() * psiMax
    }

    /** The breakpoint nearest a horizontal position — what a drag or tap grabbed. */
    fun nearestIndex(geometry: BoostPlotGeometry, pixelX: Float): Int {
        if (rpmAxis.isEmpty()) return 0
        return rpmAxis.indices.minByOrNull { abs(x(geometry, rpmAxis[it]) - pixelX) } ?: 0
    }

    companion object {
        /**
         * ``overlay`` widens the axes to fit a logged pull, when one is drawn.
         *
         * Both axes, and neither is optional. A pull that overshot the curves
         * would otherwise be clipped at the top of the plot — hiding the
         * overshoot, which is the single most important thing a boost trace has
         * to say. And a pull that ran past the last slot breakpoint would be
         * squeezed against the right frame, which would misplace *every* sample
         * against the curves rather than only the ones off the end.
         */
        fun of(
            model: BoostCurveModel,
            draft: List<Double>,
            overlay: OverlayPull? = null,
        ): BoostPlotScale {
            val overlaySamples = overlay?.series.orEmpty().flatMap { it.segments }
            val highest = maxOf(
                model.refusalCeilingPsi,
                draft.maxOrNull() ?: 0.0,
                model.slots.flatMap { it.psi }.maxOrNull() ?: 0.0,
                overlaySamples.flatMap { it.values }.maxOrNull() ?: 0.0,
            )
            val overlayRpm = overlaySamples.flatMap { it.rpm }
            return BoostPlotScale(
                rpmMin = minOf(
                    model.rpmAxis.minOrNull() ?: 0.0,
                    overlayRpm.minOrNull() ?: Double.MAX_VALUE,
                ),
                rpmMax = maxOf(
                    model.rpmAxis.maxOrNull() ?: 1.0,
                    overlayRpm.maxOrNull() ?: Double.MIN_VALUE,
                ),
                // Headroom above the highest line so the refusal dash and the top
                // of a curve are never drawn on the frame itself.
                psiMax = if (highest > 0) highest * 1.12 else 1.0,
                rpmAxis = model.rpmAxis,
            )
        }
    }
}
