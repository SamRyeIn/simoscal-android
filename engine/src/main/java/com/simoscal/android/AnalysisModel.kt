package com.simoscal.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * The read model for the `analyze_logs` bridge op — pure, Android-free, JVM-testable.
 *
 * Everything here is *parsed*, never *decided*. The engine owns which channel
 * belongs on which panel, which samples are in a pull, what a finding says, and
 * the words printed above each plot; this file turns that JSON into Kotlin types
 * and does no analysis of its own. That split is the whole reason the on-device
 * plots can be trusted to say the same thing as the library's own PNG report:
 * `simoscal.analysis.series` declares the inventory once and both renderers read
 * it (see the engine's `plot_payload`).
 *
 * The one convention this layer does impose is *presentation* order: plots are
 * sorted by id, which is alphabetical, so the screen is a stable list a person
 * can learn the shape of rather than one that reshuffles with the data. The
 * engine already emits them in that order; sorting here as well means a future
 * engine that did not would still render predictably.
 */

/** Finding severity, most to least urgent. Order is the display order. */
enum class Severity(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    ;

    companion object {
        /**
         * Parse the engine's severity string.
         *
         * An unrecognised severity falls to [LOW] rather than being dropped: a
         * finding the app cannot categorise is still a finding, and silently
         * losing one would be worse than filing it low.
         */
        fun from(raw: String): Severity =
            values().firstOrNull { it.label.equals(raw, ignoreCase = true) } ?: LOW
    }
}

/** What a series *is*, which fixes how it is drawn. Mirrors the engine's `Role`. */
enum class SeriesRole {
    /** Measured. Solid, one colour per pull. */
    PRIMARY,

    /** Asked-for — a setpoint, a base table, a target. Dashed, neutral. */
    REFERENCE,

    /** A second measured quantity sharing the panel. Dash-dot, neutral. */
    SECONDARY,

    /** Loaded-but-unsettled samples. Faint dots, because a transient is not a curve. */
    TRANSIENT,
    ;

    companion object {
        fun from(raw: String): SeriesRole = when (raw) {
            "primary" -> PRIMARY
            "reference" -> REFERENCE
            "secondary" -> SECONDARY
            "transient" -> TRANSIENT
            // An unknown role is drawn as a neutral secondary rather than as a
            // measured primary: mistaking a reference for a measurement is the
            // error that would actually mislead someone reading the plot.
            else -> SECONDARY
        }
    }
}

/**
 * What a horizontal threshold line means.
 *
 * None of these is a limit the ECU enforces — they are the lines at which the
 * analysis battery starts paying attention, which is why the screen never paints
 * one in the refusal colour the rest of the app reserves for an engine rejection.
 */
enum class ThresholdTone {
    ZERO,
    WATCH,
    HIGH,
    ;

    companion object {
        fun from(raw: String): ThresholdTone = when (raw) {
            "zero" -> ZERO
            "watch" -> WATCH
            "high" -> HIGH
            else -> ZERO
        }
    }
}

/**
 * One unbroken run of samples, already x-sorted by the engine.
 *
 * Not a `data class`: it holds [FloatArray]s, whose generated `equals` would
 * compare by identity and quietly make two equal segments unequal. Nothing in
 * the UI compares segments — they are keyed by plot id — so the arrays are kept
 * as arrays, which is what the canvas wants to walk.
 *
 * Float rather than Double throughout the plot path: the canvas is in pixels and
 * every value is on its way to one. The engine's own numbers stay double all the
 * way to the JSON; this is a display narrowing at the last possible moment.
 */
class Segment(val x: FloatArray, val y: FloatArray) {
    val size: Int get() = minOf(x.size, y.size)
}

/** One line (or dot cloud) on a panel. */
data class PlotSeries(
    val source: String,
    val role: SeriesRole,
    val label: String,
    /** 1-based pull number, as the findings and the report refer to it. */
    val pull: Int,
    /**
     * The colour slot, assigned by the engine from the pull's *position*.
     *
     * Sent rather than re-derived so a pull that contributes nothing to one
     * panel cannot shift every later pull's colour on that panel alone — "the
     * blue curve" has to mean the same run from one plot to the next.
     */
    val ordinal: Int,
    val segments: List<Segment>,
) {
    val hasData: Boolean get() = segments.any { it.size > 0 }
}

/** A horizontal line at a fixed value, with the meaning its tone carries. */
data class Threshold(val value: Float, val tone: ThresholdTone, val label: String)

/** One set of axes. */
data class PlotPanel(
    val title: String,
    val xLabel: String,
    val yLabel: String,
    val series: List<PlotSeries>,
    val thresholds: List<Threshold>,
    /** The engine's own verdict on whether this panel produced any line. */
    val drawn: Boolean,
)

/**
 * One evidence plot: a stack of panels, plus the copy that goes above it.
 *
 * [description] says which parameters are drawn; [tip] says how to read them.
 * Both come from the engine so the app and the library's report describe the
 * same plot in the same words.
 */
data class AnalysisPlot(
    val id: String,
    val title: String,
    val description: String,
    val tip: String,
    val panels: List<PlotPanel>,
    val drawn: Boolean,
) {
    /** Only the panels worth drawing — an empty panel is reported, never rendered blank. */
    val drawablePanels: List<PlotPanel> get() = panels.filter { it.drawn }

    /** The pulls that appear on this plot, in colour-slot order, for the legend. */
    val pulls: List<Pair<Int, Int>>
        get() = drawablePanels
            .flatMap { it.series }
            .filter { it.role == SeriesRole.PRIMARY && it.hasData }
            .map { it.pull to it.ordinal }
            .distinct()
            .sortedBy { it.second }
}

/** One finding from a check that ran. */
data class Finding(
    val checkId: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val evidence: List<Pair<String, String>>,
    val pullRefs: List<Int>,
)

/**
 * A check that could not run.
 *
 * Rendered as its own section rather than folded away, for the reason the engine
 * emits it at all: a check that did not run is not a check that passed, and the
 * difference is exactly what someone reading a clean report needs to know.
 */
data class SkippedCheck(
    val checkId: String,
    val title: String,
    val reason: String,
    val missingChannels: List<String>,
)

/** One parsed CSV, as the engine's load-quality preflight saw it. */
data class LogSummary(
    val name: String,
    val rows: Int,
    val gearResolution: String,
    val gaps: Int,
    val shortRows: Int,
    val stuckChannels: List<String>,
    val unmappedCount: Int,
)

/** One detected WOT pull. */
data class PullSummary(
    val index: Int,
    val file: String,
    val samples: Int,
    val durationSeconds: Double?,
    /** Actual gear, or null when the log's gear channel could not be resolved. */
    val gear: Int?,
    val rpmMin: Double,
    val rpmMax: Double,
)

/** Everything one `analyze_logs` call produced. */
data class AnalysisReport(
    val logs: List<LogSummary>,
    val pulls: List<PullSummary>,
    val findings: List<Finding>,
    val skipped: List<SkippedCheck>,
    val ran: List<String>,
    val calResolved: Boolean,
    val plots: List<AnalysisPlot>,
    val notes: List<String>,
) {
    fun findingsOf(severity: Severity): List<Finding> = findings.filter { it.severity == severity }

    /** Plots with something on them, in id order — what the screen actually renders. */
    val drawnPlots: List<AnalysisPlot> get() = plots.filter { it.drawn }

    /**
     * Plots the log had no data for.
     *
     * Surfaced by name rather than omitted, for the same reason [skipped] is: an
     * absent plot means an unlogged channel, not a quantity that was fine.
     */
    val undrawnPlots: List<AnalysisPlot> get() = plots.filterNot { it.drawn }

    companion object {

        /** Parse one `analyze_logs` result envelope. Tolerant of absent keys throughout. */
        fun parse(result: JSONObject): AnalysisReport = AnalysisReport(
            logs = result.objects("logs").map { it.toLogSummary() },
            pulls = result.objects("pulls").map { it.toPullSummary() },
            findings = result.objects("findings").map { it.toFinding() },
            skipped = result.objects("skipped").map { it.toSkippedCheck() },
            ran = result.strings("ran"),
            calResolved = result.optBoolean("cal_resolved", false),
            // Sorted by id so the screen's order is alphabetical and stable
            // whatever order the engine happened to send.
            plots = result.objects("plots").map { it.toPlot() }.sortedBy { it.id },
            notes = result.strings("notes"),
        )
    }
}

// ------------------------------------------------------------------- parsing

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun JSONObject.strings(key: String): List<String> = optJSONArray(key).toStrings()

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }.filter { it.isNotEmpty() }
}

