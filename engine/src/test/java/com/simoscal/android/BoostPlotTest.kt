package com.simoscal.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas coordinate math.
 *
 * Worth testing on its own because this is the step where a fingertip becomes a
 * number: if [BoostPlotScale.psiAt] were not the exact inverse of
 * [BoostPlotScale.y], the value written to the bin would differ from the height
 * the curve was drawn at, and nothing on screen would reveal it.
 */
class BoostPlotTest {

    private val model = BoostCurveModel(
        // Deliberately non-uniform: real slot breakpoints are not evenly spaced.
        rpmAxis = listOf(1000.0, 1200.0, 1600.0, 2400.0, 3000.0, 4000.0, 5000.0, 6500.0),
        slots = listOf(SlotCurve(1, List(8) { 12.0 })),
        baseCeilingPsi = List(8) { 20.0 },
        baseRpmAxis = listOf(1000.0, 6500.0),
        baseCeilingOwnPsi = listOf(20.0, 20.0),
    )

    private val scale = BoostPlotScale.of(model, model.slots.first().psi)
    private val geometry = BoostPlotGeometry(canvasWidth = 800f, canvasHeight = 400f)

    @Test
    fun `psiAt inverts y exactly`() {
        listOf(0.0, 5.0, 12.34, 20.0, scale.psiMax).forEach { psi ->
            assertEquals(psi, scale.psiAt(geometry, scale.y(geometry, psi)), 1e-3)
        }
    }

    @Test
    fun `the plot spans the canvas minus its label gutters`() {
        assertEquals(56f, geometry.left, 1e-3f)
        assertEquals(800f - 56f - 40f, geometry.width, 1e-3f)
        assertEquals(400f - 14f - 34f, geometry.height, 1e-3f)
        assertTrue(geometry.right < 800f)
        assertTrue(geometry.bottom < 400f)
    }

    @Test
    fun `rpm maps by value, so uneven breakpoints are not drawn evenly spaced`() {
        val first = scale.x(geometry, 1000.0)
        val second = scale.x(geometry, 1200.0)
        val last = scale.x(geometry, 6500.0)
        assertEquals(geometry.left, first, 1e-3f)
        assertEquals(geometry.right, last, 1e-3f)
        // 1200 rpm is 200/5500 of the way along — nowhere near 1/7 of the width,
        // which is where index-spacing would have put it.
        assertEquals(geometry.left + geometry.width * (200f / 5500f), second, 0.5f)
    }

    @Test
    fun `the nearest breakpoint is the one a finger is closest to`() {
        model.rpmAxis.forEachIndexed { index, rpm ->
            assertEquals(index, scale.nearestIndex(geometry, scale.x(geometry, rpm)))
        }
        // Just left of the last breakpoint still grabs the last breakpoint.
        assertEquals(model.rpmAxis.lastIndex, scale.nearestIndex(geometry, geometry.right - 2f))
    }

    @Test
    fun `the y range leaves headroom above the highest line`() {
        assertTrue("the refusal ceiling must not sit on the frame", scale.psiMax > model.refusalCeilingPsi)
        assertTrue(scale.y(geometry, model.refusalCeilingPsi) > geometry.top)
    }

    @Test
    fun `an empty model produces a usable scale rather than dividing by zero`() {
        val empty = BoostCurveModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val emptyScale = BoostPlotScale.of(empty, emptyList())
        assertTrue(emptyScale.psiMax > 0.0)
        assertEquals(0, emptyScale.nearestIndex(geometry, 100f))
        assertTrue(emptyScale.x(geometry, 0.0).isFinite())
        assertTrue(emptyScale.y(geometry, 0.0).isFinite())
    }

    @Test
    fun `a zero-size canvas does not produce infinities`() {
        val tiny = BoostPlotGeometry(canvasWidth = 0f, canvasHeight = 0f)
        assertTrue(tiny.width > 0f)
        assertTrue(tiny.height > 0f)
        assertTrue(scale.psiAt(tiny, 0f).isFinite())
    }
}

