package com.simoscal.android

import org.json.JSONObject

/**
 * The logged-pull overlay's read model — pure, Android-free, JVM-testable.
 *
 * What this is *for*: the boost editor draws five calibration curves, and until
 * now nothing on that canvas said where the car actually went. This carries one
 * detected pull's measured boost and the ECU's own setpoint, in the same psi and
 * rpm the curves are drawn in, so the two can be read against each other without
 * leaving the tablet.
 *
 * What it deliberately is **not**: an analysis. There are no findings here, no
 * verdicts, no thresholds and nothing that proposes a calibration change. The
 * overlay draws data behind the curves; every judgement stays with the person
 * doing the editing.
 *
 * Three things arrive already decided by the engine, and re-deciding any of them
 * here would let the canvas and the desktop's own plots disagree about the same
 * pull:
 *
 * * **Which samples are on the trace.** The engine masks to loaded WOT and trims
 *   to the pull's attributed gear before serializing.
 * * **What "boost" means.** Gauge psi — PUT above ambient — computed once, by the
 *   same `boost` plot declaration the desktop PNGs are drawn from.
 * * **Which gear a pull was in.** [OverlayPull.gear] is an *actual* gear, already
 *   resolved from the log's channel header. There is no offset to apply here, and
 *   applying one would double-count the log layer's own correction.
 */

/** One unbroken run of samples. Split at mask holes, so a line never bridges one. */
data class OverlaySegment(val rpm: List<Double>, val values: List<Double>)

/** One trace of one pull: measured boost, or the setpoint it was chasing. */
data class OverlaySeries(
    /** `boost` (measured) or `boost_sp` (what the ECU asked for). */
    val source: String,
    val label: String,
    val segments: List<OverlaySegment>,
) {
    val isSetpoint: Boolean get() = source == SETPOINT_SOURCE

    val hasData: Boolean get() = segments.any { it.rpm.isNotEmpty() }

    companion object {
        const val MEASURED_SOURCE = "boost"
        const val SETPOINT_SOURCE = "boost_sp"
    }
}

/**
 * One detected wide-open-throttle pull, with the traces it contributes.
 *
 * [gearResolved] is carried separately from [gear] rather than collapsed into a
 * nullable: a log whose gear channel the engine could not resolve still yields a
 * perfectly good boost trace, and the chooser should offer it while saying the
 * gear is unknown — rather than either hiding the pull or captioning it with a
 * gear nobody established.
 */
data class OverlayPull(
    val index: Int,
    val file: String,
    val gear: Int?,
    val gearResolved: Boolean,
    val rpmMin: Double,
    val rpmMax: Double,
    val durationSeconds: Double?,
    val sampleCount: Int,
    val series: List<OverlaySeries>,
) {
    val drawn: Boolean get() = series.any { it.hasData }

    val measured: OverlaySeries?
        get() = series.firstOrNull { it.source == OverlaySeries.MEASURED_SOURCE }

    val setpoint: OverlaySeries?
        get() = series.firstOrNull { it.isSetpoint }

    /** How the pull chooser names this run: gear, rpm span, and how long it ran. */
    val caption: String
        get() = buildString {
            append(if (gearResolved && gear != null) "${gear}${gear.ordinalSuffix()} gear" else "Gear unknown")
            append(" · ${rpmMin.roundedRpm()}–${rpmMax.roundedRpm()} rpm")
            durationSeconds?.let { append(" · ${it.display("%.1f")} s") }
        }
}

/** Everything the overlay draws, plus why it cannot when it cannot. */
data class LogOverlayModel(
    val pulls: List<OverlayPull>,
    /**
     * Whether the log carried the channels the trace needs.
     *
     * False is not a failure to read the file — it parsed fine. It means the log
     * has no ambient pressure (or no PUT), and without ambient there is no
     * honest baseline to zero gauge boost against. [missingChannels] names what
     * was absent so the screen can say which, rather than showing a blank canvas.
     */
    val available: Boolean,
    val missingChannels: List<String>,
) {
    val drawablePulls: List<OverlayPull> get() = pulls.filter { it.drawn }

    fun pull(index: Int): OverlayPull? = pulls.firstOrNull { it.index == index }

    companion object {
        fun fromJson(payload: JSONObject): LogOverlayModel {
            val pullsArray = payload.optJSONArray("pulls")
            val pulls = (0 until (pullsArray?.length() ?: 0)).mapNotNull { position ->
                pullsArray?.optJSONObject(position)?.let { entry -> parsePull(entry) }
            }
            val missing = payload.optJSONArray("missing_channels")
            return LogOverlayModel(
                pulls = pulls,
                available = payload.optBoolean("available", false),
                missingChannels = (0 until (missing?.length() ?: 0)).mapNotNull {
                    missing?.optString(it)?.takeIf(String::isNotEmpty)
                },
            )
        }

        private fun parsePull(entry: JSONObject): OverlayPull {
            val seriesArray = entry.optJSONArray("series")
            val series = (0 until (seriesArray?.length() ?: 0)).mapNotNull { position ->
                seriesArray?.optJSONObject(position)?.let { parseSeries(it) }
            }
            return OverlayPull(
                index = entry.optInt("index"),
                file = entry.optString("file", ""),
                // `null` where the engine sent null: an unresolved gear must not
                // arrive as 0, which would read as a real gear on the chooser.
                gear = if (entry.isNull("gear")) null else entry.optInt("gear"),
                gearResolved = entry.optBoolean("gear_resolved", false),
                rpmMin = entry.optDouble("rpm_min", 0.0),
                rpmMax = entry.optDouble("rpm_max", 0.0),
                durationSeconds = if (entry.isNull("duration_s")) null else entry.optDouble("duration_s"),
                sampleCount = entry.optInt("n_samples"),
                series = series,
            )
        }

        private fun parseSeries(entry: JSONObject): OverlaySeries {
            val segmentsArray = entry.optJSONArray("segments")
            val segments = (0 until (segmentsArray?.length() ?: 0)).mapNotNull { position ->
                segmentsArray?.optJSONObject(position)?.let { segment ->
                    OverlaySegment(
                        rpm = segment.doubleList("x"),
                        values = segment.doubleList("y"),
                    )
                }
            }
            return OverlaySeries(
                source = entry.optString("source", ""),
                label = entry.optString("label", ""),
                // A segment whose axes came back different lengths is dropped
                // rather than drawn to whichever is shorter: the pairing is the
                // whole meaning of the sample, and half a pair is not a datum.
                segments = segments.filter { it.rpm.size == it.values.size && it.rpm.isNotEmpty() },
            )
        }
    }
}

private fun Int.ordinalSuffix(): String = when {
    this % 100 in 11..13 -> "th"
    this % 10 == 1 -> "st"
    this % 10 == 2 -> "nd"
    this % 10 == 3 -> "rd"
    else -> "th"
}

private fun Double.roundedRpm(): String = "${Math.round(this)}"
