package com.simoscal.android

/**
 * The editor screen model — pure data plus pure derived rules.
 *
 * Deliberately free of Android and of the bridge: every safety-relevant rule
 * the UI obeys ("no session before preflight passes", "no export before a
 * verified build") is a property of this file and is tested on the JVM without
 * a device.
 */

/** Workspace destinations, available only once a session is open. */
enum class Destination { TABLES, BOOST, LIMITERS, PEDAL, LAMBDA, SLOTS, CHANGES, BUILD }

sealed interface PreflightState {
    /** Inputs are not both chosen yet, or nothing has been checked. */
    data object NotRun : PreflightState

    data object Running : PreflightState

    /**
     * The bin is not safely editable. This is a dead end by design: the only
     * ways out are choosing a different bin or cancelling. There is deliberately
     * no "continue anyway" — a wrong byte in the wrong bin can brick an ECU.
     */
    data class Blocked(val summary: String, val reasons: List<String>) : PreflightState

    data class Passed(
        val summary: String,
        val reasons: List<String>,
        /** null when unknown — e.g. no switch-patch XDF was supplied. */
        val switchPatchPresent: Boolean?,
    ) : PreflightState
}

sealed interface BuildState {
    data object NotBuilt : BuildState

    data object Running : BuildState

    /** A build that ran its gates and failed one. Not an error dialog — a report. */
    data class Failed(val summary: String, val reasons: List<String>) : BuildState

    /**
     * Every gate passed. [sharePath] is the app-private candidate bin; it is the
     * only thing the app ever hands to another app.
     */
    data class Verified(
        val revision: String,
        val sharePath: String,
        val binName: String,
        val changedTables: List<String>,
        val gates: List<GateResult>,
    ) : BuildState
}

/**
 * One verification gate's verdict.
 *
 * [ran] is not decoration: a gate that never applied — a byte audit with no
 * reference bin, coherence rules with no recipe — is *not* a pass, and the UI
 * must not let it read as one.
 */
data class GateResult(
    val name: String,
    val passed: Boolean,
    val ran: Boolean,
    val detail: String,
)

/** An engine or import failure, phrased for a person. */
data class UserFacingError(val code: String, val message: String, val advanced: String)

/**
 * Which open editor holds an unapplied proposal.
 *
 * Named rather than a bare boolean because the refusal has to say *what* would
 * be lost, and the notice has to land in that editor rather than in a snackbar
 * over the other one.
 */
enum class DirtyDraft { BOOST, TABLE, LIMITERS, PEDAL, LAMBDA }

data class EditorUiState(
    /**
     * Whether the table grid shades cells by value.
     *
     * On by default, because the shape of a map is the thing hardest to read out
     * of bare numerals. Kept on the screen model rather than local to the grid so
     * it survives closing one table and opening the next — a person who turned
     * the colours off did so because they dislike them, not because of the table
     * they happened to be looking at.
     */
    val heatmap: Boolean = true,
    val bin: ImportedFile? = null,
    val xdf: ImportedFile? = null,
    val switchPatchXdf: ImportedFile? = null,
    val preflight: PreflightState = PreflightState.NotRun,
    val sessionId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val build: BuildState = BuildState.NotBuilt,
    val boost: BoostUiState = BoostUiState(),
    /**
     * The logged pull drawn behind the boost curves, when one is loaded.
     *
     * Read-only decoration, and kept out of everything that decides what the bin
     * contains: no gate below reads it, [forgettingPreviousInputs] clears it with
     * the rest of the per-bin state, and the recovery record never carries it.
     */
    val overlay: OverlayUiState = OverlayUiState(),
    val limiters: LimitersUiState = LimitersUiState(),
    val pedal: PedalUiState = PedalUiState(),
    val lambda: LambdaUiState = LambdaUiState(),
    val tables: TablesUiState = TablesUiState(),
    val slots: SlotsUiState = SlotsUiState(),
    val changes: ChangesUiState = ChangesUiState(),
    val busy: Boolean = false,
    val error: UserFacingError? = null,
) {
    /**
     * Whether the session holds committed edits.
     *
     * Derived from [canUndo] rather than counted locally: the engine's journal is
     * the only thing that knows, and an app-side counter would drift the first
     * time an undo or a rejected edit did not match what it assumed.
     */
    val hasEdits: Boolean
        get() = canUndo

    /** Both required inputs are present, so the bin can be checked. */
    val canRunPreflight: Boolean
        get() = bin != null && xdf != null && !busy

    /** A session may only ever be opened over a bin that *passed*. */
    val canOpenSession: Boolean
        get() = preflight is PreflightState.Passed && sessionId == null && !busy

    /** Non-null while a blocking, non-dismissible failure is on screen. */
    val blocker: PreflightState.Blocked?
        get() = preflight as? PreflightState.Blocked

    val sessionOpen: Boolean
        get() = sessionId != null

    /** The workspace is reachable only with a live session. */
    fun destinationEnabled(destination: Destination): Boolean = when (destination) {
        // Changes sits with Tables and Build rather than behind the switch patch:
        // it reports the journal, and a base-only session journals just as much
        // as a patched one. Gating it on the patch would hide a person's own
        // edits from them for want of a file the edits did not need.
        // Limiters sits with these rather than behind the switch patch: the
        // road-speed quartet is base-calibration, so the screen has real work to
        // do on an unpatched bin. It hides the cylinder-cut trio in that case
        // rather than refusing to open — a degraded screen, not an error.
        // Pedal joins these for the same reason: the driver-interpretation maps
        // are base calibration and need no patch.
        Destination.TABLES, Destination.CHANGES, Destination.BUILD,
        Destination.LIMITERS, Destination.PEDAL, Destination.LAMBDA -> sessionOpen
        // Boost and Slots both live in the switch-patch space, which only exists
        // if its XDF was imported. Same gate, same reason.
        Destination.BOOST, Destination.SLOTS -> sessionOpen && switchPatchXdf != null
    }

    val canBuild: Boolean
        get() = sessionOpen && !busy

    /**
     * The open editor holding an unapplied proposal, if any.
     *
     * Boost is reported first only because both cannot be on screen at once; a
     * dirty draft in either blocks the same set of actions.
     */
    val dirtyDraft: DirtyDraft?
        get() = when {
            boost.dirty -> DirtyDraft.BOOST
            tables.dirty -> DirtyDraft.TABLE
            limiters.dirty -> DirtyDraft.LIMITERS
            pedal.dirty -> DirtyDraft.PEDAL
            lambda.dirty -> DirtyDraft.LAMBDA
            else -> null
        }

    /**
     * Whether an engine-mutating action other than Apply may run.
     *
     * Undo, Redo, Restore, and the shared-rpm-axis Apply all re-read the open
     * editor from the engine when they succeed, which replaces a staged draft
     * with freshly committed values — silently discarding a proposal the person
     * built and was still reviewing. The slot switch has always refused while
     * dirty; this extends the same rule to every other action that moves the
     * session out from under an open editor (CR-20260813-04).
     *
     * Apply is deliberately *not* gated by this: applying is how a dirty draft
     * stops being dirty.
     */
    val canMutateSession: Boolean
        get() = sessionOpen && !busy && dirtyDraft == null

    /** Why [canMutateSession] is false, phrased for the editor that is blocking. */
    val dirtyDraftRefusal: String?
        get() = when (dirtyDraft) {
            DirtyDraft.BOOST ->
                "Apply or discard the change to slot ${boost.activeSlot} first — " +
                    "this would replace it with the values the engine holds."
            DirtyDraft.TABLE ->
                "Apply or discard the change to this table first — this would " +
                    "replace it with the values the engine holds."
            DirtyDraft.LIMITERS ->
                "Apply or discard the limiter change first — this would replace " +
                    "it with the values the engine holds."
            DirtyDraft.PEDAL ->
                "Apply or discard the pedal-curve change first — this would " +
                    "replace it with the values the engine holds."
            DirtyDraft.LAMBDA ->
                "Apply or discard the enrichment change first — this would " +
                    "replace it with the values the engine holds."
            null -> null
        }

    /**
     * Export/Share exists **only** in the verified state.
     *
     * Not "is greyed out" — absent. A bin that failed a gate, or that was edited
     * after it was verified, has no share affordance at all, because the only
     * thing downstream of this button is a flash to a real ECU.
     */
    val exportVisible: Boolean
        get() = build is BuildState.Verified

    val verifiedSharePath: String?
        get() = (build as? BuildState.Verified)?.sharePath
}

