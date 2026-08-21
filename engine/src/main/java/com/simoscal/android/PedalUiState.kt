package com.simoscal.android

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pedal-feel editor: a driver-interpretation map as a curve you can drag.
 *
 * These maps turn pedal travel into a fraction of maximum torque, over engine
 * speed. They are ordinary grids — no unit lies about itself, and no invariant
 * spans two tables — so unlike the boost slots and the limiters they stay on the
 * generic `table_detail` + `edit` path and have **no domain op of their own**.
 * What they get instead is a shape: a 12×12 grid of torque factors read as
 * numbers tells you almost nothing about how a car will feel, and the same grid
 * drawn as pedal-percent against torque-factor tells you immediately.
 *
 * The screen edits **one engine-speed column at a time** — that column *is* the
 * pedal curve at that rpm, which is the thing a person means when they say the
 * pedal feels lazy off idle or twitchy in the midrange. Rows are pedal value,
 * so a curve point is (pedal %, torque factor) and dragging one moves exactly
 * one cell.
 *
 * Everything here is a plain function of state, so the mapping between a curve
 * and its grid can be pinned on the JVM. That mapping is the whole risk: an
 * off-by-one between "the point I dragged" and "the cell that changed" would
 * write a pedal response nobody asked for, and nothing on screen would say so.
 */

/**
 * The logical-name prefix every driver-interpretation map shares.
 *
 * Filtering the ordinary catalog on it, rather than hard-coding a list of seven
 * names, means a map added to the profile later appears here without this file
 * changing — and a map *removed* from it disappears rather than becoming a chip
 * that opens nothing.
 */
const val PEDAL_MAP_PREFIX: String = "pedal_"

/** The factor granularity a drag snaps to. Finer than anyone can feel; coarse enough to type. */
const val PEDAL_STEP: Double = 0.001

/**
 * The highest torque factor this editor will set.
 *
 * The store holds up to ~2.0, but every stock map tops out at exactly 1.0 —
 * "all of the torque the rest of the calibration allows". Above 1.0 the map is
 * asking the torque structure for more than its own maximum, which is not a
 * pedal-feel change at all. The numeric field still refuses rather than clamps,
 * so nobody is quietly given a different number than they typed.
 */
const val PEDAL_MAX_FACTOR: Double = 1.0

data class PedalUiState(
    /** Every driver-interpretation map the profile offers, for the picker. */
    val maps: List<TableSummary> = emptyList(),
    val detail: TableDetail? = null,
    /** Which engine-speed column is being edited — an index into the x axis. */
    val column: Int = 0,
    /** The working curve: one torque factor per pedal row. */
    val draft: List<Double> = emptyList(),
    val loading: Boolean = false,
    val notice: String? = null,
    val lastApplied: String? = null,
) {

    /** The committed curve for [column], as the engine last reported it. */
    val committed: List<Double>
        get() = detail?.values?.map { row -> row.getOrElse(column) { 0.0 } }.orEmpty()

    /**
     * The imported bin's curve for [column] — the ghost.
     *
     * Empty when the engine sent no source values. Deliberately *not* filled in
     * from [committed] in that case: a ghost drawn on top of the working curve
     * would say "nothing has changed here", which is a claim this screen would
     * have no evidence for.
     */
    val ghost: List<Double>
        get() = detail?.sourceValues?.map { row -> row.getOrElse(column) { 0.0 } }.orEmpty()

    /** Pedal position (%) for each point of the curve — the y axis of the grid. */
    val pedalAxis: List<Double>
        get() = detail?.yAxis?.values.orEmpty()

    /** The engine speeds the columns stand for. */
    val rpmAxis: List<Double>
        get() = detail?.xAxis?.values.orEmpty()

    val columnRpm: Double?
        get() = rpmAxis.getOrNull(column)

    val dirty: Boolean
        get() = draft.size == committed.size &&
            draft.indices.any { abs(draft[it] - committed[it]) > 1e-9 }

    val canApply: Boolean
        get() = detail != null && dirty && !loading

    /** Whether the open map can be written back from physical units at all. */
    val editable: Boolean
        get() = detail?.summary?.reversible == true && detail.summary.owner.isEmpty()
}

