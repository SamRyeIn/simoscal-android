package com.simoscal.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives the Analyze flow: pick datalogs → run the battery → read the findings.
 *
 * A separate view model from [EditorViewModel], not a corner of it, because the
 * two share no state and gate on nothing in common. Analysis is **read-only and
 * sessionless**: it opens no session, holds no bin buffer, has nothing to undo,
 * and needs no recovery record, so folding it into the editor's lifecycle would
 * attach a session's worth of machinery to a screen that writes nothing.
 *
 * As everywhere else in this app, all judgment is the engine's. This class
 * sequences one bridge call and maps its result into [AnalysisUiState]; it does
 * not decide what a pull is, what a finding means, or which channel belongs on
 * which plot.
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val bridge = BridgeClient(application)
    private val imports = ImportStore(application)

    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    fun dismissError() = _state.update { it.errorDismissed() }

    fun clearAll() = _state.update { it.cleared() }

    fun removeLog(file: ImportedFile) = _state.update { it.withoutLog(file) }

    /**
     * Copy a picked file in and attach it to the flow.
     *
     * The copy is what hashes the bytes, so nothing downstream refers to the
     * picker's URI — a `content://` handle can point at a file in Drive that
     * changes or vanishes between the pick and the call. [ImportStore.importFile]
     * moves itself to an IO dispatcher, so a slow provider cannot freeze
     * composition while the busy indicator is up.
     */
    fun onFilePicked(uri: Uri, kind: InputKind) {
        viewModelScope.launch {
            _state.update { it.busy(true) }
            val imported = runCatching { imports.importFile(uri, kind) }
            _state.update { current ->
                imported.fold(
                    onSuccess = { file ->
                        when (kind) {
                            InputKind.LOG -> current.withLog(file)
                            InputKind.BIN -> current.withBin(file)
                            else -> current.withXdf(file)
                        }.busy(false)
                    },
                    onFailure = { error ->
                        current.busy(false).withError(
                            UserFacingError(
                                code = "IMPORT_FAILED",
                                message = (error as? ImportFailure)?.reason
                                    ?: "That file could not be imported.",
                                advanced = error.toString(),
                            )
                        )
                    },
                )
            }
        }
    }

    /** Pick several datalogs at once — the usual case, since a session is many CSVs. */
    fun onLogsPicked(uris: List<Uri>) {
        uris.forEach { uri -> onFilePicked(uri, InputKind.LOG) }
    }

    /**
     * Run the battery over the picked logs.
     *
     * One bridge call. The bin and XDF ride along only when *both* are present:
     * the engine needs the pair to open a calibration at all, and sending half of
     * one would be asking it to guess.
     */
    fun run() {
        val current = _state.value
        if (!current.canRun) return

        viewModelScope.launch {
            _state.update { it.busy(true) }

            val params = params {
                put("logs", JSONArray().apply {
                    current.logs.forEach { file ->
                        put(
                            JSONObject()
                                .putVerified("log", file)
                                // The app's copy is content-addressed, so its
                                // filename on disk is a hash. The name a person
                                // will recognise on a pull summary is the one the
                                // picker showed, and it has to travel separately.
                                .put("display_name", file.displayName)
                        )
                    }
                })
                if (current.calibrationReady) {
                    putVerified("bin", current.bin!!)
                    putVerified("xdf", current.xdf!!)
                }
            }

            when (val outcome = bridge.call("analyze_logs", params)) {
                is BridgeOutcome.Ok -> {
                    val report = runCatching { AnalysisReport.parse(outcome.result) }
                    _state.update { state ->
                        report.fold(
                            onSuccess = { state.withReport(it) },
                            // A result the app cannot read is the app's fault, not
                            // a rejection — reported as such so the two do not
                            // look alike in a bug report.
                            onFailure = { error ->
                                state.withError(
                                    UserFacingError(
                                        code = AppErrorCode.MALFORMED_RESPONSE,
                                        message = "The analysis came back in a form the app could not read.",
                                        advanced = error.toString(),
                                    )
                                )
                            },
                        )
                    }
                }

                is BridgeOutcome.Failed -> _state.update {
                    it.withError(
                        UserFacingError(outcome.code, outcome.message, outcome.advanced)
                    )
                }
            }
        }
    }
}
