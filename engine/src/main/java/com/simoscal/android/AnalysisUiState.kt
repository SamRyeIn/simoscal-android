package com.simoscal.android

/**
 * The Analyze screen's state, as pure data. Every gate rule lives here.
 *
 * Modelled on [EditorState]'s arrangement — transitions are functions on an
 * immutable state, so they can be tested on the JVM without a device or an
 * engine — but the rules themselves are much lighter, and deliberately so. The
 * editor's gates exist because a wrong byte can brick an ECU; nothing on this
 * screen writes anything. The one rule worth enforcing is [withoutStaleReport]:
 * a report must never outlive the set of logs it describes.
 */
data class AnalysisUiState(
    /** The picked datalogs, in pick order. Content-addressed, so never duplicated. */
    val logs: List<ImportedFile> = emptyList(),

    /**
     * The bin that was *flashed when these logs were recorded*, if the person
     * supplied it, and the XDF that decodes it.
     *
     * Optional, and never taken from an open edit session even when there is
     * one. The bin someone is editing is the *next* calibration; the logs came
     * off the previous one, and quietly checking a log against the wrong bin
     * would produce a confident, wrong answer about the boost ceiling — the
     * exact failure mode the calibration-aware checks exist to catch.
     */
    val bin: ImportedFile? = null,
    val xdf: ImportedFile? = null,

    val busy: Boolean = false,
    val report: AnalysisReport? = null,
    val error: UserFacingError? = null,
) {

    val canRun: Boolean get() = logs.isNotEmpty() && !busy

    /** Both halves or neither: a bin without its XDF cannot be decoded. */
    val calibrationReady: Boolean get() = bin != null && xdf != null

    /**
     * True when a bin is half-supplied — the state worth saying something about.
     *
     * Not an error: the analysis runs fine without a calibration, and the two
     * calibration-aware checks simply report as skipped. But someone who picked
     * a bin and no XDF is one tap from what they wanted, and silently ignoring
     * their pick would leave them reading a skipped check with no idea why.
     */
    val calibrationIncomplete: Boolean get() = (bin == null) != (xdf == null)

    fun busy(value: Boolean): AnalysisUiState = copy(busy = value, error = if (value) null else error)

    /**
     * Add a picked log, ignoring a file already in the list.
     *
     * Identity is the SHA-256 of the bytes, not the display name: two exports of
     * the same drive can carry different names, and analysing one capture twice
     * would double-count every pull in it. (The engine dedups overlapping
     * captures as well — this is the cheaper, exact half of the same rule.)
     */
    fun withLog(file: ImportedFile): AnalysisUiState =
        if (logs.any { it.sha256 == file.sha256 }) this
        else withoutStaleReport(logs = logs + file)

    fun withoutLog(file: ImportedFile): AnalysisUiState =
        withoutStaleReport(logs = logs.filterNot { it.sha256 == file.sha256 })

    fun withBin(file: ImportedFile): AnalysisUiState = withoutStaleReport(bin = file)

    fun withXdf(file: ImportedFile): AnalysisUiState = withoutStaleReport(xdf = file)

    fun cleared(): AnalysisUiState = AnalysisUiState()

    fun withReport(value: AnalysisReport): AnalysisUiState =
        copy(report = value, busy = false, error = null)

    fun withError(value: UserFacingError): AnalysisUiState =
        copy(error = value, busy = false)

    fun errorDismissed(): AnalysisUiState = copy(error = null)

    /**
     * Apply an input change and drop any report that predates it.
     *
     * The findings, the pull list and every plot on screen are statements about
     * one exact set of files. Leaving them up while the inputs beneath them
     * changed would let someone read a knock finding from three logs while
     * looking at a list of four — so the results go, and the Run button comes
     * back. It is the same reasoning as the editor invalidating a completed
     * build on any edit, for a much cheaper artefact.
     */
    private fun withoutStaleReport(
        logs: List<ImportedFile> = this.logs,
        bin: ImportedFile? = this.bin,
        xdf: ImportedFile? = this.xdf,
    ): AnalysisUiState = copy(logs = logs, bin = bin, xdf = xdf, report = null, error = null)
}
