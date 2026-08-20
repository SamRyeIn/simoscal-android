package com.simoscal.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.BOOST_NUDGE_STEPS
import com.simoscal.android.BoostCurveModel
import com.simoscal.android.BoostUiState
import com.simoscal.android.display
import com.simoscal.android.displayExact
import com.simoscal.android.EditorUiState
import com.simoscal.android.EditorViewModel
import com.simoscal.android.SLOT_IDS
import com.simoscal.android.withFlippedSign
import kotlin.math.abs

/**
 * How tall the plot is drawn upright.
 *
 * Bigger than the 260dp this started at, because the plot is the screen: every
 * other control here exists to move one of its twelve breakpoints, and a
 * fingertip covers a larger share of a short plot than of a tall one — the psi
 * a drag lands on is read off its height. The rest of the column scrolls, so
 * the height is bought from scroll rather than from anything else on screen.
 */
private val PortraitPlotHeight = 360.dp

/**
 * The boost editor: the switch patch's five map slots against the base ceiling.
 *
 * Edits are *staged*. A drag and the batch controls all move a local draft, and
 * only **Apply** sends it to the engine as one journaled op. That is why the
 * Apply button carries an intent line: every calibration change in this project
 * records why it was made, and the phone is no exception.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoostScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boost = state.boost

    // Read once per session rather than on every visit: the model is only stale
    // after an edit, and every path that edits already re-reads it.
    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null && boost.model == null) viewModel.loadBoostCurve()
    }

    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var flatCapOpen by remember { mutableStateOf(false) }
    var axisOpen by remember { mutableStateOf(false) }
    // Saveable, unlike the three above: this one holds something a person typed.
    // The dialogs can afford to close on a rotation; a half-written reason for a
    // calibration change cannot afford to vanish.
    var intent by rememberSaveable { mutableStateOf("") }

    // Hoisted above the layout because the dialogs below it need the same model,
    // and the alternative is re-reaching into `boost` with a second null check.
    val model = boost.model

    // A tap on the plot keeps opening numeric entry, as it always has, and now
    // also moves the stepper's selection there — so the breakpoint the buttons
    // act on is the one last touched, and no existing gesture changed meaning.
    val selectAndType: (Int) -> Unit = { index ->
        viewModel.onBoostPointSelected(index)
        editingIndex = index
    }

    // One definition of what Apply does, shared by both orientations: the
    // default intent is the thing that gets journaled when the field is left
    // blank, and two copies of that sentence would eventually disagree.
    val applyDraft: () -> Unit = {
        viewModel.applyBoostDraft(
            intent.ifBlank { "cap map slot ${boost.activeSlot} from the simoscal boost editor" }
        )
        intent = ""
    }

    when {
        // Nothing to draw yet. Same answer either way up — a header and one card
        // that says what is missing.
        state.switchPatchXdf == null || model == null -> BoostUnavailable(state)

        // Turning the device sideways is the gesture for "show me the curve":
        // the plot takes the height the shell's top bar gave back (see
        // [SimoscalApp]) and everything that is not the plot, or a control the
        // plot needs, stands down until the screen is upright again.
        isLandscape() -> BoostLandscape(
            viewModel = viewModel,
            state = state,
            model = model,
            intent = intent,
            onIntentChanged = { intent = it },
            onApply = applyDraft,
            onFlatCap = { flatCapOpen = true },
            onEditPoint = selectAndType,
            onEditSelected = { editingIndex = boost.selectedIndex },
        )

        else -> BoostPortrait(
            viewModel = viewModel,
            state = state,
            model = model,
            intent = intent,
            onIntentChanged = { intent = it },
            onApply = applyDraft,
            onFlatCap = { flatCapOpen = true },
            onEditAxis = { axisOpen = true },
            onEditPoint = selectAndType,
            onEditSelected = { editingIndex = boost.selectedIndex },
        )
    }

    // ------------------------------------------------------------------ dialogs

    editingIndex?.let { index ->
        NumericEntryDialog(
            title = "Slot ${boost.activeSlot} at ${model?.rpmAxis?.getOrNull(index)?.toInt() ?: 0} rpm",
            supporting = "psi gauge. Values at or above the base ceiling are refused, not rounded down.",
            initial = boost.draft.getOrNull(index)?.let { it.display("%.2f") } ?: "",
            onDismiss = { editingIndex = null },
            onConfirm = { value ->
                viewModel.onBoostPointTyped(index, value)
                editingIndex = null
            },
        )
    }

    if (flatCapOpen) {
        NumericEntryDialog(
            title = "Flat cap for slot ${boost.activeSlot}",
            supporting = "One psi figure across every rpm breakpoint.",
            initial = boost.draft.firstOrNull()?.let { it.display("%.2f") } ?: "",
            onDismiss = { flatCapOpen = false },
            onConfirm = { value ->
                viewModel.onBoostFlatCap(value)
                flatCapOpen = false
            },
        )
    }

    if (axisOpen) {
        val current = model?.rpmAxis.orEmpty()
        RpmAxisDialog(
            current = current,
            onDismiss = { axisOpen = false },
            onConfirm = { breakpoints ->
                viewModel.applySlotRpmAxis(breakpoints, "re-breakpoint the shared slot rpm axis")
                axisOpen = false
            },
        )
    }
}

/**
 * The header and one card, for the two states with no curve to draw.
 *
 * Kept separate from the editors so neither of them has to open with a pair of
 * null checks and an early return out of a layout it has already started.
 */
