package com.simoscal.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.EditorViewModel
import com.simoscal.android.PEDAL_NUDGE_STEPS
import com.simoscal.android.PedalUiState
import com.simoscal.android.display
import com.simoscal.android.displayExact
import kotlin.math.abs

/** The pedal plot gets the same portrait emphasis as the boost plot. */
private val PedalPlotHeight = 360.dp

/**
 * The pedal-feel editor: how far the pedal goes before the engine does.
 *
 * A driver-interpretation map turns pedal travel into a fraction of maximum
 * torque, scheduled on engine speed. As a 12×12 grid of numbers it says almost
 * nothing about how a car will feel; as a curve of pedal-percent against
 * torque-factor it says it immediately, which is the whole reason this screen
 * exists.
 *
 * These maps are **not** domain-owned. No unit lies about itself and no
 * invariant spans two of them, so they ride the ordinary `table_detail` + `edit`
 * path and are equally editable in the Tables grid — this is a better shape for
 * the same tables, not a privileged route to them.
 *
 * One column at a time, because a column *is* the pedal curve at that engine
 * speed. The imported bin's curve is ghosted behind the draft so a reshaping can
 * always be read against where it started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedalScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pedal = state.pedal

    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null && pedal.maps.isEmpty()) viewModel.loadPedalMaps()
    }

    var editing by remember { mutableStateOf<Int?>(null) }
    var intent by rememberSaveable { mutableStateOf("") }

    val selectAndType: (Int) -> Unit = { index ->
        viewModel.onPedalPointSelected(index)
        editing = index
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Every rpm, one pedal map", title = "Pedal feel")

        SessionProvenanceCard(binName = state.bin?.displayName, shortHash = state.bin?.shortHash)

        Panel {
            PanelTitle("Which map")
            Text(
                "This car reads the DCT family. High and low vehicle speed are " +
                    "separate maps and are often set the same; the sport and " +
                    "off-road variants only apply in those modes.",
                style = MaterialTheme.typography.bodySmall,
                color = PromoPalette.TextDim,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                pedal.maps.forEach { summary ->
                    FilterChip(
                        selected = summary.name == pedal.detail?.summary?.name,
                        onClick = { viewModel.openPedalMap(summary) },
                        label = { Text(pedalMapLabel(summary.name)) },
                        colors = promoFilterChipColors(),
                    )
                }
            }
        }

        val detail = pedal.detail
        when {
            pedal.loading && detail == null -> Panel { PanelTitle("Reading the map…") }

            detail == null -> Panel {
                PanelTitle("No map open")
                Text(
                    "Choose one above to shape its pedal curves.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PromoPalette.TextDim,
                )
            }

            else -> {
                Panel {
                    Text(
                        detail.summary.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        detail.summary.idAndDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextFaint,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                RpmCurveChips(pedal = pedal, onSelect = viewModel::onPedalColumnSelected)

                Panel(tone = if (pedal.dirty) PanelTone.Accent else PanelTone.Neutral) {
                    PanelTitle(
                        "Editing ${pedal.columnRpm?.display("%.0f") ?: "—"} rpm",
                        tone = if (pedal.dirty) PanelTone.Accent else PanelTone.Neutral,
                    )

                    PedalCanvas(
                        pedal = pedal,
                        modifier = Modifier.fillMaxWidth().height(PedalPlotHeight),
                        onDragPoint = viewModel::onPedalPointDragged,
                        onTapPoint = selectAndType,
                    )

                    Text(
                        if (pedal.ghost.isEmpty()) {
                            "All rpm curves are shown. The thicker curve is active; drag one " +
                                "of its points or tap it to type an exact value."
                        } else {
                            "All rpm curves are shown. The thicker curve is active; its dashed " +
                                "line is the imported bin."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextFaint,
                    )
                }

                PedalBreakpointStepper(
                    pedal = pedal,
                    onStepSelection = viewModel::onPedalSelectionStepped,
                    onSelectIncrement = viewModel::onPedalNudgeStepChanged,
                    onNudge = viewModel::onPedalNudged,
                    onType = { editing = pedal.selectedIndex },
                )

                pedal.notice?.let { NoticeCard(title = "Not applied", body = it, emphasise = true) }
                pedal.lastApplied?.let { NoticeCard(title = "Applied", body = it) }

                if (!pedal.editable) {
                    NoticeCard(
                        title = "Read-only",
                        body = "This map cannot be written back from physical units, " +
                            "so the editor shows it without offering to change it.",
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PromoOutlinedButton(
                        onClick = viewModel::onPedalDiscard,
                        enabled = pedal.dirty,
                        modifier = Modifier.weight(1f),
                    ) { Text("Discard") }
                    if (pedal.ghost.isNotEmpty()) {
                        AssistChip(
                            onClick = viewModel::onPedalRevertToSource,
                            label = { Text("Back to imported") },
                        )
                    }
                }

                OutlinedTextField(
                    value = intent,
                    onValueChange = { intent = it },
                    label = { Text("Why this change (recorded in the journal)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )

                PromoButton(
                    onClick = {
                        viewModel.applyPedalDraft(
                            intent.ifBlank {
                                "shape the pedal curve at " +
                                    "${pedal.columnRpm?.display("%.0f")} rpm"
                            }
                        )
                        intent = ""
                    },
                    enabled = pedal.canApply && pedal.editable && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (pedal.dirty) {
                            "Apply ${pedal.columnRpm?.display("%.0f")} rpm curve"
                        } else {
                            "No change to apply"
                        }
                    )
                }
            }
        }
    }

    editing?.let { index ->
        NumericEntryDialog(
            title = "${pedal.pedalAxis.getOrNull(index)?.display("%.0f") ?: "—"}% pedal at " +
                "${pedal.columnRpm?.display("%.0f") ?: "—"} rpm",
            supporting = "Torque factor from 0 to 1.",
            initial = pedal.draft.getOrNull(index)?.display("%.3f") ?: "",
            onDismiss = { editing = null },
            onConfirm = { factor ->
                viewModel.onPedalPointTyped(index, factor)
                editing = null
            },
        )
    }
}

/** The rpm chips are both the curve legend and the active-curve picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RpmCurveChips(pedal: PedalUiState, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker("Engine speed — choose curve to edit", color = PromoPalette.TextFaint)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            pedal.rpmAxis.forEachIndexed { index, rpm ->
                val color = pedalCurveColor(index)
                FilterChip(
                    selected = index == pedal.column,
                    onClick = { onSelect(index) },
                    label = {
                        Text(
                            rpm.display("%.0f"),
                            color = if (index == pedal.column) color else color.copy(alpha = 0.72f),
                        )
                    },
                    colors = promoFilterChipColors(),
                )
            }
        }
        if (pedal.dirty) {
            Caption(
                "${pedal.columnRpm?.display("%.0f")} rpm has an unapplied change. " +
                    "Apply or discard it before switching curves.",
                color = PromoPalette.Danger,
            )
        }
    }
}

/** Pick a pedal breakpoint, choose an increment, and add or subtract it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PedalBreakpointStepper(
    pedal: PedalUiState,
    onStepSelection: (Int) -> Unit,
    onSelectIncrement: (Double) -> Unit,
    onNudge: (Int) -> Unit,
    onType: () -> Unit,
) {
    Panel(padding = 12.dp, spacing = 10.dp) {
        Kicker("Breakpoint", color = PromoPalette.TextFaint)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PromoOutlinedButton(onClick = { onStepSelection(-1) }) {
                Text("◀", style = PromoType.identifier)
            }
            PedalBreakpointReadout(
                pedal = pedal,
                onType = onType,
                modifier = Modifier.weight(1f),
            )
            PromoOutlinedButton(onClick = { onStepSelection(1) }) {
                Text("▶", style = PromoType.identifier)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Kicker("Step", color = PromoPalette.TextFaint)
            PEDAL_NUDGE_STEPS.forEach { step ->
                FilterChip(
                    selected = abs(step - pedal.nudgeStepFactor) < 1e-9,
                    onClick = { onSelectIncrement(step) },
                    label = { Text(step.displayExact()) },
                    colors = promoFilterChipColors(),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PromoOutlinedButton(
                onClick = { onNudge(-1) },
                modifier = Modifier.weight(1f),
            ) { Text("− ${pedal.nudgeStepFactor.displayExact()}") }
            PromoOutlinedButton(
                onClick = { onNudge(1) },
                modifier = Modifier.weight(1f),
            ) { Text("+ ${pedal.nudgeStepFactor.displayExact()}") }
        }
    }
}

/** The exact point the stepper will move; tapping opens numeric entry. */
@Composable
private fun PedalBreakpointReadout(
    pedal: PedalUiState,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClickLabel = "Type an exact value", onClick = onType)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(IdentifierSpan) {
                    append(pedal.selectedPedal?.display("%.0f") ?: "—")
                }
                withStyle(SpanStyle(color = PromoPalette.TextFaint)) { append("% pedal") }
                append("   ")
                withStyle(SpanStyle(color = pedalCurveColor(pedal.column))) {
                    append(pedal.selectedFactor?.display("%.3f") ?: "—")
                }
                withStyle(SpanStyle(color = PromoPalette.TextFaint)) { append(" factor") }
            },
            style = PromoType.identifier,
        )
        Caption("Point ${pedal.selectedIndex + 1} of ${pedal.draft.size} · tap to type")
    }
}

