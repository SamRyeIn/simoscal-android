package com.simoscal.android

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * The limiters screen's state and every rule its fingertip obeys — pure.
 *
 * Two limiters live here, and they are grouped on one screen because they share
 * a shape rather than a subject: **neither is a single table**, and both break in
 * the same quiet way if written a table at a time.
 *
 * * The **cylinder-cut trio** must escalate — soft cuts one cylinder every four,
 *   medium one every three, hard two every four — so a trio ordered any other way
 *   asks the ECU to escalate backwards. Dragging clamps at the neighbour; typing
 *   is refused with the engine's own reason.
 * * The **road-speed quartet** is four tables holding one number. The ECU selects
 *   among them, so writing one alone leaves the car limited by an un-written
 *   level. The screen edits it as a single control and the engine writes all four.
 *
 * A note on what the trio is *not*: these are rpm offsets above the patch's own
 * engagement point, not absolute rev limits, and all three sit in the patch's
 * RAL category beside its engagement-rpm pair. The screen says so, because a
 * control captioned "rev limit" that actually moves an offset is how somebody
 * ends up expecting a 7200 rpm cut and getting something else entirely.
 */

/** One limiter scalar as the engine reports it: value plus the words for it. */
data class LimiterValue(
    val name: String,
    val label: String,
    val description: String,
    val units: String,
    val value: Double,
    /** Non-empty when only a domain call may write it. */
    val owner: String,
) {
    companion object {
        fun fromJson(entry: JSONObject): LimiterValue = LimiterValue(
            name = entry.optString("name", ""),
            label = entry.optString("label", ""),
            description = entry.optString("description", ""),
            units = entry.optString("units", ""),
            value = entry.optDouble("value", 0.0),
            owner = entry.optString("owner", ""),
        )
    }
}

/**
 * Everything the limiters screen reads.
 *
 * [revLimits] and [launchControl] are null on a base-only session — a bin
 * without the switch patch genuinely has no trio, which is a *state* of the
 * session rather than a failure, so the screen shows the speed limiter alone.
 */
data class LimitersModel(
    val speedLimiter: List<LimiterValue>,
    /** The standstill rev cap — four transmission variants of one number. */
    val staticRevLimit: List<LimiterValue>,
    /**
     * The engine's own rev limiter, which applies moving or stopped.
     *
     * Carried alongside the cap because the cap is unreadable without it: 3808
     * says nothing until you know the engine itself stops at 6816, and a screen
     * showing one without the other invites reading the cap as the redline.
     */
    val engineRevLimit: Double?,
    val revLimits: List<LimiterValue>?,
    val launchControl: List<LimiterValue>?,
) {
    val hasRevLimits: Boolean get() = !revLimits.isNullOrEmpty()

    /**
     * The one number the whole speed quartet holds, or null when they disagree.
     *
     * Disagreement is not an error to hide: a bin whose four scalars differ was
     * written by something that did not treat them as a set, and the screen says
     * so rather than picking one to show.
     */
    val speedKmh: Double?
        get() {
            val values = speedLimiter.map { it.value }
            if (values.isEmpty()) return null
            return values.first().takeIf { first -> values.all { abs(it - first) < 1e-6 } }
        }

    fun rev(index: Int): Double? = revLimits?.getOrNull(index)?.value

    /** The one number the four standstill scalars hold, or null if they differ. */
    val staticRevRpm: Double?
        get() {
            val values = staticRevLimit.map { it.value }
            if (values.isEmpty()) return null
            return values.first().takeIf { first -> values.all { abs(it - first) < 1e-6 } }
        }

    /** Whether the cap is already at the limiter — nothing left to raise. */
    val staticRevAtLimiter: Boolean
        get() {
            val cap = staticRevRpm ?: return false
            val limit = engineRevLimit ?: return false
            return cap >= limit - 1e-6
        }

    companion object {
        /** Index of each cut level within [revLimits], in escalation order. */
        const val SOFT = 0
        const val MEDIUM = 1
        const val HARD = 2

        fun fromJson(payload: JSONObject): LimitersModel = LimitersModel(
            speedLimiter = payload.limiterList("speed_limiter").orEmpty(),
            staticRevLimit = payload.limiterList("static_rev_limit").orEmpty(),
            engineRevLimit = if (payload.isNull("engine_rev_limit")) {
                null
            } else {
                payload.optDouble("engine_rev_limit")
            },
            revLimits = payload.limiterList("rev_limits"),
            launchControl = payload.limiterList("launch_control"),
        )

        private fun JSONObject.limiterList(key: String): List<LimiterValue>? {
            if (isNull(key)) return null
            val array = optJSONArray(key) ?: return null
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(LimiterValue::fromJson)
            }
        }
    }
}

/**
 * The rpm step the trio's markers move in.
 *
 * The stored field is whole rpm, and a limiter set to the nearest 25 rpm is a
 * decision someone can restate; one set to 3417 is an artifact of where a finger
 * landed.
 */
