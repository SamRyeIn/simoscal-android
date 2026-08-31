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
    /**
     * What the three arrays above are in — the **table's** own units.
     *
     * Not [AdviceStaging.units], which describes a different set of numbers: a
     * slot cap is stored in hPa absolute and edited in psi gauge, so labelling
     * these arrays with the editor's units would print an hPa figure and call it
     * psi. Both are sent; each labels only its own.
     */
    val units: String = "",
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

/**
 * How the editor that owns a recommendation pre-loads it — in *that* editor's units.
 *
 * The conversion happens engine-side (`simoscal.advice.review.Staging`) and this
 * class only carries the result, because there is no unit arithmetic anywhere in
 * this app and none may be added here: a slot cap is stored in hPa absolute and
 * edited in psi gauge, and a second implementation of that conversion on a
 * tablet is exactly the drift the dry run exists to prevent.
 *
 * [editor] always names the screen, even when nothing can be pre-loaded onto it,
 * so Show-me has a destination for every queued item. A non-empty [reason] is
 * what says the pre-load is absent, and why — a flag that is one press, a shared
 * axis with no draft to put values in.
 */
data class AdviceStaging(
    val editor: String = "",
    /** Nested rows, the same shape discipline the previews use. */
    val values: List<List<Double>> = emptyList(),
    val units: String = "",
    /** The switch-patch slot this stages onto; 0 when the editor has no slots. */
    val slot: Int = 0,
    /** The row of a per-row editor; -1 when it edits the whole thing. */
    val row: Int = -1,
    /** Which member of a multi-field editor — a limiter's name, a flag's key. */
    val key: String = "",
    /** Why there is nothing to pre-load. Empty whenever a pre-load is possible. */
    val reason: String = "",
) {
    /** Whether Accept can put values into the owning editor's draft. */
    val loadable: Boolean get() = values.isNotEmpty() && reason.isEmpty()

    /** The single row a curve or scalar editor stages; empty for a grid. */
    val curve: List<Double> get() = values.singleOrNull().orEmpty()

    /** The one number a scalar editor stages, or null when it is not one. */
    val scalar: Double? get() = curve.singleOrNull()

    /**
     * The screen that owns this change, or null for an editor this build does
     * not know. Null is not a failure: a newer engine naming a screen this app
     * has not got is a queued item shown without a Show-me, which is better
     * than a button that goes nowhere.
     */
    val destination: Destination?
        get() = when (editor) {
            "boost" -> Destination.BOOST
            "lambda" -> Destination.LAMBDA
            "limiters" -> Destination.LIMITERS
            "slots" -> Destination.SLOTS
            "table" -> Destination.TABLES
            else -> null
        }

    companion object {
        fun fromJson(value: JSONObject?): AdviceStaging {
            if (value == null) return AdviceStaging()
            return AdviceStaging(
                editor = value.optString("editor", ""),
                values = value.optJSONArray("values").asRows(),
                units = value.optString("units", ""),
                slot = value.optInt("slot", 0),
                row = value.optInt("row", -1),
                key = value.optString("key", ""),
                reason = value.optString("reason", ""),
            )
        }
    }
}

/** One guard-approved recommendation, with everything a decision needs. */
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
    val staging: AdviceStaging = AdviceStaging(),
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
                        units = preview.optString("units", ""),
                    ),
                    overlaps = item.adviceStringList("overlaps"),
                    note = item.optString("note", ""),
                    staging = AdviceStaging.fromJson(item.optJSONObject("staging")),
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

/** What a person did with one queued recommendation. There is no third verdict. */
enum class AdviceDecision { ACCEPTED, REJECTED }

/**
 * An accepted recommendation, waiting on the screen that owns it.
 *
 * Accepting **stages**; it does not journal. The edit enters the journal when
 * the person presses Apply on the domain screen, exactly as it does for an edit
 * they composed themselves — so a recommendation gets no shortcut past the
 * review a hand-made change gets.
 */
