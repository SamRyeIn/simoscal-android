package com.simoscal.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.AdvicePreview
import com.simoscal.android.AdviceUiState
import com.simoscal.android.Destination
import com.simoscal.android.EditorViewModel
import com.simoscal.android.QueuedAdvice
import com.simoscal.android.ValueFormat
import kotlin.math.abs

/**
 * The review queue: guard-approved recommendations, one at a time.
 *
 * Everything needed to decide is on the card, because the decision is made here
 * and nowhere else — what table, in both its names; what it holds now and what
 * this would make it; why, on what evidence, at what risk, with what predicted
 * result; and what the engine's own dry run says the value will *really* encode
 * to. That last pair is the requested-vs-encoded discipline the editors already
 * use, and it belongs here for the same reason: a number that cannot be stored
 * exactly must never be shown as though it can.
 *
 * Three things this screen deliberately does not have:
 *
 * * **No accept-all.** There is no bulk affordance, because the unit of judgment
 *   is one recommendation and a button that skips past that is a button that
 *   flashes something nobody read.
 * * **No apply.** Accept *stages* the change on the screen that owns it; the
 *   journal entry happens when the person applies it there, exactly as it does
 *   for an edit they composed themselves. A recommendation gets no shortcut past
 *   the review a hand-made change gets.
 * * **No list of what was refused.** The refused count is always on screen, so
 *   nobody can believe they saw everything Claude said; the refusals themselves
 *   are not, because rendering a suggestion the guards rejected is the one thing
 *   dropping it was for.
 */
@Composable
fun AdviceScreen(viewModel: EditorViewModel, onShowMe: (Destination) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val advice = state.advice

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "One at a time, decided by you", title = "Review")

        if (advice.review == null) {
            Panel {
                PanelTitle("Nothing to review")
                Caption(
                    "Export a context bundle on the Build screen, ask Claude outside " +
                        "the app, then import the reply there. Every recommendation is " +
                        "replayed through the engine's real guards before it reaches " +
                        "this queue."
                )
            }
            advice.notice?.let { notice ->
                Panel(tone = PanelTone.Warn) { Caption(notice, color = PromoPalette.Warn) }
            }
            return@Column
        }

        QueueProgress(advice)

        advice.staged?.let { staged ->
            Panel(tone = PanelTone.Accent, spacing = 8.dp) {
                PanelTitle("Staged, not applied", tone = PanelTone.Accent)
                Caption(
                    "${staged.table.id} is waiting in its editor. Open it, look at " +
                        "the change on the screen that draws it, and Apply there — " +
                        "that is what puts it in the journal."
                )
                staged.destination?.let { destination ->
                    PromoOutlinedButton(onClick = { onShowMe(destination) }) {
                        Text("Open ${destination.screenName}")
                    }
                }
            }
        }

        advice.queueNotice?.let { notice ->
            Panel(tone = PanelTone.Warn, spacing = 8.dp) {
                Caption(notice, color = PromoPalette.Warn)
                TextButton(onClick = viewModel::dismissAdviceQueueNotice) { Text("Dismiss") }
            }
        }

        val item = advice.current
        if (item == null) {
            FinishedCard(advice)
        } else {
            AdviceCard(
                item = item,
                onAccept = { viewModel.acceptAdvice(item) },
                onReject = { viewModel.rejectAdvice(item) },
                onShowMe = onShowMe,
            )
        }
    }
}

/**
 * How far through, and how much was never shown.
 *
 * The refused count sits beside the progress rather than in a corner: "3 of 5"
 * means something different when four more were refused before anyone saw them,
 * and somebody deciding on the third has to be able to read both at once.
 */
@Composable
private fun QueueProgress(advice: AdviceUiState) {
    val total = advice.queuedAdvice.size
    val decided = total - advice.remaining.size
    Panel(spacing = 4.dp) {
        Kicker("Queue", color = PromoPalette.TextFaint)
        Text(
            if (total == 0) "Nothing survived the guards"
            else "$decided of $total decided",
            style = MaterialTheme.typography.titleMedium,
        )
        Caption(
            "${advice.acceptedCount} accepted · ${advice.rejectedCount} rejected · " +
                "${advice.refusedCount} refused before you saw them"
        )
        val summary = advice.review?.summary.orEmpty()
        if (summary.isNotBlank()) Caption(summary, color = PromoPalette.TextFaint)
    }
}

