package com.simoscal.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis canvas's coordinate math and its display thinning.
 *
 * Nothing on these plots is editable, so unlike [BoostPlotTest] there is no
 * fingertip-to-value round trip to protect. What is worth protecting instead is
 * that the picture does not lie: an axis that clipped a knock spike, a threshold
 * line that fell off the frame, or a thinning pass that dropped a one-sample
 * overshoot would each hide the exact feature the plot exists to show.
 */
class AnalysisPlotTest {

    private val geometry = AnalysisPlotGeometry(canvasWidth = 800f, canvasHeight = 300f)

    private fun segment(vararg pairs: Pair<Float, Float>) = Segment(
        pairs.map { it.first }.toFloatArray(),
        pairs.map { it.second }.toFloatArray(),
    )

    private fun panel(
        series: List<PlotSeries> = emptyList(),
        thresholds: List<Threshold> = emptyList(),
    ) = PlotPanel("p", "rpm", "y", series, thresholds, drawn = true)

    private fun primary(vararg pairs: Pair<Float, Float>) =
        PlotSeries("put", SeriesRole.PRIMARY, "", 1, 0, listOf(segment(*pairs)))

    // ------------------------------------------------------------------ axes

    @Test
    fun `nice steps come off the 1-2-5 ladder`() {
        listOf(
            1f to 0.2f,
            4f to 1f,
            9f to 2f,
            30f to 10f,
            6500f to 2000f,
        ).forEach { (range, expected) ->
            assertEquals("range=$range", expected, AnalysisAxis.niceStep(range), expected * 1e-3f)
        }
    }

    @Test
    fun `an axis pads outward so a curve never sits on the frame`() {
        val axis = AnalysisAxis.of(low = 2510f, high = 6480f)
        assertTrue("min must not clip the data", axis.min <= 2510f)
        assertTrue("max must not clip the data", axis.max >= 6480f)
        assertTrue(axis.ticks.isNotEmpty())
    }

    @Test
    fun `a flat series still gets a readable frame`() {
        // Every sample identical is a real case — a frozen channel — and it must
        // produce a frame rather than a divide-by-zero.
        val axis = AnalysisAxis.of(low = 5f, high = 5f)
        assertTrue(axis.max > axis.min)
        assertTrue(axis.span > 0f)
    }

    @Test
    fun `thresholds are inside the axis even when the data never reached them`() {
        // A watch line clipped off the top reads exactly like a line that was
        // never crossed — the opposite of what an off-scale threshold means.
        val scale = AnalysisPlotScale.of(
            panel(
                series = listOf(primary(1000f to 0f, 2000f to 1f)),
                thresholds = listOf(Threshold(190f, ThresholdTone.WATCH, "190k")),
            )
        )
        assertTrue(scale.containsY(190f))
        assertTrue(scale.y.max >= 190f)
    }

    @Test
    fun `a panel with no samples still yields a usable scale`() {
        val scale = AnalysisPlotScale.of(panel())
        assertTrue(scale.x.span > 0f)
        assertTrue(scale.y.span > 0f)
    }

    // ----------------------------------------------------------- pixel mapping

    @Test
    fun `values map inside the plot area and increase rightward and upward`() {
        val scale = AnalysisPlotScale.of(panel(series = listOf(primary(1000f to 0f, 7000f to 30f))))
        val left = scale.px(geometry, scale.x.min)
        val right = scale.px(geometry, scale.x.max)
        val bottom = scale.py(geometry, scale.y.min)
        val top = scale.py(geometry, scale.y.max)

        assertEquals(geometry.left, left, 1e-2f)
        assertEquals(geometry.right, right, 1e-2f)
        assertEquals(geometry.bottom, bottom, 1e-2f)
        assertEquals(geometry.top, top, 1e-2f)
        // Screen y grows downward, so a larger value must sit *higher*.
        assertTrue(top < bottom)
    }

    @Test
    fun `the plot area leaves its label gutters`() {
        assertEquals(62f, geometry.left, 1e-3f)
        assertTrue(geometry.right < 800f)
        assertTrue(geometry.bottom < 300f)
    }

    // ---------------------------------------------------------------- thinning

    @Test
    fun `a short segment is returned untouched`() {
        val original = segment(1f to 1f, 2f to 2f, 3f to 3f)
        assertTrue(thinForDisplay(original, limit = 600) === original)
    }

    @Test
    fun `thinning keeps the extremes, which is the whole point`() {
        // A single-sample knock spike buried in a long flat run. Stride sampling
        // — take every nth point — is what would drop it, and dropping it would
        // erase the one feature this plot exists to show.
        val n = 4000
        val x = FloatArray(n) { it.toFloat() }
        val y = FloatArray(n) { 0f }
        y[1234] = -9.5f     // the spike
        y[2500] = 4.25f     // and a peak the other way

        val thinned = thinForDisplay(Segment(x, y), limit = 200)
        assertTrue("thinning must actually reduce", thinned.size <= 200)
        assertEquals(-9.5f, thinned.y.min(), 1e-4f)
        assertEquals(4.25f, thinned.y.max(), 1e-4f)
    }

    @Test
    fun `thinning keeps x moving forward`() {
        val n = 3000
        val x = FloatArray(n) { it.toFloat() }
        val y = FloatArray(n) { kotlin.math.sin(it / 40.0).toFloat() }
        val thinned = thinForDisplay(Segment(x, y), limit = 300)
        for (i in 1 until thinned.size) {
            assertTrue("thinned segment must not step backwards", thinned.x[i] >= thinned.x[i - 1])
        }
    }

    @Test
    fun `thinning spans the original range`() {
        val n = 2000
        val x = FloatArray(n) { 1000f + it }
        val y = FloatArray(n) { it.toFloat() }
        val thinned = thinForDisplay(Segment(x, y), limit = 100)
        assertTrue(thinned.x.first() < 1100f)
        assertTrue(thinned.x.last() > 1000f + n - 100f)
    }
}
