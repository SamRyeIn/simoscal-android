package com.simoscal.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The changes list's rules.
 *
 * Two of them carry the screen. **An unknown verdict never reads as applied** —
 * a newer engine's verdict rendered green would tell someone a write landed when
 * nobody knows whether it did. And **a failed refresh never blanks the list** —
 * this screen's entire job is to say what the session changed, so replacing that
 * with an empty list is not a degraded answer, it is a confident wrong one.
 */
class ChangesUiStateTest {

    private fun entry(
        label: String = "`C_PRS_IM_SP_MAX` — Maximum requested intake-manifold pressure setpoint",
        name: String = "prs_im_sp_max",
        verdict: String = "applied",
        scope: String = "table",
        units: String = "hPa",
        intent: String = "raise the ceiling for the IS20",
        detail: String = "",
        warning: String = "",
        before: String = "2400",
        after: String = "2700",
        cellsChanged: Int = 1,
        touched: Boolean = true,
        supersededBy: String = "",
    ) = ChangeEntry.fromJson(
        JSONObject()
            .put("space", "base")
            .put("label", label)
            .put("name", name)
            .put("kind", "table")
            .put("scope", scope)
            .put("verdict", verdict)
            .put("units", units)
            .put("intent", intent)
            .put("detail", detail)
            .put("warning", warning)
            .put("before", before)
            .put("after", after)
            .put("cells_changed", cellsChanged)
            .put("touched", touched)
            .apply { if (supersededBy.isNotBlank()) put("superseded_by", supersededBy) }
    )

    private fun state(vararg entries: ChangeEntry, counts: Map<String, Int> = emptyMap()) =
        ChangesUiState().withEntries(entries.toList(), counts)

    // ------------------------------------------------------------- verdicts

    @Test
    fun `every engine verdict parses to its own case`() {
        assertEquals(Verdict.APPLIED, Verdict.parse("applied"))
        assertEquals(Verdict.UNCHANGED, Verdict.parse("unchanged"))
        assertEquals(Verdict.GUARDED_SKIP, Verdict.parse("guarded_skip"))
        assertEquals(Verdict.BLOCKED, Verdict.parse("blocked"))
        assertEquals(Verdict.SKIPPED, Verdict.parse("skipped"))
        assertEquals(Verdict.SUPERSEDED, Verdict.parse("superseded"))
    }

    @Test
    fun `a verdict this build does not know is never mistaken for applied`() {
        val unknown = Verdict.parse("quantum_superposition")
        assertEquals(Verdict.UNKNOWN, unknown)
        // The two things the screen would otherwise infer from it: that bytes
        // moved, and that nobody needs to look. Neither may be assumed.
        assertFalse(unknown.needsEyes)
        assertEquals(0, state(entry(verdict = "quantum_superposition")).appliedCount)
    }

    @Test
    fun `held-back verdicts are the ones that need eyes`() {
        assertTrue(Verdict.BLOCKED.needsEyes)
        assertTrue(Verdict.GUARDED_SKIP.needsEyes)
        assertTrue(Verdict.SKIPPED.needsEyes)
        assertFalse(Verdict.APPLIED.needsEyes)
        assertFalse(Verdict.UNCHANGED.needsEyes)
    }

    @Test
    fun `a superseded skip is not flagged as held back`() {
        // It is a skip, but a later write in this same session stands in its
        // place. Flagging it would train someone to scroll past the section.
        assertFalse(Verdict.SUPERSEDED.needsEyes)
        val superseded = entry(verdict = "superseded", supersededBy = "prs_im_sp_max")
        assertTrue(state(superseded).attention.isEmpty())
    }

    @Test
    fun `a warning needs eyes even on an applied entry`() {
        val warned = entry(warning = "this ceiling is above what the IS20 has been logged holding")
        assertTrue(warned.needsEyes)
        assertEquals(1, state(warned).attention.size)
    }

    // --------------------------------------------------------------- counts

