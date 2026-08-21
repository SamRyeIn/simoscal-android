package com.simoscal.android.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.EditorViewModel
import com.simoscal.android.PedalUiState
import com.simoscal.android.display
import kotlin.math.abs

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "How far before it goes", title = "Pedal feel")

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
                    "Choose one above to shape its pedal curve.",
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

                Panel(tone = if (pedal.dirty) PanelTone.Accent else PanelTone.Neutral) {
                    PanelTitle(
                        "Pedal curve at ${pedal.columnRpm?.display("%.0f") ?: "—"} rpm",
                        tone = if (pedal.dirty) PanelTone.Accent else PanelTone.Neutral,
                    )

                    PedalCanvas(
                        pedal = pedal,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        onDragPoint = viewModel::onPedalPointDragged,
                        onTapPoint = { editing = it },
                    )

                    Text(
                        if (pedal.ghost.isEmpty()) {
                            "Dragging a point moves exactly one cell of the map."
                        } else {
                            "The faint line is the bin as it was imported. Dragging a " +
                                "point moves exactly one cell of the map."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextFaint,
                    )
                }

                Panel {
                    PanelTitle("Engine speed")
                    Text(
                        "Each column is the pedal curve at one engine speed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextDim,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        pedal.rpmAxis.forEachIndexed { index, rpm ->
                            FilterChip(
                                selected = index == pedal.column,
                                onClick = { viewModel.onPedalColumnSelected(index) },
                                label = { Text(rpm.display("%.0f")) },
                            )
                        }
                    }
                }

                pedal.notice?.let { NoticeCard(title = "Not applied", body = it, emphasise = true) }
                pedal.lastApplied?.let { NoticeCard(title = "Applied", body = it) }

                if (!pedal.editable) {
                    NoticeCard(
                        title = "Read-only",
                        body = "This map cannot be written back from physical units, " +
                            "so the editor shows it without offering to change it.",
                    )
                }

                OutlinedTextField(
                    value = intent,
                    onValueChange = { intent = it },
                    label = { Text("Why this change") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        enabled = pedal.canApply && pedal.editable,
                    ) { Text("Apply") }
                    PromoOutlinedButton(
                        onClick = viewModel::onPedalDiscard,
                        enabled = pedal.dirty,
                    ) { Text("Discard") }
                    if (pedal.ghost.isNotEmpty()) {
                        AssistChip(
                            onClick = viewModel::onPedalRevertToSource,
                            label = { Text("Back to imported") },
                        )
                    }
                }
            }
        }
    }

    editing?.let { index ->
        NumericEntryDialog(
            title = "Torque factor",
            supporting = "at ${pedal.pedalAxis.getOrNull(index)?.display("%.0f") ?: "—"}% pedal, " +
                "${pedal.columnRpm?.display("%.0f") ?: "—"} rpm. 0 to 1.",
            initial = pedal.draft.getOrNull(index)?.display("%.3f") ?: "",
            onDismiss = { editing = null },
            onConfirm = { factor ->
                viewModel.onPedalPointTyped(index, factor)
                editing = null
            },
        )
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
    val draft = pedal.draft

    Canvas(
        modifier = modifier
            .pointerInput(axis.size) {
                detectTapGestures { position ->
                    onTapPoint(nearestPedalIndex(axis, position.x, size.width.toFloat()))
                }
            }
            .pointerInput(axis.size) {
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
        fun py(factor: Double) = bottom - (factor / 1.0).toFloat() * plotHeight

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
                PromoPalette.TextFaint.copy(alpha = 0.55f),
                1.8f,
                dashed = true,
            )
        }

        if (draft.size == axis.size && draft.isNotEmpty()) {
            val points = axis.indices.map { Offset(px(axis[it]), py(draft[it])) }
            drawPedalLine(points, PromoPalette.Accent, 3.5f)
            points.forEach { point ->
                drawCircle(PromoPalette.Bg, radius = 6f, center = point)
                drawCircle(PromoPalette.Accent, radius = 6f, center = point, style = Stroke(width = 2.5f))
            }
        }

        drawLine(PromoPalette.Rule, Offset(left, 10f), Offset(left, bottom), 1.5f)
        drawLine(PromoPalette.Rule, Offset(left, bottom), Offset(left + plotWidth, bottom), 1.5f)
    }
}

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
