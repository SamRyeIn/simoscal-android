package com.simoscal.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log overlay: parsing what the engine sends, and staying inert.
 *
 * Two things are being pinned. First, that the app reads the engine's own
 * attributions rather than re-deriving them — the gear especially, which is
 * offset differently under two different log headers and has already been
 * resolved upstream. Second, that loading and drawing a pull cannot change
 * anything about the edit in progress: the overlay is evidence on the canvas,
 * and a feature that reads a file must not be able to move a byte.
 */
class LogOverlayTest {

    private fun payload(
        available: Boolean = true,
        missing: List<String> = emptyList(),
        pulls: String = """
            {
              "index": 1, "file": "drive.csv", "gear": 3, "gear_resolved": true,
              "rpm_min": 3010.0, "rpm_max": 6480.0, "duration_s": 7.25, "n_samples": 145,
              "series": [
                {"source": "boost", "label": "Boost", "segments":
                  [{"x": [3000.0, 4000.0, 5000.0], "y": [12.0, 19.5, 21.0]}]},
                {"source": "boost_sp", "label": "Boost SP", "segments":
                  [{"x": [3000.0, 4000.0, 5000.0], "y": [13.0, 20.0, 20.5]}]}
              ]
            }
        """.trimIndent(),
    ): JSONObject = JSONObject(
        """
        {
          "plot_id": "boost",
          "available": $available,
          "missing_channels": [${missing.joinToString(",") { "\"$it\"" }}],
          "pulls": [$pulls]
        }
        """.trimIndent()
    )

    // ------------------------------------------------------------------ parsing

    @Test
    fun `a pull carries both traces and its chooser facts`() {
        val model = LogOverlayModel.fromJson(payload())
        val pull = model.pull(1)!!

        assertTrue(model.available)
        assertTrue(pull.drawn)
        assertEquals(3, pull.gear)
        assertTrue(pull.gearResolved)
        assertEquals(145, pull.sampleCount)
        assertEquals(listOf(12.0, 19.5, 21.0), pull.measured!!.segments.single().values)
        assertEquals(listOf(13.0, 20.0, 20.5), pull.setpoint!!.segments.single().values)
        assertTrue(pull.setpoint!!.isSetpoint)
        assertFalse(pull.measured!!.isSetpoint)
    }

    @Test
    fun `the caption names the gear the engine attributed`() {
        val pull = LogOverlayModel.fromJson(payload()).pull(1)!!
        assertEquals("3rd gear · 3010–6480 rpm · 7.3 s", pull.caption)
    }

    @Test
    fun `an unresolved gear is null and says so rather than reading as neutral`() {
        val model = LogOverlayModel.fromJson(
            payload(
                pulls = """
                    {"index": 1, "file": "d.csv", "gear": null, "gear_resolved": false,
                     "rpm_min": 3000.0, "rpm_max": 6000.0, "duration_s": 6.0, "n_samples": 100,
                     "series": [{"source": "boost", "label": "Boost", "segments":
                       [{"x": [3000.0], "y": [12.0]}]}]}
                """.trimIndent()
            )
        )
        val pull = model.pull(1)!!

        assertNull("a gear nobody established must not arrive as 0", pull.gear)
        assertTrue(pull.caption.startsWith("Gear unknown"))
        assertTrue("it still draws — the trace is fine", pull.drawn)
    }

    @Test
    fun `a segment whose axes disagree in length is dropped, not half-drawn`() {
        val model = LogOverlayModel.fromJson(
            payload(
                pulls = """
                    {"index": 1, "file": "d.csv", "gear": 3, "gear_resolved": true,
                     "rpm_min": 3000.0, "rpm_max": 6000.0, "duration_s": 6.0, "n_samples": 10,
                     "series": [{"source": "boost", "label": "Boost", "segments":
                       [{"x": [3000.0, 4000.0], "y": [12.0]}]}]}
                """.trimIndent()
            )
        )
        assertTrue(model.pull(1)!!.measured!!.segments.isEmpty())
        assertFalse(model.pull(1)!!.drawn)
    }

    @Test
    fun `a log without the boost channels reports what was missing`() {
        val model = LogOverlayModel.fromJson(
            payload(available = false, missing = listOf("put", "ambient_press"), pulls = "")
        )
        assertFalse(model.available)
        assertEquals(listOf("put", "ambient_press"), model.missingChannels)
        assertTrue(model.pulls.isEmpty())
    }

    // ------------------------------------------------------------------- state

    @Test
    fun `a lone pull is selected for you, since there is nothing to choose`() {
        val state = OverlayUiState().withModel(LogOverlayModel.fromJson(payload()), "drive.csv")

        assertEquals(1, state.selectedPull)
        assertTrue(state.active)
        assertEquals("drive.csv", state.logName)
        assertNull(state.unavailable)
    }

