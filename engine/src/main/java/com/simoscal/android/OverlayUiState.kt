package com.simoscal.android

/**
 * The overlay's screen state — held *beside* the edit session, never inside it.
 *
 * That separation is the whole safety argument for this feature, so it is worth
 * stating plainly: loading a log adds no journal entry, changes no table, alters
 * no gate, and touches nothing the recovery record carries. A session recovered
 * after a process kill simply has no overlay loaded, which is the correct
 * outcome — the overlay is something a person is *looking at*, not part of what
 * the bin now contains.
 *
 * Nothing here can make an edit possible that was not already possible, or
 * impossible that was. The type has no path to the draft, the journal, or the
 * build; that is enforced by it not knowing about them rather than by a rule
 * somebody has to remember.
 */
data class OverlayUiState(
    val model: LogOverlayModel? = null,
    /** Which pull is drawn, by its engine-assigned 1-based index. */
    val selectedPull: Int? = null,
    val loading: Boolean = false,
    /** The name of the log as the picker showed it, for the chooser's header. */
    val logName: String? = null,
    /** Why nothing can be drawn, when a log loaded but carries no usable trace. */
    val unavailable: String? = null,
    val notice: String? = null,
) {

    /** True while a log is loaded and a pull is chosen — i.e. something is drawn. */
    val active: Boolean
        get() = visiblePull != null

    /** The pull currently drawn, or null when none is chosen or it has no data. */
    val visiblePull: OverlayPull?
        get() = selectedPull?.let { model?.pull(it) }?.takeIf { it.drawn }

    /** The pulls worth offering in the chooser: the ones that would actually draw. */
    val choosablePulls: List<OverlayPull>
        get() = model?.drawablePulls.orEmpty()

    /** Whether the chooser is worth showing at all. */
    val hasChoices: Boolean
        get() = choosablePulls.isNotEmpty()
}

/** A log pick is under way. Any previously drawn pull stays until the new one lands. */
fun OverlayUiState.loading(): OverlayUiState = copy(loading = true, notice = null)

/**
 * Adopt a freshly read overlay, auto-selecting a pull when there is only one.
 *
 * Auto-selecting the single pull is a convenience, not a decision about the data:
 * with one candidate the chooser has nothing to choose, and making someone tap it
 * anyway is friction for its own sake. With several, none is picked — which pull
 * to read against a curve is exactly the kind of judgement this feature exists to
 * hand to a person, and guessing "the last one" would sometimes silently draw a
 * warm-up run against a calibration decision.
 */
fun OverlayUiState.withModel(loaded: LogOverlayModel, logName: String?): OverlayUiState {
    val drawable = loaded.drawablePulls
    val unavailable = when {
        !loaded.available -> {
            val missing = loaded.missingChannels
            if (missing.isEmpty()) {
                "That log does not carry the channels a boost trace needs."
            } else {
                "That log has no ${missing.joinToString(", ")} channel" +
                    (if (missing.size > 1) "s" else "") +
                    " — without ambient pressure there is no baseline to measure gauge boost against."
            }
        }
        drawable.isEmpty() -> "No wide-open-throttle pulls were detected in that log."
        else -> null
    }
    return copy(
        model = loaded,
        logName = logName,
        loading = false,
        unavailable = unavailable,
        notice = null,
        selectedPull = drawable.singleOrNull()?.index,
    )
}

/**
 * Draw a different pull.
 *
 * Unlike a slot switch, this is never refused for a dirty draft: choosing which
 * logged run to look at changes nothing about the edit in progress, and blocking
 * it would be a gate with no safety behind it.
 */
fun OverlayUiState.selectingPull(index: Int): OverlayUiState =
    if (model?.pull(index)?.drawn == true) copy(selectedPull = index, notice = null) else this

/** Put the canvas back to curves only. One tap, and it loses nothing recoverable. */
fun OverlayUiState.cleared(): OverlayUiState = OverlayUiState()

/** A log could not be read at all — distinct from one that read but cannot draw. */
fun OverlayUiState.failed(reason: String): OverlayUiState =
    copy(loading = false, notice = reason)
