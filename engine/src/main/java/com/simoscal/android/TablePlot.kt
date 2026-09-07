package com.simoscal.android

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import java.math.BigDecimal

/** Which table dimension runs horizontally; lookup values always run vertically. */
enum class TablePlotDirection(val label: String) {
    Columns("Columns"), Rows("Rows");

    fun cell(curve: Int, point: Int): CellRef =
        if (this == Columns) CellRef(curve, point) else CellRef(point, curve)
}

data class TablePlotAxis(val values: List<Double>, val label: String)

/** A view of the shared grid draft, never a transposed copy to write back. */
class TablePlotModel(val tables: TablesUiState, val direction: TablePlotDirection) {
    private val detail = requireNotNull(tables.detail)
    private val rows = tables.draft.size
    private val columns = tables.draft.first().size
    private val alongColumns = direction == TablePlotDirection.Columns

    val xAxis = axis(
        source = if (alongColumns) detail.xAxis else detail.yAxis,
        alternate = if (alongColumns) detail.yAxis else detail.xAxis,
        count = if (alongColumns) columns else rows,
        otherCount = if (alongColumns) rows else columns,
        name = if (alongColumns) "Column" else "Row",
    )
    val constantAxis = axis(
        source = if (alongColumns) detail.yAxis else detail.xAxis,
        alternate = if (alongColumns) detail.xAxis else detail.yAxis,
        count = if (alongColumns) rows else columns,
        otherCount = if (alongColumns) columns else rows,
        name = if (alongColumns) "Row" else "Column",
    )

    /**
     * How many curves this orientation draws.
     *
     * One for a vector table, which is the whole reason the plot editor is not
     * restricted to grids: a 1-D calibration *is* a curve, and reading one off a
     * single row of numbers is the case a plot helps most.
     */
    val curves: Int get() = constantAxis.values.size

    fun value(curve: Int, point: Int): Double {
        val cell = direction.cell(curve, point)
        return tables.draft[cell.row][cell.col]
    }

    /**
     * The imported bin's value for the same cell — the ghost the pedal and boost
     * curve editors already draw behind their working curve.
     *
     * Null when the engine sent no pre-edit buffer (a recovered rather than
     * opened session), which is "no ghost to draw" and never "unchanged".
     */
    fun ghost(curve: Int, point: Int): Double? {
        if (!hasGhost) return null
        val cell = direction.cell(curve, point)
        return detail.sourceValues[cell.row][cell.col].takeIf { it.isFinite() }
    }

    val hasGhost: Boolean = detail.sourceValues.size == rows &&
        detail.sourceValues.all { it.size == columns }

    /** Every ghost value, for bracketing the view so the reference is on screen. */
    val ghostValues: List<Double>
        get() = if (hasGhost) detail.sourceValues.flatten().filter { it.isFinite() } else emptyList()

    fun nearestPoint(x: Double): Int = xAxis.values.indices.minBy { abs(xAxis.values[it] - x) }

    fun nearestVisiblePoint(x: Double, range: TablePlotRange): Int? = xAxis.values.indices
        .filter { xAxis.values[it] in range.min..range.max }
        .minByOrNull { abs(xAxis.values[it] - x) }

    companion object {
        /**
         * The orientations worth plotting: both for a grid, the long one for a
         * vector, none for a scalar or a table carrying a non-finite value.
         *
         * Read off the draft's own shape rather than the summary's `ndim`,
         * because the draft is what gets plotted — a shape the plot cannot index
         * must fail this check even if the engine described it as a grid.
         */
        fun directions(tables: TablesUiState): List<TablePlotDirection> {
            val draft = tables.draft
            if (tables.detail == null || draft.isEmpty() || draft.first().isEmpty()) return emptyList()
            val width = draft.first().size
            if (draft.any { row -> row.size != width || row.any { !it.isFinite() } }) return emptyList()
            return buildList {
                if (width > 1) add(TablePlotDirection.Columns)
                if (draft.size > 1) add(TablePlotDirection.Rows)
            }
        }

        fun available(tables: TablesUiState): Boolean = directions(tables).isNotEmpty()

        private fun axis(
            source: TableAxis?,
            alternate: TableAxis?,
            count: Int,
            otherCount: Int,
            name: String,
        ): TablePlotAxis {
            // Only one axis can be `count` long when the two dimensions differ,
            // so a vector table whose breakpoints arrived under `y_axis` rather
            // than `x_axis` still gets its real labels. A square table is
            // ambiguous and is never crossed over: a real label on the wrong
            // axis reads as fact and is worse than an honest index.
            val candidates = if (count == otherCount) listOf(source) else listOf(source, alternate)
            val chosen = candidates.firstOrNull { candidate ->
                candidate != null && candidate.values.size == count && candidate.values.all { it.isFinite() }
            } ?: return TablePlotAxis(
                List(count) { (it + 1).toDouble() },
                "$name index (breakpoints unavailable)",
            )
            return TablePlotAxis(chosen.values, chosen.label.ifBlank {
                "$name breakpoint" + chosen.units.takeIf { it.isNotBlank() && it != "-" }?.let { " [$it]" }.orEmpty()
            })
        }
    }
}

/** Linear, round-number bounds, including negative and constant-valued tables. */
data class TablePlotRange(val min: Double, val max: Double, val step: Double) {
    fun fraction(value: Double): Float = ((value - min) / (max - min)).toFloat()
    fun value(fraction: Float): Double = min + fraction.toDouble() * (max - min)
    val ticks: List<Double> get() {
        val first = ceil(min / step) * step
        return (0..((max - first) / step).toInt().coerceIn(0, 20))
            .map { first + it * step }.filter { it <= max }
    }

