package com.simoscal.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class TablePlotTest {
    @Test fun `automatic step ladder uses round numbers across calibration scales`() {
        assertEquals(listOf(0.1, 0.2, 0.5, 1.0, 2.0), tablePlotSteps(listOf(-18.0, 40.0)))
        assertEquals(0.001, tablePlotSteps(listOf(0.8, 0.9))[2], 1e-12)
        assertEquals(2.0, tablePlotSteps(listOf(2000.0, 2000.0))[2], 0.0)
        listOf(listOf(0.0), listOf(0.001, 0.003), listOf(-1000.0, -500.0), emptyList()).forEach { values ->
            val steps = tablePlotSteps(values)
            assertEquals(5, steps.size)
            assertTrue(steps.zipWithNext().all { (a, b) -> a > 0 && b > a })
            steps.forEach { step ->
                val scaled = step / 10.0.pow(kotlin.math.floor(kotlin.math.log10(step)))
                assertTrue(listOf(1.0, 2.0, 5.0, 10.0).any { kotlin.math.abs(it - scaled) < 1e-9 })
            }
        }
    }

    @Test fun `nudges preserve off step values and reverse without decimal drift`() {
        val raised = tablePlotNudge(17.625, 0.2, 1)
        assertEquals(17.825, raised, 0.0)
        assertEquals(17.625, tablePlotNudge(raised, 0.2, -1), 0.0)
        assertEquals(-0.1, tablePlotNudge(0.0, 0.1, -1), 0.0)
        var value = 0.0
        repeat(10) { value = tablePlotNudge(value, 0.1, 1) }
        assertEquals(1.0, value, 0.0)
    }

    @Test fun `zoom preserves its anchor and pan follows the finger in data space`() {
        val original = TablePlotViewport(TablePlotRange(0.0, 100.0, 20.0), TablePlotRange(-10.0, 10.0, 5.0))
        val zoomed = original.transformed(0.25f, 0.75f, 0f, 0f, 2f)
        assertEquals(25.0, zoomed.x.value(0.25f), 1e-9)
        assertEquals(5.0, zoomed.y.value(0.75f), 1e-9)
        assertEquals(50.0, zoomed.x.max - zoomed.x.min, 1e-9)
        val panned = zoomed.transformed(0.5f, 0.5f, 0.1f, -0.2f, 1f)
        assertEquals(zoomed.x.value(0.5f), panned.x.value(0.6f), 1e-5)
        assertEquals(zoomed.y.value(0.5f), panned.y.value(0.3f), 1e-5)
        val inverse = zoomed.transformed(0.25f, 0.75f, 0f, 0f, 0.5f)
        assertEquals(original.x.min, inverse.x.min, 1e-9)
        assertEquals(original.y.max, inverse.y.max, 1e-9)
    }

    @Test fun `zoom is bounded and visible ticks stay inside the viewport`() {
        val original = TablePlotViewport(TablePlotRange(0.0, 100.0, 20.0), TablePlotRange(-10.0, 10.0, 5.0))
        val zoomed = original.transformed(0.4f, 0.6f, 0f, 0f, 10000f)
        assertEquals(100f, zoomed.zoom)
        assertEquals(0.5f, original.transformed(0.5f, 0.5f, 0f, 0f, 0.001f).zoom)
        assertEquals(original, original.transformed(0f, 0f, Float.NaN, 0f, 1f))
        listOf(zoomed.x, zoomed.y).forEach { range ->
            assertTrue(range.ticks.isNotEmpty())
            assertTrue(range.ticks.all { it >= range.min && it <= range.max })
            assertEquals(range.value(0.3f), range.value(range.fraction(range.value(0.3f))), 1e-6)
        }
    }

    @Test fun `zoomed gestures cannot grab horizontally hidden breakpoints`() {
        val model = TablePlotModel(state(), TablePlotDirection.Columns)
        assertEquals(1, model.nearestVisiblePoint(1000.0, TablePlotRange(1200.0, 5000.0, 1000.0)))
        assertNull(model.nearestVisiblePoint(3000.0, TablePlotRange(2000.0, 5000.0, 1000.0)))
    }

    private fun state(): TablesUiState = TablesUiState().withDetail(TableDetail(
        summary = TableSummary.fromJson(JSONObject("""{
            "name":"test_grid", "shape":[2,3], "ndim":2, "reversible":true
        }""")),
        values = listOf(listOf(-2.0, 0.0, 7.0), listOf(3.0, 5.0, 9.0)),
        xAxis = TableAxis("rpm", listOf(1000.0, 1500.0, 6000.0), label = "Engine speed [rpm]"),
        yAxis = TableAxis("%", listOf(10.0, 80.0), label = "Pedal [%]"),
    ))

    @Test fun `columns plot rows as curves and rows plot columns as curves`() {
        val state = state()
        assertTrue(TablePlotModel.available(state))
        val columns = TablePlotModel(state, TablePlotDirection.Columns)
        assertEquals(listOf(1000.0, 1500.0, 6000.0), columns.xAxis.values)
        assertEquals(listOf(10.0, 80.0), columns.constantAxis.values)
        assertEquals(7.0, columns.value(0, 2), 0.0)
        val rows = TablePlotModel(state, TablePlotDirection.Rows)
        assertEquals(listOf(10.0, 80.0), rows.xAxis.values)
        assertEquals(listOf(1000.0, 1500.0, 6000.0), rows.constantAxis.values)
        assertEquals(7.0, rows.value(2, 0), 0.0)
        assertEquals(1, columns.nearestPoint(2000.0))
    }

    @Test fun `edits in both orientations affect only the intended shared cells`() {
        val before = state()
        val edited = before
            .withTypedCell(TablePlotDirection.Columns.cell(1, 2), 12.0)
            .withTypedCell(TablePlotDirection.Rows.cell(0, 1), -8.0)
        assertEquals(listOf(listOf(-2.0, 0.0, 7.0), listOf(-8.0, 5.0, 12.0)), edited.draft)
        assertEquals(setOf(CellRef(1, 0), CellRef(1, 2)), edited.changedCells.toSet())
        assertEquals(12.0, TablePlotModel(edited, TablePlotDirection.Rows).value(2, 1), 0.0)
        assertEquals(before.draft, edited.discardingDraft().draft)
        assertEquals(before.committed, edited.committed)
    }

    @Test fun `missing or malformed breakpoints are explicitly plotted as indices`() {
        val before = state()
        val detail = before.detail!!.copy(xAxis = null, yAxis = TableAxis("%", listOf(Double.NaN)))
        val model = TablePlotModel(before.withDetail(detail), TablePlotDirection.Columns)
        assertEquals(listOf(1.0, 2.0, 3.0), model.xAxis.values)
        assertEquals(listOf(1.0, 2.0), model.constantAxis.values)
        assertTrue(model.xAxis.label.contains("index"))
        assertTrue(model.constantAxis.label.contains("unavailable"))
    }

    @Test fun `ragged and non finite data do not offer a plot`() {
        assertFalse(TablePlotModel.available(TablesUiState()))
        // Ragged: a row the plot would index past the end of.
        assertFalse(TablePlotModel.available(state().copy(draft = listOf(listOf(1.0, 2.0), listOf(1.0)))))
        // Non-finite: a value with no position on any axis.
        assertFalse(TablePlotModel.available(state().withTypedCell(CellRef(0, 0), 1.0).let {
            it.copy(draft = listOf(listOf(Double.NaN, 0.0, 7.0), it.draft[1]))
        }))
        // A single row is a vector, not a non-grid: it is the case the plot
        // editor serves best, and it is offered along its one long dimension.
        assertEquals(
            listOf(TablePlotDirection.Columns),
            TablePlotModel.directions(state().copy(draft = listOf(listOf(1.0, 2.0)))),
        )
    }

    @Test fun `range brackets negative tiny and constant values and inverts coordinates`() {
        listOf(listOf(-15.0, -3.0), listOf(-1.0, 5.0), listOf(0.0, 0.0),
            listOf(0.002, 0.003), listOf(2000.0, 2000.0)).forEach { values ->
            val range = TablePlotRange.of(values)
            assertTrue(range.min < values.min())
            assertTrue(range.max > values.max())
            assertTrue(range.ticks.size in 3..8)
            values.forEach { assertEquals(it, range.value(range.fraction(it)), (range.max - range.min) * 1e-6) }
            // Dragging beyond the frame must not silently clamp a requested value.
            assertTrue(range.value(1.1f) > range.max)
        }
    }

    // ------------------------------------------------- vectors, frames, ghosts

    /** A 1-D table as the bridge sends one: a flat list normalized to a grid. */
    private fun vector(
        values: List<List<Double>>,
        xAxis: TableAxis? = null,
        yAxis: TableAxis? = null,
        source: List<List<Double>> = emptyList(),
    ): TablesUiState = TablesUiState().withDetail(TableDetail(
        summary = TableSummary.fromJson(JSONObject("""{
            "name":"vector_table", "shape":[${values.size},${values.first().size}],
            "ndim":1, "reversible":true
        }""")),
        values = values,
        xAxis = xAxis,
        yAxis = yAxis,
        sourceValues = source,
    ))

    @Test fun `a vector table offers exactly the orientation that has length`() {
        val asRow = vector(listOf(listOf(1.0, 2.0, 3.0)))
        assertTrue(TablePlotModel.available(asRow))
        assertEquals(listOf(TablePlotDirection.Columns), TablePlotModel.directions(asRow))

        val asColumn = vector(listOf(listOf(1.0), listOf(2.0), listOf(3.0)))
        assertTrue(TablePlotModel.available(asColumn))
        assertEquals(listOf(TablePlotDirection.Rows), TablePlotModel.directions(asColumn))

        assertEquals(
            listOf(TablePlotDirection.Columns, TablePlotDirection.Rows),
            TablePlotModel.directions(state()),
        )
    }

    @Test fun `a vector plots as one curve over its own breakpoints`() {
        val rpm = TableAxis("rpm", listOf(1000.0, 3000.0, 6000.0), label = "Engine speed [rpm]")
        val asRow = TablePlotModel(vector(listOf(listOf(4.0, 9.0, 2.0)), xAxis = rpm), TablePlotDirection.Columns)
        assertEquals(1, asRow.curves)
        assertEquals(rpm.values, asRow.xAxis.values)
        assertEquals("Engine speed [rpm]", asRow.xAxis.label)
        assertEquals(9.0, asRow.value(0, 1), 0.0)
        assertEquals(CellRef(0, 1), TablePlotDirection.Columns.cell(0, 1))
    }

    @Test fun `a column vector keeps its breakpoints whichever axis carried them`() {
        // The engine files a 1-D table's breakpoints under whichever axis the XDF
        // used; only one axis can be as long as the plotted dimension, so the
        // label is unambiguous and must not degrade to an index.
        val rpm = TableAxis("rpm", listOf(1000.0, 3000.0, 6000.0), label = "Engine speed [rpm]")
        val column = listOf(listOf(4.0), listOf(9.0), listOf(2.0))
        listOf(vector(column, xAxis = rpm), vector(column, yAxis = rpm)).forEach { state ->
            val model = TablePlotModel(state, TablePlotDirection.Rows)
            assertEquals(rpm.values, model.xAxis.values)
            assertEquals("Engine speed [rpm]", model.xAxis.label)
            assertEquals(1, model.curves)
            assertEquals(9.0, model.value(0, 1), 0.0)
        }
    }

    @Test fun `a square table never borrows the other axis's label`() {
        val pedal = TableAxis("%", listOf(10.0, 80.0), label = "Pedal [%]")
        val square = vector(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), yAxis = pedal)
        val model = TablePlotModel(square, TablePlotDirection.Columns)
        assertTrue(model.xAxis.label.contains("index"))
        assertEquals(listOf(1.0, 2.0), model.xAxis.values)
        assertEquals(pedal.values, model.constantAxis.values)
    }

    @Test fun `a scalar table offers no plot at all`() {
        assertEquals(emptyList<TablePlotDirection>(), TablePlotModel.directions(vector(listOf(listOf(7.0)))))
        assertFalse(TablePlotModel.available(vector(listOf(listOf(7.0)))))
    }

    @Test fun `the ghost is drawn only when the engine sent a whole one`() {
        val values = listOf(listOf(4.0, 9.0, 2.0))
        val withGhost = TablePlotModel(
            vector(values, source = listOf(listOf(3.0, 8.0, 1.0))), TablePlotDirection.Columns,
        )
        assertTrue(withGhost.hasGhost)
        assertEquals(8.0, withGhost.ghost(0, 1)!!, 0.0)
        assertEquals(listOf(3.0, 8.0, 1.0), withGhost.ghostValues)

        // A recovered session sends none: that is "nothing to draw", not "unchanged".
        val none = TablePlotModel(vector(values), TablePlotDirection.Columns)
        assertFalse(none.hasGhost)
        assertNull(none.ghost(0, 1))
        assertEquals(emptyList<Double>(), none.ghostValues)

        // A ghost of the wrong shape is refused rather than indexed into.
        val ragged = TablePlotModel(
            vector(values, source = listOf(listOf(3.0, 8.0))), TablePlotDirection.Columns,
        )
        assertFalse(ragged.hasGhost)
        assertNull(ragged.ghost(0, 1))
    }

    @Test fun `the plot frame is the exact inverse of the renderer's coordinates`() {
        val frame = TablePlotFrame(left = 72f, top = 12f, right = 372f, bottom = 292f)
        assertTrue(frame.usable)
        assertEquals(300f, frame.width, 0f)
        assertEquals(280f, frame.height, 0f)
        assertTrue(frame.contains(72f, 12f) && frame.contains(372f, 292f))
        assertFalse(frame.contains(71f, 100f))
        assertFalse(frame.contains(200f, 293f))

        // Fractions run rightwards and upwards, matching the data axes: the
        // bottom of the frame is 0 and the top is 1, so a fingertip dragged up
        // raises the value it is holding.
        assertEquals(0f, frame.fractionX(frame.left), 1e-6f)
        assertEquals(1f, frame.fractionX(frame.right), 1e-6f)
        assertEquals(0f, frame.fractionY(frame.bottom), 1e-6f)
        assertEquals(1f, frame.fractionY(frame.top), 1e-6f)

        // Round-tripping a value through the renderer's y and the gesture's
        // fractionY must land back on the same number.
        val range = TablePlotRange(-10.0, 10.0, 5.0)
        listOf(-9.0, 0.0, 3.25, 9.5).forEach { value ->
            val y = frame.bottom - range.fraction(value) * frame.height
            assertEquals(value, range.value(frame.fractionY(y)), 1e-4)
        }

        // A collapsed frame divides by zero; it is refused, not drawn.
        assertFalse(TablePlotFrame(72f, 12f, 60f, 292f).usable)
        assertFalse(TablePlotFrame(72f, 12f, 372f, 12f).usable)
    }

    @Test fun `a pinch that began as a drag puts the value it moved back`() {
        // The hazard this exists for: two fingers on a plot in Edit mode. The
        // first lands, drags a breakpoint, and only then does the second arrive
        // and declare the gesture a zoom. The started edit must not survive it.
        val touch = TablePlotTouch(point = 4, original = 17.625)
        assertEquals(4, touch.point)
        touch.edited()
        assertTrue(touch.wrote)
        assertEquals(4 to 17.625, touch.escalate())
        // Escalated: it is a view change now and can no longer write anything.
        assertTrue(touch.pinching)
        assertNull(touch.point)
        assertFalse(touch.wrote)
        touch.edited()
        assertFalse(touch.wrote)
        // A third finger is the same pinch, and must not re-issue the undo over
        // whatever the zoom has since let someone do.
        assertNull(touch.escalate())
    }

    @Test fun `a pinch that never moved anything has nothing to undo`() {
        val untouched = TablePlotTouch(point = 2, original = 0.8)
        assertNull(untouched.escalate())
        assertTrue(untouched.pinching)
        assertNull(untouched.point)

        // A touch that started off the plot, or in Navigate mode, holds no point.
        val outside = TablePlotTouch(point = null, original = 0.0)
        outside.edited()
        assertFalse(outside.wrote)
        assertNull(outside.escalate())
    }
}