@Composable
private fun BoostUnavailable(state: EditorUiState) {
    val boost = state.boost
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Five maps, one switch", title = "Boost")

        SessionProvenanceCard(binName = state.bin?.displayName, shortHash = state.bin?.shortHash)

        if (state.switchPatchXdf == null) {
            NoticeCard(
                title = "Switch-patch XDF needed",
                body = "The Boost editor needs a switch-patch XDF, imported from the landing " +
                    "screen, before it has anything to edit.",
            )
        } else {
            NoticeCard(
                title = if (boost.loading) "Reading the slot curves…" else "No boost model",
                body = boost.unavailable
                    ?: "The five map-slot boost caps are being read from the session.",
            )
        }
    }
}

/**
 * The upright editor: plot first, then every control, in one scrolling column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoostPortrait(
    viewModel: EditorViewModel,
    state: EditorUiState,
    model: BoostCurveModel,
    intent: String,
    onIntentChanged: (String) -> Unit,
    onApply: () -> Unit,
    onFlatCap: () -> Unit,
    onEditAxis: () -> Unit,
    onEditPoint: (Int) -> Unit,
    onEditSelected: () -> Unit,
) {
    val boost = state.boost

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Five maps, one switch", title = "Boost")

        SessionProvenanceCard(binName = state.bin?.displayName, shortHash = state.bin?.shortHash)

        SlotChips(
            activeSlot = boost.activeSlot,
            dirty = boost.dirty,
            onSelect = viewModel::onBoostSlotSelected,
        )

        BoostCanvas(
            model = model,
            activeSlot = boost.activeSlot,
            draft = boost.draft,
            selectedIndex = boost.selectedIndex,
            modifier = Modifier.height(PortraitPlotHeight),
            onDragPoint = viewModel::onBoostPointDragged,
            onTapPoint = onEditPoint,
        )

        BreakpointStepper(viewModel = viewModel, boost = boost, onType = onEditSelected)

        // Directly under the stepper rather than down by Apply, which is where
        // this card used to live. Leaning on + at the top of the range stops the
        // number moving and posts the reason here — a screen's height away, the
        // refusal was invisible and the value simply appeared to stick.
        boost.notice?.let { notice ->
            NoticeCard(title = "Not applied", body = notice, emphasise = true)
        }

        CeilingLegend(
            refusalPsi = model.refusalCeilingPsi,
            cappedCount = boost.draftCappedByBase.size,
        )

        // Batch controls. Copy is offered per source slot rather than as a menu so
        // the destination is never ambiguous: whatever is copied lands on the
        // slot the chips say is active.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PromoOutlinedButton(onClick = onFlatCap) { Text("Flat cap") }
            PromoOutlinedButton(onClick = viewModel::onBoostSmooth) { Text("Smooth") }
            PromoOutlinedButton(onClick = viewModel::onBoostDiscard, enabled = boost.dirty) { Text("Discard") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Kicker(
                "Copy from",
                color = PromoPalette.TextFaint,
                modifier = Modifier.padding(top = 14.dp),
            )
            SLOT_IDS.filter { it != boost.activeSlot }.forEach { slot ->
                AssistChip(
                    onClick = { viewModel.onBoostCopyFrom(slot) },
                    label = { Text("$slot", color = slotColor(slot)) },
                    colors = promoAssistChipColors(),
                )
            }
        }

        OutlinedTextField(
            value = intent,
            onValueChange = onIntentChanged,
            label = { Text("Why this change (recorded in the journal)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        PromoButton(
            onClick = onApply,
            enabled = boost.canApply && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (boost.dirty) "Apply to slot ${boost.activeSlot}" else "No change to apply")
        }

        boost.lastEdit?.let { receipt -> EncodedReceiptCard(receipt.slot, receipt.requestedPsi, receipt.encodedPsi, receipt.floored) }

        // History and the shared axis are disabled while a draft is staged: both
        // re-read this editor from the engine on success, which would replace the
        // draft with committed values and drop the proposal without asking.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PromoOutlinedButton(
                onClick = viewModel::undo,
                enabled = state.canUndo && state.canMutateSession,
            ) { Text("Undo") }
            PromoOutlinedButton(
                onClick = viewModel::redo,
                enabled = state.canRedo && state.canMutateSession,
            ) { Text("Redo") }
        }

        PromoOutlinedButton(
            onClick = onEditAxis,
            enabled = state.canMutateSession,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit shared rpm breakpoints")
        }
        Caption(
            "One rpm axis serves all five slots — moving it re-interprets every " +
                "slot curve at once, so read the five curves before you move it."
        )

        state.dirtyDraftRefusal?.let { reason ->
            Caption(reason, color = PromoPalette.Danger)
        }
    }
}

/**
 * The sideways editor: the plot, and only what it takes to work on it.
 *
 * The column does not scroll. Everything on screen is either the plot or a
 * control the plot needs, so the plot can take every pixel the other two rows
 * do not — which is the entire reason to turn the device. What is dropped
 * against portrait is what a person can read while upright and does not need
 * mid-drag: the provenance card, the copy-from chips, the ceiling legend in
 * prose, the receipt, and the shared-axis editor, whose blast radius is not
 * something to reach for on the way past.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoostLandscape(
    viewModel: EditorViewModel,
    state: EditorUiState,
    model: BoostCurveModel,
    intent: String,
    onIntentChanged: (String) -> Unit,
    onApply: () -> Unit,
    onFlatCap: () -> Unit,
    onEditPoint: (Int) -> Unit,
    onEditSelected: () -> Unit,
) {
    val boost = state.boost

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SlotChipRow(activeSlot = boost.activeSlot, onSelect = viewModel::onBoostSlotSelected)
            // The intent line stays, narrowed rather than dropped: an edit that
            // reaches the bin without a reason recorded is the one thing this
            // project does not do, in either orientation.
            OutlinedTextField(
                value = intent,
                onValueChange = onIntentChanged,
                label = { Text("Why this change") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        BoostCanvas(
            model = model,
            activeSlot = boost.activeSlot,
            draft = boost.draft,
            selectedIndex = boost.selectedIndex,
            modifier = Modifier.weight(1f),
            onDragPoint = viewModel::onBoostPointDragged,
            onTapPoint = onEditPoint,
        )

        BoostStatusLine(state = state, model = model)

        CompactBreakpointStepper(viewModel = viewModel, boost = boost, onType = onEditSelected)

        // One row, scrollable sideways rather than wrapped, so the buttons keep
        // the same order and the same place on every device — this is the row a
        // hand comes back to between drags.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            PromoOutlinedButton(onClick = onFlatCap) { Text("Flat cap") }
            PromoOutlinedButton(onClick = viewModel::onBoostSmooth) { Text("Smooth") }
            PromoOutlinedButton(
                onClick = viewModel::onBoostDiscard,
                enabled = boost.dirty,
            ) { Text("Discard") }
            PromoOutlinedButton(
                onClick = viewModel::undo,
                enabled = state.canUndo && state.canMutateSession,
            ) { Text("Undo") }
            PromoOutlinedButton(
                onClick = viewModel::redo,
                enabled = state.canRedo && state.canMutateSession,
            ) { Text("Redo") }
            PromoButton(onClick = onApply, enabled = boost.canApply && !state.busy) {
                Text(if (boost.dirty) "Apply to slot ${boost.activeSlot}" else "No change to apply")
            }
        }
    }
}

/**
 * The stepper: pick a breakpoint, pick an increment, press plus or minus.
 *
 * The reason it exists next to a draggable plot is that the two answer different
 * questions. A drag is for the *shape* of a curve — fast, approximate, and read
 * off the picture. This is for the *number*: 0.2 psi off one breakpoint is a
 * change a fingertip cannot reliably make on a plot this size, and the person
 * making it already knows exactly which breakpoint and exactly how much.
 *
 * Every press goes through the typed path (see [nudgingSelection]), so the
 * ceiling refuses rather than clamps, and the value that lands is the value the
 * arithmetic asked for.
 */
