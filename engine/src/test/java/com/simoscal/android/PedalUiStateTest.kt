package com.simoscal.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pedal editor's curve↔grid mapping and staging rules.
 *
 * The mapping is the whole risk on this screen. A curve point is one cell of a
 * 12×12 grid, and an off-by-one between "the point I dragged" and "the cell that
 * changed" would write a pedal response nobody asked for with nothing on screen
 * to reveal it. So the tests below check not only that the right cell moved but
 * that every other cell did not.
 */
class PedalUiStateTest {

    private val pedalAxis = listOf(0.0, 5.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 99.9)
    private val rpmAxis = listOf(700.0, 1000.0, 1500.0, 2000.0, 3000.0, 4000.0)

    /** A grid whose every cell is distinguishable: value = row + col/100. */
    private fun grid(offset: Double = 0.0): List<List<Double>> =
        pedalAxis.indices.map { row ->
            rpmAxis.indices.map { col -> (row / 100.0) + (col / 1000.0) + offset }
        }

    private fun summary(name: String = "pedal_dct_high") = TableSummary(
        space = "base",
        name = name,
        symbol = "IP_FAC_TQ_REQ_DRIV_H_VS_DCT",
        title = "Driver interpretation map for high vehicle speed (DCT)",
        description = "Driver interpretation map for high vehicle speed (DCT)",
        uniqueidHex = "0x1234",
        units = "-",
        rows = pedalAxis.size,
        cols = rpmAxis.size,
        ndim = 2,
        reversible = true,
        isAxis = false,
        categories = emptyList(),
        owner = "",
    )

    private fun detail(
        values: List<List<Double>> = grid(),
        source: List<List<Double>> = grid(),
        reversible: Boolean = true,
        owner: String = "",
    ) = TableDetail(
        summary = summary().copy(reversible = reversible, owner = owner),
        values = values,
        xAxis = TableAxis(units = "rpm", values = rpmAxis, label = "Engine speed [rpm]"),
        yAxis = TableAxis(units = "%", values = pedalAxis, label = "Pedal value [%]"),
        sourceValues = source,
    )

    private fun loaded(
        values: List<List<Double>> = grid(),
        source: List<List<Double>> = grid(),
    ) = PedalUiState().withDetail(detail(values, source))

    // ------------------------------------------------------- curve ↔ grid

    @Test
    fun `the curve is one engine-speed column, top to bottom of the pedal axis`() {
        val state = loaded()

        assertEquals(pedalAxis.size, state.draft.size)
        // Column 0: every row's first cell, in row order.
        assertEquals(grid().map { it[0] }, state.draft)
        assertEquals(700.0, state.columnRpm!!, 1e-9)
    }

    @Test
    fun `switching column reads that column, not a re-indexed one`() {
        val state = loaded().selectingColumn(4)

        assertEquals(3000.0, state.columnRpm!!, 1e-9)
        assertEquals(grid().map { it[4] }, state.draft)
    }

    @Test
    fun `a dragged point edits exactly one cell`() {
        // The screen's Apply composes the full grid from the draft; this is that
        // composition, and it must touch one cell only.
        val state = loaded().selectingColumn(2).withDraggedPoint(5, 0.42)
        val proposed = state.detail!!.values.mapIndexed { row, cells ->
            cells.mapIndexed { col, value ->
                if (col == state.column) state.draft.getOrElse(row) { value } else value
            }
        }

        assertEquals(0.42, proposed[5][2], 1e-9)
        proposed.indices.forEach { row ->
            proposed[row].indices.forEach { col ->
                if (row != 5 || col != 2) {
                    assertEquals(
                        "cell ($row,$col) must not have moved",
                        grid()[row][col], proposed[row][col], 1e-12,
                    )
                }
            }
        }
    }

    @Test
    fun `a drag is clamped into the legal range and snapped`() {
        val state = loaded()

        assertEquals(1.0, state.withDraggedPoint(0, 3.0).draft[0], 1e-9)
        assertEquals(0.0, state.withDraggedPoint(0, -2.0).draft[0], 1e-9)
        assertEquals(0.123, state.withDraggedPoint(0, 0.12345).draft[0], 1e-9)
    }

    // ------------------------------------------------------------- the ghost

