package com.simoscal.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.android.AnalysisPlot
import com.simoscal.android.AnalysisReport
import com.simoscal.android.AnalysisUiState
import com.simoscal.android.AnalysisViewModel
import com.simoscal.android.Finding
import com.simoscal.android.ImportedFile
import com.simoscal.android.InputKind
import com.simoscal.android.PlotPanel
import com.simoscal.android.Severity
import com.simoscal.android.SeriesRole
import com.simoscal.android.SkippedCheck
import com.simoscal.android.displayMeasured

/**
 * Datalog analysis: pick SimosTools CSVs, run the engine's check battery, read
 * the findings and the evidence plots.
 *
 * The one screen in the app that touches no calibration bytes. It opens no
 * session and is reachable without one, because reading a log has nothing to do
 * with editing a bin — gating it on an open session would be a gate with no
 * safety behind it.
 *
 * What it renders is deliberately the *engine's* output and not a second opinion
 * on it. The findings, their severities, the pull segmentation, which channel is
 * on which panel, and even the sentence printed above each plot all arrive over
 * the bridge from `simoscal.analysis`; this file lays them out. So a plot here
 * and the same plot in the library's PNG report are the same plot, and neither
 * can quietly drift from the other.
 *
 * Plots are presented **in alphabetical order by id**, always the same order
 * whatever the log contained, so the screen is a list someone can learn rather
 * than one that reshuffles with the data.
 */
@Composable
fun AnalyzeScreen(viewModel: AnalysisViewModel, onBack: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.onLogsPicked(uris) }
    val binPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.onFilePicked(it, InputKind.BIN) } }
    val xdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.onFilePicked(it, InputKind.XDF) } }

    Column(modifier = Modifier.fillMaxWidth()) {
        // This screen has its own busy bar. The shell's one is driven by the
        // editor's state, and analysis runs on a different view model entirely —
        // without this, a battery running over eight CSVs would look like a
        // screen doing nothing at all.
        if (state.busy) {
            LinearProgressIndicator(
                color = PromoPalette.Accent,
                trackColor = PromoPalette.Rule,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ScreenHeader("Datalog analysis", "Analyze")
                // Only when the navigation bar is absent — with no session open
                // there is otherwise no way off this screen.
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("Done") }
                }
            }

            LogInputs(
                state = state,
                onAddLogs = { logPicker.launch(InputKind.LOG.mimeTypes) },
                onRemoveLog = viewModel::removeLog,
                onChooseBin = { binPicker.launch(InputKind.BIN.mimeTypes) },
                onChooseXdf = { xdfPicker.launch(InputKind.XDF.mimeTypes) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromoButton(
                    onClick = viewModel::run,
                    enabled = state.canRun,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.report == null) "Analyze" else "Analyze again")
                }
                if (state.logs.isNotEmpty()) {
                    PromoOutlinedButton(onClick = viewModel::clearAll) { Text("Clear") }
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Reading logs and running the checks...")
                }
            }

            state.error?.let { error ->
                Panel(tone = PanelTone.Danger) {
                    PanelTitle("That did not run", tone = PanelTone.Danger)
                    Text(error.message, style = MaterialTheme.typography.bodyMedium)
                    if (error.advanced.isNotBlank()) {
                        Caption(error.advanced, color = PromoPalette.TextFaint)
                    }
                    TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                }
            }

            state.report?.let { report -> ReportBody(report) }
        }
    }
}

// --------------------------------------------------------------------- inputs