/**
 * The end of the queue — and the two different ways of reaching it.
 *
 * "Nothing to review, N refused" is not the same statement as "you have decided
 * on everything", and neither is the same as "no reply imported" (handled a
 * level up). A person who is told the wrong one of the three will go looking for
 * a file that is already loaded, or trust an empty screen that is empty because
 * every suggestion was rejected by the guards.
 */
@Composable
private fun FinishedCard(advice: AdviceUiState) {
    val nothingSurvived = advice.queuedAdvice.isEmpty()
    Panel(tone = if (nothingSurvived) PanelTone.Warn else PanelTone.Good, spacing = 8.dp) {
        PanelTitle(
            if (nothingSurvived) "Nothing to review" else "Queue finished",
            tone = if (nothingSurvived) PanelTone.Warn else PanelTone.Good,
        )
        if (nothingSurvived) {
            Caption(
                "The guards refused all ${advice.refusedCount} of this reply's " +
                    "recommendations, so none of them reached you. That is the " +
                    "system working — but it also means this reply changed nothing."
            )
        } else {
            Caption(
                "${advice.acceptedCount} accepted, ${advice.rejectedCount} rejected. " +
                    if (advice.staged != null) {
                        "The accepted one is staged in its editor and is not in the " +
                            "journal until you apply it there."
                    } else {
                        "Nothing is staged."
                    }
            )
        }
    }
}

/**
 * One recommendation, with everything a decision needs and nothing else.
 *
 * A safety-relevant item is styled distinctly and takes a second, deliberate
 * press: `danger` is the palette's word for a refusal or a limit, and an item
 * that moves fuelling or a limiter is not something to agree to with the same
 * thumb-flick as a pedal curve.
 */