private fun JSONArray?.toFloats(): FloatArray {
    if (this == null) return FloatArray(0)
    return FloatArray(length()) { optDouble(it, Double.NaN).toFloat() }
}

private fun JSONObject.toLogSummary() = LogSummary(
    name = optString("name", "(unnamed log)"),
    rows = optInt("n_rows", 0),
    gearResolution = optString("gear_resolution", ""),
    gaps = optInt("n_gaps", 0),
    shortRows = optInt("n_short_rows", 0),
    stuckChannels = optJSONArray("stuck_channels").toStrings(),
    unmappedCount = optInt("unmapped_count", 0),
)

private fun JSONObject.toPullSummary() = PullSummary(
    index = optInt("index", 0),
    file = optString("file", ""),
    samples = optInt("n_samples", 0),
    durationSeconds = if (isNull("duration_s")) null else optDouble("duration_s"),
    // `gear` is null when the log's header did not let the engine resolve the
    // gear offset. It is left null rather than defaulted, because a wrong gear
    // on a pull summary is worse than an absent one.
    gear = if (isNull("gear")) null else optInt("gear"),
    rpmMin = optDouble("rpm_min", Double.NaN),
    rpmMax = optDouble("rpm_max", Double.NaN),
)

private fun JSONObject.toFinding(): Finding {
    val evidence = optJSONObject("evidence")
    return Finding(
        checkId = optString("check_id", ""),
        severity = Severity.from(optString("severity", "")),
        title = optString("title", ""),
        message = optString("message", ""),
        evidence = evidence?.keys()?.asSequence()
            ?.sorted()
            ?.map { key -> key to evidence.opt(key).asDisplayText() }
            ?.toList()
            .orEmpty(),
        pullRefs = optJSONArray("pull_refs")?.let { array ->
            (0 until array.length()).map { array.optInt(it) }
        }.orEmpty(),
    )
}

