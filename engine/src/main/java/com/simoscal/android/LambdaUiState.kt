package com.simoscal.android

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * The full-load enrichment editor: lambda against engine speed, per time-at-load.
 *
 * The map is engine speed across and **time at full load** down: as a pull holds
 * wide-open throttle the ECU walks down the rows, so each row is "how rich, this
 * many seconds in". The screen edits one time-row as a curve with the others
 * ghosted — the same active/ghost interaction the slot curves already use, and
 * for the same reason: a fingertip that could land on any of eight overlapping
 * curves would sometimes move the wrong one, and the wrong one here is fuelling
 * at full load.
 *
 * The danger has a **direction**, which is what makes this different from every
 * other curve in the app. Leaner is hotter: at wide-open throttle this
 * enrichment is what carries heat out of the combustion chamber and off the
 * turbine. So up is dangerous, and the screen draws that as a band rather than
 * leaving it to be inferred from a number.
 *
 * Two bounds, and they are not the same kind of thing:
 *
 * * [leanMax] is the engine's **refusal** — a setpoint at or above it is
 *   rejected outright, never clamped, and the value comes from the engine rather
 *   than a constant here so the band drawn and the bound enforced cannot drift
 *   apart.
 * * [WARN_LAMBDA] is a **warning** — the band starts here, and everything in it
 *   is legal. It is drawn from a fixed value on purpose: it marks where a person
 *   should think, not where the engine acts.
 */

/** Where the warning band starts. Legal above it — this is advice, not a limit. */
const val WARN_LAMBDA: Double = 0.90

/** The lambda granularity a drag snaps to. */
const val LAMBDA_STEP: Double = 0.001

data class LambdaUiState(
    val detail: TableDetail? = null,
    /**
     * The bound the engine refuses at, as the engine reported it.
     *
     * Defaulted to 1.00 to match the engine's own constant, but always overwritten
     * from the payload: a screen whose danger band disagreed with the engine's
     * refusal would either block legal values or forward rejected ones.
     */
    val leanMax: Double = 1.00,
    val richMin: Double = 0.50,
    /** Which time-row is the editable curve — an index into the y axis. */
    val row: Int = 0,
    val draft: List<Double> = emptyList(),
    val loading: Boolean = false,
    val unavailable: String? = null,
    val notice: String? = null,
    val lastApplied: String? = null,
) {

    val committed: List<Double>
        get() = detail?.values?.getOrNull(row).orEmpty()

    /** The rows that are not being edited — drawn ghosted, as context. */
    val ghostRows: List<List<Double>>
        get() = detail?.values.orEmpty().filterIndexed { index, _ -> index != row }

    /** Engine speed across the curve. */
    val rpmAxis: List<Double>
        get() = detail?.xAxis?.values.orEmpty()

    /** Seconds at full load, one per row. */
    val timeAxis: List<Double>
        get() = detail?.yAxis?.values.orEmpty()

    val rowSeconds: Double?
        get() = timeAxis.getOrNull(row)

    val dirty: Boolean
        get() = draft.size == committed.size &&
            draft.indices.any { abs(draft[it] - committed[it]) > 1e-9 }

    val canApply: Boolean
        get() = detail != null && dirty && !loading

    /**
     * The highest lambda a drag will ever set: one step below the refusal.
     *
     * The engine's test is `>=`, so landing *on* the bound is a rejection. Backing
     * off exactly one step means no reachable drag position can compose an edit
     * the engine will refuse — the same argument [BoostCurveModel.maxSettablePsi]
     * makes about the base ceiling.
     */
    val maxSettableLambda: Double
        get() {
            val stepped = floor(leanMax / LAMBDA_STEP + 1e-6) * LAMBDA_STEP
            return if (stepped >= leanMax - 1e-9) stepped - LAMBDA_STEP else stepped
        }

    /** Every staged point sitting in the warning band, moved there or not. */
    val draftInWarningBand: List<Int>
        get() = draft.indices.filter { draft[it] > WARN_LAMBDA + 1e-9 }

    /**
     * Points *this draft* moved into the warning band.
     *
     * The distinction matters more than it looks. Stock is a flat 1.00 map, so
     * every point of an untouched row is already above the warning bound — a
     * card driven by [draftInWarningBand] would therefore accuse someone of a
     * lean setpoint the moment they opened the screen, before they had done
     * anything. A warning that fires on arrival is one people learn to dismiss
     * without reading, which is exactly the wrong habit for this particular
     * band. So the card speaks only about points the person actually moved
     * there, and the standing state of the map is reported separately by
     * [providesNoEnrichment] in its own, calmer words.
     */
    val stagedIntoWarningBand: List<Int>
        get() = draft.indices.filter { index ->
            draft[index] > WARN_LAMBDA + 1e-9 &&
                abs(draft[index] - committed.getOrElse(index) { draft[index] }) > 1e-9
        }

    /**
     * Whether the staged row asks for no enrichment at all across the board.
     *
     * True of the stock map, which is flat 1.00 — worth stating plainly on open,
     * because "this map is doing nothing for you" is the single most useful fact
     * about it and is invisible in a flat line.
     */
    val providesNoEnrichment: Boolean
        get() = draft.isNotEmpty() && draft.all { it >= leanMax - 1e-9 }
}