@Composable
private fun LogInputs(
    state: AnalysisUiState,
    onAddLogs: () -> Unit,
    onRemoveLog: (ImportedFile) -> Unit,
    onChooseBin: () -> Unit,
    onChooseXdf: () -> Unit,
) {
    Panel(spacing = 8.dp) {
        Kicker("Datalogs", color = PromoPalette.TextFaint)
        if (state.logs.isEmpty()) {
            Caption(
                "Pick the SimosTools CSVs from one session. Several at once is normal — " +
                    "the engine treats them as one set and numbers the pulls across all of them.",
            )
        } else {
            state.logs.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Identifier(file.displayName)
                        Text(
                            "SHA-256 ${file.shortHash}",
                            style = PromoType.figureSmall,
                            color = PromoPalette.TextFaint,
                        )
                    }
                    TextButton(onClick = { onRemoveLog(file) }) { Text("Remove") }
                }
            }
        }
        PromoOutlinedButton(onClick = onAddLogs) {
            Text(if (state.logs.isEmpty()) "Choose datalogs" else "Add more")
        }
    }

    Panel(spacing = 8.dp) {
        Kicker("Flashed bin + XDF (optional)", color = PromoPalette.TextFaint)
        // Said plainly, because getting this wrong produces a confident wrong
        // answer rather than an error: the two calibration-aware checks compare
        // what the log did against what the flashed bin allowed.
        Caption(
            "Adds two checks that compare these logs against the calibration ceilings — " +
                "the bin that was on the car when you recorded them, not the one you are editing.",
        )
        FileLine("Bin", state.bin, onChooseBin)
        FileLine("XDF", state.xdf, onChooseXdf)
        if (state.calibrationIncomplete) {
            Caption(
                "Both are needed to decode a calibration, so those two checks will report as skipped.",
                color = PromoPalette.Warn,
            )
        }
    }
}

@Composable
private fun FileLine(label: String, file: ImportedFile?, onChoose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Caption(label, color = PromoPalette.TextFaint)
            if (file != null) Identifier(file.displayName)
            else Caption("No file chosen", color = PromoPalette.TextFaint)
        }
        PromoOutlinedButton(onClick = onChoose) { Text(if (file == null) "Choose" else "Replace") }
    }
}

// --------------------------------------------------------------------- report

@Composable
private fun ReportBody(report: AnalysisReport) {
    SummaryPanel(report)

    report.notes.forEach { note ->
        Panel(tone = PanelTone.Warn) { Caption(note, color = PromoPalette.Warn) }
    }

    Severity.values().forEach { severity ->
        val findings = report.findingsOf(severity)
        if (findings.isNotEmpty()) {
            Kicker("${severity.label} — ${findings.size}", color = severity.tone.ink)
            findings.forEach { finding -> FindingPanel(finding, severity) }
        }
    }

    if (report.findings.isEmpty()) {
        Panel(tone = PanelTone.Good) {
            PanelTitle("Nothing flagged", tone = PanelTone.Good)
            Caption(
                "No check that ran raised a finding. Read the skipped list below before " +
                    "reading that as a clean bill of health — a check that could not run is not a check that passed.",
            )
        }
    }

    if (report.skipped.isNotEmpty()) {
        Kicker("Skipped — ${report.skipped.size}", color = PromoPalette.TextFaint)
        report.skipped.forEach { skipped -> SkippedPanel(skipped) }
    }

    HowToReadPanel()

    Kicker("Plots", color = PromoPalette.Accent)
    // Alphabetical, always. See the KDoc on this file.
    report.drawnPlots.forEach { plot -> PlotSection(plot) }

    if (report.undrawnPlots.isNotEmpty()) {
        Panel {
            PanelTitle("Not plotted")
            Caption(
                "Nothing in these logs to draw — the channels these plots need were not in " +
                    "your PID list, so this says nothing about the car.",
            )
            report.undrawnPlots.forEach { plot ->
                Caption("• ${plot.title}", color = PromoPalette.TextFaint)
            }
        }
    }
}

