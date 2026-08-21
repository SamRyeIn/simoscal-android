package com.simoscal.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limiters screen's rules.
 *
 * The load-bearing test here is [no reachable drag can break the escalation
 * order][`no reachable drag position produces a trio the engine would refuse`]:
 * the engine refuses an out-of-order trio, and a UI that let a finger *ask* for
 * one would be forwarding edits it knows will bounce. Everything else follows
 * the house rules — drags clamp, typed values are refused with a reason, and a
 * refusal leaves the draft exactly as it was.
 */
class LimitersUiStateTest {

    private fun payload(
        soft: Double = 0.0,
        medium: Double = 64.0,
        hard: Double = 64.0,
        speeds: List<Double> = List(4) { 200.0 },
        withPatch: Boolean = true,
        staticRev: Double = 3808.0,
        engineRevLimit: Double = 6816.0,
    ): JSONObject {
        fun scalar(name: String, value: Double, units: String, owner: String) =
            """{"name":"$name","label":"`x` — $name","description":"$name",
                "units":"$units","value":$value,"owner":"$owner"}"""

        val quartet = listOf(
            "speed_limiter_level1", "speed_limiter_level2",
            "speed_limiter_level3", "speed_limiter_inactive",
        ).mapIndexed { index, name ->
            scalar(name, speeds[index], "km/h", "tune.limits.speed_limiter()")
        }.joinToString(",")

        val rev = if (!withPatch) "null" else """[
            ${scalar("rev_limit_soft", soft, "rpm", "tune.limits.rev_limits()")},
            ${scalar("rev_limit_medium", medium, "rpm", "tune.limits.rev_limits()")},
            ${scalar("rev_limit_hard", hard, "rpm", "tune.limits.rev_limits()")}
        ]"""
        val lc = if (!withPatch) "null" else """[
            ${scalar("lc_limiter_timing", -25.125, "°CRK", "")},
            ${scalar("lc_release_speed", 3.0, "km/h", "")}
        ]"""

        val static = listOf(
            "static_rev_limit_dct", "static_rev_limit_at",
            "static_rev_limit_mt", "static_rev_limit_cvt",
        ).joinToString(",") {
            scalar(it, staticRev, "rpm", "tune.limits.static_rev_limit()")
        }

        return JSONObject(
            """{"speed_limiter":[$quartet],"static_rev_limit":[$static],
                "engine_rev_limit":$engineRevLimit,
                "rev_limits":$rev,"launch_control":$lc}"""
        )
    }

    private fun loaded(
        soft: Double = 0.0,
        medium: Double = 64.0,
        hard: Double = 64.0,
        speeds: List<Double> = List(4) { 200.0 },
        withPatch: Boolean = true,
        staticRev: Double = 3808.0,
        engineRevLimit: Double = 6816.0,
    ) = LimitersUiState().withModel(
        LimitersModel.fromJson(
            payload(soft, medium, hard, speeds, withPatch, staticRev, engineRevLimit)
        )
    )

    // ------------------------------------------------------------------ reading

    @Test
    fun `a loaded model starts clean drafts of both limiters`() {
        val state = loaded()

        assertEquals(listOf(0.0, 64.0, 64.0), state.revDraft)
        assertEquals(200.0, state.speedDraft!!, 1e-9)
        assertFalse(state.dirty)
        assertFalse(state.canApply)
        assertTrue(state.hasRevLimits)
    }

    @Test
    fun `a base-only session has no trio but a real speed limiter`() {
        val state = loaded(withPatch = false)

        assertFalse(state.hasRevLimits)
        assertTrue(state.revDraft.isEmpty())
        assertEquals(200.0, state.speedDraft!!, 1e-9)
        // Still a working screen: the quartet is base calibration.
        assertTrue(state.withTypedSpeed(250.0).canApply)
    }

    @Test
    fun `a quartet that disagrees reports no single value rather than picking one`() {
        val state = loaded(speeds = listOf(200.0, 200.0, 250.0, 200.0))
        assertNull(state.committedSpeed)
        // And staging any value is then dirty, since there is nothing to match.
        assertTrue(state.withTypedSpeed(250.0).speedDirty)
    }

    // ------------------------------------------- the escalation invariant

