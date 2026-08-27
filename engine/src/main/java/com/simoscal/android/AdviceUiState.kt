package com.simoscal.android

import org.json.JSONArray
import org.json.JSONObject

/** Work currently moving through the off-device advice courier. */
enum class AdviceWork { IDLE, IMPORTING_LOGS, EXPORTING, IMPORTING_REPLY, REVIEWING }

/** The engine-written context bundle and the summary shown before it is shared. */
data class ExportedAdviceBundle(
    val path: String,
    val sha256: String,
    val bytes: Long,
    val summary: AdviceBundleSummary,
) {
    val shortHash: String get() = sha256.take(12)

    companion object {
        fun fromJson(value: JSONObject): ExportedAdviceBundle {
            val summary = value.getJSONObject("summary")
            return ExportedAdviceBundle(
                path = value.getString("path"),
                sha256 = value.getString("sha256"),
                bytes = value.getLong("bytes"),
                summary = AdviceBundleSummary(
                    bundleVersion = summary.getInt("bundle_version"),
                    profile = summary.getString("profile"),
                    tables = summary.getInt("tables"),
                    journalEntries = summary.getInt("journal_entries"),
                    logs = summary.adviceStringList("logs"),
                    pulls = summary.getInt("pulls"),
                    findings = summary.getInt("findings"),
                ),
            )
        }
    }
}

data class AdviceBundleSummary(
    val bundleVersion: Int,
    val profile: String,
    val tables: Int,
    val journalEntries: Int,
    val logs: List<String>,
    val pulls: Int,
    val findings: Int,
)

data class AdviceReviewCounts(
    val queued: Int,
    val dropped: Int,
    val malformed: Int,
    val total: Int,
)

data class AdviceTable(
    val name: String,
    val id: String,
    val description: String,
    val label: String,
)

data class AdvicePreview(
    val before: List<Double>,
    val requested: List<Double>,
    val encoded: List<Double>,
    val quantized: Boolean,
    val maxAbsQuantization: Double,
    val warning: String,
)

sealed interface AdviceSelection {
    val kind: String

    data object All : AdviceSelection { override val kind = "all" }
    data class Row(val index: Int) : AdviceSelection { override val kind = "row" }
    data class Column(val index: Int) : AdviceSelection { override val kind = "col" }
    data class Region(
        val firstRow: Int,
        val lastRow: Int,
        val firstColumn: Int,
        val lastColumn: Int,
    ) : AdviceSelection { override val kind = "region" }
    data class Cells(val cells: List<CellRef>) : AdviceSelection { override val kind = "cells" }
}

sealed interface AdviceArray {
    data class Vector(val values: List<Double>) : AdviceArray
    data class Grid(val values: List<List<Double>>) : AdviceArray
}

data class AdviceChange(
    val space: String,
    val operation: String,
    val selection: AdviceSelection,
    val value: Double?,
    val array: AdviceArray?,
)

/** One guard-approved recommendation. U7 owns rendering and decisions. */
data class QueuedAdvice(
    val id: String,
    val table: AdviceTable,
    val change: AdviceChange,
    val intent: String,
    val evidence: String,
    val risk: String,
    val confidence: String,
    val prediction: String,
    val routedVia: String,
    val preview: AdvicePreview,
    val overlaps: List<String>,
    val note: String,
)

data class DroppedAdvice(
    val id: String,
    val table: AdviceTable,
    val reason: String,
)

data class AdviceProblem(val where: String, val field: String, val message: String)

data class MalformedAdvice(
    val index: Int,
    val id: String?,
    val problems: List<AdviceProblem>,
)

