package com.simoscal.android.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.EditorViewModel
import com.simoscal.android.LimitersModel
import com.simoscal.android.LimitersUiState
import com.simoscal.android.REV_LEVEL_EFFECTS
import com.simoscal.android.REV_LEVEL_NAMES
import com.simoscal.android.REV_MAX_RPM
import com.simoscal.android.display
import com.simoscal.android.revBounds
import com.simoscal.android.revFraction
import com.simoscal.android.revRpmAt

/**
 * The limiters screen: the cylinder-cut trio on one strip, the speed limiter as
 * one control.
 *
 * Both limiters here are multi-table, and both break quietly when written a
 * table at a time — that is why they are on a purpose-built screen instead of in
 * the generic grid, and why Apply sends one op. The trio's ordering is enforced
 * at the fingertip: a marker cannot be dragged past its neighbour, and a typed
 * value that would break the order is refused with the reason rather than
 * silently corrected.
 *
 * The screen is careful about what the trio *is*. These are rpm offsets above
 * the patch's engagement point, not absolute rev limits, and the copy says so —
 * a control captioned "rev limit" that actually moves an offset is how somebody
 * ends up expecting one cut point and getting another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitersScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val limiters = state.limiters

    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null && limiters.model == null) viewModel.loadLimiters()
    }

    var editing by remember { mutableStateOf<Int?>(null) }
    var speedEditing by remember { mutableStateOf(false) }
    var staticRevEditing by remember { mutableStateOf(false) }
    // Saveable for the same reason the boost editor's is: a half-written reason
    // for a calibration change must survive a rotation.
    var intent by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Where the ECU says stop", title = "Limiters")

        when {
            limiters.model == null -> Panel {
                PanelTitle(if (limiters.loading) "Reading limiters…" else "No limiters loaded")
                limiters.unavailable?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> {
                RevTrioSection(
                    limiters = limiters,
                    onDrag = viewModel::onRevDragged,
                    onType = { editing = it },
                )

                StaticRevSection(
                    limiters = limiters,
                    onEdit = { staticRevEditing = true },
                )

                SpeedLimiterSection(
                    limiters = limiters,
                    onEdit = { speedEditing = true },
                )

                limiters.model?.launchControl?.takeIf { it.isNotEmpty() }?.let { values ->
                    Panel {
                        PanelTitle("Launch control")
                        Text(
                            "How launch control's own rpm limiter behaves. Ordinary " +
                                "independent values — edit them in Tables.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PromoPalette.TextDim,
                        )
                        values.forEach { value ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    value.description.substringBefore(" — "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${value.value.display("%.2f")} ${value.units}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                limiters.notice?.let { NoticeCard(title = "Not applied", body = it, emphasise = true) }
                limiters.lastApplied?.let { NoticeCard(title = "Applied", body = it) }

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
                            viewModel.applyLimiters(
                                intent.ifBlank { "set limiters from the simoscal limiters screen" }
                            )
                            intent = ""
                        },
                        enabled = limiters.canApply,
                    ) { Text("Apply") }
                    PromoOutlinedButton(
                        onClick = viewModel::onLimitersDiscard,
                        enabled = limiters.dirty,
                    ) { Text("Discard") }
                }
            }
        }
    }

    editing?.let { index ->
        val bounds = limiters.revBounds(index)
        NumericEntryDialog(
            title = "${REV_LEVEL_NAMES[index].replaceFirstChar(Char::uppercase)} cut",
            supporting = "rpm above the engagement point. Must stay between " +
                "${bounds.start.display("%.0f")} and ${bounds.endInclusive.display("%.0f")} " +
                "so the cut still escalates.",
            initial = limiters.revDraft.getOrNull(index)?.display("%.0f") ?: "",
            onDismiss = { editing = null },
            onConfirm = { rpm ->
                viewModel.onRevTyped(index, rpm)
                editing = null
            },
        )
    }

    if (staticRevEditing) {
        NumericEntryDialog(
            title = "Rev limit while stopped",
            supporting = "rpm. The engine's own rev limiter is " +
                "${limiters.engineRevLimit?.display("%.0f") ?: "—"} rpm and applies " +
                "whether the car is moving or not — a cap above it could never be " +
                "reached. Stored in 32 rpm steps.",
            initial = limiters.staticRevDraft?.display("%.0f") ?: "",
            onDismiss = { staticRevEditing = false },
            onConfirm = { rpm ->
                viewModel.onStaticRevLimitTyped(rpm)
                staticRevEditing = false
            },
        )
    }

    if (speedEditing) {
        NumericEntryDialog(
            title = "Road-speed limiter",
            supporting = "km/h. Written to all four limiter scalars together — the " +
                "ECU selects among them, so they only mean anything as a set.",
            initial = limiters.speedDraft?.display("%.1f") ?: "",
            onDismiss = { speedEditing = false },
            onConfirm = { kmh ->
                viewModel.onSpeedLimiterTyped(kmh)
                speedEditing = false
            },
        )
    }
}

@Composable
private fun RevTrioSection(
    limiters: LimitersUiState,
    onDrag: (index: Int, rpm: Double) -> Unit,
    onType: (Int) -> Unit,
) {
    Panel(tone = if (limiters.revDirty) PanelTone.Accent else PanelTone.Neutral) {
        PanelTitle(
            "Cylinder cut",
            tone = if (limiters.revDirty) PanelTone.Accent else PanelTone.Neutral,
        )

        if (!limiters.hasRevLimits) {
            // Not an error, and said as such: an unpatched bin has no trio, and
            // the speed limiter below is still perfectly editable.
            Text(
                "This bin has no switch patch, so it carries no cylinder-cut trio. " +
                    "The road-speed limiter below is part of the base calibration " +
                    "and can still be set.",
                style = MaterialTheme.typography.bodyMedium,
                color = PromoPalette.TextDim,
            )
            return@Panel
        }

        Text(
            "Engine speed above the patch's engagement point, not an absolute rev " +
                "limit. The cut escalates through the three, so they must stay in " +
                "order — drag one and it stops at its neighbour.",
            style = MaterialTheme.typography.bodySmall,
            color = PromoPalette.TextDim,
        )

        RevStrip(
            draft = limiters.revDraft,
            committed = limiters.committedRev,
            onDrag = onDrag,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(top = 8.dp),
        )

        limiters.revDraft.forEachIndexed { index, rpm ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    REV_LEVEL_NAMES[index].replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.bodyMedium,
                    color = revLevelColor(index),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    REV_LEVEL_EFFECTS[index],
                    style = MaterialTheme.typography.bodySmall,
                    color = PromoPalette.TextFaint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${rpm.display("%.0f")} rpm",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onType(index) },
                )
            }
        }
    }
}

/**
 * The standstill rev cap, shown against the limiter it sits under.
 *
 * Both numbers, always. The cap alone is unreadable — "3808 rpm" says nothing
 * until you know the engine itself stops at 6816 — and a control that showed
 * only the cap would invite reading it as the redline and then "raising the
 * redline" by moving it.
 */