    @Test
    fun `no reachable drag position produces a trio the engine would refuse`() {
        // Sweep every marker across the whole strip, one pixel-equivalent at a
        // time, and assert the draft is always in escalation order. This is the
        // fingertip analogue of the engine's own refusal: if any position here
        // produced soft > medium or medium > hard, the screen would be composing
        // an edit it already knows will bounce.
        var state = loaded(soft = 2000.0, medium = 4000.0, hard = 6000.0)
        listOf(0, 1, 2).forEach { index ->
            (0..400).forEach { step ->
                val asked = REV_MIN_RPM + step * (REV_MAX_RPM - REV_MIN_RPM) / 400.0
                state = state.withDraggedRev(index, asked)
                val (soft, medium, hard) = state.revDraft
                assertTrue(
                    "drag of $index to $asked produced $soft/$medium/$hard",
                    soft <= medium && medium <= hard,
                )
                assertTrue(soft >= REV_MIN_RPM && hard <= REV_MAX_RPM)
            }
        }
    }

    @Test
    fun `a drag stops at its neighbour rather than crossing it`() {
        val state = loaded(soft = 2000.0, medium = 4000.0, hard = 6000.0)

        // Soft dragged well past medium lands *on* medium, not beyond it.
        assertEquals(4000.0, state.withDraggedRev(0, 7000.0).revDraft[0], 1e-9)
        // Hard dragged below medium stops at medium.
        assertEquals(4000.0, state.withDraggedRev(2, 100.0).revDraft[2], 1e-9)
        // Medium is fenced by both.
        assertEquals(6000.0, state.withDraggedRev(1, 9000.0).revDraft[1], 1e-9)
        assertEquals(2000.0, state.withDraggedRev(1, 0.0).revDraft[1], 1e-9)
    }

    @Test
    fun `a drag snaps to the rpm step`() {
        val state = loaded(soft = 2000.0, medium = 4000.0, hard = 6000.0)
        assertEquals(3025.0, state.withDraggedRev(0, 3017.0).revDraft[0], 1e-9)
    }

