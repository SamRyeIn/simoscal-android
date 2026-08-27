package com.simoscal.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoscal.engine.R
import com.simoscal.android.BuildState
import com.simoscal.android.AdviceUiState
import com.simoscal.android.AdviceWork
import com.simoscal.android.GateResult
import com.simoscal.android.EditorViewModel
import com.simoscal.android.ImportedFile
import com.simoscal.android.InputKind
import com.simoscal.android.ShareBin
import com.simoscal.android.ShareBundle

/**
 * The build/verify/share screen — the only place in the app an export
 * affordance can appear, and only in the [BuildState.Verified] branch below.
 *
 * The disclaimer (`R.string.build_disclaimer`) is shown regardless of which
 * build-state branch is active, because it is not a caveat about *this* build
 * — it is the standing boundary of what any of these gates can ever prove:
 * file integrity, not mechanical safety.
 */
@Composable
fun BuildScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val logPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.onAdviceLogsPicked(uris) }
    val replyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::onAdviceReplyPicked) }

    var revision by rememberSaveable { mutableStateOf("R00") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(kicker = "Verify, then you flash it", title = "Build")

        OutlinedTextField(
            value = revision,
            onValueChange = { revision = it },
            label = { Text("Revision") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        PromoButton(
            onClick = { viewModel.build(revision) },
            enabled = state.canBuild && revision.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Build")
        }

        when (val build = state.build) {
            is BuildState.NotBuilt -> {
                Caption("Nothing built yet.")
            }
            is BuildState.Running -> {
                Row {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Building...")
                }
            }
            is BuildState.Failed -> {
                FailedCard(build)
            }
            is BuildState.Verified -> {
                VerifiedCard(build)
                // A share that cannot be set up must report itself, not crash the
                // app: FileProvider throws if the staged bin ever falls outside
                // the declared paths, and losing the whole screen would take the
                // build report down with it.
                var shareError by remember { mutableStateOf<String?>(null) }
                PromoButton(
                    onClick = {
                        shareError = runCatching {
                            context.startActivity(ShareBin.intentFor(context, build))
                        }.exceptionOrNull()?.let { "This bin could not be shared: ${it.message}" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export / Share")
                }
                shareError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HairRule()
        AdviceTransport(
            state = state.advice,
            enabled = state.sessionOpen && !state.busy,
            onNotesChanged = viewModel::onAdviceNotesChanged,
            onAddLogs = { logPicker.launch(InputKind.LOG.mimeTypes) },
            onRemoveLog = viewModel::removeAdviceLog,
            onExport = viewModel::exportAdviceBundle,
            onShare = { bundle -> context.startActivity(ShareBundle.intentFor(context, bundle)) },
            onImportReply = { replyPicker.launch(InputKind.RECOMMENDATIONS.mimeTypes) },
            onDismissError = viewModel::dismissAdviceError,
        )

        HairRule()
        Text(
            stringResource(R.string.build_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AdviceTransport(
    state: AdviceUiState,
    enabled: Boolean,
    onNotesChanged: (String) -> Unit,
    onAddLogs: () -> Unit,
    onRemoveLog: (ImportedFile) -> Unit,
    onExport: () -> Unit,
    onShare: (com.simoscal.android.ExportedAdviceBundle) -> Unit,
    onImportReply: () -> Unit,
    onDismissError: () -> Unit,
) {
    ScreenHeader(kicker = "Files out, recommendations in", title = "Tune with Claude")
    Caption(
        "Export this open session, ask Claude outside the app, then bring one " +
            "recommendations file back. The app stays offline and every item is " +
            "replayed through the engine's real guards before review."
    )

    OutlinedTextField(
        value = state.notes,
        onValueChange = onNotesChanged,
        label = { Text("What do you want help with?") },
        enabled = enabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    Panel(spacing = 8.dp) {
        Kicker("Datalogs (optional)", color = PromoPalette.TextFaint)
        if (state.logs.isEmpty()) {
            Caption("No logs selected. The bundle will still include every table and the edit journal.")
        } else {
            state.logs.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Identifier(file.displayName)
                        Caption("SHA-256 ${file.shortHash}", color = PromoPalette.TextFaint)
                    }
                    TextButton(onClick = { onRemoveLog(file) }, enabled = enabled) { Text("Remove") }
                }
            }
        }
        PromoOutlinedButton(onClick = onAddLogs, enabled = enabled) {
            Text(if (state.logs.isEmpty()) "Choose datalogs" else "Add more")
        }
    }

    PromoButton(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.bundle == null) "Export context bundle" else "Export again")
    }

    when (state.work) {
        AdviceWork.IMPORTING_LOGS -> AdviceProgress("Copying and hashing datalogs...")
        AdviceWork.EXPORTING -> AdviceProgress("Building the context bundle...")
        AdviceWork.IMPORTING_REPLY -> AdviceProgress("Copying and hashing recommendations...")
        AdviceWork.REVIEWING -> AdviceProgress("Replaying recommendations through the guards...")
        AdviceWork.IDLE -> Unit
    }

    state.bundle?.let { bundle ->
        Panel(tone = PanelTone.Good, spacing = 8.dp) {
            PanelTitle("Bundle ready", tone = PanelTone.Good)
            Identifier("${bundle.summary.profile} · v${bundle.summary.bundleVersion}")
            Caption(
                "${bundle.summary.tables} tables · ${bundle.summary.journalEntries} journal entries · " +
                    "${bundle.summary.logs.size} logs · ${bundle.summary.pulls} pulls · " +
                    "${bundle.summary.findings} findings"
            )
            Caption("${bundle.bytes} bytes · SHA-256 ${bundle.shortHash}", color = PromoPalette.TextFaint)
            var shareError by remember { mutableStateOf<String?>(null) }
            PromoButton(
                onClick = {
                    shareError = runCatching { onShare(bundle) }.exceptionOrNull()?.let {
                        "This bundle could not be shared: ${it.message}"
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share context bundle") }
            shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    PromoOutlinedButton(
        onClick = onImportReply,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.reply == null) "Import recommendations" else "Replace recommendations")
    }

    state.reply?.let { reply ->
        Identifier(reply.displayName)
        Caption("SHA-256 ${reply.shortHash}", color = PromoPalette.TextFaint)
    }

    state.review?.let { review ->
        Panel(tone = if (review.counts.queued > 0) PanelTone.Accent else PanelTone.Neutral, spacing = 8.dp) {
            PanelTitle("Review complete", tone = if (review.counts.queued > 0) PanelTone.Accent else PanelTone.Neutral)
            Text(
                "${review.counts.queued} queued · ${review.counts.dropped} refused · " +
                    "${review.counts.malformed} malformed"
            )
            if (review.summary.isNotBlank()) Caption(review.summary)
            if (review.counts.queued > 0) {
                Caption("The accepted items are ready for the one-at-a-time review queue.")
            }
        }
    }

    state.notice?.let { notice ->
        Panel(tone = PanelTone.Warn) { Caption(notice, color = PromoPalette.Warn) }
    }

    state.error?.let { error ->
        Panel(tone = PanelTone.Danger) {
            PanelTitle("That did not work", tone = PanelTone.Danger)
            Text(error.message)
            if (error.advanced.isNotBlank()) Caption(error.advanced, color = PromoPalette.TextFaint)
            TextButton(onClick = onDismissError) { Text("Dismiss") }
        }
    }
}

@Composable
private fun AdviceProgress(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
        Text(message)
    }
}

@Composable
private fun FailedCard(build: BuildState.Failed) {
    Panel(tone = PanelTone.Danger) {
        PanelTitle("Build failed", tone = PanelTone.Danger)
        Text(build.summary)
        build.reasons.forEach { reason ->
            Text("• $reason")
        }
    }
}

@Composable
private fun VerifiedCard(build: BuildState.Verified) {
    Panel(tone = PanelTone.Good, spacing = 8.dp) {
        PanelTitle("Verified", tone = PanelTone.Good)
        Text("Revision ${build.revision}")
        // The built file's name, monospace: it is the string that gets picked out
        // of a share sheet later, and one wrong revision digit is the whole point
        // of naming it at all.
        Identifier(build.binName)

        HairRule(color = PromoPalette.Good.copy(alpha = 0.3f))
        Kicker("Changed tables", color = PromoPalette.TextFaint)
        if (build.changedTables.isEmpty()) {
            Caption("No tables were changed.")
        } else {
            build.changedTables.forEach { label ->
                Text("• $label", style = MaterialTheme.typography.bodyMedium)
            }
        }

        HairRule(color = PromoPalette.Good.copy(alpha = 0.3f))
        Kicker("Gates", color = PromoPalette.TextFaint)
        build.gates.forEach { gate -> GateRow(gate) }
    }
}

/**
 * Three distinct visual states, not two: a gate that never ran must not read
 * as a pass. `ran == false` gets its own label and its own color regardless
 * of [GateResult.passed] — a byte audit with no reference bin, for instance,
 * defaults to `passed = false` upstream, but even if it did not, "did not
 * run" has to stay visually distinct from both pass and fail so nobody mistakes
 * an unexercised gate for evidence of anything.
 */
@Composable
private fun GateRow(gate: GateResult) {
    // Straight off the palette's own vocabulary: `good` is a check that passed,
    // `danger` is a refusal, and a gate that never ran is faint — it is not a
    // verdict at all and must not be coloured like one.
    val (label, color) = when {
        !gate.ran -> "DID NOT RUN" to PromoPalette.TextFaint
        gate.passed -> "PASSED" to PromoPalette.Good
        else -> "FAILED" to PromoPalette.Danger
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kicker(label, color = color)
            Text(gate.name, style = MaterialTheme.typography.bodyMedium)
        }
        // The detail line is what the verdict is *made of* — which bytes, which
        // checksum, how many tables read back. A verdict without it is a claim
        // rather than a report, so it is always printed when the gate sent one.
        if (gate.detail.isNotBlank()) {
            Caption(gate.detail)
        }
    }
}