/**
 * The pedal curve: torque factor up, pedal percent across.
 *
 * Pedal on the x axis rather than the grid's own row order, because that is the
 * axis a person thinks in — "at half throttle I want more" is a statement about
 * a place on the pedal, not about row six.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun PedalCanvas(
    pedal: PedalUiState,
    modifier: Modifier = Modifier,
    onDragPoint: (index: Int, factor: Double) -> Unit,
    onTapPoint: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val axis = pedal.pedalAxis

    Canvas(
        modifier = modifier
            .pointerInput(axis.size, pedal.column) {
                detectTapGestures { position ->
                    onTapPoint(nearestPedalIndex(axis, position.x, size.width.toFloat()))
                }
            }
            .pointerInput(axis.size, pedal.column) {
                // Grabbed at touch-down and held, as on every other canvas here:
                // re-deriving it per move lets a diagonal drag walk sideways and
                // reshape a whole run of the curve with one finger.
                var grabbed = 0
                detectDragGestures(
                    onDragStart = { position ->
                        grabbed = nearestPedalIndex(axis, position.x, size.width.toFloat())
                    },
                ) { change, _ ->
                    change.consume()
                    onDragPoint(grabbed, factorAt(change.position.y, size.height.toFloat()))
                }
            },
    ) {
        val left = 40f
        val bottom = size.height - 26f
        val plotWidth = size.width - left - 12f
        val plotHeight = bottom - 10f

        fun px(percent: Double) = left + (percent / 100.0).toFloat() * plotWidth
        fun py(factor: Double) = bottom - factor.toFloat() * plotHeight

        // Grid at quarter factors — the steps a person actually reasons in.
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { factor ->
            val y = py(factor)
            drawLine(
                PromoPalette.Rule.copy(alpha = 0.5f),
                Offset(left, y),
                Offset(left + plotWidth, y),
                1f,
            )
            drawText(
                textMeasurer = measurer,
                text = factor.display("%.2f"),
                topLeft = Offset(0f, y - 12f),
                style = TextStyle(
                    fontSize = 9.sp,
                    color = PromoPalette.TextFaint,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        listOf(0.0, 50.0, 100.0).forEach { percent ->
            drawText(
                textMeasurer = measurer,
                text = "${percent.toInt()}%",
                topLeft = Offset(px(percent) - 12f, bottom + 6f),
                style = TextStyle(
                    fontSize = 9.sp,
                    color = PromoPalette.TextFaint,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        // The imported bin, behind everything — the reference a reshaping is read
        // against. Absent rather than faked when the engine sent no source values.
        val ghost = pedal.ghost
        if (ghost.size == axis.size && ghost.isNotEmpty()) {
            drawPedalLine(
                axis.indices.map { Offset(px(axis[it]), py(ghost[it])) },
                pedalCurveColor(pedal.column).copy(alpha = 0.45f),
                1.8f,
                dashed = true,
            )
        }

        // Context curves first, active draft last. Every rpm therefore stays
        // visible without a staged change appearing on any curve but its own.
        val drawOrder = pedal.rpmAxis.indices.filter { it != pedal.column } + pedal.column
        drawOrder.forEach { column ->
            val curve = pedal.curveAt(column)
            if (curve.size != axis.size || curve.isEmpty()) return@forEach
            val active = column == pedal.column
            val color = pedalCurveColor(column)
            val points = axis.indices.map { Offset(px(axis[it]), py(curve[it])) }
            drawPedalLine(
                points,
                if (active) color else color.copy(alpha = 0.48f),
                if (active) 3.5f else 1.8f,
            )
            if (active) {
                points.forEachIndexed { index, point ->
                    if (index == pedal.selectedIndex) {
                        drawCircle(PromoPalette.Text, radius = 9f, center = point)
                    }
                    drawCircle(PromoPalette.Bg, radius = 6f, center = point)
                    drawCircle(color, radius = 6f, center = point, style = Stroke(width = 2.5f))
                }
            }
        }

        drawLine(PromoPalette.Rule, Offset(left, 10f), Offset(left, bottom), 1.5f)
        drawLine(PromoPalette.Rule, Offset(left, bottom), Offset(left + plotWidth, bottom), 1.5f)
    }
}

/** Stable curve colors shared by the graph and rpm picker. */
private val PedalCurveColors = listOf(
    PromoPalette.Accent,
    PromoPalette.Accent2,
    PromoPalette.Good,
    PromoPalette.Warn,
    PromoPalette.Danger,
    Color(0xFFB388FF),
    Color(0xFF00D4C7),
    Color(0xFFFF7AB6),
    Color(0xFFA6E22E),
    Color(0xFFFFA657),
    Color(0xFF8DA1FF),
    Color(0xFFD5A6FF),
)