    fun window(low: Double, high: Double): TablePlotRange =
        TablePlotRange(low, high, roundPlotStep((high - low) / 5.0))

    companion object {
        fun of(values: List<Double>): TablePlotRange {
            val low = values.minOrNull() ?: 0.0
            val high = values.maxOrNull() ?: 1.0
            val padding = if (high > low) (high - low) * 0.08 else maxOf(abs(low) * 0.1, 0.001)
            val start = low - padding
            val end = high + padding
            val rough = (end - start) / 4.0
            val magnitude = 10.0.pow(floor(log10(rough)))
            val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= rough } * magnitude
            return TablePlotRange(floor(start / step) * step, ceil(end / step) * step, step)
        }
    }
}

/**
 * The plot's data area in pixels — everything inside the axis gutters.
 *
 * Kept out of the `ui` package and off the draw scope for the same reason the
 * boost canvas keeps its coordinate math here: a fingertip position becomes a
 * number written to a bin through these four numbers, and both the gesture
 * handler and the renderer have to agree on them exactly. Deriving them twice
 * in two places is how a drag lands on a different breakpoint than the one the
 * marker was drawn at.
 */
data class TablePlotFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Whether the plot has room to draw; a collapsed frame divides by zero. */
    val usable: Boolean get() = width > 0f && height > 0f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Fraction across the frame, increasing rightwards with the data axis. */
    fun fractionX(x: Float): Float = (x - left) / width

    /** Fraction up the frame, increasing upwards with the data axis. */
    fun fractionY(y: Float): Float = (bottom - y) / height
}

/**
 * One touch on the plot, resolved into either an edit or a view change.
 *
 * The two cannot be told apart when the first finger lands: a pinch *begins* as
 * a one-finger touch and only declares itself when the second finger arrives,
 * by which time a drag may already have written a value. This holds the value
 * the touch started on so escalation can put it back — zooming in to read a
 * curve must never be able to change it.
 *
 * A small state machine rather than a pair of flags in the gesture handler,
 * because "did that pinch change my calibration" is not a question a screenshot
 * can answer, and this is where the answer is decided.
 */
class TablePlotTouch(point: Int?, private val original: Double) {
    /** The breakpoint this touch is dragging, or null once it is a view change. */
    var point: Int? = point
        private set

    /** Whether a value has actually been written since the touch began. */
    var wrote: Boolean = false
        private set

    /** Whether this touch has become a pinch, and so can no longer edit. */
    var pinching: Boolean = false
        private set

    /** Record that [point] has been written to, so [escalate] can undo it. */
    fun edited() {
        if (point != null) wrote = true
    }

    /**
     * A second finger landed: the touch becomes a pinch and stops editing.
     *
     * Returns the breakpoint and the value that must be written back to undo
     * what the one-finger phase did, or null when there is nothing to undo.
     * Idempotent — a third finger is still the same pinch and must not re-issue
     * an undo that would overwrite a later edit.
     */
    fun escalate(): Pair<Int, Double>? {
        val undo = if (wrote) point?.let { it to original } else null
        pinching = true
        point = null
        wrote = false
        return undo
    }
}

/** Round-number ladder, scaled to the table's values rather than its axis units. */
fun tablePlotSteps(values: List<Double>): List<Double> {
    val finite = values.filter { it.isFinite() }
    val low = finite.minOrNull() ?: 0.0
    val high = finite.maxOrNull() ?: 0.0
    val scale = maxOf(high - low, maxOf(abs(low), abs(high)) * 0.1)
    val target = if (scale > 0.0) scale / 100.0 else 0.01
    val decade = floor(log10(target)).toInt().coerceIn(-12, 30)
    val ladder = ((decade - 2)..(decade + 2)).flatMap { exponent ->
        listOf(1.0, 2.0, 5.0).map { it * 10.0.pow(exponent) }
    }
    val center = ladder.indices.minBy { abs(log10(ladder[it] / target)) }.coerceIn(2, ladder.lastIndex - 2)
    return ladder.subList(center - 2, center + 3)
}

private fun roundPlotStep(target: Double): Double {
    val magnitude = 10.0.pow(floor(log10(target)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= target } * magnitude
}

/** Preserve the starting value; only the increment is round, never snap the calibration. */
fun tablePlotNudge(value: Double, step: Double, direction: Int): Double {
    require(value.isFinite() && step.isFinite() && step > 0 && direction in listOf(-1, 1))
    return BigDecimal.valueOf(value).add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(direction.toLong()))).toDouble()
}

/** View-only transform. Fractions increase rightwards and upwards, matching the data axes. */
data class TablePlotViewport(val x: TablePlotRange, val y: TablePlotRange, val zoom: Float = 1f) {
    fun transformed(anchorX: Float, anchorY: Float, panX: Float, panY: Float, factor: Float): TablePlotViewport {
        if (!listOf(anchorX, anchorY, panX, panY, factor).all { it.isFinite() } || factor <= 0f) return this
        val nextZoom = (zoom * factor).coerceIn(0.5f, 100f)
        val ratio = nextZoom / zoom
        fun move(range: TablePlotRange, anchor: Float, pan: Float): TablePlotRange {
            val span = (range.max - range.min) / ratio
            val start = range.value(anchor) - (anchor + pan) * span
            return range.window(start, start + span)
        }
        return TablePlotViewport(move(x, anchorX, panX), move(y, anchorY, panY), nextZoom)
    }
}