/** The dry-run review result. It is read-only; nothing here is an applied edit. */
data class AdviceReview(
    val schemaVersion: Int,
    val summary: String,
    val counts: AdviceReviewCounts,
    val queued: List<QueuedAdvice>,
    val dropped: List<DroppedAdvice>,
    val malformed: List<MalformedAdvice>,
) {
    companion object {
        fun fromJson(value: JSONObject): AdviceReview {
            val countsJson = value.getJSONObject("counts")
            val queued = value.getJSONArray("queued").objects().map { item ->
                val preview = item.getJSONObject("preview")
                QueuedAdvice(
                    id = item.getString("id"),
                    table = item.getJSONObject("table").toAdviceTable(),
                    change = item.getJSONObject("change").toAdviceChange(),
                    intent = item.getString("intent"),
                    evidence = item.getString("evidence"),
                    risk = item.getString("risk"),
                    confidence = item.getString("confidence"),
                    prediction = item.getString("prediction"),
                    routedVia = item.getString("routed_via"),
                    preview = AdvicePreview(
                        before = preview.adviceDoubleList("before"),
                        requested = preview.adviceDoubleList("requested"),
                        encoded = preview.adviceDoubleList("encoded"),
                        quantized = preview.getBoolean("quantized"),
                        maxAbsQuantization = preview.getDouble("max_abs_quantization"),
                        warning = preview.optString("warning", ""),
                    ),
                    overlaps = item.adviceStringList("overlaps"),
                    note = item.optString("note", ""),
                )
            }
            val dropped = value.getJSONArray("dropped").objects().map { item ->
                DroppedAdvice(
                    id = item.getString("id"),
                    table = item.getJSONObject("table").toAdviceTable(),
                    reason = item.getString("reason"),
                )
            }
            val malformed = value.getJSONArray("malformed").objects().map { item ->
                MalformedAdvice(
                    index = item.getInt("index"),
                    id = item.optString("id").takeIf { it.isNotEmpty() },
                    problems = item.getJSONArray("problems").objects().map { problem ->
                        AdviceProblem(
                            where = problem.getString("where"),
                            field = problem.getString("field"),
                            message = problem.getString("message"),
                        )
                    },
                )
            }
            val counts = AdviceReviewCounts(
                queued = countsJson.getInt("queued"),
                dropped = countsJson.getInt("dropped"),
                malformed = countsJson.getInt("malformed"),
                total = countsJson.getInt("total"),
            )
            require(counts.queued == queued.size) { "queued count does not match queued records" }
            require(counts.dropped == dropped.size) { "dropped count does not match dropped records" }
            require(counts.malformed == malformed.size) { "malformed count does not match malformed records" }
            require(counts.total == queued.size + dropped.size + malformed.size) {
                "total count does not match review records"
            }
            return AdviceReview(
                schemaVersion = value.getInt("schema_version"),
                summary = value.optString("summary", ""),
                counts = counts,
                queued = queued,
                dropped = dropped,
                malformed = malformed,
            )
        }
    }
}

/**
 * Pure state for the courier: session context out, recommendations in.
 *
 * Imported replies and reviews are deliberately not retained when any bundle
 * input or session value changes. The current recommendations schema identifies
 * the source bin and XDF, not the mutable working session, so clearing stale
 * state here is the only honest app-side rule until that schema carries a
 * session-state fingerprint.
 */
