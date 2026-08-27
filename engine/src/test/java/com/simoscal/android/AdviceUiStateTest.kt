package com.simoscal.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdviceUiStateTest {

    private fun file(hash: String, name: String = "$hash.json") = ImportedFile(
        path = "/data/user/0/com.simoscal.engine/files/imports/$hash.advice.json",
        sha256 = hash,
        displayName = name,
        sizeBytes = 2048,
    )

    private fun bundle() = ExportedAdviceBundle.fromJson(
        JSONObject()
            .put("path", "/files/staging/bundles/abc/bundle.json")
            .put("sha256", "b".repeat(64))
            .put("bytes", 12345)
            .put("summary", JSONObject()
                .put("bundle_version", 1)
                .put("profile", "SC8S50")
                .put("tables", 92)
                .put("journal_entries", 3)
                .put("logs", JSONArray().put("pull-a.csv"))
                .put("pulls", 4)
                .put("findings", 2))
    )

    private fun table() = JSONObject()
        .put("name", "pressure_quotient_max")
        .put("id", "IP_PQ_CHA_MAX")
        .put("description", "Maximum allowed pressure quotient at turbocharger compressor")
        .put("label", "`IP_PQ_CHA_MAX` — Maximum allowed pressure quotient at turbocharger compressor")

    private fun recommendation(id: String = "rec-1") = JSONObject()
        .put("id", id)
        .put("table", table())
        .put("change", JSONObject()
            .put("space", "base")
            .put("operation", "set")
            .put("selection", JSONObject()
                .put("kind", "cells")
                .put("args", JSONArray().put(JSONArray().put(0).put(1))))
            .put("value", 3.0)
            .put("array", JSONObject.NULL))
        .put("intent", "raise the proven ceiling")
        .put("evidence", "pull 2, 4200-5000 rpm")
        .put("risk", "performance")
        .put("confidence", "medium")
        .put("prediction", "the limiter stays inactive through 5000 rpm")
        .put("routed_via", "bridge op `edit`")
        .put("preview", JSONObject()
            .put("before", JSONArray().put(2.9))
            .put("requested", JSONArray().put(3.0))
            .put("encoded", JSONArray().put(3.0))
            .put("quantized", false)
            .put("max_abs_quantization", 0.0)
            .put("warning", ""))
        .put("overlaps", JSONArray())
        .put("note", "")

    private fun reviewJson() = JSONObject()
        .put("schema_version", 1)
        .put("summary", "One item survived the guards.")
        .put("counts", JSONObject()
            .put("queued", 1)
            .put("dropped", 1)
            .put("malformed", 1)
            .put("total", 3))
        .put("queued", JSONArray().put(recommendation()))
        .put("dropped", JSONArray().put(
            recommendation("refused").apply {
                remove("preview")
                remove("overlaps")
                remove("note")
                put("reason", "the engine refused this value")
            }
        ))
        .put("malformed", JSONArray().put(JSONObject()
            .put("index", 2)
            .put("id", "broken")
            .put("problems", JSONArray().put(JSONObject()
                .put("where", "recommendations[2].evidence")
                .put("field", "evidence")
                .put("message", "must not be empty")))))

    @Test
    fun `bundle result keeps the engine summary intact`() {
        val bundle = bundle()
        assertEquals("SC8S50", bundle.summary.profile)
        assertEquals(92, bundle.summary.tables)
        assertEquals(listOf("pull-a.csv"), bundle.summary.logs)
        assertEquals(12345, bundle.bytes)
        assertEquals("b".repeat(12), bundle.shortHash)
    }

    @Test
    fun `review parses every outcome and verifies the counts`() {
        val review = AdviceReview.fromJson(reviewJson())
        assertEquals(AdviceReviewCounts(1, 1, 1, 3), review.counts)
        assertEquals("IP_PQ_CHA_MAX", review.queued.single().table.id)
        assertEquals(AdviceSelection.Cells(listOf(CellRef(0, 1))), review.queued.single().change.selection)
        assertEquals(listOf(3.0), review.queued.single().preview.encoded)
        assertEquals("the engine refused this value", review.dropped.single().reason)
        assertEquals("evidence", review.malformed.single().problems.single().field)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a review whose counts disagree with its records is rejected`() {
        val payload = reviewJson()
        payload.getJSONObject("counts").put("queued", 2)
        AdviceReview.fromJson(payload)
    }

    @Test
    fun `the same log bytes are included only once`() {
        val first = file("a".repeat(64), "first.csv")
        val renamed = first.copy(path = "/other.csv", displayName = "renamed.csv")
        val state = AdviceUiState().withLogs(listOf(first)).withLogs(listOf(renamed))
        assertEquals(listOf(first), state.logs)
    }

    @Test
    fun `re-adding only duplicate logs leaves completed artifacts intact`() {
        val log = file("a".repeat(64), "pull.csv")
        val state = AdviceUiState().withLogs(listOf(log)).exported(bundle())
        val again = state.withLogs(listOf(log))
        assertEquals(state.bundle, again.bundle)
        assertEquals(AdviceWork.IDLE, again.work)
    }

    @Test
    fun `changing notes clears a bundle and any reply derived from it`() {
        val reply = file("c".repeat(64), "reply.json")
        val state = AdviceUiState(notes = "old")
            .exported(bundle())
            .reviewing(reply)
            .reviewed(AdviceReview.fromJson(reviewJson()))
        val changed = state.withNotes("new")
        assertNull(changed.bundle)
        assertNull(changed.reply)
        assertNull(changed.review)
    }

    @Test
    fun `importing a second reply replaces the first and clears its review`() {
        val first = file("1".repeat(64), "first.json")
        val second = file("2".repeat(64), "second.json")
        val reviewed = AdviceUiState()
            .reviewing(first)
            .reviewed(AdviceReview.fromJson(reviewJson()))
        val replacing = reviewed.importingReply().reviewing(second)
        assertEquals(second, replacing.reply)
        assertNull(replacing.review)
        assertEquals(AdviceWork.REVIEWING, replacing.work)
    }

    @Test
    fun `a session mutation withdraws bundle reply and queue`() {
        val state = AdviceUiState()
            .exported(bundle())
            .reviewing(file("c".repeat(64)))
            .reviewed(AdviceReview.fromJson(reviewJson()))
        val stale = state.invalidatedBySessionChange()
        assertNull(stale.bundle)
        assertNull(stale.reply)
        assertNull(stale.review)
        assertTrue(stale.notice!!.contains("Session changed"))
        assertFalse(stale.busy)
    }

    @Test
    fun `a mutation with no advice artifacts leaves state untouched`() {
        val state = AdviceUiState(notes = "question")
        assertSame(state, state.invalidatedBySessionChange())
    }

    @Test
    fun `engine failures keep their stable code and own wording`() {
        val failed = AdviceUiState().exporting().failed(
            UserFacingError("ADVICE_REJECTED", "this recommendations file could not be used", "different calibration")
        )
        assertEquals(AdviceWork.IDLE, failed.work)
        assertEquals("ADVICE_REJECTED", failed.error?.code)
        assertEquals("different calibration", failed.error?.advanced)
        assertNull(failed.errorDismissed().error)
    }
}