@Composable
private fun BreakpointStepper(
    viewModel: EditorViewModel,
    boost: BoostUiState,
    onType: () -> Unit,
) {
    Panel(padding = 12.dp, spacing = 10.dp) {
        Kicker("Breakpoint", color = PromoPalette.TextFaint)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PromoOutlinedButton(onClick = { viewModel.onBoostSelectionStepped(-1) }) {
                Text("◀", style = PromoType.identifier)
            }
            BreakpointReadout(boost = boost, onType = onType, modifier = Modifier.weight(1f))
            PromoOutlinedButton(onClick = { viewModel.onBoostSelectionStepped(1) }) {
                Text("▶", style = PromoType.identifier)
            }
        }

        StepChips(selected = boost.nudgeStepPsi, onSelect = viewModel::onBoostNudgeStepChanged)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PromoOutlinedButton(
                onClick = { viewModel.onBoostNudged(-1) },
                modifier = Modifier.weight(1f),
            ) { Text("− ${boost.nudgeStepPsi.displayExact()} psi") }
            PromoOutlinedButton(
                onClick = { viewModel.onBoostNudged(1) },
                modifier = Modifier.weight(1f),
            ) { Text("+ ${boost.nudgeStepPsi.displayExact()} psi") }
        }
    }
}

/**
 * The same controls in one row, for landscape.
 *
 * Scrolls sideways rather than wrapping: the order these sit in is muscle
 * memory after the first minute, and a row that re-flows on a narrower device
 * would put minus where plus was.
 */
