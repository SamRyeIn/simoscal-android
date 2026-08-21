package com.simoscal.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full-load enrichment editor.
 *
 * The property that matters most is the same one the boost editor pins about its
 * ceiling: **no reachable drag position can compose an edit the engine refuses**.
 * Here the refusal is a lean full-load setpoint, which is the failure this whole
 * domain exists to prevent — so a UI that let a fingertip ask for one would be
 * forwarding an edit it already knows is dangerous *and* rejected.
 *
 * The second property is that the bound is the engine's, not the app's. The
 * screen shades a band and the engine refuses a value, and if those two numbers
 * ever came from different places they would eventually disagree.
 */
class LambdaUiStateTest {

    private val rpmAxis = listOf(512.0, 704.0, 800.0, 992.0, 1760.0, 3584.0, 3616.0, 4000.0, 4512.0, 5504.0, 6016.0, 6496.0)
    private val timeAxis = listOf(0.0, 0.5, 1.0, 3.0, 30.0, 40.0, 50.0, 60.0)

    private fun payload(
        values: List<List<Double>> = timeAxis.map { rpmAxis.map { 1.0 } },
        leanMax: Double = 1.00,
        richMin: Double = 0.50,
    ): JSONObject {
        fun grid(rows: List<List<Double>>) =
            rows.joinToString(",", "[", "]") { row -> row.joinToString(",", "[", "]") }

        return JSONObject(
            """
            {
              "table": {
                "space": "base", "name": "lambda_full_load",
                "symbol": "IP_LAMB_FL_SP",
                "title": "Lambda Full Load Enrichment depending on N_32 and time T_FL",
                "description": "Lambda Full Load Enrichment depending on N_32 and time T_FL",
                "uniqueid_hex": "0x1", "units": "-",
                "shape": [8, 12], "ndim": 2, "reversible": true, "is_axis": false,
                "owner": "tune.fueling.full_load_enrichment()",
                "categories": [],
                "values": ${grid(values)},
                "x_axis": {"units": "rpm", "values": ${rpmAxis.joinToString(",", "[", "]")}},
                "y_axis": {"units": "s", "values": ${timeAxis.joinToString(",", "[", "]")}}
              },
              "lean_max": $leanMax,
              "rich_min": $richMin
            }
            """.trimIndent()
        )
    }

    private fun loaded(
        values: List<List<Double>> = timeAxis.map { rpmAxis.map { 1.0 } },
        leanMax: Double = 1.00,
    ): LambdaUiState {
        val (detail, max, min) = parseLambdaPayload(payload(values, leanMax))!!
        return LambdaUiState().withDetail(detail, max, min)
    }

    // -------------------------------------------------------------- reading

    @Test
    fun `the map loads as a curve per time-row, with both axes`() {
        val state = loaded()

        assertEquals(rpmAxis.size, state.draft.size)
        assertEquals(rpmAxis, state.rpmAxis)
        assertEquals(timeAxis, state.timeAxis)
        assertEquals(0.0, state.rowSeconds!!, 1e-9)
        assertEquals("the other seven rows are context", 7, state.ghostRows.size)
        assertTrue("stock is a flat 1.00 map", state.draft.all { it == 1.0 })
    }

    @Test
    fun `the bound comes from the engine, not from a constant here`() {
        // If the engine ever moves its refusal, the band moves with it.
        val state = loaded(leanMax = 0.98)
        assertEquals(0.98, state.leanMax, 1e-9)
        assertNotNull(state.rejectTypedLambda(0.98))
        assertNull(state.rejectTypedLambda(0.97))
    }

    // ------------------------------------------- the lean bound at the fingertip

    @Test
    fun `no reachable drag position produces a value the engine would refuse`() {
        var state = loaded()
        // Sweep the whole plausible fingertip range, well past both ends of the
        // drawn axis, and assert every resulting value is one the engine accepts.
        (-200..300).forEach { step ->
            val asked = step / 200.0   // -1.0 .. 1.5
            state = state.withDraggedPoint(4, asked)
            val value = state.draft[4]
            assertTrue(
                "drag to $asked produced $value, which the engine refuses",
                value < state.leanMax && value > state.richMin,
            )
            assertNull("and so it is never a refusal", state.rejectTypedLambda(value))
        }
    }

    @Test
    fun `a drag stops one step below the refusal, never on it`() {
        val state = loaded().withDraggedPoint(0, 5.0)

        // The engine's test is `>=`, so landing *on* 1.00 would be rejected.
        assertTrue(state.draft[0] < 1.0)
        assertEquals(0.999, state.draft[0], 1e-9)
    }

    @Test
    fun `a drag will not go richer than the engine allows either`() {
        val state = loaded().withDraggedPoint(0, -3.0)
        assertTrue(state.draft[0] > state.richMin)
        assertNull(state.rejectTypedLambda(state.draft[0]))
    }