    @Test
    fun `a typed value that breaks the order is refused with the reason`() {
        val state = loaded(soft = 2000.0, medium = 4000.0, hard = 6000.0)
        val refused = state.withTypedRev(0, 5000.0)

        assertEquals("the draft is untouched", state.revDraft, refused.revDraft)
        val notice = refused.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("escalate"))
        assertTrue("it names the range that was available", notice.contains("4000"))
    }

    @Test
    fun `a typed value past the encodable range is refused`() {
        val state = loaded()
        val refused = state.withTypedRev(2, 9000.0)

        assertEquals(state.revDraft, refused.revDraft)
        assertTrue(refused.notice!!.contains("stored field"))
    }

    @Test
    fun `a typed value inside the order is taken exactly as typed`() {
        // Not snapped: a typed number is a stated intent, and 3017 means 3017.
        val state = loaded(soft = 2000.0, medium = 4000.0, hard = 6000.0)
        val typed = state.withTypedRev(0, 3017.0)

        assertEquals(3017.0, typed.revDraft[0], 1e-9)
        assertNull(typed.notice)
        assertTrue(typed.revDirty)
    }

    @Test
    fun `equal neighbours are allowed — the stock trio has two of them`() {
        // Stock is 0 / 64 / 64, so `<=` and not `<` is the real rule.
        val state = loaded()
        assertNull(state.rejectTypedRev(1, 64.0))
        assertNull(state.rejectTypedRev(2, 64.0))
    }

    @Test
    fun `an already out-of-order bin does not throw, and lets the engine refuse`() {
        // A trio stored backwards would make an empty clamp range; degrading to
        // the field's own range keeps the screen usable and leaves the refusal
        // where it belongs.
        val state = loaded(soft = 6000.0, medium = 4000.0, hard = 2000.0)
        val bounds = state.revBounds(1)

        assertTrue(bounds.start <= bounds.endInclusive)
        assertEquals(3000.0, state.withDraggedRev(1, 3000.0).revDraft[1], 1e-9)
    }

    // ------------------------------------------------------- the speed quartet

    @Test
    fun `a typed speed above the stored field is refused`() {
        val state = loaded()
        val refused = state.withTypedSpeed(600.0)

        assertEquals(200.0, refused.speedDraft!!, 1e-9)
        assertTrue(refused.notice!!.contains("stored field"))
    }

    @Test
    fun `a zero or negative speed is refused`() {
        assertNotNull(loaded().rejectTypedSpeed(0.0))
        assertNotNull(loaded().rejectTypedSpeed(-10.0))
    }

    @Test
    fun `a legal speed stages cleanly`() {
        val state = loaded().withTypedSpeed(250.0)
        assertEquals(250.0, state.speedDraft!!, 1e-9)
        assertTrue(state.speedDirty)
        assertTrue(state.canApply)
        assertNull(state.notice)
    }

    // -------------------------------------------------------------- staging

    @Test
    fun `discard returns both drafts to what the engine holds`() {
        val state = loaded()
            .withTypedRev(0, 500.0)
            .withTypedSpeed(250.0)
            .discardingDraft()

        assertEquals(listOf(0.0, 64.0, 64.0), state.revDraft)
        assertEquals(200.0, state.speedDraft!!, 1e-9)
        assertFalse(state.dirty)
    }

    @Test
    fun `an unapplied limiter draft blocks the actions that would overwrite it`() {
        val editor = EditorUiState(sessionId = "s1", limiters = loaded().withTypedSpeed(250.0))

        assertEquals(DirtyDraft.LIMITERS, editor.dirtyDraft)
        assertFalse("undo would replace the draft with engine values", editor.canMutateSession)
        assertNotNull(editor.dirtyDraftRefusal)
    }

    @Test
    fun `a clean limiters screen blocks nothing`() {
        val editor = EditorUiState(sessionId = "s1", limiters = loaded())
        assertNull(editor.dirtyDraft)
        assertTrue(editor.canMutateSession)
    }

    // ------------------------------------------------- the standstill rev cap

    @Test
    fun `the standstill cap loads with the limiter it sits under`() {
        val state = loaded()

        assertEquals(3808.0, state.staticRevDraft!!, 1e-9)
        assertEquals(6816.0, state.engineRevLimit!!, 1e-9)
        assertFalse("stock is well below the limiter", state.model!!.staticRevAtLimiter)
    }

    @Test
    fun `raising the cap to the limiter stages cleanly`() {
        val state = loaded().withTypedStaticRev(6816.0)

        assertEquals(6816.0, state.staticRevDraft!!, 1e-9)
        assertTrue(state.staticRevDirty)
        assertTrue(state.canApply)
        assertNull(state.notice)
    }

    @Test
    fun `a cap above the engines own limiter is refused, not clamped`() {
        // It could never be reached, so it would change nothing except what the
        // calibration appears to say — and asking for it suggests expecting this
        // control to raise the redline, which it does not do.
        val state = loaded()
        val refused = state.withTypedStaticRev(7200.0)

        assertEquals(3808.0, refused.staticRevDraft!!, 1e-9)
        val notice = refused.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("rev limiter"))
        assertTrue(notice.contains("6816"))
    }

    @Test
    fun `a cap already at the limiter says so`() {
        val state = loaded(staticRev = 6816.0)
        assertTrue(state.model!!.staticRevAtLimiter)
    }

    @Test
    fun `with no limiter reported the cap is not second-guessed`() {
        // A bin whose rev limiter did not resolve leaves the ceiling unknown.
        // Inventing one would be worse than letting the engine refuse.
        val model = LimitersModel.fromJson(
            JSONObject(
                """{"speed_limiter":[],"static_rev_limit":[],
                    "engine_rev_limit":null,"rev_limits":null,"launch_control":null}"""
            )
        )
        val state = LimitersUiState().withModel(model)
        assertNull(state.engineRevLimit)
        assertNull(state.rejectTypedStaticRev(7200.0))
    }

    @Test
    fun `a zero or negative cap is refused`() {
        assertNotNull(loaded().rejectTypedStaticRev(0.0))
        assertNotNull(loaded().rejectTypedStaticRev(-100.0))
    }

    @Test
    fun `discard returns the standstill cap too`() {
        val state = loaded().withTypedStaticRev(6816.0).discardingDraft()
        assertEquals(3808.0, state.staticRevDraft!!, 1e-9)
        assertFalse(state.dirty)
    }
}