@Composable
private fun CompactBreakpointStepper(
    viewModel: EditorViewModel,
    boost: BoostUiState,
    onType: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        PromoOutlinedButton(onClick = { viewModel.onBoostSelectionStepped(-1) }) {
            Text("◀", style = PromoType.identifier)
        }
        BreakpointReadout(boost = boost, onType = onType, compact = true)
        PromoOutlinedButton(onClick = { viewModel.onBoostSelectionStepped(1) }) {
            Text("▶", style = PromoType.identifier)
        }
        StepChips(selected = boost.nudgeStepPsi, onSelect = viewModel::onBoostNudgeStepChanged)
        PromoOutlinedButton(onClick = { viewModel.onBoostNudged(-1) }) {
            Text("− ${boost.nudgeStepPsi.displayExact()}")
        }
        PromoOutlinedButton(onClick = { viewModel.onBoostNudged(1) }) {
            Text("+ ${boost.nudgeStepPsi.displayExact()}")
        }
    }
}

/**
 * Which breakpoint is selected, and what it currently holds.
 *
 * Both numbers, always: the rpm says which point on the plot is about to move
 * and the psi is the thing being moved, and a stepper that showed only one of
 * them would be asking someone to look up at the curve after every press to
 * find out what they had done. Tapping it opens numeric entry for the same
 * breakpoint — the exact-value route, for when stepping is the wrong tool.
 */
@Composable
private fun BreakpointReadout(
    boost: BoostUiState,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val rpm = boost.selectedRpm
    val psi = boost.selectedPsi
    Column(
        modifier = modifier
            .clickable(onClickLabel = "Type an exact value", onClick = onType)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(IdentifierSpan) { append(rpm?.toInt()?.toString() ?: "—") }
                withStyle(SpanStyle(color = PromoPalette.TextFaint)) { append(" rpm") }
                append("   ")
                withStyle(SpanStyle(color = slotColor(boost.activeSlot))) {
                    append(psi?.display("%.2f") ?: "—")
                }
                withStyle(SpanStyle(color = PromoPalette.TextFaint)) { append(" psi") }
            },
            style = PromoType.identifier,
        )
        if (!compact) {
            Caption("Breakpoint ${boost.selectedIndex + 1} of ${boost.draft.size} · tap to type")
        }
    }
}