@Composable
private fun SummaryPanel(report: AnalysisReport) {
    Panel(spacing = 6.dp) {
        PanelTitle("What was read")
        Caption(
            "${report.logs.size} ${plural(report.logs.size, "log")}, " +
                "${report.pulls.size} ${plural(report.pulls.size, "pull")}, " +
                "${report.ran.size} ${plural(report.ran.size, "check")} run."
        )
        report.pulls.forEach { pull ->
            val gear = pull.gear?.let { "${it}${ordinalSuffix(it)} gear" } ?: "gear unresolved"
            val rpm = "${pull.rpmMin.displayMeasured()}–${pull.rpmMax.displayMeasured()} rpm"
            val seconds = pull.durationSeconds?.let { " · ${it.displayMeasured()} s" } ?: ""
            Text(
                "Pull ${pull.index}  $gear · $rpm$seconds",
                style = PromoType.figureSmall,
                color = PromoPalette.TextDim,
            )
        }
        if (!report.calResolved) {
            Caption(
                "No calibration supplied, so the two ceiling checks were skipped.",
                color = PromoPalette.TextFaint,
            )
        }
    }
}

@Composable
private fun FindingPanel(finding: Finding, severity: Severity) {
    Panel(tone = severity.tone, spacing = 6.dp) {
        PanelTitle(finding.title, tone = severity.tone)
        Text(finding.message, style = MaterialTheme.typography.bodyMedium)
        if (finding.pullRefs.isNotEmpty()) {
            Caption(
                "Pull ${finding.pullRefs.joinToString(", ")}",
                color = PromoPalette.TextFaint,
            )
        }
        finding.evidence.forEach { (key, value) ->
            Text(
                "$key  $value",
                style = PromoType.figureSmall,
                color = PromoPalette.TextFaint,
            )
        }
    }
}

@Composable
private fun SkippedPanel(skipped: SkippedCheck) {
    Panel(spacing = 4.dp) {
        PanelTitle(skipped.title)
        Caption(skipped.reason, color = PromoPalette.TextFaint)
        if (skipped.missingChannels.isNotEmpty()) {
            Text(
                skipped.missingChannels.joinToString(", "),
                style = PromoType.figureSmall,
                color = PromoPalette.TextFaint,
            )
        }
    }
}

// ---------------------------------------------------------------------- plots

/**
 * The standing guide to the encoding, above the plots rather than repeated on
 * each one.
 *
 * Every plot below shares one set of conventions, so they are explained once. A
 * legend redrawn seven times would take more of the screen than several of the
 * plots do, and someone would still have to learn it once.
 */
@Composable
private fun HowToReadPanel() {
    Panel(spacing = 8.dp) {
        PanelTitle("How to read these")
        LegendKey(
            "Solid, coloured",
            "What was measured. One colour per pull, the same colour across every plot.",
            SeriesRole.PRIMARY,
        )
        LegendKey(
            "Dashed, grey",
            "What the ECU asked for — a setpoint, a base table, a target. Compare it against the solid line: the gap is the story.",
            SeriesRole.REFERENCE,
        )
        LegendKey(
            "Dash-dot, grey",
            "A second measured quantity sharing the panel, so two related signals can be read together.",
            SeriesRole.SECONDARY,
        )
        LegendKey(
            "Faint dots",
            "Loaded samples that were not settled — shift recovery and torque cuts. Context only; the lines exclude them.",
            SeriesRole.TRANSIENT,
        )
        HairRule()
        Caption(
            "Horizontal dashed lines are this tool's watch and high thresholds, not limits the " +
                "ECU enforces. They are where the analysis starts paying attention.",
        )
        Caption(
            "Only loaded wide-open-throttle samples are drawn, and a line is broken rather than " +
                "bridged wherever those samples stop — a gap is a lift, not a measurement.",
        )
        Caption(
            "Everything is plotted against engine speed, so the same rpm is the same place on " +
                "every plot. Reading two plots at one rpm is the fastest way to tell a cause from a symptom.",
        )
    }
}

@Composable
private fun LegendKey(title: String, body: String, role: SeriesRole) {
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.width(44.dp).padding(top = 8.dp, end = 8.dp)) {
            LineSwatch(role = role, color = pullColor(0))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Caption(body)
        }
    }
}