const val REV_STEP_RPM: Double = 25.0

/** The lowest and highest offset the stored field can hold (0–8160 rpm). */
const val REV_MIN_RPM: Double = 0.0
const val REV_MAX_RPM: Double = 8160.0

/** The road-speed field is stored /128, so this is where it actually tops out. */
const val SPEED_MAX_KMH: Double = 511.99

data class LimitersUiState(
    val model: LimitersModel? = null,
    /** The staged trio, in escalation order. Empty until a model loads. */
    val revDraft: List<Double> = emptyList(),
    /** The staged road-speed limiter, in km/h. */
    val speedDraft: Double? = null,
    /** The staged standstill rev cap, in rpm. */
    val staticRevDraft: Double? = null,
    val loading: Boolean = false,
    val unavailable: String? = null,
    val notice: String? = null,
    val lastApplied: String? = null,
) {

    val committedRev: List<Double>
        get() = model?.revLimits?.map { it.value }.orEmpty()

    val committedSpeed: Double?
        get() = model?.speedKmh

    val revDirty: Boolean
        get() = revDraft.size == committedRev.size &&
            revDraft.indices.any { abs(revDraft[it] - committedRev[it]) > 1e-9 }

    val speedDirty: Boolean
        get() {
            val draft = speedDraft ?: return false
            val committed = committedSpeed ?: return true
            return abs(draft - committed) > 1e-9
        }

    val committedStaticRev: Double?
        get() = model?.staticRevRpm

    val staticRevDirty: Boolean
        get() {
            val draft = staticRevDraft ?: return false
            val committed = committedStaticRev ?: return true
            return abs(draft - committed) > 1e-9
        }

    /** The engine's rev limiter — the ceiling a standstill cap may not exceed. */
    val engineRevLimit: Double?
        get() = model?.engineRevLimit

    val dirty: Boolean get() = revDirty || speedDirty || staticRevDirty

    val canApply: Boolean get() = model != null && dirty && !loading

    /** Whether the trio is even editable in this session. */
    val hasRevLimits: Boolean get() = model?.hasRevLimits == true
}

/** Adopt a freshly read model, starting clean drafts. */
fun LimitersUiState.withModel(loaded: LimitersModel): LimitersUiState = copy(
    model = loaded,
    revDraft = loaded.revLimits?.map { it.value }.orEmpty(),
    speedDraft = loaded.speedKmh,
    staticRevDraft = loaded.staticRevRpm,
    loading = false,
    unavailable = null,
    notice = null,
)

/**
 * The range one cut level may take, given the other two.
 *
 * This is the invariant expressed as an interval rather than as a check, which is
 * what lets a drag *clamp* instead of being refused: soft cannot pass medium,
 * hard cannot fall below medium, and medium is fenced by both. The engine
 * re-checks the same rule on the way in — this only moves the refusal to the
 * fingertip, where it can be felt rather than read.
 */
fun LimitersUiState.revBounds(index: Int): ClosedFloatingPointRange<Double> {
    val draft = revDraft
    val low = when (index) {
        LimitersModel.SOFT -> REV_MIN_RPM
        LimitersModel.MEDIUM -> draft.getOrElse(LimitersModel.SOFT) { REV_MIN_RPM }
        else -> draft.getOrElse(LimitersModel.MEDIUM) { REV_MIN_RPM }
    }
    val high = when (index) {
        LimitersModel.HARD -> REV_MAX_RPM
        LimitersModel.MEDIUM -> draft.getOrElse(LimitersModel.HARD) { REV_MAX_RPM }
        else -> draft.getOrElse(LimitersModel.MEDIUM) { REV_MAX_RPM }
    }
    // A bin whose stored trio is already out of order would otherwise produce an
    // empty range, and `coerceIn` on an empty range throws. Degrade to the fixed
    // field range and let the engine be the one to refuse the write.
    return if (low <= high) low..high else REV_MIN_RPM..REV_MAX_RPM
}

/** Move one cut level by drag: snapped to the step and clamped into [revBounds]. */
fun LimitersUiState.withDraggedRev(index: Int, rpm: Double): LimitersUiState {
    if (index !in revDraft.indices) return this
    val bounds = revBounds(index)
    val snapped = snapToRevStep(rpm).coerceIn(bounds.start, bounds.endInclusive)
    return copy(
        revDraft = revDraft.toMutableList().also { it[index] = snapped },
        notice = null,
    )
}

/**
 * Set one cut level from typed input: validated, never clamped.
 *
 * The project's rule, applied here as everywhere: a typed number is a stated
 * intent, and quietly storing a different one is the thing the safety model
 * forbids most plainly. So an out-of-order entry leaves the draft alone and says
 * which neighbour it collided with.
 */
fun LimitersUiState.withTypedRev(index: Int, rpm: Double): LimitersUiState {
    if (index !in revDraft.indices) return this
    val refusal = rejectTypedRev(index, rpm)
    if (refusal != null) return copy(notice = refusal)
    return copy(
        revDraft = revDraft.toMutableList().also { it[index] = rpm },
        notice = null,
    )
}