@Composable
private fun AdviceCard(
    item: QueuedAdvice,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onShowMe: (Destination) -> Unit,
) {
    val safety = item.risk.equals("safety-relevant", ignoreCase = true)
    val tone = if (safety) PanelTone.Danger else PanelTone.Neutral

    // Armed state is per-item: walking to the next recommendation must not
    // inherit a press aimed at the last one.
    var armedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) { armedFor = null }
    val armed = armedFor == item.id

    Panel(tone = tone, spacing = 12.dp) {
        if (safety) Kicker("SAFETY-RELEVANT", color = PromoPalette.Danger)

        // Both names, always — the ID is what the XDF and the logs call it, the
        // description is the only half a person can reason about.
        Identifier(item.table.id)
        Text(item.table.description, style = MaterialTheme.typography.titleSmall)

        HairRule(color = tone.edgeFaint())
        Field("Change", changeSummary(item.preview))
        EncodedNote(item.preview)
        if (item.staging.loadable && item.staging.units.isNotBlank()) {
            // The editor works in different units from the table for at
            // least one screen, so say which are about to appear rather
            // than letting the number change shape on the way there.
            Caption(
                "Stages on ${item.staging.destination?.screenName ?: "its editor"} " +
                    "in ${item.staging.units}.",
                color = PromoPalette.TextFaint,
            )
        }

        HairRule(color = tone.edgeFaint())
        Field("Intent", item.intent)
        Field("Evidence", item.evidence)
        Field("Prediction", item.prediction)
        Field("Risk", item.risk)
        Field("Confidence", item.confidence)

        HairRule(color = tone.edgeFaint())
        Caption("Written by ${item.routedVia}", color = PromoPalette.TextFaint)
        if (item.note.isNotBlank()) Caption(item.note, color = PromoPalette.Warn)
        if (item.overlaps.isNotEmpty()) {
            Caption(
                "Touches the same cells as ${item.overlaps.joinToString(", ")}. " +
                    "Each was checked against the session on its own, so accepting " +
                    "both is not the same as accepting either.",
                color = PromoPalette.Warn,
            )
        }
        if (!item.staging.loadable && item.staging.reason.isNotBlank()) {
            Caption("Nothing to stage: ${item.staging.reason}", color = PromoPalette.Warn)
        }

        HairRule(color = tone.edgeFaint())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PromoButton(
                onClick = { if (safety && !armed) armedFor = item.id else onAccept() },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (safety && !armed) "Accept..." else "Accept")
            }
            PromoOutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text("Reject")
            }
        }
        if (safety && armed) {
            Caption("Press Accept again to stage this safety-relevant change.", color = PromoPalette.Danger)
        }
        item.staging.destination?.let { destination ->
            TextButton(onClick = { onShowMe(destination) }, modifier = Modifier.fillMaxWidth()) {
                Text("Show me on ${destination.screenName}")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Kicker(label, color = PromoPalette.TextFaint)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Where the dry run and the request disagree, and by how much.
 *
 * Only shown when they do. A quantization line under every recommendation would
 * be read as decoration within a day, and the one that matters — a value the bin
 * cannot hold — would be read the same way.
 */
@Composable
private fun EncodedNote(preview: AdvicePreview) {
    if (preview.warning.isNotBlank()) {
        Caption(preview.warning, color = PromoPalette.Warn)
    }
    if (!preview.quantized) return
    val worst = preview.maxAbsQuantization
    Caption(
        if (worst.isFinite()) {
            "The bin cannot hold this exactly. What it will really encode to " +
                "differs from what was asked for by up to ${ValueFormat.of(listOf(worst)).format(worst)}."
        } else {
            "What the bin will really encode to differs in shape from what was asked for."
        },
        color = PromoPalette.Warn,
    )
}

/**
 * Current → proposed, in physical units, at one precision for the whole set.
 *
 * The *requested* values, not the encoded ones: this line is what the
 * recommendation asks for, and [EncodedNote] is what the bin will do about it.
 * Whole grids are summarised by how many cells move and the range they move
 * between — ninety-six numbers on a card is not a thing anybody reads.
 */
private fun changeSummary(preview: AdvicePreview): String {
    val before = preview.before
    val after = preview.requested
    // The preview's own units, never the staging payload's: these are the
    // table's numbers, and the editor's units describe a different set.
    val suffix = if (preview.units.isBlank()) "" else " ${preview.units}"
    if (before.isEmpty() || after.isEmpty()) return "The engine sent no before/after values."

    val changed = after.indices.filter { index ->
        val was = before.getOrNull(index) ?: return@filter true
        abs(after[index] - was) > 1e-9
    }
    if (changed.isEmpty()) return "No cell changes."

    val format = ValueFormat.of(before, after)
    if (changed.size == 1) {
        val index = changed.first()
        return "${format.format(before[index])} → ${format.format(after[index])}$suffix"
    }
    val wasRange = range(changed.mapNotNull { before.getOrNull(it) }, format)
    val nowRange = range(changed.map { after[it] }, format)
    return "${changed.size} of ${after.size} cells · $wasRange → $nowRange$suffix"
}

private fun range(values: List<Double>, format: ValueFormat): String {
    if (values.isEmpty()) return "?"
    val low = format.format(values.min())
    val high = format.format(values.max())
    return if (low == high) low else "$low–$high"
}

/** The nav bar's own word for a destination, so the button names what it opens. */
private val Destination.screenName: String
    get() = when (this) {
        Destination.TABLES -> "Tables"
        Destination.BOOST -> "Boost"
        Destination.LIMITERS -> "Limiters"
        Destination.PEDAL -> "Pedal"
        Destination.LAMBDA -> "Lambda"
        Destination.SLOTS -> "Slots"
        Destination.CHANGES -> "Changes"
        Destination.BUILD -> "Build"
        Destination.ADVICE -> "Review"
    }

/** A panel's own edge, dimmed — a divider inside a toned panel, not across one. */
private fun PanelTone.edgeFaint() = edge.copy(alpha = 0.35f)