    @Test
    fun `only entries that measurably moved bytes count as changes`() {
        // `applied` and `touched` can honestly disagree: a write whose target was
        // already met stages nothing and is still applied. The headline figure is
        // what moved, so it follows the measurement, not the verdict.
        val moved = entry(name = "a", touched = true)
        val stagedNothing = entry(name = "b", label = "`B` — B", touched = false)
        assertEquals(1, state(moved, stagedNothing).appliedCount)
    }

    @Test
    fun `two edits to one table are one changed table`() {
        val first = entry(intent = "raise the ceiling")
        val second = entry(intent = "trim the top row back")
        assertEquals(2, state(first, second).appliedCount)
        assertEquals(1, state(first, second).changedTables.size)
    }

    @Test
    fun `the tally is the engine's, in the engine's order`() {
        val counts = linkedMapOf("applied" to 3, "unchanged" to 1, "skipped" to 12)
        val ui = state(entry(), counts = counts)
        // Not re-derived from the entries — the engine's count already moves a
        // superseded skip out of the skipped bucket, and re-counting here would
        // put the app's header and the report's header at different numbers.
        assertEquals(listOf("applied", "unchanged", "skipped"), ui.counts.keys.toList())
        assertEquals(12, ui.counts["skipped"])
    }

    // --------------------------------------------------------------- parsing

    @Test
    fun `a reply's entries and counts parse off the envelope`() {
        val reply = JSONObject()
            .put("entries", JSONArray().put(JSONObject()
                .put("label", "`X` — X")
                .put("verdict", "applied")
                .put("before", "1")
                .put("after", "2")))
            .put("counts", JSONObject().put("applied", 1))
        assertEquals(1, reply.changeEntries().size)
        assertEquals(mapOf("applied" to 1), reply.changeCounts())
    }

    @Test
    fun `a reply with no entries parses to an empty list rather than throwing`() {
        assertTrue(JSONObject().changeEntries().isEmpty())
        assertTrue(JSONObject().changeCounts().isEmpty())
    }

    @Test
    fun `intent and detail join into one why line`() {
        val both = entry(intent = "raise the ceiling", detail = "clamped to the profile max")
        assertEquals("raise the ceiling — clamped to the profile max", both.why)
        assertEquals("raise the ceiling", entry(intent = "raise the ceiling", detail = "").why)
        assertEquals("clamped to the profile max", entry(intent = "", detail = "clamped to the profile max").why)
    }

    @Test
    fun `an entry with no values does not offer a before-after pair`() {
        // A check or a patch journals no arrays, so there is no arrow to draw.
        assertFalse(entry(before = "", after = "").hasValues)
        assertTrue(entry().hasValues)
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `not read yet is distinct from nothing changed`() {
        val fresh = ChangesUiState()
        assertFalse(fresh.loaded)
        // Crucially not "empty": the screen must not tell someone their edits are
        // gone while the read is still in flight.
        assertFalse(fresh.isEmpty)

        val read = ChangesUiState().withEntries(emptyList(), emptyMap())
        assertTrue(read.loaded)
        assertTrue(read.isEmpty)
    }

    @Test
    fun `a failed refresh keeps the last list it read`() {
        val loaded = state(entry())
        val failed = loaded.failed("the engine is busy with another request")

        assertEquals(1, failed.entries.size)
        assertFalse(failed.loading)
        assertEquals("the engine is busy with another request", failed.notice)
        // Still loaded — a stale list under a notice, never an empty one that
        // claims nothing was changed.
        assertTrue(failed.loaded)
    }

    @Test
    fun `a successful read clears a previous notice`() {
        val recovered = state(entry()).failed("transient").let {
            it.withEntries(it.entries, mapOf("applied" to 1))
        }
        assertNull(recovered.notice)
        assertFalse(recovered.loading)
    }

    @Test
    fun `loading clears the notice but keeps the entries on screen`() {
        val refreshing = state(entry()).failed("transient").loading()
        assertTrue(refreshing.loading)
        assertNull(refreshing.notice)
        assertEquals(1, refreshing.entries.size)
    }
}