/** Adopt a freshly read table, starting a clean draft on the current column. */
fun PedalUiState.withDetail(loaded: TableDetail): PedalUiState {
    val columns = loaded.xAxis?.values?.size ?: 0
    val index = column.coerceIn(0, (columns - 1).coerceAtLeast(0))
    return copy(
        detail = loaded,
        column = index,
        draft = loaded.values.map { row -> row.getOrElse(index) { 0.0 } },
        loading = false,
        notice = null,
    )
}

/**
 * Move to another engine-speed column — refused while the draft is dirty.
 *
 * The same rule the boost editor applies to a slot switch, for the same reason:
 * the alternative is silently dropping an edit somebody made on the way past.
 */
fun PedalUiState.selectingColumn(index: Int): PedalUiState = when {
    index == column -> copy(notice = null)
    index !in rpmAxis.indices -> this
    dirty -> copy(
        notice = "Apply or discard the change at ${columnRpm?.display("%.0f")} rpm first."
    )
    else -> copy(
        column = index,
        draft = detail?.values?.map { row -> row.getOrElse(index) { 0.0 } }.orEmpty(),
        notice = null,
    )
}

/** Move one curve point by drag: snapped into the legal range, never refused. */
fun PedalUiState.withDraggedPoint(index: Int, factor: Double): PedalUiState {
    if (index !in draft.indices) return this
    return copy(
        draft = draft.toMutableList().also { it[index] = clampPedalFactor(factor) },
        notice = null,
    )
}

/**
 * Set one curve point from typed input: validated, never clamped.
 *
 * The house rule, and it matters as much here as on a boost cap: a typed number
 * is a stated intent, so an out-of-range one leaves the draft alone and says why
 * rather than quietly becoming a different number.
 */
fun PedalUiState.withTypedPoint(index: Int, factor: Double): PedalUiState {
    val refusal = rejectTypedFactor(factor)
    if (refusal != null) return copy(notice = refusal)
    if (index !in draft.indices) return this
    return copy(
        draft = draft.toMutableList().also { it[index] = factor },
        notice = null,
    )
}

fun rejectTypedFactor(factor: Double): String? = when {
    factor.isNaN() || factor.isInfinite() -> "Enter a torque factor between 0 and 1."
    factor < 0.0 -> "A torque factor cannot be negative."
    factor > PEDAL_MAX_FACTOR ->
        "${factor.display("%.3f")} is above 1.000 — a factor of 1 already asks for all " +
            "the torque the rest of the calibration allows, so more is not a pedal change."
    else -> null
}

fun clampPedalFactor(factor: Double): Double =
    if (factor.isNaN()) 0.0 else max(0.0, min(PEDAL_MAX_FACTOR, snapToPedalStep(factor)))

fun snapToPedalStep(factor: Double): Double =
    if (factor.isNaN()) 0.0 else (factor / PEDAL_STEP).roundToInt() * PEDAL_STEP

/** Put the draft back to what the engine holds. */
fun PedalUiState.discardingDraft(): PedalUiState = copy(draft = committed, notice = null)

/** Put the draft back to what the *imported bin* held — undo a session's shaping. */
fun PedalUiState.revertingToSource(): PedalUiState {
    val source = ghost
    if (source.isEmpty()) {
        return copy(notice = "This session has no record of the imported bin's values.")
    }
    return copy(draft = source, notice = null)
}

/**
 * Fold a committed edit's encoded values back into the open table.
 *
 * The encoded grid, not the requested one: a factor is stored /32768, so what
 * the bin holds can sit a hair off what was asked for, and showing the request
 * back would overstate the bin's contents by that much.
 */
fun PedalUiState.applied(encoded: List<List<Double>>, label: String): PedalUiState {
    val current = detail ?: return this
    val updated = current.copy(values = encoded)
    return copy(
        detail = updated,
        draft = encoded.map { row -> row.getOrElse(column) { 0.0 } },
        lastApplied = label,
        notice = null,
    )
}
