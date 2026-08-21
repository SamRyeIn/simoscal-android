package com.simoscal.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.ChangeEntry
import com.simoscal.android.ChangesUiState
import com.simoscal.android.EditorViewModel
import com.simoscal.android.Verdict

/**
 * Everything this session has changed in the bin, in the order it happened.
 *
 * Laid out after the library's own `report.md` HTML page — the page a person
 * reads at the moment they decide whether to flash — because the two answer the
 * same question and there is no reason to make someone learn it twice: a tally
 * of verdicts across the top, held-back items lifted out of the list before the
 * list, then the changes themselves as cards, then the full journal collapsed
 * underneath. The colours carry the same meanings the report's do.
 *
 * What it deliberately does *not* borrow is the report's verdict banner. That
 * banner is a build's product — it speaks for checksums, readback and the byte
 * audit — and this screen has run none of those. It shows the *unverified*
 * running list, says so plainly at the top, and points at Build for the rest
 * (see the engine's `journal` op and CR-20260724-02). A person must never be
 * able to mistake this page for a flash gate.
 *
 * It re-reads the journal on every visit rather than subscribing to anything:
 * navigating away leaves the composition, coming back re-runs the effect below,
 * so an edit made on Tables or Boost is on this screen the moment you return to
 * it.
 */
@Composable
fun ChangesScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val changes = state.changes

    // Unconditional, and keyed on the session so a recovered session re-reads
    // too. Not gated on `loaded` the way the slots screen gates its one-time
    // read: this list exists to be current, and a stale one is the failure mode.
    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null) viewModel.loadJournal()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(kicker = "What you changed this session", title = "Changes")

        SessionProvenanceCard(
            binName = state.bin?.displayName,
            shortHash = state.bin?.shortHash,
        )

        UnverifiedBanner(changes)

        if (!changes.loaded) {
            NoticeCard(
                title = if (changes.loading) "Reading the journal…" else "Not read yet",
                body = changes.notice
                    ?: "The session's edit journal is being read from the engine.",
            )
            return@Column
        }

        changes.notice?.let { notice ->
            NoticeCard(
                title = "This list may be out of date",
                body = "$notice The entries below are the last ones read successfully.",
                emphasise = true,
            )
        }

        if (changes.isEmpty) {
            NothingChangedCard()
            return@Column
        }

        VerdictTally(changes)
        AttentionSection(changes.attention)
        ChangedSection(changes)
        FullJournalSection(changes.entries)
        Footnote()
    }
}

// ------------------------------------------------------------------- sections

/**
 * The standing caveat, in place of the report's verdict banner.
 *
 * Always on screen, never conditional on what the list holds, because it is not
 * a caveat about *these* changes — it is the boundary of what this page can ever
 * say. Nothing here has been checksummed, read back, or audited against the
 * bytes; that happens on Build and only there.
 */
@Composable
private fun UnverifiedBanner(changes: ChangesUiState) {
    Panel(tone = PanelTone.Warn, spacing = 6.dp) {
        PanelTitle("Unverified — this is the running list, not a build", tone = PanelTone.Warn)
        Text(
            "These are the edits the session has recorded so far. Nothing here has " +
                "been checksummed, read back off a saved file, or audited byte by " +
                "byte — Build does that, and only a verified build can be exported.",
            style = MaterialTheme.typography.bodySmall,
            color = PromoPalette.TextDim,
        )
        if (changes.loading && changes.loaded) {
            Caption("Refreshing…", color = PromoPalette.TextFaint)
        }
    }
}

/** The report's chip row: the verdict tally, in the engine's own order. */
@Composable
private fun VerdictTally(changes: ChangesUiState) {
    SectionHead("Tally", "${changes.entries.size} journal entries")
    Panel(padding = 12.dp, spacing = 10.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                changes.appliedCount.toString(),
                style = PromoType.figureLarge,
                color = PromoPalette.Accent,
            )
            Column {
                Text(
                    if (changes.appliedCount == 1) "edit moved bytes" else "edits moved bytes",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Caption(
                    "across ${changes.changedTables.size} " +
                        if (changes.changedTables.size == 1) "table" else "tables",
                )
            }
        }
        // Every verdict the engine counted, including the ones with no bytes
        // behind them. The big figure above is what changed; this is everything
        // that was *recorded*, and the difference between the two is the point.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            changes.counts.forEach { (verdict, count) ->
                VerdictPill(Verdict.parse(verdict), count = count)
            }
        }
    }
}

