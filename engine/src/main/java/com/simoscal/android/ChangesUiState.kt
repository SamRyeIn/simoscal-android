package com.simoscal.android

import org.json.JSONObject

/**
 * The session's running list of changes — what the Changes screen renders.
 *
 * Every field here is read straight off the engine's edit journal and nothing is
 * computed from it. That is deliberate: undo and redo restore the journal
 * *wholesale* rather than popping an entry, so a tally the app accumulated from
 * edit replies would show an undone edit as still made. The engine's copy is the
 * only one that can be right, and re-reading it is cheaper than being wrong
 * about what a person is about to flash.
 *
 * It is a running list, **never a report**. There is no verified flag, no gate
 * verdict and no share path in this file, because a report is only ever the
 * atomic product of a build's gate run (CR-20260724-02) — the Build screen owns
 * that and this screen must never look like it does.
 */

/** How one journaled edit turned out. Mirrors the engine's verdict vocabulary. */
enum class Verdict {
    /** Bytes staged. */
    APPLIED,

    /** The target was already met; nothing was staged. */
    UNCHANGED,

    /** A guard declined to lower or alter a value. */
    GUARDED_SKIP,

    /** A guard rejected the write outright. */
    BLOCKED,

    /** Deliberately not done; the reason is recorded. */
    SKIPPED,

    /** A bulk-recipe skip a later write in this session covers. */
    SUPERSEDED,

    /** A verdict this build does not know — newer engine, older app. */
    UNKNOWN;

    /**
     * Whether a reviewer has to read this row before flashing.
     *
     * [SUPERSEDED] is pointedly absent: it *is* a skip, but a later write in the
     * same session stands in its place, so nothing was held back and flagging it
     * would train someone to scroll past the section that matters.
     */
    val needsEyes: Boolean
        get() = this == GUARDED_SKIP || this == BLOCKED || this == SKIPPED

    companion object {
        fun parse(raw: String): Verdict = when (raw) {
            "applied" -> APPLIED
            "unchanged" -> UNCHANGED
            "guarded_skip" -> GUARDED_SKIP
            "blocked" -> BLOCKED
            "skipped" -> SKIPPED
            "superseded" -> SUPERSEDED
            else -> UNKNOWN
        }
    }
}

/**
 * One journaled change, as flat text.
 *
 * [before] and [after] arrive already narrowed to the rows that moved — a
 * whole-grid `min..max` hides a one-row edit completely, and one row is exactly
 * what the boost editor writes — so this class never summarizes values itself.
 *
 * [touched] is the engine's own measurement of whether bytes moved, not an
 * inference from [verdict]. The two can disagree honestly: a write whose target
 * already matched stages nothing and is still `applied`.
 */
data class ChangeEntry(
    val label: String,
    val name: String,
    val space: String,
    val kind: String,
    val scope: String,
    val verdict: Verdict,
    val units: String,
    val intent: String,
    val detail: String,
    val warning: String,
    val before: String,
    val after: String,
    val cellsChanged: Int,
    val touched: Boolean,
    /** Which later writes cover this skip, when the engine marked it superseded. */
    val supersededBy: String,
) {

    /** Whether there is a before → after pair worth drawing an arrow between. */
    val hasValues: Boolean
        get() = before.isNotBlank() && after.isNotBlank()

    /**
     * The line explaining this row: the author's intent, with the engine's own
     * detail appended when it added one. Same order the reports use.
     */
    val why: String
        get() = listOf(intent, detail).filter { it.isNotBlank() }.joinToString(" — ")

    /** Whether a reviewer must read this row — a held-back verdict or a warning. */
    val needsEyes: Boolean
        get() = verdict.needsEyes || warning.isNotBlank()

    companion object {
        fun fromJson(json: JSONObject): ChangeEntry = ChangeEntry(
            label = json.optString("label"),
            name = json.optString("name"),
            space = json.optString("space", ""),
            kind = json.optString("kind", ""),
            scope = json.optString("scope", ""),
            verdict = Verdict.parse(json.optString("verdict")),
            units = json.optString("units", ""),
            intent = json.optString("intent", ""),
            detail = json.optString("detail", ""),
            warning = json.optString("warning", ""),
            before = json.optString("before", ""),
            after = json.optString("after", ""),
            cellsChanged = json.optInt("cells_changed", 0),
            touched = json.optBoolean("touched", false),
            supersededBy = json.optString("superseded_by", ""),
        )
    }
}

data class ChangesUiState(
    val entries: List<ChangeEntry> = emptyList(),
    /** Verdict tally as the engine counted it — not re-derived from [entries]. */
    val counts: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    /**
     * Whether the journal has been read at least once for this session.
     *
     * Separate from `entries.isEmpty()` because the two states must not draw the
     * same: "no edits yet" is a fact about the session, "not read yet" is a fact
     * about the screen, and showing the first before the read lands would tell
     * someone their edits are gone.
     */
    val loaded: Boolean = false,
    val notice: String? = null,
) {

    /** The rows a reviewer must not miss, in journal order. */
    val attention: List<ChangeEntry>
        get() = entries.filter { it.needsEyes }

    /** Edits that measurably moved bytes — the "you changed N things" number. */
    val appliedCount: Int
        get() = entries.count { it.verdict == Verdict.APPLIED && it.touched }

    /**
     * Distinct tables this session has written, in first-touch order.
     *
     * Counted by table rather than by entry because two edits to one map are one
     * changed table on the bin, and the bin is what gets flashed.
     */
    val changedTables: List<String>
        get() = entries.filter { it.verdict == Verdict.APPLIED && it.touched }
            .map { it.label }
            .distinct()

    /** Nothing recorded yet, and we know it — the honest empty state. */
    val isEmpty: Boolean
        get() = loaded && entries.isEmpty()

    fun loading(): ChangesUiState = copy(loading = true, notice = null)

    fun withEntries(entries: List<ChangeEntry>, counts: Map<String, Int>): ChangesUiState =
        copy(entries = entries, counts = counts, loading = false, loaded = true, notice = null)

    /**
     * A read that failed leaves the *previous* list on screen under a notice.
     *
     * Blanking it would be worse than stale: the screen's whole job is to say
     * what this session has changed, and an empty list is a specific, wrong claim
     * about that. The notice says the list may be out of date; it never pretends
     * the edits went away.
     */
    fun failed(reason: String): ChangesUiState = copy(loading = false, notice = reason)
}

/** The `entries` array of a `journal` reply. */
internal fun JSONObject.changeEntries(): List<ChangeEntry> {
    val array = optJSONArray("entries") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { ChangeEntry.fromJson(it) }
    }
}

/**
 * The `counts` object of a `journal` reply, in the engine's own key order.
 *
 * `LinkedHashMap` on purpose: the engine orders the tally applied-first, and
 * that order is the one the reports print. Re-sorting it here would put the
 * app's header and the report's header in different orders for the same build.
 */
internal fun JSONObject.changeCounts(): Map<String, Int> {
    val node = optJSONObject("counts") ?: return emptyMap()
    val counts = LinkedHashMap<String, Int>()
    node.keys().forEach { key -> counts[key] = node.optInt(key) }
    return counts
}