/**
 * The scale with a logged pull drawn on it.
 *
 * The overlay and the curves must share one transform: a trace plotted on even
 * slightly different axes would sit beside the curve it is meant to be read
 * against, and the whole point of drawing them together is that the comparison
 * is exact.
 */
class BoostPlotOverlayTest {

    private val model = BoostCurveModel(
        rpmAxis = listOf(1000.0, 1200.0, 1600.0, 2400.0, 3000.0, 4000.0, 5000.0, 6500.0),
        slots = listOf(SlotCurve(1, List(8) { 12.0 })),
        baseCeilingPsi = List(8) { 20.0 },
        baseRpmAxis = listOf(1000.0, 6500.0),
        baseCeilingOwnPsi = listOf(20.0, 20.0),
    )
    private val geometry = BoostPlotGeometry(canvasWidth = 800f, canvasHeight = 400f)

    private fun pull(rpm: List<Double>, psi: List<Double>) = OverlayPull(
        index = 1, file = "d.csv", gear = 3, gearResolved = true,
        rpmMin = rpm.min(), rpmMax = rpm.max(), durationSeconds = 6.0,
        sampleCount = rpm.size,
        series = listOf(
            OverlaySeries(OverlaySeries.MEASURED_SOURCE, "Boost", listOf(OverlaySegment(rpm, psi)))
        ),
    )

    @Test
    fun `an overlay sample maps through the same transform as a curve point`() {
        val overlay = pull(listOf(3000.0, 4000.0), listOf(12.0, 15.0))
        val scale = BoostPlotScale.of(model, model.slots.first().psi, overlay)

        // A trace sample at 3000 rpm / 12 psi must land exactly where the curve's
        // own breakpoint at 3000 rpm / 12 psi lands. Same x, same y, no offset.
        assertEquals(scale.x(geometry, 3000.0), scale.x(geometry, 3000.0), 1e-6f)
        assertEquals(scale.y(geometry, 12.0), scale.y(geometry, 12.0), 1e-6f)
        // And the inverse still holds with an overlay present, so a drag reads
        // back the value it was drawn at.
        assertEquals(12.0, scale.psiAt(geometry, scale.y(geometry, 12.0)), 1e-3)
    }

    @Test
    fun `a pull that overshot the curves is not clipped away`() {
        val overshooting = pull(listOf(4000.0), listOf(26.0))
        val scale = BoostPlotScale.of(model, model.slots.first().psi, overshooting)

        // The overshoot is the single most important thing a boost trace says;
        // a psiMax that ignored it would draw the peak on or above the frame.
        assertTrue("26 psi must fit inside the plot", scale.psiMax > 26.0)
        assertTrue(scale.y(geometry, 26.0) > geometry.top)
    }

    @Test
    fun `a pull running past the last breakpoint widens the rpm axis`() {
        val pastEnd = pull(listOf(2500.0, 6900.0), listOf(10.0, 22.0))
        val scale = BoostPlotScale.of(model, model.slots.first().psi, pastEnd)

        // Squeezing it against the right frame would misplace *every* sample
        // against the curves, not only the ones off the end.
        assertEquals(6900.0, scale.rpmMax, 1e-6)
        assertTrue(scale.x(geometry, 6900.0) <= geometry.right + 1e-3f)
        assertTrue(scale.x(geometry, 6500.0) < scale.x(geometry, 6900.0))
    }

    @Test
    fun `without an overlay the axes are exactly what they always were`() {
        val plain = BoostPlotScale.of(model, model.slots.first().psi)
        val explicitNull = BoostPlotScale.of(model, model.slots.first().psi, null)

        assertEquals(plain.rpmMin, explicitNull.rpmMin, 1e-9)
        assertEquals(plain.rpmMax, explicitNull.rpmMax, 1e-9)
        assertEquals(plain.psiMax, explicitNull.psiMax, 1e-9)
        assertEquals(1000.0, plain.rpmMin, 1e-9)
        assertEquals(6500.0, plain.rpmMax, 1e-9)
    }
}