    @Test
    fun `the ghost is the imported bin, and does not follow the draft`() {
        val state = loaded().withDraggedPoint(3, 0.9)

        assertEquals(grid().map { it[0] }, state.ghost)
        assertEquals(0.9, state.draft[3], 1e-9)
        assertTrue("the ghost is not the draft", state.ghost[3] != state.draft[3])
    }

    @Test
    fun `the ghost stays put after an edit is applied`() {
        // The reference must survive committing, or it stops being a reference:
        // a ghost that tracked the last-applied values would always sit exactly
        // on the curve and say nothing.
        val applied = loaded()
            .withDraggedPoint(3, 0.9)
            .applied(grid(offset = 0.5), "Applied.")

        assertEquals(grid().map { it[0] }, applied.ghost)
        assertEquals(grid(offset = 0.5).map { it[0] }, applied.draft)
    }

    @Test
    fun `no source values means no ghost, never a stand-in`() {
        // Filling the ghost from the committed values would draw it exactly on
        // the working curve and claim nothing had changed — a claim the screen
        // would have no evidence for.
        val state = PedalUiState().withDetail(detail(source = emptyList()))

        assertTrue(state.ghost.isEmpty())
        val refused = state.revertingToSource()
        assertEquals(state.draft, refused.draft)
        assertNotNull(refused.notice)
    }

    @Test
    fun `reverting to source restores the imported curve`() {
        val state = loaded(values = grid(offset = 0.3), source = grid())
            .withDraggedPoint(2, 0.75)
            .revertingToSource()

        assertEquals(grid().map { it[0] }, state.draft)
        assertTrue("and that is a change from what the engine holds", state.dirty)
    }

    // -------------------------------------------------------------- staging

    @Test
    fun `a typed factor above one is refused with a reason`() {
        val state = loaded()
        val refused = state.withTypedPoint(4, 1.5)

        assertEquals(state.draft, refused.draft)
        val notice = refused.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("1.000"))
    }

    @Test
    fun `a typed factor is taken exactly, not snapped`() {
        val typed = loaded().withTypedPoint(4, 0.4321)
        assertEquals(0.4321, typed.draft[4], 1e-12)
        assertNull(typed.notice)
    }

    @Test
    fun `a negative typed factor is refused`() {
        assertNotNull(rejectTypedFactor(-0.1))
        assertNotNull(rejectTypedFactor(Double.NaN))
        assertNull(rejectTypedFactor(0.0))
        assertNull(rejectTypedFactor(1.0))
    }

    @Test
    fun `switching column with an unapplied draft is refused, not silently dropped`() {
        val dirty = loaded().withDraggedPoint(3, 0.9)
        val blocked = dirty.selectingColumn(3)

        assertEquals("still on the original column", 0, blocked.column)
        assertEquals(dirty.draft, blocked.draft)
        assertNotNull(blocked.notice)
    }

    @Test
    fun `switching column is fine once the draft is clean`() {
        val state = loaded().withDraggedPoint(3, 0.9).discardingDraft().selectingColumn(3)
        assertEquals(3, state.column)
        assertFalse(state.dirty)
    }

    @Test
    fun `a read-only or owned map is not applyable`() {
        val readOnly = PedalUiState().withDetail(detail(reversible = false))
            .withDraggedPoint(1, 0.5)
        assertFalse(readOnly.editable)

        val owned = PedalUiState().withDetail(detail(owner = "tune.something()"))
            .withDraggedPoint(1, 0.5)
        assertFalse(owned.editable)
    }

    @Test
    fun `an unapplied pedal draft blocks the actions that would overwrite it`() {
        val editor = EditorUiState(sessionId = "s1", pedal = loaded().withDraggedPoint(1, 0.5))

        assertEquals(DirtyDraft.PEDAL, editor.dirtyDraft)
        assertFalse(editor.canMutateSession)
        assertNotNull(editor.dirtyDraftRefusal)
    }

    @Test
    fun `applied folds the encoded grid back, not the requested one`() {
        // A factor is stored /32768, so what the bin holds can sit a hair off
        // what was asked for. Showing the request back would overstate it.
        val encoded = grid(offset = 0.001)
        val state = loaded().withDraggedPoint(2, 0.5).applied(encoded, "Applied.")

        assertEquals(encoded, state.detail!!.values)
        assertEquals(encoded.map { it[0] }, state.draft)
        assertFalse("folding the receipt back leaves nothing staged", state.dirty)
    }
}
