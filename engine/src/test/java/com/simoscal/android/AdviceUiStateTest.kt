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
    fun `a two-dimensional preview flattens row-major instead of failing the import`() {
        // The engine sends a preview in the table's own shape. A 10x16 map arrives
        // as rows, and reading a row as a double used to throw and take the whole
        // import down with it — every recommendation against a 2-D table.
        val grid = JSONArray()
            .put(JSONArray().put(1.0).put(2.0).put(3.0))
            .put(JSONArray().put(4.0).put(5.0).put(6.0))
        val item = recommendation().apply {
            getJSONObject("preview")
                .put("before", grid)
                .put("requested", grid)
                .put("encoded", grid)
        }
        val review = AdviceReview.fromJson(
            reviewJson().put("queued", JSONArray().put(item))
        )
        assertEquals(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
            review.queued.single().preview.before,
        )
    }

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

    // ------------------------------------------------------ the review queue

    private fun stagingJson(
        editor: String = "table",
        values: JSONArray = JSONArray().put(JSONArray().put(3.0)),
        units: String = "-",
        slot: Int = 0,
        row: Int = -1,
        key: String = "",
        reason: String = "",
    ) = JSONObject()
        .put("editor", editor)
        .put("values", values)
        .put("units", units)
        .put("slot", slot)
        .put("row", row)
        .put("key", key)
        .put("reason", reason)

    private fun queuedJson(
        id: String,
        staging: JSONObject = stagingJson(),
        risk: String = "performance",
    ) = recommendation(id).put("risk", risk).put("staging", staging)

    private fun reviewOf(vararg queued: JSONObject, refused: Int = 0) = AdviceReview.fromJson(
        JSONObject()
            .put("schema_version", 1)
            .put("summary", "")
            .put("counts", JSONObject()
                .put("queued", queued.size)
                .put("dropped", refused)
                .put("malformed", 0)
                .put("total", queued.size + refused))
            .put("queued", JSONArray().also { array -> queued.forEach(array::put) })
            .put("dropped", JSONArray().also { array ->
                repeat(refused) {
                    array.put(recommendation("refused-$it").apply {
                        remove("preview"); remove("overlaps"); remove("note")
                        put("reason", "the engine refused this value")
                    })
                }
            })
            .put("malformed", JSONArray())
    )

    private fun queue(vararg queued: JSONObject, refused: Int = 0) =
        AdviceUiState().reviewed(reviewOf(*queued, refused = refused))

    @Test
    fun `staging arrives in the units of the editor that owns the change`() {
        val review = reviewOf(queuedJson("boost", stagingJson(
            editor = "boost",
            values = JSONArray().put(JSONArray().put(14.5).put(15.5)),
            units = "psi gauge",
            slot = 3,
        )))
        val staging = review.queued.single().staging

        assertEquals("boost", staging.editor)
        assertEquals(listOf(14.5, 15.5), staging.curve)
        assertEquals("psi gauge", staging.units)
        assertEquals(3, staging.slot)
        assertEquals(Destination.BOOST, staging.destination)
        assertTrue(staging.loadable)
    }

    @Test
    fun `the preview and the staging carry their own units and are not interchangeable`() {
        // A slot cap is stored in hPa absolute and edited in psi gauge. Labelling
        // the preview arrays with the editor's units printed an hPa figure and
        // called it psi.
        val item = queuedJson("boost", stagingJson(
            editor = "boost",
            values = JSONArray().put(JSONArray().put(14.5)),
            units = "psi gauge",
            slot = 1,
        )).apply { getJSONObject("preview").put("units", "hPa") }
        val queued = reviewOf(item).queued.single()

        assertEquals("hPa", queued.preview.units)
        assertEquals("psi gauge", queued.staging.units)
    }

    @Test
    fun `an item with nothing to pre-load still names its screen`() {
        // Routing is the engine's fact either way, so Show-me works for a flag
        // exactly as it does for a curve — only the pre-load is missing.
        val review = reviewOf(queuedJson("flag", stagingJson(
            editor = "slots",
            values = JSONArray(),
            key = "enable_lc",
            slot = 1,
            reason = "a per-slot flag is one press on the Slots screen",
        )))
        val staging = review.queued.single().staging

        assertEquals(Destination.SLOTS, staging.destination)
        assertFalse(staging.loadable)
        assertTrue(staging.reason.isNotBlank())
    }

    @Test
    fun `accepting stages exactly one item and rejecting stages none`() {
        var state = queue(queuedJson("one"), queuedJson("two"), queuedJson("three"))
        val first = state.current!!

        state = state.rejecting(first)
        assertNull(state.staged)
        assertEquals("two", state.current?.id)

        state = state.rejecting(state.current!!)
        state = state.accepting(state.current!!)

        assertEquals("three", state.staged?.id)
        assertEquals(1, state.acceptedCount)
        assertEquals(2, state.rejectedCount)
        assertTrue(state.queueFinished)
    }

    @Test
    fun `a second acceptance is refused while the first is still unapplied`() {
        // Not a nag: two staged proposals means one of them is out of sight, and
        // applying either invalidates the whole review anyway.
        var state = queue(queuedJson("one"), queuedJson("two"))
        state = state.accepting(state.current!!)
        val blocked = state.current!!

        state = state.accepting(blocked)

        assertEquals("one", state.staged?.id)
        assertEquals("two", state.current?.id)
        assertEquals(1, state.acceptedCount)
        assertTrue(state.queueNotice!!.contains("only one"))
    }

    @Test
    fun `a rejected item is not resurrected by rebuilding the queue`() {
        var state = queue(queuedJson("one"), queuedJson("two"))
        state = state.rejecting(state.current!!)

        // The screen is rebuilt from `decisions` on every composition, so this is
        // exactly what a rotation does.
        assertEquals(listOf("two"), state.remaining.map { it.id })
        assertEquals(listOf("two"), state.copy().remaining.map { it.id })
    }

    @Test
    fun `nothing to review reads differently from nothing imported`() {
        val nothingImported = AdviceUiState()
        assertNull(nothingImported.review)
        assertFalse(nothingImported.queueFinished)

        val allRefused = queue(refused = 4)
        assertTrue(allRefused.queueFinished)
        assertTrue(allRefused.queuedAdvice.isEmpty())
        assertEquals(4, allRefused.refusedCount)
    }

    @Test
    fun `a session change withdraws the decisions along with the review`() {
        var state = queue(queuedJson("one"), queuedJson("two"))
        state = state.accepting(state.current!!)

        val after = state.invalidatedBySessionChange()

        assertNull(after.review)
        assertNull(after.staged)
        assertTrue(after.decisions.isEmpty())
    }

    @Test
    fun `a refused staging puts the recommendation back in the queue`() {
        var state = queue(queuedJson("one"))
        state = state.accepting(state.current!!)

        state = state.stagingRefused("the rpm axis moved since the review")

        assertNull(state.staged)
        assertEquals("one", state.current?.id)
        assertEquals(0, state.acceptedCount)
        assertTrue(state.queueNotice!!.contains("rpm axis"))
    }

    // ------------------------------------------------- pre-loading an editor

    private val boostModel = BoostCurveModel(
        rpmAxis = listOf(2000.0, 3000.0, 4000.0),
        slots = listOf(
            SlotCurve(1, listOf(15.0, 16.0, 17.0)),
            SlotCurve(2, listOf(10.0, 10.0, 10.0)),
        ),
        baseCeilingPsi = listOf(22.0, 22.0, 22.0),
        baseRpmAxis = listOf(2000.0, 4000.0),
        baseCeilingOwnPsi = listOf(22.0, 22.0),
    )

    private fun accepted(staging: JSONObject): AdviceUiState {
        val state = queue(queuedJson("rec-1", staging))
        return state.accepting(state.current!!)
    }

    @Test
    fun `an accepted boost curve lands on its own slot as a dirty draft`() {
        val advice = accepted(stagingJson(
            editor = "boost",
            values = JSONArray().put(JSONArray().put(12.0).put(13.0).put(14.0)),
            units = "psi gauge",
            slot = 2,
        ))
        val state = EditorUiState(
            boost = BoostUiState().withModel(boostModel),
            advice = advice,
        ).preloadingStagedAdvice()

        assertEquals(2, state.boost.activeSlot)
        assertEquals(listOf(12.0, 13.0, 14.0), state.boost.draft)
        // Staged, not applied: the journal entry happens at Apply on that screen.
        assertTrue(state.boost.dirty)
        assertEquals("rec-1", state.advice.staged?.id)
    }

    @Test
    fun `a curve that no longer fits the axis is refused rather than part-loaded`() {
        val advice = accepted(stagingJson(
            editor = "boost",
            values = JSONArray().put(JSONArray().put(12.0).put(13.0)),
            units = "psi gauge",
            slot = 2,
        ))
        val state = EditorUiState(
            boost = BoostUiState().withModel(boostModel),
            advice = advice,
        ).preloadingStagedAdvice()

        assertFalse(state.boost.dirty)
        assertNull(state.advice.staged)
        assertEquals("rec-1", state.advice.current?.id)
        assertTrue(state.advice.queueNotice!!.contains("breakpoints"))
    }

    @Test
    fun `an accepted road-speed limiter lands in the limiters draft`() {
        val advice = accepted(stagingJson(
            editor = "limiters",
            values = JSONArray().put(JSONArray().put(250.0)),
            units = "km/h",
            key = "speed",
        ))
        val model = LimitersModel.fromJson(JSONObject(
            """{"speed_limiter":[{"name":"speed_limiter_level1","label":"L1",
                 "description":"","units":"km/h","value":200.0,"owner":"limits.speed_limiter()"}],
                "static_rev_limit":[],"engine_rev_limit":6816.0,
                "rev_limits":null,"launch_control":null}"""
        ))
        val state = EditorUiState(
            limiters = LimitersUiState().withModel(model),
            advice = advice,
        ).preloadingStagedAdvice()

        assertEquals(250.0, state.limiters.speedDraft!!, 1e-9)
        assertTrue(state.limiters.speedDirty)
    }

    @Test
    fun `an editor that has not loaded yet is left alone rather than refused`() {
        // Pre-loading happens in the owning screen's *load* path, so a screen
        // that has never been opened must not consume the staged item.
        val advice = accepted(stagingJson(
            editor = "boost",
            values = JSONArray().put(JSONArray().put(12.0).put(13.0).put(14.0)),
            slot = 2,
        ))
        val state = EditorUiState(advice = advice).preloadingStagedAdvice()

        assertEquals("rec-1", state.advice.staged?.id)
        assertNull(state.advice.queueNotice)
    }

    @Test
    fun `a staged grid waits for its own table to be the one open`() {
        val advice = accepted(stagingJson(
            editor = "table",
            values = JSONArray()
                .put(JSONArray().put(1.0).put(2.0))
                .put(JSONArray().put(3.0).put(4.0)),
        ))
        val other = TableSummary(
            space = "base", name = "some_other_table", symbol = null, title = null,
            description = "another table", uniqueidHex = "0x9", units = "-",
            rows = 2, cols = 2, ndim = 2, reversible = true, isAxis = false,
            categories = emptyList(),
        )
        val wrongTable = EditorUiState(
            tables = TablesUiState().withDetail(
                TableDetail(other, listOf(listOf(0.0, 0.0), listOf(0.0, 0.0)), null, null)
            ),
            advice = advice,
        ).preloadingStagedAdvice()

        assertTrue(wrongTable.tables.draft.flatten().all { it == 0.0 })
        assertEquals("rec-1", wrongTable.advice.staged?.id)

        val right = other.copy(name = "pressure_quotient_max")
        val loaded = EditorUiState(
            tables = TablesUiState().withDetail(
                TableDetail(right, listOf(listOf(0.0, 0.0), listOf(0.0, 0.0)), null, null)
            ),
            advice = advice,
        ).preloadingStagedAdvice()

        assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), loaded.tables.draft)
    }

    @Test
    fun `a flag has nothing to pre-load and consumes no editor`() {
        val advice = accepted(stagingJson(
            editor = "slots",
            values = JSONArray(),
            key = "enable_lc",
            slot = 1,
            reason = "a per-slot flag is one press on the Slots screen",
        ))
        // Accepted with the engine's instruction, and no draft anywhere.
        assertNull(advice.staged)
        assertEquals(1, advice.acceptedCount)
        assertTrue(advice.queueNotice!!.contains("one press"))

        val state = EditorUiState(advice = advice).preloadingStagedAdvice()
        assertSame(advice, state.advice)
    }
}