data class StagedAdvice(
    val id: String,
    val table: AdviceTable,
    val space: String,
    val intent: String,
    val risk: String,
    val staging: AdviceStaging,
) {
    val destination: Destination? get() = staging.destination
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
    /**
     * What has been decided so far, by recommendation id.
     *
     * The decisions are the queue: an item is shown while it has no entry here
     * and gone once it has one, which is what makes a rejection final. Rebuilding
     * the queue as "the ones not yet decided" rather than removing items from a
     * list means a rejected item cannot be resurrected by anything short of a
     * fresh review — including a rotation, which rebuilds the screen from
     * exactly this map.
     */
    val decisions: Map<String, AdviceDecision> = emptyMap(),
    /** The one accepted recommendation currently sitting in an editor's draft. */
    val staged: StagedAdvice? = null,
    /** A refusal or an instruction about the queue itself, shown in place. */
    val queueNotice: String? = null,
    val notice: String? = null,
    val error: UserFacingError? = null,
) {
    val busy: Boolean get() = work != AdviceWork.IDLE

    /** Everything the guards approved, decided or not. */
    val queuedAdvice: List<QueuedAdvice> get() = review?.queued.orEmpty()

    /** The survivors still awaiting a decision, in the order the file stated them. */
    val remaining: List<QueuedAdvice> get() = queuedAdvice.filterNot { it.id in decisions }

    /** The one item on screen. One at a time; there is deliberately no accept-all. */
    val current: QueuedAdvice? get() = remaining.firstOrNull()

    val acceptedCount: Int get() = decisions.count { it.value == AdviceDecision.ACCEPTED }

    val rejectedCount: Int get() = decisions.count { it.value == AdviceDecision.REJECTED }

    /**
     * How many were refused before a person saw them, dropped and malformed together.
     *
     * Always shown, never itemised: somebody has to know Claude said more than
     * they were shown, and showing *what* was refused would put a suggestion the
     * guards rejected back in front of them — which is the one thing the drop is
     * for.
     */
    val refusedCount: Int
        get() = review?.let { it.counts.dropped + it.counts.malformed } ?: 0

    /**
     * Whether every survivor has been decided.
     *
     * Distinct from "there was nothing to review": a reply whose recommendations
     * were all refused arrives here already true, with [refusedCount] non-zero
     * and [queuedAdvice] empty, and the screen says so in different words.
     */
    val queueFinished: Boolean get() = review != null && remaining.isEmpty()

    /**
     * Record a rejection. Free — nothing about it touches the session.
     *
     * A rejected item is not resurrected by re-entering the screen: it stays in
     * [decisions] for as long as the review does, and the review outlives only
     * an unchanged session.
     */
    fun rejecting(item: QueuedAdvice): AdviceUiState = copy(
        decisions = decisions + (item.id to AdviceDecision.REJECTED),
        queueNotice = null,
    )

    /**
     * Accept one recommendation, staging it on the screen that owns it.
     *
     * **One at a time.** A second acceptance is refused while the first is still
     * sitting unapplied in an editor, for the same reason the editors refuse a
     * slot switch on a dirty draft: the alternative is two staged proposals
     * where only one can be seen, and the one out of sight being the one that
     * gets lost. It also keeps the honest ordering — applying anything
     * invalidates this whole review, because every survivor was replayed against
     * the session as it stood before the apply.
     *
     * An item with nothing to pre-load ([AdviceStaging.loadable] false) is still
     * accepted; it just leaves no draft behind, and the engine's own reason says
     * what to do by hand instead.
     */
    fun accepting(item: QueuedAdvice): AdviceUiState {
        val holding = staged
        if (holding != null) {
            return copy(
                queueNotice = "Apply or discard the change staged from " +
                    "${holding.table.id} first — only one accepted " +
                    "recommendation can sit in an editor at a time.",
            )
        }
        val decided = decisions + (item.id to AdviceDecision.ACCEPTED)
        if (!item.staging.loadable) {
            return copy(decisions = decided, queueNotice = item.staging.reason)
        }
        return copy(
            decisions = decided,
            staged = StagedAdvice(
                id = item.id,
                table = item.table,
                space = item.change.space,
                intent = item.intent,
                risk = item.risk,
                staging = item.staging,
            ),
            queueNotice = null,
        )
    }

    /**
     * The owning screen would not take the staged values — put the item back.
     *
     * The session can move between a review and an acceptance (an undo, an axis
     * re-breakpoint), and a draft that cannot be pre-loaded must not leave the
     * recommendation counted as accepted. Returning it to the queue means the
     * only two resting states are "decided" and "still to decide".
     */
    fun stagingRefused(message: String): AdviceUiState {
        val holding = staged ?: return copy(queueNotice = message)
        return copy(
            decisions = decisions - holding.id,
            staged = null,
            queueNotice = message,
        )
    }

    fun queueNoticeDismissed(): AdviceUiState = copy(queueNotice = null)

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
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
        notice = null,
        error = null,
    )

    fun exported(value: ExportedAdviceBundle): AdviceUiState = copy(
        work = AdviceWork.IDLE,
        bundle = value,
        reply = null,
        review = null,
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
        notice = null,
        error = null,
    )

    fun importingReply(): AdviceUiState = copy(
        work = AdviceWork.IMPORTING_REPLY,
        reply = null,
        review = null,
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
        notice = null,
        error = null,
    )

    fun reviewing(file: ImportedFile): AdviceUiState = copy(
        work = AdviceWork.REVIEWING,
        reply = file,
        review = null,
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
        notice = null,
        error = null,
    )

    fun reviewed(value: AdviceReview): AdviceUiState = copy(
        work = AdviceWork.IDLE,
        review = value,
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
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
            // Decisions go with the review that produced them. Every survivor
            // was replayed against the session as it stood, so a verdict on one
            // says nothing once the session has moved — including the accepted
            // one, whose apply is usually what moved it.
            decisions = emptyMap(),
            staged = null,
            queueNotice = null,
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
        decisions = emptyMap(),
        staged = null,
        queueNotice = null,
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

private fun JSONObject.adviceDoubleList(key: String): List<Double> =
    getJSONArray(key).flattenDoubles()

/** Row-major flattening of a preview array.
 *
 * The engine sends a preview in the table's own shape, so a 2-D table arrives as
 * a list of rows, not a flat list of cells — and nearly every table worth a
 * recommendation is 2-D. Reading one row as a double threw
 * `JSONException: Value [1,1,...] cannot be converted to double` and failed the
 * whole import. Row-major is the order the change's own `array` operand already
 * uses, so a preview and the values that produced it read the same way round.
 */
private fun JSONArray.flattenDoubles(): List<Double> =
    (0 until length()).flatMap { index ->
        val element = get(index)
        if (element is JSONArray) element.flattenDoubles() else listOf(getDouble(index))
    }

/**
 * A payload array as rows.
 *
 * The engine sends every values array in the table's own shape, so a grid
 * arrives as a list of rows and a curve as a list holding one row. A bare flat
 * array is read as a single row rather than as N rows of one value — that is
 * what it means everywhere the engine sends one.
 */
private fun JSONArray?.asRows(): List<List<Double>> {
    if (this == null || length() == 0) return emptyList()
    if (get(0) !is JSONArray) return listOf((0 until length()).map { getDouble(it) })
    return (0 until length()).map { index ->
        val row = getJSONArray(index)
        (0 until row.length()).map { column -> row.getDouble(column) }
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { index -> getJSONObject(index) }

/**
 * Put the accepted recommendation into the editor that owns it, if this is it.
 *
 * Called from every editor's *load* path rather than from Accept, because an
 * editor can only be pre-loaded once it has a model to pre-load into — and the
 * screen that owns a change is almost never the one open when Accept is pressed.
 * So Accept records the staged item, and the owning screen picks it up when it
 * next reads its values. A screen that is not the target is left alone.
 *
 * Nothing here converts anything. Every value was restated engine-side in the
 * units of the editor named by [AdviceStaging.editor] (see
 * `simoscal.advice.review.Staging`), so this only has to check that the shape
 * still fits and put it in. A shape that does not fit is a refusal, not a
 * best-effort partial pre-load: the session has moved since the review, and the
 * recommendation goes back in the queue saying so.
 */
fun EditorUiState.preloadingStagedAdvice(): EditorUiState {
    val item = advice.staged ?: return this
    val staging = item.staging
    val note = "Staged from recommendation ${item.id}. Review it, then Apply."

    fun refused(why: String): EditorUiState = copy(advice = advice.stagingRefused(why))

    return when (staging.editor) {
        "boost" -> {
            val model = boost.model ?: return this
            if (model.curve(staging.slot) == null) {
                return refused("This session has no slot ${staging.slot} to stage onto.")
            }
            val switched = boost.selectingSlot(staging.slot)
            if (switched.activeSlot != staging.slot) {
                return refused(
                    "Slot ${boost.activeSlot} has an unapplied change, so the " +
                        "recommendation for slot ${staging.slot} was not staged."
                )
            }
            if (staging.curve.size != switched.draft.size) {
                return refused(
                    "The recommendation states ${staging.curve.size} breakpoints " +
                        "and this slot now has ${switched.draft.size} — the rpm " +
                        "axis moved since the review."
                )
            }
            copy(boost = switched.copy(draft = staging.curve, notice = note))
        }

        "lambda" -> {
            val detail = lambda.detail ?: return this
            val row = detail.values.getOrNull(staging.row)
                ?: return refused(
                    "The recommendation names time-row ${staging.row}, which this " +
                        "map no longer has."
                )
            if (staging.curve.size != row.size) {
                return refused(
                    "The recommendation states ${staging.curve.size} values and " +
                        "the row now holds ${row.size} — the map changed since " +
                        "the review."
                )
            }
            copy(lambda = lambda.copy(row = staging.row, draft = staging.curve, notice = note))
        }

        "limiters" -> {
            if (limiters.model == null) return this
            val value = staging.scalar
                ?: return refused("The recommendation does not state a single limiter value.")
            when (staging.key) {
                "speed" -> copy(limiters = limiters.copy(speedDraft = value, notice = note))
                "static" -> copy(limiters = limiters.copy(staticRevDraft = value, notice = note))
                "soft", "medium", "hard" -> {
                    val index = when (staging.key) {
                        "soft" -> LimitersModel.SOFT
                        "medium" -> LimitersModel.MEDIUM
                        else -> LimitersModel.HARD
                    }
                    if (index !in limiters.revDraft.indices) {
                        return refused(
                            "This session has no cylinder-cut trio to stage the " +
                                "${staging.key} level onto."
                        )
                    }
                    copy(
                        limiters = limiters.copy(
                            revDraft = limiters.revDraft.toMutableList()
                                .also { it[index] = value },
                            notice = note,
                        )
                    )
                }
                else -> refused("This build does not know the limiter '${staging.key}'.")
            }
        }

        "table" -> {
            // The generic editor opens one table at a time, so this waits until
            // the right one is open rather than refusing: Show-me is what gets
            // the person there, and arriving on the wrong table is a step in
            // that journey, not a failure of it.
            //
            // Known gap: the pedal maps are not domain-owned, so a pedal
            // recommendation routes here rather than to the Pedal screen that
            // draws them as curves. The grid it lands in is the right table and
            // the right values — it is just the plainer of the two editors that
            // can write them.
            val detail = tables.detail ?: return this
            if (detail.summary.name != item.table.name) return this
            val shapeFits = staging.values.size == detail.values.size &&
                staging.values.indices.all { staging.values[it].size == detail.values[it].size }
            if (!shapeFits) {
                return refused(
                    "The recommendation states a ${staging.values.size}×" +
                        "${staging.values.firstOrNull()?.size ?: 0} grid and the " +
                        "table now reads ${detail.values.size}×" +
                        "${detail.values.firstOrNull()?.size ?: 0}."
                )
            }
            copy(tables = tables.copy(draft = staging.values, notice = note))
        }

        // "slots" and anything a newer engine names: nothing to pre-load. The
        // item was accepted with the engine's own instruction for doing it by
        // hand, which is already on screen.
        else -> this
    }
}