/** Why a typed cut level cannot be used, or null if it can. */
fun LimitersUiState.rejectTypedRev(index: Int, rpm: Double): String? {
    if (rpm.isNaN() || rpm.isInfinite()) return "Enter a number of rpm."
    if (rpm < REV_MIN_RPM || rpm > REV_MAX_RPM) {
        return "${rpm.display("%.0f")} rpm is outside the ${REV_MIN_RPM.display("%.0f")}–" +
            "${REV_MAX_RPM.display("%.0f")} rpm the stored field can hold."
    }
    val bounds = revBounds(index)
    if (rpm < bounds.start || rpm > bounds.endInclusive) {
        return "${rpm.display("%.0f")} rpm would put ${REV_LEVEL_NAMES[index]} out of order. " +
            "The cut escalates — soft, then medium, then hard — so this one must sit " +
            "between ${bounds.start.display("%.0f")} and ${bounds.endInclusive.display("%.0f")} rpm."
    }
    return null
}

/** Set the road-speed limiter from typed input: validated, never clamped. */
fun LimitersUiState.withTypedSpeed(kmh: Double): LimitersUiState {
    val refusal = rejectTypedSpeed(kmh)
    if (refusal != null) return copy(notice = refusal)
    return copy(speedDraft = kmh, notice = null)
}

fun LimitersUiState.rejectTypedSpeed(kmh: Double): String? = when {
    kmh.isNaN() || kmh.isInfinite() -> "Enter a road speed in km/h."
    kmh <= 0.0 -> "A speed limiter cannot be zero or negative."
    kmh > SPEED_MAX_KMH ->
        "${kmh.display("%.1f")} km/h is above the ${SPEED_MAX_KMH.display("%.2f")} km/h " +
            "the stored field can hold."
    else -> null
}

fun LimitersUiState.discardingDraft(): LimitersUiState = copy(
    revDraft = committedRev,
    speedDraft = committedSpeed,
    staticRevDraft = committedStaticRev,
    notice = null,
)

/** Set the standstill rev cap from typed input: validated, never clamped. */
fun LimitersUiState.withTypedStaticRev(rpm: Double): LimitersUiState {
    val refusal = rejectTypedStaticRev(rpm)
    if (refusal != null) return copy(notice = refusal)
    return copy(staticRevDraft = rpm, notice = null)
}

/**
 * Why a typed standstill cap cannot be used, or null if it can.
 *
 * The ceiling is the engine's *own* rev limiter, not the field's range. A cap
 * above the limiter could never be reached, so it would change nothing except
 * what the calibration appears to say — and asking for one is a sign of
 * expecting this control to raise the redline, which it does not do.
 */
fun LimitersUiState.rejectTypedStaticRev(rpm: Double): String? {
    if (rpm.isNaN() || rpm.isInfinite()) return "Enter an engine speed in rpm."
    if (rpm <= 0) return "A rev cap cannot be zero or negative."
    val limit = engineRevLimit
    if (limit != null && rpm > limit + 1e-6) {
        return "${rpm.display("%.0f")} rpm is above this engine's own rev limiter of " +
            "${limit.display("%.0f")} rpm, which applies whether the car is moving or " +
            "not. A standstill cap above it could never be reached. Raising the rev " +
            "limiter itself is a separate change this control does not make."
    }
    return null
}

/** Snap to [REV_STEP_RPM]. Applied to drags only — a typed number is taken as typed. */
fun snapToRevStep(rpm: Double): Double =
    if (rpm.isNaN()) 0.0 else (rpm / REV_STEP_RPM).roundToInt() * REV_STEP_RPM

/** How the screen names each cut level, in escalation order. */
val REV_LEVEL_NAMES: List<String> = listOf("soft", "medium", "hard")

/** What each level actually does, straight from the patch's own description. */
val REV_LEVEL_EFFECTS: List<String> = listOf(
    "cuts fuel and spark to 1 cylinder every 4",
    "cuts fuel and spark to 1 cylinder every 3",
    "cuts fuel and spark to 2 cylinders every 4",
)

/**
 * Where a marker sits on the rpm strip, as a 0–1 fraction.
 *
 * The strip spans the full encodable range rather than the trio's own span, so
 * the markers do not rearrange themselves as they are dragged — a scale that
 * rescaled under the finger would make every drag feel like it moved the wrong
 * distance.
 */
fun revFraction(rpm: Double): Float =
    (((rpm - REV_MIN_RPM) / (REV_MAX_RPM - REV_MIN_RPM)).toFloat()).coerceIn(0f, 1f)

/** The inverse of [revFraction] — the rpm a fingertip at this fraction asks for. */
fun revRpmAt(fraction: Float): Double =
    REV_MIN_RPM + max(0f, min(1f, fraction)).toDouble() * (REV_MAX_RPM - REV_MIN_RPM)