/**
 * The increment ladder.
 *
 * Chips rather than a menu because the current choice has to be readable without
 * opening anything — the plus button says "+ 0.5 psi", and the chip row is where
 * that 0.5 came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepChips(selected: Double, onSelect: (Double) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kicker("Step", color = PromoPalette.TextFaint)
        BOOST_NUDGE_STEPS.forEach { step ->
            FilterChip(
                selected = abs(step - selected) < 1e-9,
                onClick = { onSelect(step) },
                label = { Text(step.displayExact()) },
                colors = promoFilterChipColors(),
            )
        }
    }
}

/**
 * The one line of prose landscape keeps, and what it says in priority order.
 *
 * Capped at two lines and always present, so the plot above it keeps the same
 * height as the draft changes: a refusal outranks a warning, a warning outranks
 * the legend, and with nothing to report it names the two limits drawn on the
 * canvas.
 */
@Composable
private fun BoostStatusLine(state: EditorUiState, model: BoostCurveModel) {
    val boost = state.boost
    val capped = boost.draftCappedByBase.size
    val notice = boost.notice
    val (text, color) = when {
        // A refusal that already happened outranks anything about the draft on
        // screen: it is the one line that says an edit did *not* land.
        notice != null -> "Not applied — $notice" to PromoPalette.Danger
        // Ahead of "unapplied change", which is true whenever this is: a
        // breakpoint the base ceiling swallows is the finding, and being told
        // only that the draft is unapplied would bury it.
        capped > 0 ->
            "$capped breakpoint${if (capped == 1) "" else "s"} above the base ceiling — " +
                "accepted, but the ECU takes the base there." to PromoPalette.Warn
        boost.dirty ->
            "Slot ${boost.activeSlot} has an unapplied change — apply or discard " +
                "before switching slots." to PromoPalette.Warn
        else ->
            "Solid line: base ceiling. Dashed: ${model.refusalCeilingPsi.display("%.2f")} psi, " +
                "which the engine refuses." to PromoPalette.TextFaint
    }
    Caption(text, color = color, maxLines = 2)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotChips(activeSlot: Int, dirty: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlotChipRow(activeSlot = activeSlot, onSelect = onSelect)
        if (dirty) {
            Caption(
                "Slot $activeSlot has an unapplied change. Apply or discard it before " +
                    "switching slots.",
                color = PromoPalette.Danger,
            )
        }
    }
}

/** The five chips alone — the picker both orientations share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotChipRow(activeSlot: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SLOT_IDS.forEach { slot ->
            FilterChip(
                selected = slot == activeSlot,
                onClick = { onSelect(slot) },
                // The slot's own curve colour on its chip, so the chips and the
                // canvas name the same five things the same way — the chip is
                // how you pick a curve, and matching them is what makes "which
                // one am I about to edit" a glance rather than a count.
                label = {
                    Text(
                        "Slot $slot",
                        color = if (slot == activeSlot) slotColor(slot) else PromoPalette.TextDim,
                    )
                },
                colors = promoFilterChipColors(),
            )
        }
    }
}

/**
 * Names the two ceilings in words, because a shaded band alone does not say which
 * limit refuses an edit and which merely swallows it.
 */
@Composable
private fun CeilingLegend(refusalPsi: Double, cappedCount: Int) {
    Panel(padding = 12.dp) {
        Caption(
            buildAnnotatedString {
                append("Solid line: the base ")
                withStyle(IdentifierSpan) { append("IP_PUT_SP") }
                append(
                    " — Pressure up throttle setpoint full-load ceiling. The ECU " +
                        "targets min(base, slot), so a slot above it changes nothing " +
                        "at that rpm."
                )
            }
        )
        Caption(
            "Dashed line: ${refusalPsi.display("%.2f")} psi. The engine refuses any cap that reaches it."
        )
        if (cappedCount > 0) {
            Caption(
                "$cappedCount breakpoint${if (cappedCount == 1) "" else "s"} of this " +
                    "draft sit above the base ceiling and will have no effect there.",
                // Warn, not Danger: the base ceiling swallows these breakpoints, it
                // does not refuse the edit. Danger is reserved for a refusal.
                color = PromoPalette.Warn,
            )
        }
    }
}