/**
 * Any change to the calibration invalidates a completed build.
 *
 * This is the rule that keeps [EditorUiState.exportVisible] honest: without
 * it, editing a table after a successful build would leave the Share button on
 * screen still pointing at the *previous* candidate bin. Call this from every
 * path that mutates the session — edit, undo, redo.
 */
fun EditorUiState.invalidatingBuild(): EditorUiState =
    if (build is BuildState.NotBuilt) this else copy(build = BuildState.NotBuilt)

/**
 * Retract a blocked verdict, back to un-checked.
 *
 * The blocker dialog cannot be dismissed, so something must be able to take the
 * verdict back or the person is trapped behind it with the file pickers
 * unreachable. This grants nothing: [EditorUiState.canOpenSession] requires a
 * *passed* preflight, so retracting only returns to "no bin has been checked".
 * It must never be reachable from a state other than [PreflightState.Blocked].
 */
fun EditorUiState.retractingBlocker(): EditorUiState =
    if (preflight is PreflightState.Blocked) copy(preflight = PreflightState.NotRun) else this

/**
 * Drop everything that describes the *previous* inputs.
 *
 * Written once and shared by all three input setters rather than repeated in
 * each: the failure mode here is forgetting one field, and a stale boost model
 * or table grid left over from another bin would show a person values that are
 * not in the file they now have open. The verdict, the session, the build, and
 * every decoded value all belong to the bytes they were read from.
 */
private fun EditorUiState.forgettingPreviousInputs(): EditorUiState = copy(
    preflight = PreflightState.NotRun,
    sessionId = null,
    canUndo = false,
    canRedo = false,
    build = BuildState.NotBuilt,
    boost = BoostUiState(),
    // The overlay is a log, not a bin — but it is drawn *against* this bin's
    // curves, and a trace left hanging behind a different calibration would
    // invite exactly the comparison it is no longer entitled to.
    overlay = OverlayUiState(),
    limiters = LimitersUiState(),
    pedal = PedalUiState(),
    lambda = LambdaUiState(),
    tables = TablesUiState(),
    changes = ChangesUiState(),
    error = null,
)

/**
 * Choosing a different bin resets everything downstream of it.
 *
 * The session, the preflight verdict, and any build all describe the *old* bin;
 * carrying any of them across would let a verdict vouch for bytes it never saw.
 */
fun EditorUiState.withBin(imported: ImportedFile): EditorUiState =
    copy(bin = imported).forgettingPreviousInputs()

/** Same reasoning as [withBin]: a different definition re-reads every table. */
fun EditorUiState.withXdf(imported: ImportedFile): EditorUiState =
    copy(xdf = imported).forgettingPreviousInputs()

fun EditorUiState.withSwitchPatchXdf(imported: ImportedFile?): EditorUiState =
    copy(switchPatchXdf = imported).forgettingPreviousInputs()