    @Test
    fun `several pulls select none — which run to read against a curve is a judgement`() {
        val two = payload(
            pulls = """
                {"index": 1, "file": "d.csv", "gear": 2, "gear_resolved": true,
                 "rpm_min": 3000.0, "rpm_max": 6000.0, "duration_s": 5.0, "n_samples": 90,
                 "series": [{"source": "boost", "label": "Boost", "segments":
                   [{"x": [3000.0], "y": [10.0]}]}]},
                {"index": 2, "file": "d.csv", "gear": 3, "gear_resolved": true,
                 "rpm_min": 3100.0, "rpm_max": 6400.0, "duration_s": 7.0, "n_samples": 140,
                 "series": [{"source": "boost", "label": "Boost", "segments":
                   [{"x": [3100.0], "y": [11.0]}]}]}
            """.trimIndent()
        )
        val state = OverlayUiState().withModel(LogOverlayModel.fromJson(two), "d.csv")

        assertNull(state.selectedPull)
        assertFalse(state.active)
        assertEquals(2, state.choosablePulls.size)

        val chosen = state.selectingPull(2)
        assertEquals(2, chosen.selectedPull)
        assertEquals(3, chosen.visiblePull!!.gear)
    }

    @Test
    fun `a missing-channel log explains itself instead of offering an empty canvas`() {
        val state = OverlayUiState().withModel(
            LogOverlayModel.fromJson(
                payload(available = false, missing = listOf("ambient_press"), pulls = "")
            ),
            "thin.csv",
        )

        assertNotNull(state.unavailable)
        assertTrue(state.unavailable!!.contains("ambient_press"))
        assertFalse(state.active)
    }

    @Test
    fun `a log with no pulls says that, rather than reading as a broken file`() {
        val state = OverlayUiState().withModel(
            LogOverlayModel.fromJson(payload(pulls = "")), "cruise.csv",
        )
        assertEquals("No wide-open-throttle pulls were detected in that log.", state.unavailable)
    }

    @Test
    fun `selecting a pull that cannot draw is ignored`() {
        val state = OverlayUiState()
            .withModel(LogOverlayModel.fromJson(payload()), "d.csv")
            .selectingPull(99)
        assertEquals(1, state.selectedPull)
    }

    @Test
    fun `clearing puts the canvas back to curves only`() {
        val cleared = OverlayUiState()
            .withModel(LogOverlayModel.fromJson(payload()), "d.csv")
            .cleared()

        assertNull(cleared.model)
        assertNull(cleared.selectedPull)
        assertFalse(cleared.active)
    }

    // ------------------------------------------- AE3: the overlay changes nothing

    @Test
    fun `an overlay changes no gate the editor decides anything by`() {
        val boostModel = BoostCurveModel(
            rpmAxis = List(12) { 1000.0 + it * 500.0 },
            slots = SLOT_IDS.map { slot -> SlotCurve(slot, List(12) { 8.0 + slot }) },
            baseCeilingPsi = List(12) { 18.0 },
            baseRpmAxis = listOf(1000.0, 6500.0),
            baseCeilingOwnPsi = listOf(18.0, 18.0),
        )
        val before = EditorUiState(
            sessionId = "s1",
            boost = BoostUiState().withModel(boostModel).withDraggedPoint(3, 12.0),
            canUndo = true,
        )
        val after = before.copy(
            overlay = OverlayUiState().withModel(LogOverlayModel.fromJson(payload()), "d.csv")
        )

        // Every decision the editor makes is byte-for-byte the same with a pull
        // drawn as without one. If loading a log could move any of these, it
        // could change what reaches the bin.
        assertEquals(before.boost.draft, after.boost.draft)
        assertEquals(before.boost.canApply, after.boost.canApply)
        assertEquals(before.boost.dirty, after.boost.dirty)
        assertEquals(before.hasEdits, after.hasEdits)
        assertEquals(before.canUndo, after.canUndo)
        assertEquals(before.canRedo, after.canRedo)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.build, after.build)
        assertEquals(before.dirtyDraft, after.dirtyDraft)
        // And the only field that did move is the overlay itself.
        assertEquals(before, after.copy(overlay = before.overlay))
    }

    @Test
    fun `choosing a pull is never refused for a dirty draft`() {
        val two = payload(
            pulls = """
                {"index": 1, "file": "d.csv", "gear": 2, "gear_resolved": true,
                 "rpm_min": 3000.0, "rpm_max": 6000.0, "duration_s": 5.0, "n_samples": 90,
                 "series": [{"source": "boost", "label": "Boost", "segments":
                   [{"x": [3000.0], "y": [10.0]}]}]},
                {"index": 2, "file": "d.csv", "gear": 3, "gear_resolved": true,
                 "rpm_min": 3100.0, "rpm_max": 6400.0, "duration_s": 7.0, "n_samples": 140,
                 "series": [{"source": "boost", "label": "Boost", "segments":
                   [{"x": [3100.0], "y": [11.0]}]}]}
            """.trimIndent()
        )
        // Unlike a slot switch, which *is* refused: that would drop an edit,
        // whereas looking at another pull drops nothing.
        val state = OverlayUiState().withModel(LogOverlayModel.fromJson(two), "d.csv")
        assertEquals(2, state.selectingPull(2).selectedPull)
        assertNull(state.selectingPull(2).notice)
    }
}