@Composable
private fun StaticRevSection(limiters: LimitersUiState, onEdit: () -> Unit) {
    Panel(tone = if (limiters.staticRevDirty) PanelTone.Accent else PanelTone.Neutral) {
        PanelTitle(
            "Rev limit while stopped",
            tone = if (limiters.staticRevDirty) PanelTone.Accent else PanelTone.Neutral,
        )
        Text(
            "How high the engine will rev in park or neutral. Separate from — and " +
                "lower than — the rev limiter itself, which applies moving or not. " +
                "Raising this does not raise what the engine will reach; it lets the " +
                "limiter be what catches you in park, as it already is in gear.",
            style = MaterialTheme.typography.bodySmall,
            color = PromoPalette.TextDim,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                limiters.staticRevDraft?.let { "${it.display("%.0f")} rpm" } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onEdit() },
            )
            Text(
                limiters.engineRevLimit?.let { "  of ${it.display("%.0f")} rpm limiter" }
                    ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = PromoPalette.TextFaint,
                modifier = Modifier.weight(1f),
            )
            PromoOutlinedButton(onClick = onEdit) { Text("Set") }
        }

        if (limiters.model?.staticRevAtLimiter == true) {
            Text(
                "Already at the limiter — the engine will rev as freely stopped as " +
                    "it does in gear.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelTone.Good.ink,
            )
        }
    }
}

@Composable
private fun SpeedLimiterSection(limiters: LimitersUiState, onEdit: () -> Unit) {
    Panel(tone = if (limiters.speedDirty) PanelTone.Accent else PanelTone.Neutral) {
        PanelTitle(
            "Road-speed limiter",
            tone = if (limiters.speedDirty) PanelTone.Accent else PanelTone.Neutral,
        )
        Text(
            "Four tables holding one number — three levels and a not-active value. " +
                "The ECU picks among them, so Apply writes all four together.",
            style = MaterialTheme.typography.bodySmall,
            color = PromoPalette.TextDim,
        )

        val committed = limiters.committedSpeed
        if (committed == null && limiters.model != null) {
            // Worth saying out loud rather than papering over: four scalars that
            // disagree were written by something that did not treat them as a set.
            Text(
                "The four scalars currently disagree: " +
                    limiters.model.speedLimiter.joinToString(", ") {
                        "${it.value.display("%.1f")}"
                    } +
                    " km/h. Applying a value here puts all four back in step.",
                style = MaterialTheme.typography.bodyMedium,
                color = PanelTone.Danger.ink,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                limiters.speedDraft?.let { "${it.display("%.1f")} km/h" } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).clickable { onEdit() },
            )
            PromoOutlinedButton(onClick = onEdit) { Text("Set") }
        }
    }
}