/** Adopt a freshly read map, starting a clean draft on the current row. */
fun LambdaUiState.withDetail(
    loaded: TableDetail,
    leanMax: Double,
    richMin: Double,
): LambdaUiState {
    val rows = loaded.values.size
    val index = row.coerceIn(0, (rows - 1).coerceAtLeast(0))
    return copy(
        detail = loaded,
        leanMax = leanMax,
        richMin = richMin,
        row = index,
        draft = loaded.values.getOrNull(index).orEmpty(),
        loading = false,
        unavailable = null,
        notice = null,
    )
}

/**
 * Edit a different time-row — refused while the draft is dirty.
 *
 * The slot-switch rule again: the alternative is silently dropping an edit made
 * on the way to another row, and a fuelling change that quietly reverted is
 * exactly the sort of thing found on a datalog rather than on screen.
 */
fun LambdaUiState.selectingRow(index: Int): LambdaUiState = when {
    index == row -> copy(notice = null)
    index !in (detail?.values?.indices ?: IntRange.EMPTY) -> this
    dirty -> copy(
        notice = "Apply or discard the change at ${rowSeconds?.display("%.1f")} s first."
    )
    else -> copy(
        row = index,
        draft = detail?.values?.getOrNull(index).orEmpty(),
        notice = null,
    )
}

/** Move one point by drag: snapped into the legal range, never refused. */
fun LambdaUiState.withDraggedPoint(index: Int, lambda: Double): LambdaUiState {
    if (index !in draft.indices) return this
    return copy(
        draft = draft.toMutableList().also { it[index] = clampLambda(lambda) },
        notice = null,
    )
}

/**
 * Set one point from typed input: validated, never clamped.
 *
 * Emphatically not clamped here of all places. A lean full-load setpoint is the
 * failure this whole domain exists to prevent, and silently correcting one to a
 * safe value would hide that it was ever asked for — the person would believe
 * the calibration says what they typed.
 */
fun LambdaUiState.withTypedPoint(index: Int, lambda: Double): LambdaUiState {
    val refusal = rejectTypedLambda(lambda)
    if (refusal != null) return copy(notice = refusal)
    if (index !in draft.indices) return this
    return copy(
        draft = draft.toMutableList().also { it[index] = lambda },
        notice = null,
    )
}

/** Why a typed lambda cannot be used, or null if it can. */
fun LambdaUiState.rejectTypedLambda(lambda: Double): String? = when {
    lambda.isNaN() || lambda.isInfinite() -> "Enter a lambda value."
    lambda >= leanMax ->
        "${lambda.display("%.3f")} is at or above lambda ${leanMax.display("%.2f")}. At " +
            "full load this map is what carries heat out of the chamber and off the " +
            "turbine, so the engine refuses a setpoint that asks for no enrichment."
    lambda <= richMin ->
        "${lambda.display("%.3f")} is at or below lambda ${richMin.display("%.2f")} — " +
            "richer than any use this calibration has. Check for a mistyped decimal."
    else -> null
}

fun LambdaUiState.clampLambda(lambda: Double): Double {
    if (lambda.isNaN()) return richMin + LAMBDA_STEP
    return max(richMin + LAMBDA_STEP, min(maxSettableLambda, snapToLambdaStep(lambda)))
}

fun snapToLambdaStep(lambda: Double): Double =
    if (lambda.isNaN()) 0.0 else (lambda / LAMBDA_STEP).roundToInt() * LAMBDA_STEP

fun LambdaUiState.discardingDraft(): LambdaUiState = copy(draft = committed, notice = null)

/** Flatten the whole row to one lambda. Typed, so validated rather than clamped. */
fun LambdaUiState.withFlatRow(lambda: Double): LambdaUiState {
    val refusal = rejectTypedLambda(lambda)
    if (refusal != null) return copy(notice = refusal)
    return copy(draft = List(draft.size) { lambda }, notice = null)
}

/**
 * Fold a committed edit's encoded row back into the map.
 *
 * The encoded values, not the requested ones: lambda is stored /1024, so what the
 * bin holds can sit a hair off what was asked for.
 */
fun LambdaUiState.applied(encodedRow: List<Double>, label: String): LambdaUiState {
    val current = detail ?: return this
    val updated = current.copy(
        values = current.values.mapIndexed { index, existing ->
            if (index == row) encodedRow else existing
        }
    )
    return copy(detail = updated, draft = encodedRow, lastApplied = label, notice = null)
}

/** Parse the `lambda_fl` payload: the table plus the engine's own bounds. */
fun parseLambdaPayload(result: JSONObject): Triple<TableDetail, Double, Double>? {
    val table = result.optJSONObject("table") ?: return null
    return Triple(
        TableDetail.fromJson(table),
        result.optDouble("lean_max", 1.00),
        result.optDouble("rich_min", 0.50),
    )
}