    @Test
    fun `a typed lean value is refused with the reason, and changes nothing`() {
        val state = loaded()
        val refused = state.withTypedPoint(3, 1.0)

        assertEquals(state.draft, refused.draft)
        val notice = refused.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("at or above lambda"))
        assertTrue("it says why it matters", notice.contains("turbine"))
    }

    @Test
    fun `a typed lean value is never quietly corrected to a safe one`() {
        // The cardinal sin, at the one place it would be most tempting: silently
        // storing 0.999 for a typed 1.05 would leave someone believing the
        // calibration says what they typed.
        val refused = loaded().withTypedPoint(3, 1.05)
        assertTrue(refused.draft.all { it == 1.0 })
    }

    @Test
    fun `a mistyped decimal is refused as too rich`() {
        val refused = loaded().withTypedPoint(3, 0.08)
        val notice = refused.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("mistyped"))
    }

    @Test
    fun `a legal enrichment value is taken exactly as typed`() {
        val state = loaded().withTypedPoint(3, 0.856)
        assertEquals(0.856, state.draft[3], 1e-12)
        assertNull(state.notice)
        assertTrue(state.dirty)
    }

    // ------------------------------------------------------- the warning band

    @Test
    fun `the warning band is a fixed bound, not one that moves with the data`() {
        // It marks where a person should think, not where the engine acts, so it
        // does not move with the data range or with the engine's refusal.
        assertEquals(0.90, WARN_LAMBDA, 1e-9)

        val state = loaded().withTypedPoint(0, 0.95).withTypedPoint(1, 0.85)
        // Stock is flat 1.00, so every untouched point is in the band too — the
        // whole-curve view says so honestly.
        assertTrue(state.draftInWarningBand.contains(0))
        assertFalse(state.draftInWarningBand.contains(1))
    }

    @Test
    fun `the warning names only points this draft moved into the band`() {
        // The card must not fire on arrival. Stock is a flat 1.00 map, so a
        // warning driven by the whole curve would accuse someone of a lean
        // setpoint before they had touched anything — and a warning that always
        // fires is one people stop reading, which is the worst possible habit to
        // teach about this particular band.
        val untouched = loaded()
        assertTrue(untouched.draftInWarningBand.isNotEmpty())
        assertTrue("nothing staged, nothing to warn about", untouched.stagedIntoWarningBand.isEmpty())

        val staged = untouched.withTypedPoint(0, 0.95)
        assertEquals(listOf(0), staged.stagedIntoWarningBand)

        // Enriching a point is a move, but not into the band.
        val enriched = untouched.withTypedPoint(3, 0.85)
        assertTrue(enriched.stagedIntoWarningBand.isEmpty())
    }

    @Test
    fun `a stock row reports that it provides no enrichment`() {
        assertTrue(loaded().providesNoEnrichment)
        assertFalse(loaded().withTypedPoint(0, 0.85).providesNoEnrichment)
    }

    @Test
    fun `values in the band are legal — a warning is not a refusal`() {
        val state = loaded().withTypedPoint(0, 0.95)
        assertNull(state.notice)
        assertTrue(state.canApply)
    }

    // -------------------------------------------------------------- staging

    @Test
    fun `switching row with an unapplied draft is refused`() {
        val dirty = loaded().withTypedPoint(2, 0.85)
        val blocked = dirty.selectingRow(4)

        assertEquals(0, blocked.row)
        assertEquals(dirty.draft, blocked.draft)
        assertNotNull(blocked.notice)
    }

    @Test
    fun `switching row is fine once clean, and reads that row`() {
        val values = timeAxis.mapIndexed { row, _ -> rpmAxis.map { 1.0 - row / 100.0 } }
        val state = loaded(values).selectingRow(4)

        assertEquals(4, state.row)
        assertEquals(30.0, state.rowSeconds!!, 1e-9)
        assertEquals(values[4], state.draft)
    }

    @Test
    fun `a flat row is validated like any typed value`() {
        val refused = loaded().withFlatRow(1.0)
        assertNotNull(refused.notice)
        assertTrue(refused.draft.all { it == 1.0 })

        val ok = loaded().withFlatRow(0.86)
        assertTrue(ok.draft.all { abs(it - 0.86) < 1e-12 })
    }

    @Test
    fun `applied folds only the edited row back, leaving the rest alone`() {
        val values = timeAxis.mapIndexed { row, _ -> rpmAxis.map { 1.0 - row / 100.0 } }
        val encoded = rpmAxis.map { 0.855 }
        val state = loaded(values).selectingRow(4).withTypedPoint(0, 0.85).applied(encoded, "Applied.")

        assertEquals(encoded, state.detail!!.values[4])
        values.indices.filter { it != 4 }.forEach { row ->
            assertEquals("row $row must not have moved", values[row], state.detail!!.values[row])
        }
        assertFalse(state.dirty)
    }

    @Test
    fun `an unapplied enrichment draft blocks the actions that would overwrite it`() {
        val editor = EditorUiState(sessionId = "s1", lambda = loaded().withTypedPoint(1, 0.85))

        assertEquals(DirtyDraft.LAMBDA, editor.dirtyDraft)
        assertFalse(editor.canMutateSession)
        assertNotNull(editor.dirtyDraftRefusal)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)
}