/**
 * The three cut levels as markers on one rpm strip.
 *
 * One strip rather than three sliders because the invariant *is* a relationship:
 * seen as three separate controls, "soft below medium below hard" is a rule to
 * remember, and seen as three markers on a shared axis it is just what the
 * picture looks like. A marker that would cross its neighbour stops against it,
 * so no reachable drag position produces a trio the engine would refuse.
 *
 * The strip spans the field's whole encodable range rather than the trio's own
 * span, so it does not rescale under the finger mid-drag.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun RevStrip(
    draft: List<Double>,
    committed: List<Double>,
    onDrag: (index: Int, rpm: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .pointerInput(draft.size) {
                // The grabbed marker is fixed at touch-down, exactly as the boost
                // canvas does it: re-deriving it from x each move would let a
                // drag hand off to whichever marker it passed, and the markers
                // here sit close together by design.
                var grabbed = 0
                detectDragGestures(
                    onDragStart = { position ->
                        grabbed = nearestMarker(draft, position.x, size.width.toFloat())
                    },
                ) { change, _ ->
                    change.consume()
                    onDrag(grabbed, revRpmAt(change.position.x / size.width.toFloat()))
                }
            }
            .pointerInput(draft.size) {
                detectTapGestures { position ->
                    val index = nearestMarker(draft, position.x, size.width.toFloat())
                    onDrag(index, revRpmAt(position.x / size.width.toFloat()))
                }
            },
    ) {
        val trackY = size.height * 0.55f

        drawLine(
            PromoPalette.Rule,
            Offset(0f, trackY),
            Offset(size.width, trackY),
            strokeWidth = 3f,
        )

        // Where the engine currently holds each level, so a staged change reads
        // as a move *from* something rather than as a number in isolation.
        committed.forEachIndexed { index, rpm ->
            val x = revFraction(rpm) * size.width
            drawCircle(
                revLevelColor(index).copy(alpha = 0.30f),
                radius = 9f,
                center = Offset(x, trackY),
            )
        }

        draft.forEachIndexed { index, rpm ->
            val x = revFraction(rpm) * size.width
            drawLine(
                revLevelColor(index),
                Offset(x, trackY - 22f),
                Offset(x, trackY + 22f),
                strokeWidth = 3f,
            )
            drawCircle(PromoPalette.Bg, radius = 11f, center = Offset(x, trackY))
            drawCircle(
                revLevelColor(index),
                radius = 11f,
                center = Offset(x, trackY),
                style = Stroke(width = 3f),
            )
            drawText(
                textMeasurer = measurer,
                text = REV_LEVEL_NAMES[index].take(1).uppercase(),
                topLeft = Offset(x - 5f, trackY - 8f),
                style = TextStyle(
                    fontSize = 10.sp,
                    color = revLevelColor(index),
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        drawEndLabels(measurer, size.width, trackY)
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEndLabels(
    measurer: androidx.compose.ui.text.TextMeasurer,
    width: Float,
    trackY: Float,
) {
    val style = TextStyle(
        fontSize = 9.sp,
        color = PromoPalette.TextFaint,
        fontFamily = FontFamily.Monospace,
    )
    drawText(measurer, "0", topLeft = Offset(0f, trackY + 28f), style = style)
    drawText(
        measurer,
        "${REV_MAX_RPM.toInt()}",
        topLeft = Offset(width - 34f, trackY + 28f),
        style = style,
    )
}

/** The nearest marker to a horizontal position — what a drag or tap grabbed. */
private fun nearestMarker(draft: List<Double>, pixelX: Float, width: Float): Int {
    if (draft.isEmpty()) return 0
    return draft.indices.minByOrNull {
        kotlin.math.abs(revFraction(draft[it]) * width - pixelX)
    } ?: 0
}

/** Cool→warm with escalation, the same ordering language the slot curves use. */
private fun revLevelColor(index: Int): Color = when (index) {
    LimitersModel.SOFT -> PromoPalette.Accent2
    LimitersModel.MEDIUM -> PromoPalette.Accent
    else -> PromoPalette.Danger
}