/**
 * The report's "Needs your eyes": everything held back or flagged, lifted out of
 * the journal so it cannot be scrolled past.
 *
 * The empty case gets its own card rather than being omitted. "No section" and
 * "nothing was held back" look identical when one of them is silence, and only
 * one of them is a finding.
 */
@Composable
private fun AttentionSection(attention: List<ChangeEntry>) {
    SectionHead(
        "Needs your eyes",
        if (attention.isEmpty()) "nothing held back" else "${attention.size} flagged",
    )
    if (attention.isEmpty()) {
        Panel(tone = PanelTone.Good, spacing = 4.dp) {
            PanelTitle("Nothing held back", tone = PanelTone.Good)
            Caption("Every recorded edit applied, and no guard, skip, or warning was logged.")
        }
        return
    }
    attention.forEach { entry ->
        val tone = if (entry.verdict == Verdict.BLOCKED || entry.warning.isNotBlank()) {
            PanelTone.Warn
        } else {
            PanelTone.Neutral
        }
        Panel(tone = tone, spacing = 6.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VerdictPill(entry.verdict)
                if (entry.warning.isNotBlank()) {
                    Tag("WARNING", PromoPalette.Warn, PromoPalette.WarnContainer)
                }
            }
            Identifier(entry.label)
            val body = listOf(entry.warning, entry.why).firstOrNull { it.isNotBlank() }
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = PromoPalette.TextDim)
            }
        }
    }
}

/** The report's inline cards: one per table this session actually wrote. */
@Composable
private fun ChangedSection(changes: ChangesUiState) {
    val applied = changes.entries.filter { it.verdict == Verdict.APPLIED && it.touched }
    SectionHead("What changed", "${applied.size} applied")
    if (applied.isEmpty()) {
        Panel(spacing = 4.dp) {
            PanelTitle("No bytes moved")
            Caption(
                "The journal has entries, but none of them changed the bin — every " +
                    "target was already met, or every write was held back."
            )
        }
        return
    }
    applied.forEach { ChangeCard(it) }
}

/**
 * One applied edit: what it was, what moved, and why.
 *
 * Before and after are printed as the engine summarized them — already narrowed
 * to the rows that actually moved, so a one-row change in a 16×16 map reads as
 * that change rather than as a whole-grid range that barely shifts.
 */
@Composable
private fun ChangeCard(entry: ChangeEntry) {
    Panel(tone = PanelTone.Accent, spacing = 8.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Identifier(entry.label)
                Caption(
                    listOf(entry.scope, entry.cellsText).filter { it.isNotBlank() }
                        .joinToString(" · "),
                    color = PromoPalette.TextFaint,
                )
            }
            VerdictPill(entry.verdict)
        }

        if (entry.hasValues) {
            BeforeAfter(entry)
        }

        if (entry.why.isNotBlank()) {
            Text(
                entry.why,
                style = MaterialTheme.typography.bodySmall,
                color = PromoPalette.TextDim,
            )
        }

        if (entry.warning.isNotBlank()) {
            Text(
                entry.warning,
                style = MaterialTheme.typography.bodySmall,
                color = PromoPalette.Warn,
            )
        }
    }
}

/**
 * `before → after`, with the after in the accent.
 *
 * The video's rule, kept: the coloured thing on screen is the number that
 * changed, never the container it sits in. The units ride on the after so the
 * pair does not print them twice.
 */
@Composable
private fun BeforeAfter(entry: ChangeEntry) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(entry.before, style = PromoType.figureSmall, color = PromoPalette.TextDim)
        Text("→", style = PromoType.figureSmall, color = PromoPalette.TextFaint)
        Text(
            entry.after,
            style = PromoType.figureSmall,
            color = PromoPalette.Accent,
            fontWeight = FontWeight.Bold,
        )
        if (entry.units.isNotBlank()) {
            Text(entry.units, style = PromoType.figureSmall, color = PromoPalette.TextFaint)
        }
    }
}