private fun pedalCurveColor(index: Int): Color =
    PedalCurveColors[((index % PedalCurveColors.size) + PedalCurveColors.size) % PedalCurveColors.size]

private fun DrawScope.drawPedalLine(
    points: List<Offset>,
    color: Color,
    width: Float,
    dashed: Boolean = false,
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = width,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
        ),
    )
}

/** The factor a fingertip at this height is asking for. Inverse of the canvas `py`. */
private fun factorAt(pixelY: Float, canvasHeight: Float): Double {
    val bottom = canvasHeight - 26f
    val plotHeight = bottom - 10f
    return ((bottom - pixelY) / plotHeight).toDouble()
}

/** The curve point nearest a horizontal position — what a drag or tap grabbed. */
private fun nearestPedalIndex(axis: List<Double>, pixelX: Float, canvasWidth: Float): Int {
    if (axis.isEmpty()) return 0
    val left = 40f
    val plotWidth = canvasWidth - left - 12f
    return axis.indices.minByOrNull {
        abs(left + (axis[it] / 100.0).toFloat() * plotWidth - pixelX)
    } ?: 0
}

/** Short, human names for the map family — the chips have no room for XDF titles. */
private fun pedalMapLabel(name: String): String = when (name) {
    "pedal_dct_high" -> "Normal, high speed"
    "pedal_dct_low" -> "Normal, low speed"
    "pedal_dct_sport_high" -> "Sport, high speed"
    "pedal_dct_sport_low" -> "Sport, low speed"
    "pedal_dct_offroad_high" -> "Off-road, high"
    "pedal_dct_offroad_low" -> "Off-road, low"
    "pedal_drive_off" -> "Drive-off"
    else -> name
}
