package com.simoscal.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.simoscal.engine.R
import com.simoscal.android.AnalysisViewModel
import com.simoscal.android.Destination
import com.simoscal.android.InputKind
import com.simoscal.android.PreflightState
import com.simoscal.android.EditorViewModel

/**
 * The app's whole navigation shell.
 *
 * Everything that gates *where the person can go* lives here, not scattered
 * across screens: the navigation bar (and therefore Tables/Boost/Build) only
 * exists once a session is open, and a [PreflightState.Blocked] dialog is
 * rendered above every route because there is no route it is safe to let
 * someone tap past — see [BlockedDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimoscalApp(viewModel: EditorViewModel, analysisViewModel: AnalysisViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recoverable by viewModel.recoverable.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val landscape = isLandscape()

    // Hoisted to the shell because the blocker dialog is drawn above every route
    // and needs its own way to reach the picker — the one on the import screen is
    // behind the dialog and therefore untappable while a blocked verdict stands.
    val blockedBinPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFilePicked(it, InputKind.BIN) }
    }

    // The session is the only thing that should ever move the person between
    // "import" and the workspace. Driving navigation off `sessionOpen` (rather
    // than, say, a button's own onClick) means recovery and a fresh
    // openSession() land in the same place, and a session that closes for any
    // reason always drops back to a screen where re-import is possible.
    //
    // It only moves someone who is on the *wrong side* of that line. This effect
    // re-runs on every fresh composition, not only when the session actually
    // opens — after process death, and after any configuration change the
    // activity is recreated for — and an unconditional `navigate("tables")`
    // there threw away the restored back stack: rotating the device on the Boost
    // editor landed back on Tables, which is precisely the screen a person
    // rotates to get *away* from.
    LaunchedEffect(state.sessionOpen) {
        val route = navController.currentBackStackEntry?.destination?.route
        if (state.sessionOpen) {
            if (route == null || route == "import") {
                navController.navigate("tables") {
                    popUpTo("import") { inclusive = false }
                }
            }
        } else if (route != "import" && route != "analyze") {
            // "analyze" is exempt because it is the one destination that does not
            // belong to a session. Without this the effect — which re-runs on
            // every fresh composition, not only when the session changes — would
            // throw a person off the Analyze screen on rotation, for the sake of
            // a session they were deliberately not using.
            navController.navigate("import") {
                popUpTo(0)
            }
        }
    }

    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            // The engine's own detail line is always appended when it sent one.
            // It used to be Advanced-only; hiding it never made the failure less
            // real, only harder to report, and "op=open_session" under the
            // sentence is what turns a shrug into a bug report.
            val message = if (error.advanced.isNotBlank()) {
                "${error.message}\n${error.advanced}"
            } else {
                error.message
            }
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    Scaffold(
        containerColor = PromoPalette.Bg,
        topBar = {
            // Held back in landscape, where vertical pixels are the scarce ones
            // and this bar carries nothing but the wordmark — the boost canvas is
            // drawn from the height the chrome gives back, and the navigation bar
            // below is the half that has actual controls on it.
            if (!landscape) {
                // The bar and the hairline under it are one unit: the video separates
                // its chrome from its content with a rule and never with a shadow, and
                // Material's own elevation overlay would tint the bar away from the
                // app's ground the moment content scrolled beneath it.
                Column {
                    TopAppBar(
                        title = { Wordmark(fontSize = 20.sp) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = PromoPalette.Bg,
                            titleContentColor = PromoPalette.Text,
                            actionIconContentColor = PromoPalette.TextDim,
                        ),
                    )
                    HairRule()
                }
            }
        },
        bottomBar = {
            // The bar itself is the gate that keeps the editors reachable
            // only from a live session — it does not exist otherwise, rather than
            // existing-but-disabled, so there is nothing to tap toward at all.
            if (state.sessionOpen) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route
                Column {
                    HairRule()
                    NavigationBar(containerColor = PromoPalette.BgAlt) {
                        destinationItems().forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                // A null destination is not gated on a session — see
                                // [DestinationItem.destination].
                                enabled = item.destination?.let(state::destinationEnabled) ?: true,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PromoPalette.Accent,
                                    selectedTextColor = PromoPalette.Accent,
                                    indicatorColor = PromoPalette.AccentContainer,
                                    unselectedIconColor = PromoPalette.TextFaint,
                                    unselectedTextColor = PromoPalette.TextFaint,
                                ),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = PromoPalette.BgAlt,
                    contentColor = PromoPalette.Text,
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Busy always shows, on every screen, so a person can never mistake a
            // slow bridge call for the app doing nothing.
            if (state.busy) {
                LinearProgressIndicator(
                    color = PromoPalette.Accent,
                    trackColor = PromoPalette.Rule,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Weighted, so a screen that fills its height fills what is *left*
            // rather than the whole body: the busy bar appears and disappears
            // under it, and without this the landscape boost editor's bottom
            // button row would be pushed off the screen edge by exactly the
            // height of that bar for as long as a call was in flight.
            NavHost(
                navController = navController,
                startDestination = "import",
                modifier = Modifier.weight(1f),
            ) {
                composable("import") {
                    ImportScreen(
                        viewModel = viewModel,
                        recoverable = recoverable,
                        onAnalyze = { navController.navigate("analyze") },
                    )
                }
                // Reachable with or without a session, and it never opens one.
                // "Done" is offered instead of the navigation bar when no session
                // is open, since the bar — and with it every other way off this
                // screen — does not exist then.
                composable("analyze") {
                    AnalyzeScreen(
                        viewModel = analysisViewModel,
                        onBack = if (state.sessionOpen) null else {
                            { navController.popBackStack() }
                        },
                    )
                }
                composable("tables") { TablesScreen(viewModel = viewModel) }
                composable("boost") { BoostScreen(viewModel = viewModel) }
                composable("limiters") { LimitersScreen(viewModel = viewModel) }
                composable("pedal") { PedalScreen(viewModel = viewModel) }
                composable("slots") { SlotsScreen(viewModel = viewModel) }
                composable("changes") { ChangesScreen(viewModel = viewModel) }
                composable("build") { BuildScreen(viewModel = viewModel) }
            }
        }
    }

    // Rendered last so it draws above the Scaffold's own content. A blocked
    // preflight is a dead end by design (see PreflightState.Blocked) — the
    // dialog is the only thing on screen with `onDismissRequest = {}` and no
    // outside-tap or back-press dismissal, because "continue anyway" is exactly
    // what this app must never offer.
    state.blocker?.let { blocker ->
        BlockedDialog(
            blocker = blocker,
            onChooseAnotherBin = {
                // Retract the verdict *and* open the picker in one action. Both
                // halves are needed: the dialog cannot be dismissed, so leaving
                // the verdict in place would keep it covering the very picker the
                // button is named after. Picking a bin then resets preflight
                // again via EditorUiState.withBin.
                viewModel.dismissBlocker()
                blockedBinPicker.launch(InputKind.BIN.mimeTypes)
            },
            onCancel = {
                // Cancel returns to the import screen with no session and no
                // verdict — it is a retraction, never a way past the refusal.
                // `canOpenSession` still requires a *passed* preflight, so
                // nothing here can lead to an edit on the blocked bin.
                viewModel.dismissBlocker()
                navController.navigate("import") { popUpTo(0) }
            },
        )
    }
}

private data class DestinationItem(
    /**
     * The session destination this item leads to, or null for one that does not
     * belong to a session at all.
     *
     * Analyze is the null case, and it is null rather than a new [Destination]
     * on purpose: that enum is the set of places *a session* lets a person go,
     * and `destinationEnabled` gates every one of them on `sessionOpen`. Adding
     * Analyze to it would mean writing a case that ignores the very state the
     * enum exists to model. A null here reads as what it is — not gated.
     */
    val destination: Destination?,
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private fun destinationItems(): List<DestinationItem> = listOf(
    DestinationItem(Destination.TABLES, "tables", "Tables", Icons.Filled.List),
    DestinationItem(Destination.BOOST, "boost", "Boost", Icons.Filled.KeyboardArrowUp),
    DestinationItem(Destination.LIMITERS, "limiters", "Limiters", Icons.Filled.Warning),
    DestinationItem(Destination.PEDAL, "pedal", "Pedal", Icons.Filled.PlayArrow),
    DestinationItem(Destination.SLOTS, "slots", "Slots", Icons.Filled.Settings),
    // Sits immediately before Build, because that is the order the work happens
    // in: read what you changed, then verify it. Putting it after Build would
    // put the unverified list on the far side of the gate that verifies it.
    DestinationItem(Destination.CHANGES, "changes", "Changes", Icons.Filled.Edit),
    DestinationItem(Destination.BUILD, "build", "Build", Icons.Filled.Build),
    // Last, and always enabled. It reads datalogs from the *previous* flash, so
    // it sits after the gate rather than inside the edit → verify run: it is
    // where a person goes to find out whether the last bin actually worked,
    // which is the question that starts the next revision.
    DestinationItem(null, "analyze", "Analyze", Icons.Filled.Info),
)

/**
 * The non-dismissible dead end for a bin that cannot be safely edited.
 *
 * No "continue anyway" exists anywhere in this dialog on purpose: a blocked
 * preflight means the engine itself would refuse to open a session over this
 * bin, so offering a third button here would just be a slower way to hit the
 * same refusal one screen later — with the difference that it would look like
 * a choice. The two real ways out are choosing a different bin or backing off
 * to the import screen; neither is a "proceed" and neither may be dismissed by
 * tapping outside or pressing back.
 */
@Composable
private fun BlockedDialog(
    blocker: PreflightState.Blocked,
    onChooseAnotherBin: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        // The one dead end in the app, painted as one: the video reserves
        // `danger` for limits and refusals, and this dialog is the app's.
        containerColor = PromoPalette.DangerContainer,
        titleContentColor = PromoPalette.Danger,
        textContentColor = PromoPalette.Text,
        title = { Text(stringResource(R.string.preflight_blocked_title)) },
        text = {
            Column {
                Text(blocker.summary, style = MaterialTheme.typography.bodyMedium)
                blocker.reasons.forEach { reason ->
                    Text("• $reason", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onChooseAnotherBin) {
                Text(stringResource(R.string.choose_another_bin))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