/**
 * Requested vs encoded, always shown after an apply.
 *
 * psi is floored on its way to stored hPa, so a cap asked for as 10.00 lands a
 * hair under it. Showing both numbers is the difference between a tool that
 * reports what the bin holds and one that reports what you hoped it held.
 */
@Composable
private fun EncodedReceiptCard(
    slot: Int,
    requested: List<Double>,
    encoded: List<Double>,
    floored: Boolean,
) {
    // Accent: this panel is the thing that just changed, which is exactly what
    // the palette reserves that colour for.
    Panel(tone = PanelTone.Accent) {
        PanelTitle("Slot $slot applied", tone = PanelTone.Accent)
        val worst = requested.indices
            .maxByOrNull { abs(requested[it] - encoded.getOrElse(it) { requested[it] }) }
        if (floored && worst != null) {
            Text(
                "Floored: asked ${requested[worst].display("%.2f")} psi, stored " +
                    "${encoded.getOrElse(worst) { requested[worst] }.display("%.2f")} psi at the widest point.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Stored exactly as requested.", style = MaterialTheme.typography.bodyMedium)
        }
        // What actually landed in the bin, in a monospace row: twelve figures
        // that should be compared against each other, which a proportional face
        // makes needlessly hard.
        Text(
            encoded.joinToString(" · ") { it.display("%.1f") },
            style = PromoType.figureSmall,
            color = PromoPalette.TextDim,
        )
    }
}

/**
 * A titled block of prose. `emphasise` marks the ones that report a refusal —
 * "Not applied" — and those take the danger tone; everything else is ordinary
 * context and stays on the neutral hairline panel.
 */
@Composable
internal fun NoticeCard(title: String, body: String, emphasise: Boolean = false) {
    val tone = if (emphasise) PanelTone.Danger else PanelTone.Neutral
    Panel(tone = tone) {
        PanelTitle(title, tone = tone)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Typed numeric entry.
 *
 * Confirm is disabled until the text parses. A dialog that accepted "1O.5" and
 * quietly did nothing would be worse than one that refuses it visibly.
 */
@Composable
internal fun NumericEntryDialog(
    title: String,
    supporting: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = text.trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        // Explicit panel fill rather than Material's tonal-elevation surface: the
        // scheme's `surfaceTint` is the accent, so an elevated dialog would lift
        // the app's near-black ground toward orange for no reason anyone chose.
        containerColor = PromoPalette.BgAlt,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        // Decimal, not Number: `Number` asks the IME for digits only,
                        // and every value this dialog edits is a Double. A cell holding
                        // 3.100097 or an Offset of 0.05 cannot be typed on a keypad
                        // with no decimal point, and the Set button stays disabled
                        // because `toDoubleOrNull` never sees a parseable string.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = PromoType.identifier,
                        modifier = Modifier.weight(1f),
                    )
                    // The sign, which no numeric IME will give us — see
                    // `withFlippedSign`. Not a keyboard workaround so much as the
                    // only reliable route to a negative number on this device.
                    PromoOutlinedButton(onClick = { text = text.withFlippedSign() }) { Text("±") }
                }
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The shared rpm axis, edited as one comma-separated line.
 *
 * A twelve-field form on a phone is worse than a single line someone can read
 * end to end and sanity-check for order. The engine enforces strictly-increasing
 * breakpoints and the axis-length header regardless; this only refuses the
 * obvious cases early.
 */
@Composable
private fun RpmAxisDialog(
    current: List<Double>,
    onDismiss: () -> Unit,
    onConfirm: (List<Double>) -> Unit,
) {
    var text by remember { mutableStateOf(current.joinToString(", ") { it.toInt().toString() }) }
    val parsed = text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    val rightLength = parsed.size == current.size
    val increasing = parsed.zipWithNext().all { (a, b) -> b > a }
    val valid = rightLength && increasing && parsed.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PromoPalette.BgAlt,
        title = { Text("Shared slot rpm axis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("${current.size} breakpoints, comma separated") },
                    textStyle = PromoType.identifier,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        !rightLength -> "Needs exactly ${current.size} values; ${parsed.size} parsed."
                        !increasing -> "Breakpoints must strictly increase."
                        else -> "This axis is shared by all five slots. Every slot curve " +
                            "is re-interpreted against it — the stored grids do not move."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed) }, enabled = valid) { Text("Apply axis") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
