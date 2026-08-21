package com.simoscal.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Analyze screen's gate rules.
 *
 * Light by comparison with [EditorStateTest], because nothing on this screen
 * writes anything. The rule that carries real weight is the last group: a
 * report must never outlive the set of files it describes.
 */
class AnalysisUiStateTest {

    private fun file(hash: String, name: String = "$hash.csv") =
        ImportedFile(path = "/data/$hash.csv", sha256 = hash, displayName = name, sizeBytes = 1024)

    private val logA = file("aaa111")
    private val logB = file("bbb222")
    private val bin = file("cccbin", "Patched_259L_R14.bin")
    private val xdf = file("dddxdf", "SC8S50.V1.0.xdf")

    private fun report() = AnalysisReport.parse(
        JSONObject("""{"cal_resolved":false,"ran":["knock"],"plots":[]}""")
    )

    // ------------------------------------------------------------------ gates

    @Test
    fun `nothing can be run without a log`() {
        assertFalse(AnalysisUiState().canRun)
        assertTrue(AnalysisUiState().withLog(logA).canRun)
    }

    @Test
    fun `a run in flight blocks another`() {
        assertFalse(AnalysisUiState().withLog(logA).busy(true).canRun)
    }

    @Test
    fun `a calibration needs both halves`() {
        val onlyBin = AnalysisUiState().withLog(logA).withBin(bin)
        assertFalse(onlyBin.calibrationReady)
        assertTrue("a half-supplied bin is worth saying something about", onlyBin.calibrationIncomplete)

        val both = onlyBin.withXdf(xdf)
        assertTrue(both.calibrationReady)
        assertFalse(both.calibrationIncomplete)

        // Neither is the ordinary case and is not flagged at all.
        assertFalse(AnalysisUiState().withLog(logA).calibrationIncomplete)
    }

    // ------------------------------------------------------------------- logs

    @Test
    fun `the same bytes are never added twice`() {
        // Content, not name: two exports of one drive can carry different names,
        // and analysing one capture twice would double-count every pull in it.
        val renamed = logA.copy(displayName = "a second name.csv", path = "/data/other.csv")
        val state = AnalysisUiState().withLog(logA).withLog(renamed)
        assertEquals(1, state.logs.size)
    }

    @Test
    fun `re-adding an existing log leaves the state untouched`() {
        val state = AnalysisUiState().withLog(logA).withReport(report())
        val again = state.withLog(logA)
        // Identical state, so a duplicate pick cannot even invalidate the report.
        assertSame(state, again)
        assertTrue(again.report != null)
    }

    @Test
    fun `logs keep their pick order`() {
        val state = AnalysisUiState().withLog(logA).withLog(logB)
        assertEquals(listOf("aaa111", "bbb222"), state.logs.map { it.sha256 })
    }

    @Test
    fun `removing a log removes only that one`() {
        val state = AnalysisUiState().withLog(logA).withLog(logB).withoutLog(logA)
        assertEquals(listOf("bbb222"), state.logs.map { it.sha256 })
    }

    // -------------------------------------------------- report never goes stale

    @Test
    fun `adding a log drops a report that predates it`() {
        val state = AnalysisUiState().withLog(logA).withReport(report())
        assertNull(state.withLog(logB).report)
    }

    @Test
    fun `removing a log drops the report`() {
        val state = AnalysisUiState().withLog(logA).withLog(logB).withReport(report())
        assertNull(state.withoutLog(logB).report)
    }

    @Test
    fun `supplying a calibration drops the report`() {
        // The two calibration-aware checks would run next time, so the findings
        // on screen are no longer the findings this input set produces.
        val state = AnalysisUiState().withLog(logA).withReport(report())
        assertNull(state.withBin(bin).report)
        assertNull(state.withXdf(xdf).report)
    }

    @Test
    fun `clearing returns the screen to empty`() {
        val cleared = AnalysisUiState().withLog(logA).withBin(bin).withReport(report()).cleared()
        assertEquals(AnalysisUiState(), cleared)
    }

    // ----------------------------------------------------------------- errors

    @Test
    fun `an error ends the busy state so the run button comes back`() {
        val state = AnalysisUiState().withLog(logA).busy(true)
            .withError(UserFacingError("ANALYSIS_ERROR", "nope", "detail"))
        assertFalse(state.busy)
        assertTrue(state.canRun)
        assertEquals("nope", state.error?.message)
        assertNull(state.errorDismissed().error)
    }

    @Test
    fun `starting a run clears the previous error`() {
        val state = AnalysisUiState().withLog(logA)
            .withError(UserFacingError("ANALYSIS_ERROR", "nope", ""))
            .busy(true)
        assertNull(state.error)
    }

    @Test
    fun `a successful report clears any error`() {
        val state = AnalysisUiState().withLog(logA)
            .withError(UserFacingError("ANALYSIS_ERROR", "nope", ""))
            .withReport(report())
        assertNull(state.error)
        assertFalse(state.busy)
    }
}
