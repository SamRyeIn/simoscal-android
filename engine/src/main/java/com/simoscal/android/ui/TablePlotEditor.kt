package com.simoscal.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoscal.android.CellRef
import com.simoscal.android.TablePlotDirection
import com.simoscal.android.TablePlotFrame
import com.simoscal.android.TablePlotModel
import com.simoscal.android.TablePlotRange
import com.simoscal.android.TablePlotTouch
import com.simoscal.android.TablePlotViewport
import com.simoscal.android.tablePlotSteps
import com.simoscal.android.tablePlotNudge
import com.simoscal.android.TablesUiState
import com.simoscal.android.ValueFormat
import com.simoscal.android.displayExact

/** The axis gutters, shared by the renderer and the gesture handler. */
private val PlotGutterLeft = 72.dp
private val PlotGutterRight = 28.dp
private val PlotGutterBottom = 28.dp
private val PlotHeadroom = 12.dp

/**
 * Zoom and pan are presentation, but they must survive a rotation: a tablet
 * turned mid-edit that snapped back to the whole table would lose the window
 * someone had lined up on the breakpoints they were working.
 */
private val ViewportSaver = listSaver<TablePlotViewport, Any>(
    save = {
        listOf(it.x.min, it.x.max, it.x.step, it.y.min, it.y.max, it.y.step, it.zoom)
    },
    restore = {
        TablePlotViewport(
            TablePlotRange(it[0] as Double, it[1] as Double, it[2] as Double),
            TablePlotRange(it[3] as Double, it[4] as Double, it[5] as Double),
            it[6] as Float,
        )
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TablePlotEditor(
    tables: TablesUiState,
    enabled: Boolean,
    onEdit: (CellRef, Double) -> Unit,
    onTap: (CellRef) -> Unit,
) {
    val identity = tables.detail!!.summary.let { "${it.space}/${it.name}" }
    // Keyed on the whole state, not just its shape: a draft that stops being
    // plottable has to withdraw the plot, and shape is not the only way that
    // happens.
    val directions = remember(tables) { TablePlotModel.directions(tables) }
    if (directions.isEmpty()) return
    // A vector table has one sensible orientation; keeping a stale saved choice
    // would index the plot down a dimension one point long.
    var chosenDirection by rememberSaveable(identity) { mutableStateOf(directions.first()) }
    val direction = if (chosenDirection in directions) chosenDirection else directions.first()
    var selectedCurve by rememberSaveable(identity, direction) { mutableStateOf(0) }
    var selectedPoint by rememberSaveable(identity, direction) { mutableStateOf(0) }
    val model = remember(tables, direction) { TablePlotModel(tables, direction) }
    val curve = selectedCurve.coerceIn(model.constantAxis.values.indices)
    val point = selectedPoint.coerceIn(model.xAxis.values.indices)
    val curveFormat = remember(model.constantAxis) { ValueFormat.of(model.constantAxis.values) }
    val steps = remember(identity) { tablePlotSteps(tables.committed.flatten()) }
    var stepIndex by rememberSaveable(identity) { mutableStateOf(2) }
    val step = steps[stepIndex]
    var navigate by rememberSaveable(identity) { mutableStateOf(false) }
    // The ghost is bracketed with the draft so the reference cannot start off
    // frame — a comparison you have to zoom out to find is not a comparison.
    fun fitted() = TablePlotViewport(
        TablePlotRange.of(model.xAxis.values),
        TablePlotRange.of(tables.draft.flatten() + model.ghostValues),
    )
    var viewport by rememberSaveable(identity, direction, stateSaver = ViewportSaver) { mutableStateOf(fitted()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (directions.size > 1) {
            Kicker("X axis", color = PromoPalette.TextFaint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                directions.forEach { option ->
                    FilterChip(
                        selected = direction == option,
                        onClick = { chosenDirection = option },
                        label = { Text(option.label) },
                        colors = promoFilterChipColors(),
                    )
                }
            }
        }
        Caption("X: ${model.xAxis.label}")
        Caption("Y: Lookup value [${tables.detail.summary.unitsText}]")
        if (model.curves > 1) {
            Kicker("${model.constantAxis.label} — choose curve", color = PromoPalette.TextFaint)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.constantAxis.values.forEachIndexed { index, value ->
                    FilterChip(
                        selected = curve == index,
                        onClick = { selectedCurve = index },
                        label = { Text("${index + 1}: ${curveFormat.format(value)}", color = tableCurveColor(index)) },
                        colors = promoFilterChipColors(),
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !navigate, onClick = { navigate = false },
                label = { Text("Edit points") }, colors = promoFilterChipColors(), enabled = enabled)
            FilterChip(selected = navigate, onClick = { navigate = true },
                label = { Text("Navigate") }, colors = promoFilterChipColors())
            PromoOutlinedButton(onClick = { viewport = viewport.transformed(0.5f, 0.5f, 0f, 0f, 1.5f) }) { Text("Zoom +") }
            PromoOutlinedButton(onClick = { viewport = viewport.transformed(0.5f, 0.5f, 0f, 0f, 1f / 1.5f) }) { Text("Zoom −") }
            PromoOutlinedButton(onClick = { viewport = fitted() }) { Text("Reset view") }
        }
        Caption(
            if (navigate || !enabled) "Pinch to zoom · drag to pan. Values are unchanged."
            else "Drag a point to edit · pinch to zoom, which never edits."
        )
        TablePlotCanvas(
            model = model,
            curve = curve,
            point = point,
            enabled = enabled,
            navigate = navigate || !enabled,
            viewport = viewport,
            onViewport = { viewport = it },
            onEdit = { index, value ->
                selectedPoint = index
                onEdit(direction.cell(curve, index), value)
            },
            onTap = { index ->
                selectedPoint = index
                onTap(direction.cell(curve, index))
            },
        )
        Caption(
            "${model.xAxis.label}: ${model.xAxis.values[point].displayExact()} · " +
                "Value: ${model.value(curve, point).displayExact()} ${tables.detail.summary.unitsText}" +
                (model.ghost(curve, point)?.let { " · imported: ${it.displayExact()}" }.orEmpty())
        )
        if (enabled) {
            Panel(padding = 12.dp, spacing = 8.dp) {
                Kicker("Breakpoint ${point + 1} of ${model.xAxis.values.size}", color = PromoPalette.TextFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromoOutlinedButton(onClick = { selectedPoint = point - 1 }, enabled = point > 0) { Text("◀") }
                    PromoOutlinedButton(onClick = { onTap(direction.cell(curve, point)) }) { Text("Type value") }
                    PromoOutlinedButton(onClick = { selectedPoint = point + 1 }, enabled = point < model.xAxis.values.lastIndex) { Text("▶") }
                }
                Kicker("Step [${tables.detail.summary.unitsText}]", color = PromoPalette.TextFaint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.forEachIndexed { index, value ->
                        FilterChip(selected = stepIndex == index, onClick = { stepIndex = index },
                            label = { Text(value.displayExact()) }, colors = promoFilterChipColors())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromoOutlinedButton(onClick = {
                        onEdit(direction.cell(curve, point), tablePlotNudge(model.value(curve, point), step, -1))
                    }, modifier = Modifier.weight(1f)) { Text("− ${step.displayExact()}") }
                    PromoOutlinedButton(onClick = {
                        onEdit(direction.cell(curve, point), tablePlotNudge(model.value(curve, point), step, 1))
                    }, modifier = Modifier.weight(1f)) { Text("+ ${step.displayExact()}") }
                }
            }
        }
        Caption(buildString {
            if (enabled) {
                append("Drag a point on the highlighted curve vertically, or tap to type a value. ")
                append("Curve and axis switches keep your draft; Apply commits all changed cells. ")
            } else {
                append("Select a legend entry to highlight its curve. This plot is read-only. ")
            }
            if (model.hasGhost) append("The dashed curve is the imported bin, before this session.")
            else append("No imported-bin curve was sent for this session, so none is drawn.")
        })
    }
}

private fun tableCurveColor(index: Int): Color = Color.hsv((index * 137.508f) % 360f, 0.55f, 0.95f)

@OptIn(ExperimentalTextApi::class)
@Composable
private fun TablePlotCanvas(
    model: TablePlotModel,
    curve: Int,
    point: Int,
    enabled: Boolean,
    navigate: Boolean,
    viewport: TablePlotViewport,
    onViewport: (TablePlotViewport) -> Unit,
    onEdit: (Int, Double) -> Unit,
    onTap: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val xRange = viewport.x
    val yRange = viewport.y
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnViewport by rememberUpdatedState(onViewport)
    val latestEdit by rememberUpdatedState(onEdit)
    // The model is rebuilt on every edit, so it can never be a gesture key: a
    // drag that restarted its own handler each time it moved a point would
    // grab a fresh breakpoint every frame. Keyed on the axis instead, which is
    // stable across edits, and read through here so a *new* gesture still
    // starts from the current values.
    val latestModel by rememberUpdatedState(model)
    val latestTap by rememberUpdatedState(onTap)
    val labelStyle = TextStyle(fontSize = 10.sp, color = PromoPalette.TextDim)
    val yFormat = ValueFormat.of(yRange.ticks)
    val xFormat = ValueFormat.of(xRange.ticks)
    val density = LocalDensity.current
    val gutterLeft = with(density) { PlotGutterLeft.toPx() }
    val gutterRight = with(density) { PlotGutterRight.toPx() }
    val gutterBottom = with(density) { PlotGutterBottom.toPx() }
    val headroom = with(density) { PlotHeadroom.toPx() }
    fun frameOf(width: Float, height: Float) =
        TablePlotFrame(gutterLeft, headroom, width - gutterRight, height - gutterBottom)

    Canvas(
        Modifier.fillMaxWidth().height(320.dp)
            // One gesture loop rather than three overlapping detectors, so the
            // decision "is this touch an edit or a view change" is made once and
            // in one place. A pinch that starts as a one-finger touch reverts
            // the value it began to drag the moment the second finger lands:
            // zooming in to read a curve must never be able to change it.
            .pointerInput(model.direction, model.xAxis, curve, enabled, navigate, gutterLeft) {
                awaitEachGesture {
                    val frame = frameOf(size.width.toFloat(), size.height.toFloat())
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val heldX = latestViewport.x
                    val heldY = latestViewport.y
                    val editing = !navigate && enabled
                    val grid = latestModel
                    val grabbed = if (editing && frame.usable && frame.contains(down.position.x, down.position.y)) {
                        grid.nearestVisiblePoint(heldX.value(frame.fractionX(down.position.x)), heldX)
                    } else null
                    val touch = TablePlotTouch(grabbed, grabbed?.let { grid.value(curve, it) } ?: 0.0)
                    var moved = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed > 1 && !touch.pinching) {
                            touch.escalate()?.let { (index, value) -> latestEdit(index, value) }
                        }

                        if (touch.pinching) {
                            if (!frame.usable) continue
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid != Offset.Unspecified && (zoom != 1f || pan != Offset.Zero)) {
                                latestOnViewport(
                                    latestViewport.transformed(
                                        frame.fractionX(centroid.x), frame.fractionY(centroid.y),
                                        pan.x / frame.width, -pan.y / frame.height, zoom,
                                    )
                                )
                                event.changes.forEach { it.consume() }
                            }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!change.pressed) break
                        if (!moved && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                            continue
                        }
                        moved = true
                        if (!frame.usable) continue

                        if (editing) {
                            touch.point?.let { index ->
                                change.consume()
                                latestEdit(index, heldY.value(frame.fractionY(change.position.y)))
                                touch.edited()
                            }
                        } else {
                            val delta = change.positionChange()
                            latestOnViewport(
                                latestViewport.transformed(
                                    0.5f, 0.5f, delta.x / frame.width, -delta.y / frame.height, 1f,
                                )
                            )
                            change.consume()
                        }
                    }

                    if (!moved && !touch.pinching && editing) touch.point?.let { latestTap(it) }
                }
            },
    ) {
        val frame = frameOf(size.width, size.height)
        if (!frame.usable) return@Canvas
        fun px(value: Double) = frame.left + xRange.fraction(value) * frame.width
        fun py(value: Double) = frame.bottom - yRange.fraction(value) * frame.height

        yRange.ticks.forEach { value ->
            val y = py(value)
            drawLine(PromoPalette.Rule, Offset(frame.left, y), Offset(frame.right, y), 1f)
            drawText(
                textMeasurer = measurer,
                text = yFormat.format(value),
                topLeft = Offset(0f, (y - 6.dp.toPx()).coerceAtLeast(0f)),
                style = labelStyle,
            )
        }
        xRange.ticks.forEach { value ->
            val x = px(value)
            drawLine(PromoPalette.Rule, Offset(x, frame.top), Offset(x, frame.bottom), 1f)
            val label = measurer.measure(xFormat.format(value), labelStyle)
            drawText(label, topLeft = Offset((x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), frame.bottom + 4.dp.toPx()))
        }
        clipRect(frame.left, frame.top, frame.right, frame.bottom) {
            fun polyline(points: List<Offset>) = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }

            val order = model.constantAxis.values.indices.filter { it != curve } + curve
            order.forEach { index ->
                val active = index == curve
                val color = tableCurveColor(index).let { if (active) it else it.copy(alpha = 0.36f) }
                if (active) {
                    val ghost = model.xAxis.values.indices.map { p -> model.ghost(index, p) }
                    if (ghost.all { it != null }) {
                        drawPath(
                            polyline(model.xAxis.values.mapIndexed { p, x -> Offset(px(x), py(ghost[p]!!)) }),
                            color.copy(alpha = 0.5f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                                ),
                            ),
                        )
                    }
                }
                val points = model.xAxis.values.mapIndexed { p, x -> Offset(px(x), py(model.value(index, p))) }
                drawPath(polyline(points), color, style = Stroke(width = if (active) 3.dp.toPx() else 1.dp.toPx()))
                if (active) points.forEachIndexed { p, position ->
                    drawCircle(PromoPalette.Bg, 4.dp.toPx(), position)
                    drawCircle(color, 4.dp.toPx(), position, style = Stroke(1.5.dp.toPx()))
                    if (p == point) drawCircle(PromoPalette.Text, 7.dp.toPx(), position, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