/**
 * The report's collapsed `<details>` journal — every entry, applied or not.
 *
 * Collapsed by default for the same reason the report collapses it: a bulk
 * recipe run journals hundreds of rows, and burying the handful that changed
 * something under them is how a reviewer stops reading. Open, it is the whole
 * record in call order.
 */
@Composable
private fun FullJournalSection(entries: List<ChangeEntry>) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionHead("Full journal", "every recorded entry")
    Panel(padding = 0.dp, spacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "▾" else "▸",
                style = PromoType.figureSmall,
                color = PromoPalette.TextFaint,
            )
            Text(
                "All ${entries.size} entries",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Caption(if (open) "Hide" else "Show", color = PromoPalette.TextFaint)
        }
        AnimatedVisibility(visible = open) {
            Column {
                entries.forEach { entry ->
                    HairRule(color = PromoPalette.RuleFaint)
                    JournalRow(entry)
                }
            }
        }
    }
}

@Composable
private fun JournalRow(entry: ChangeEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Identifier(entry.label)
                if (entry.scope.isNotBlank()) {
                    Caption(entry.scope, color = PromoPalette.TextFaint)
                }
            }
            VerdictPill(entry.verdict)
        }
        if (entry.hasValues) BeforeAfter(entry)
        // Why a superseded row is not the contradiction it looks like. Without
        // this line a `skipped` and an `applied` row name the same table and
        // nothing on screen explains it.
        if (entry.supersededBy.isNotBlank()) {
            Caption(
                "Deferred by the base recipe; written later this session by " +
                    "${entry.supersededBy}.",
                color = PromoPalette.TextFaint,
            )
        }
    }
}

@Composable
private fun NothingChangedCard() {
    Panel(spacing = 6.dp) {
        PanelTitle("No changes yet")
        Caption(
            "Nothing in this session has been written to the bin. Edit a table, " +
                "draw a boost curve, or toggle a slot setting and it appears here."
        )
    }
}

@Composable
private fun Footnote() {
    HairRule()
    Caption(
        "Read live from the engine's edit journal — the same record report.md and " +
            "the byte audit are built from. Undo and redo move this list with them. " +
            "Every revision is a starting point, not a finished calibration: only " +
            "logs validate it.",
        color = PromoPalette.TextFaint,
    )
}

// --------------------------------------------------------------------- pieces

/** The report's `.sec-head`: a title, a rule, and a dim eyebrow. */
@Composable
private fun SectionHead(title: String, eyebrow: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        HairRule(modifier = Modifier.weight(1f))
        Kicker(eyebrow, color = PromoPalette.TextFaint)
    }
}

/**
 * The report's `.vpill`, with the report's colour assignments.
 *
 * `unchanged` and `skipped` stay deliberately colourless. They are the two
 * verdicts that mean *nothing happened*, and giving either a hue would make an
 * absence of change look like a kind of change.
 */
@Composable
private fun VerdictPill(verdict: Verdict, count: Int? = null) {
    val (ink, fill) = when (verdict) {
        Verdict.APPLIED, Verdict.SUPERSEDED -> PromoPalette.Good to PromoPalette.GoodContainer
        Verdict.GUARDED_SKIP, Verdict.BLOCKED -> PromoPalette.Warn to PromoPalette.WarnContainer
        Verdict.UNCHANGED, Verdict.SKIPPED -> PromoPalette.TextDim to PromoPalette.BgAlt
        Verdict.UNKNOWN -> PromoPalette.Accent2 to PromoPalette.Accent2Container
    }
    val label = verdict.name.lowercase().replace('_', ' ')
    Tag(if (count == null) label else "$count $label", ink, fill)
}

/** A small monospace pill — the report's `.vpill` and `.c-tag` in one shape. */
@Composable
private fun Tag(text: String, ink: Color, fill: Color) {
    Text(
        text,
        style = PromoType.figureSmall,
        color = ink,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(fill, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** "3 cells" — omitted entirely when the engine did not count any. */
private val ChangeEntry.cellsText: String
    get() = when (cellsChanged) {
        0 -> ""
        1 -> "1 cell"
        else -> "$cellsChanged cells"
    }