data class AdviceUiState(
    val logs: List<ImportedFile> = emptyList(),
    val notes: String = "",
    val work: AdviceWork = AdviceWork.IDLE,
    val bundle: ExportedAdviceBundle? = null,
    val reply: ImportedFile? = null,
    val review: AdviceReview? = null,
    val notice: String? = null,
    val error: UserFacingError? = null,
) {
    val busy: Boolean get() = work != AdviceWork.IDLE

    fun importingLogs(): AdviceUiState = copy(work = AdviceWork.IMPORTING_LOGS, error = null)

    fun withLogs(files: List<ImportedFile>): AdviceUiState {
        val merged = (logs + files).distinctBy { it.sha256 }
        return if (merged == logs) copy(work = AdviceWork.IDLE)
        else changedBundleInput(logs = merged)
    }

    fun withoutLog(file: ImportedFile): AdviceUiState =
        changedBundleInput(logs = logs.filterNot { it.sha256 == file.sha256 })

    fun withNotes(value: String): AdviceUiState =
        if (value == notes) this else changedBundleInput(notes = value)

    fun exporting(): AdviceUiState = copy(
        work = AdviceWork.EXPORTING,
        bundle = null,
        reply = null,
        review = null,
        notice = null,
        error = null,
    )

    fun exported(value: ExportedAdviceBundle): AdviceUiState = copy(
        work = AdviceWork.IDLE,
        bundle = value,
        reply = null,
        review = null,
        notice = null,
        error = null,
    )

    fun importingReply(): AdviceUiState = copy(
        work = AdviceWork.IMPORTING_REPLY,
        reply = null,
        review = null,
        notice = null,
        error = null,
    )

    fun reviewing(file: ImportedFile): AdviceUiState = copy(
        work = AdviceWork.REVIEWING,
        reply = file,
        review = null,
        notice = null,
        error = null,
    )

    fun reviewed(value: AdviceReview): AdviceUiState = copy(
        work = AdviceWork.IDLE,
        review = value,
        notice = null,
        error = null,
    )

    fun failed(value: UserFacingError): AdviceUiState = copy(work = AdviceWork.IDLE, error = value)

    fun errorDismissed(): AdviceUiState = copy(error = null)

    fun invalidatedBySessionChange(): AdviceUiState {
        if (bundle == null && reply == null && review == null) return this
        return copy(
            work = AdviceWork.IDLE,
            bundle = null,
            reply = null,
            review = null,
            notice = "Session changed. Export a new bundle before using recommendations.",
            error = null,
        )
    }

    private fun changedBundleInput(
        logs: List<ImportedFile> = this.logs,
        notes: String = this.notes,
    ): AdviceUiState = copy(
        logs = logs,
        notes = notes,
        work = AdviceWork.IDLE,
        bundle = null,
        reply = null,
        review = null,
        notice = null,
        error = null,
    )
}

private fun JSONObject.toAdviceTable() = AdviceTable(
    name = getString("name"),
    id = getString("id"),
    description = getString("description"),
    label = getString("label"),
)

private fun JSONObject.toAdviceChange(): AdviceChange {
    val selectionJson = getJSONObject("selection")
    val args = selectionJson.optJSONArray("args")
    val selection = when (val kind = selectionJson.getString("kind")) {
        "all" -> AdviceSelection.All
        "row" -> AdviceSelection.Row(args!!.getInt(0))
        "col" -> AdviceSelection.Column(args!!.getInt(0))
        "region" -> AdviceSelection.Region(
            firstRow = args!!.getInt(0),
            lastRow = args.getInt(1),
            firstColumn = args.getInt(2),
            lastColumn = args.getInt(3),
        )
        "cells" -> AdviceSelection.Cells(
            (0 until args!!.length()).map { index ->
                val cell = args.getJSONArray(index)
                CellRef(cell.getInt(0), cell.getInt(1))
            }
        )
        else -> error("unknown advice selection kind $kind")
    }
    val array = optJSONArray("array")?.let { values ->
        if (values.length() > 0 && values.get(0) is JSONArray) {
            AdviceArray.Grid(
                (0 until values.length()).map { row ->
                    val cells = values.getJSONArray(row)
                    (0 until cells.length()).map { col -> cells.getDouble(col) }
                }
            )
        } else {
            AdviceArray.Vector((0 until values.length()).map { index -> values.getDouble(index) })
        }
    }
    return AdviceChange(
        space = getString("space"),
        operation = getString("operation"),
        selection = selection,
        value = opt("value").let { if (it is Number) it.toDouble() else null },
        array = array,
    )
}

private fun JSONObject.adviceStringList(key: String): List<String> {
    val array = getJSONArray(key)
    return (0 until array.length()).map { index -> array.getString(index) }
}

private fun JSONObject.adviceDoubleList(key: String): List<Double> {
    val array = getJSONArray(key)
    return (0 until array.length()).map { index -> array.getDouble(index) }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { index -> getJSONObject(index) }
