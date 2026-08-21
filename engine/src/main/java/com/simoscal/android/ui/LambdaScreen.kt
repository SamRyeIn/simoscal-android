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
import androidx.compose.ui.geometry.Size
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
import com.simoscal.android.LambdaUiState
import com.simoscal.android.WARN_LAMBDA
import com.simoscal.android.display
import kotlin.math.abs

/**
 * The full-load enrichment editor: lambda against engine speed, per time at load.
 *
 * This is the one curve in the app whose danger has a *direction*. Leaner is
 * hotter: at wide-open throttle this enrichment is what carries heat out of the
 * combustion chamber and off the turbine, so *up* is the dangerous way and the
 * screen draws that as a shaded band rather than leaving it to be inferred.
 *
 * Two bounds, drawn differently because they are different things. The band from
 * λ 0.90 is a **warning** — everything in it is legal and sometimes correct. The
 * dashed line is the engine's **refusal**, and it is the value the engine sent
 * rather than a constant here, so what the screen shades and what the engine
 * rejects cannot drift apart.
 *
 * Rows are time at full load. The active row is the editable curve and the rest
 * are ghosted — the slots interaction, for the slots reason: a fingertip that
 * could grab any of eight overlapping curves would sometimes move the wrong one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LambdaScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lambda = state.lambda

    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null && lambda.detail == null) viewModel.loadLambdaMap()
    }

    var editing by remember { mutableStateOf<Int?>(null) }
    var flatOpen by remember { mutableStateOf(false) }
    var intent by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Leaner is hotter", title = "Full-load enrichment")

        when {
            lambda.detail == null -> Panel {
                PanelTitle(if (lambda.loading) "Reading the map…" else "No enrichment map")
                lambda.unavailable?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> {
                Panel {
                    Text(
                        "Extra fuel at wide-open throttle, scheduled on engine speed " +
                            "and how long the pull has been held. Richer — lower " +
                            "lambda — carries heat away from the pistons and the " +
                            "turbine.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Stock is a flat 1.00 across the whole map: this car does its " +
                            "enrichment through the basic lambda grids, so anything " +
                            "below 1.00 here is added on top of that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextDim,
                    )
                }

                Panel(tone = if (lambda.dirty) PanelTone.Accent else PanelTone.Neutral) {
                    PanelTitle(
                        "At ${lambda.rowSeconds?.display("%.1f") ?: "—"} s at full load",
                        tone = if (lambda.dirty) PanelTone.Accent else PanelTone.Neutral,
                    )

                    LambdaCanvas(
                        lambda = lambda,
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        onDragPoint = viewModel::onLambdaPointDragged,
                        onTapPoint = { editing = it },
                    )

                    Text(
                        "The shaded band above λ ${WARN_LAMBDA.display("%.2f")} is lean " +
                            "for full load — legal, but worth meaning. The dashed line " +
                            "at λ ${lambda.leanMax.display("%.2f")} is where the engine " +
                            "refuses outright.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextFaint,
                    )
                }

                Panel {
                    PanelTitle("Time at full load")
                    Text(
                        "Each row is how rich the ECU asks for after that many seconds " +
                            "of held throttle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PromoPalette.TextDim,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        lambda.timeAxis.forEachIndexed { index, seconds ->
                            FilterChip(
                                selected = index == lambda.row,
                                onClick = { viewModel.onLambdaRowSelected(index) },
                                label = { Text("${seconds.display("%.1f")} s") },
                            )
                        }
                    }
                }

                // Only points *this draft* moved into the band. Stock is flat
                // 1.00, so a card driven by the whole curve would fire on arrival
                // and be dismissed unread — the wrong habit for this band.
                if (lambda.stagedIntoWarningBand.isNotEmpty()) {
                    NoticeCard(
                        title = "Lean for full load",
                        body = "${lambda.stagedIntoWarningBand.size} point(s) staged above " +
                            "λ ${WARN_LAMBDA.display("%.2f")}. That is allowed — but at full " +
                            "load, leaner means less heat carried away from the pistons and " +
                            "the turbine.",
                        emphasise = true,
                    )
                } else if (lambda.providesNoEnrichment) {
                    NoticeCard(
                        title = "No enrichment here",
                        body = "This row is flat at λ ${lambda.leanMax.display("%.2f")} — " +
                            "the map as it stands adds nothing at full load. That is how " +
                            "this car ships; its enrichment comes from the basic lambda " +
                            "grids instead.",
                    )
                }

                lambda.notice?.let { NoticeCard(title = "Not applied", body = it, emphasise = true) }
                lambda.lastApplied?.let { NoticeCard(title = "Applied", body = it) }

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
                            viewModel.applyLambdaDraft(
                                intent.ifBlank {
                                    "set full-load enrichment at " +
                                        "${lambda.rowSeconds?.display("%.1f")} s"
                                }
                            )
                            intent = ""
                        },
                        enabled = lambda.canApply,
                    ) { Text("Apply") }
                    PromoOutlinedButton(onClick = { flatOpen = true }) { Text("Flat row") }
                    PromoOutlinedButton(
                        onClick = viewModel::onLambdaDiscard,
                        enabled = lambda.dirty,
                    ) { Text("Discard") }
                }
            }
        }
    }

    editing?.let { index ->
        NumericEntryDialog(
            title = "Lambda",
            supporting = "at ${lambda.rpmAxis.getOrNull(index)?.display("%.0f") ?: "—"} rpm, " +
                "${lambda.rowSeconds?.display("%.1f") ?: "—"} s at full load. Below " +
                "${lambda.leanMax.display("%.2f")}.",
            initial = lambda.draft.getOrNull(index)?.display("%.3f") ?: "",
            onDismiss = { editing = null },
            onConfirm = { value ->
                viewModel.onLambdaPointTyped(index, value)
                editing = null
            },
        )
    }

    if (flatOpen) {
        NumericEntryDialog(
            title = "Flat enrichment row",
            supporting = "One lambda across every engine speed at " +
                "${lambda.rowSeconds?.display("%.1f") ?: "—"} s at full load.",
            initial = lambda.draft.firstOrNull()?.display("%.3f") ?: "",
            onDismiss = { flatOpen = false },
            onConfirm = { value ->
                viewModel.onLambdaFlatRow(value)
                flatOpen = false
            },
        )
    }
}

/**
 * The enrichment curve, with the lean band shaded above it.
 *
 * The y axis is inverted from the intuitive reading on purpose — richer is
 * *down* — because that is how every lambda plot in this project and its logs is
 * drawn, and a screen that flipped it would invite reading an enrichment as a
 * leanout.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun LambdaCanvas(
    lambda: LambdaUiState,
    modifier: Modifier = Modifier,
    onDragPoint: (index: Int, value: Double) -> Unit,
    onTapPoint: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val rpm = lambda.rpmAxis

    // A fixed window rather than one fitted to the data: stock is a flat 1.00
    // map, and a self-scaling axis would draw that flat line through the middle
    // of an empty plot with no sense of how much room there is below it.
    val low = 0.70
    val high = 1.05

    Canvas(
        modifier = modifier
            .pointerInput(rpm.size) {
                detectTapGestures { position ->
                    onTapPoint(nearestLambdaIndex(rpm, position.x, size.width.toFloat()))
                }
            }
            .pointerInput(rpm.size) {
                var grabbed = 0
                detectDragGestures(
                    onDragStart = { position ->
                        grabbed = nearestLambdaIndex(rpm, position.x, size.width.toFloat())
                    },
                ) { change, _ ->
                    change.consume()
                    val fraction = (size.height.toFloat() - 26f - change.position.y) /
                        (size.height.toFloat() - 36f)
                    onDragPoint(grabbed, low + fraction.toDouble() * (high - low))
                }
            },
    ) {
        val left = 44f
        val bottom = size.height - 26f
        val plotWidth = size.width - left - 12f
        val plotHeight = bottom - 10f

        fun px(index: Int): Float =
            if (rpm.size <= 1) left else left + (index.toFloat() / (rpm.size - 1)) * plotWidth

        fun py(value: Double): Float =
            bottom - (((value - low) / (high - low)).toFloat()) * plotHeight

        // The lean band: from the warning bound up to the top of the plot. Drawn
        // first so every curve sits on top of its own explanation.
        val warnY = py(WARN_LAMBDA)
        drawRect(
            color = PromoPalette.Danger.copy(alpha = 0.12f),
            topLeft = Offset(left, 10f),
            size = Size(plotWidth, (warnY - 10f).coerceAtLeast(0f)),
        )

        listOf(0.75, 0.80, 0.85, 0.90, 0.95, 1.00).forEach { value ->
            val y = py(value)
            drawLine(
                PromoPalette.Rule.copy(alpha = 0.45f),
                Offset(left, y),
                Offset(left + plotWidth, y),
                1f,
            )
            drawText(
                textMeasurer = measurer,
                text = value.display("%.2f"),
                topLeft = Offset(0f, y - 12f),
                style = TextStyle(
                    fontSize = 9.sp,
                    color = PromoPalette.TextFaint,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        // The engine's refusal — dashed, because nothing may cross it.
        val refusalY = py(lambda.leanMax)
        if (refusalY >= 10f) {
            drawLine(
                PromoPalette.Danger,
                Offset(left, refusalY),
                Offset(left + plotWidth, refusalY),
                2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
        }

        // The other time-rows, ghosted.
        lambda.ghostRows.forEach { row ->
            if (row.size == rpm.size) {
                drawLambdaLine(
                    rpm.indices.map { Offset(px(it), py(row[it])) },
                    PromoPalette.TextFaint.copy(alpha = 0.28f),
                    1.6f,
                )
            }
        }

        if (lambda.draft.size == rpm.size && rpm.isNotEmpty()) {
            val points = rpm.indices.map { Offset(px(it), py(lambda.draft[it])) }
            drawLambdaLine(points, PromoPalette.Accent, 3.5f)
            points.forEachIndexed { index, point ->
                drawCircle(PromoPalette.Bg, radius = 6f, center = point)
                val inBand = lambda.draft[index] > WARN_LAMBDA + 1e-9
                drawCircle(
                    if (inBand) PromoPalette.Danger else PromoPalette.Accent,
                    radius = 6f,
                    center = point,
                    style = Stroke(width = 2.5f),
                )
            }
        }

        listOf(0, rpm.size / 2, rpm.size - 1).distinct().filter { it >= 0 }.forEach { index ->
            rpm.getOrNull(index)?.let { value ->
                drawText(
                    textMeasurer = measurer,
                    text = value.display("%.0f"),
                    topLeft = Offset(px(index) - 16f, bottom + 6f),
                    style = TextStyle(
                        fontSize = 9.sp,
                        color = PromoPalette.TextFaint,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }

        drawLine(PromoPalette.Rule, Offset(left, 10f), Offset(left, bottom), 1.5f)
        drawLine(PromoPalette.Rule, Offset(left, bottom), Offset(left + plotWidth, bottom), 1.5f)
    }
}

private fun DrawScope.drawLambdaLine(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width = width))
}

private fun nearestLambdaIndex(rpm: List<Double>, pixelX: Float, canvasWidth: Float): Int {
    if (rpm.isEmpty()) return 0
    val left = 44f
    val plotWidth = canvasWidth - left - 12f
    return rpm.indices.minByOrNull {
        val x = if (rpm.size <= 1) left else left + (it.toFloat() / (rpm.size - 1)) * plotWidth
        abs(x - pixelX)
    } ?: 0
}