/** A short piece of the line a role is drawn with — the legend's own ink sample. */
@Composable
private fun LineSwatch(role: SeriesRole, color: Color) {
    Canvas(modifier = Modifier.width(36.dp).height(10.dp)) {
        val y = size.height / 2f
        when (role) {
            SeriesRole.TRANSIENT -> {
                var x = 2f
                while (x < size.width) {
                    drawCircle(PromoPalette.TextFaint.copy(alpha = 0.55f), 2.5f, Offset(x, y))
                    x += 9f
                }
            }
            else -> drawLine(
                color = when (role) {
                    SeriesRole.PRIMARY -> color
                    SeriesRole.REFERENCE -> PromoPalette.TextDim
                    else -> PromoPalette.TextFaint
                },
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 3f,
                pathEffect = when (role) {
                    SeriesRole.PRIMARY -> null
                    SeriesRole.REFERENCE -> PathEffect.dashPathEffect(floatArrayOf(9f, 6f))
                    else -> PathEffect.dashPathEffect(floatArrayOf(8f, 4f, 2f, 4f))
                },
            )
        }
    }
}

/**
 * One evidence plot: what it shows, the plot itself, and how to read it.
 *
 * The description sits *above* the canvas and the tip *below* it, on purpose.
 * The description answers "what am I looking at", which has to be known before
 * the picture means anything; the tip answers "what should I notice", which only
 * lands once the picture has been seen.
 */
@Composable
private fun PlotSection(plot: AnalysisPlot) {
    Panel(spacing = 10.dp) {
        PanelTitle(plot.title)
        Caption(plot.description)

        if (plot.pulls.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                plot.pulls.forEach { (pull, ordinal) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(end = 4.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxWidth()) {
                                drawCircle(pullColor(ordinal), size.width / 2f)
                            }
                        }
                        Text(
                            " Pull $pull",
                            style = PromoType.figureSmall,
                            color = pullColor(ordinal),
                        )
                    }
                }
            }
        }

        plot.drawablePanels.forEach { panel -> PanelBlock(panel) }

        HairRule()
        Row(verticalAlignment = Alignment.Top) {
            Kicker("Tip", color = PromoPalette.Accent, modifier = Modifier.padding(top = 3.dp, end = 10.dp))
            Caption(plot.tip)
        }
    }
}

@Composable
private fun PanelBlock(panel: PlotPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(panel.title, style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(panel.yLabel, style = PromoType.figureSmall, color = PromoPalette.TextFaint)
            Text(panel.xLabel, style = PromoType.figureSmall, color = PromoPalette.TextFaint)
        }
        // A fixed height rather than an aspect ratio: these are read stacked in a
        // scrolling column, and a panel that grew with screen width would push
        // the next one off a tablet screen entirely.
        AnalysisCanvas(panel = panel, modifier = Modifier.height(200.dp))
        val labelled = panel.series.filter { it.label.isNotEmpty() }
        if (labelled.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                labelled.map { it.label to it.role }.distinct().forEach { (label, role) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineSwatch(role = role, color = pullColor(0))
                        Text(
                            "  $label",
                            style = PromoType.figureSmall,
                            color = PromoPalette.TextDim,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- pieces

/** A severity's panel tone — the palette's own meanings, reused rather than re-picked. */
private val Severity.tone: PanelTone
    get() = when (this) {
        Severity.HIGH -> PanelTone.Danger
        Severity.MEDIUM -> PanelTone.Warn
        Severity.LOW -> PanelTone.Neutral
    }

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

private fun ordinalSuffix(value: Int): String = when {
    value % 100 in 11..13 -> "th"
    value % 10 == 1 -> "st"
    value % 10 == 2 -> "nd"
    value % 10 == 3 -> "rd"
    else -> "th"
}