/**
 * Evidence values arrive as numbers, strings, or booleans and are only ever
 * shown. Formatting stays here rather than in the composable so the same value
 * cannot render two ways in two places.
 */
private fun Any?.asDisplayText(): String = when (this) {
    null, JSONObject.NULL -> "—"
    is Double -> displayMeasured()
    is Float -> toDouble().displayMeasured()
    else -> toString()
}

private fun JSONObject.toSkippedCheck() = SkippedCheck(
    checkId = optString("check_id", ""),
    title = optString("title", ""),
    reason = optString("reason", ""),
    missingChannels = optJSONArray("missing_channels").toStrings(),
)

private fun JSONObject.toPlot(): AnalysisPlot {
    val panels = objects("panels").map { it.toPanel() }
    return AnalysisPlot(
        id = optString("id", ""),
        title = optString("title", ""),
        description = optString("description", ""),
        tip = optString("tip", ""),
        panels = panels,
        // Trust the engine's flag, but never claim drawn without a drawable
        // panel to back it: the screen would render an empty frame.
        drawn = optBoolean("drawn", false) && panels.any { it.drawn },
    )
}

private fun JSONObject.toPanel(): PlotPanel {
    val series = objects("series").map { it.toSeries() }.filter { it.hasData }
    return PlotPanel(
        title = optString("title", ""),
        xLabel = optString("x_label", ""),
        yLabel = optString("y_label", ""),
        series = series,
        thresholds = objects("thresholds").map { it.toThreshold() },
        // Threshold lines alone never make a panel drawable — they are the
        // spec's, not the log's.
        drawn = optBoolean("drawn", false) && series.isNotEmpty(),
    )
}

private fun JSONObject.toSeries() = PlotSeries(
    source = optString("source", ""),
    role = SeriesRole.from(optString("role", "")),
    label = optString("label", ""),
    pull = optInt("pull", 0),
    ordinal = optInt("ordinal", 0),
    segments = objects("segments")
        .map { Segment(it.optJSONArray("x").toFloats(), it.optJSONArray("y").toFloats()) }
        .filter { it.size > 0 },
)

private fun JSONObject.toThreshold() = Threshold(
    value = optDouble("value", 0.0).toFloat(),
    tone = ThresholdTone.from(optString("tone", "")),
    label = optString("label", ""),
)
