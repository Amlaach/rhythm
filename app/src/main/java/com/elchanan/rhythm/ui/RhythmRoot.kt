package com.elchanan.rhythm.ui

import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import com.elchanan.rhythm.ui.components.rememberMetrics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elchanan.rhythm.ui.screens.AboutScreen
import com.elchanan.rhythm.ui.screens.AlbumsScreen
import com.elchanan.rhythm.ui.screens.AlgorithmSettingsScreen
import com.elchanan.rhythm.ui.screens.ArtistDetailScreen
import com.elchanan.rhythm.ui.screens.ArtistRatingsScreen
import com.elchanan.rhythm.ui.screens.DetailListScreen
import com.elchanan.rhythm.ui.screens.EqualizerScreen
import com.elchanan.rhythm.ui.screens.HomeScreen
import com.elchanan.rhythm.ui.screens.HomeSettingsScreen
import com.elchanan.rhythm.ui.screens.LibraryScreen
import com.elchanan.rhythm.ui.screens.LibrarySettingsScreen
import com.elchanan.rhythm.ui.screens.MiniPlayer
import com.elchanan.rhythm.ui.screens.PlayerScreen
import com.elchanan.rhythm.ui.screens.PlayerSettingsScreen
import com.elchanan.rhythm.ui.screens.RecapScreen
import com.elchanan.rhythm.ui.screens.SearchScreen
import com.elchanan.rhythm.ui.screens.SelectionBar
import com.elchanan.rhythm.ui.screens.SettingsScreen
import com.elchanan.rhythm.ui.screens.TagFixScreen
import com.elchanan.rhythm.ui.screens.HebrewNamesScreen
import com.elchanan.rhythm.ui.screens.SamplesScreen
import com.elchanan.rhythm.ui.screens.TagSettingsScreen
import com.elchanan.rhythm.ui.screens.TransferScreen
import com.elchanan.rhythm.ui.screens.WelcomeScreen
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val FOLDERS = "folders"
    const val RATINGS = "ratings"
    const val SETTINGS = "settings"
    const val ALGORITHM_SETTINGS = "algorithmsettings"
    const val HOME_SETTINGS = "homesettings"
    const val PLAYER_SETTINGS = "playersettings"
    const val LIBRARY_SETTINGS = "librarysettings"
    const val TAG_SETTINGS = "tagsettings"
    const val TRANSFER = "transfer"
    const val ABOUT = "about"
    const val DETAIL = "detail"
    const val ARTIST = "artist"
    const val ALBUMS = "albums"
    const val RECAP = "recap"
    const val TAGS = "tags"
    const val HEBREW_NAMES = "hebrewnames"
    const val SAMPLES = "samples"
    const val EQUALIZER = "equalizer"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "בית", Icons.Filled.Home),
    // Tastes where search was: search moved to the top of the home screen,
    // which is where it is looked for, and the bar stays four wide on a
    // narrow phone.
    Tab(Routes.SAMPLES, "טעימות", Icons.Filled.AutoAwesome),
    Tab(Routes.LIBRARY, "ספרייה", Icons.Filled.LibraryMusic),
    // "דירוגים", not "אמנים": the library already has an artists tab, and
    // this one is where they are rated - which is what the star says.
    Tab(Routes.RATINGS, "דירוגים", Icons.Filled.Star)
)

/** The folders' own tab, beside the library, for those who chose it. */
private val FOLDERS_TAB = Tab(Routes.FOLDERS, "תיקיות", Icons.Filled.Folder)

@Composable
fun RhythmRoot(
    vm: MainViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var playerOpen by remember { mutableStateOf(false) }
    var welcomeDone by remember { mutableStateOf(vm.prefs.welcomeSeen) }

    val message by vm.message.collectAsStateWithLifecycle()
    // Position ticks belong to playback controls, not the entire navigation tree.
    val navigationPlayerState = remember(vm) {
        vm.player.state.map { it.copy(positionMs = 0L, bufferedMs = 0L) }.distinctUntilChanged()
    }
    val playerState by navigationPlayerState.collectAsStateWithLifecycle(
        initialValue = vm.player.state.value.copy(positionMs = 0L, bufferedMs = 0L)
    )
    val library by vm.library.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        val m = message
        if (m != null) {
            snackbarHostState.showSnackbar(m)
            vm.consumeMessage()
        }
    }

    // Every launch, not only the first one ever. The library is a view of what
    // is on the device, and the device changes while the app is closed.
    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.scanOnLaunch()
    }

    // The first tastes, prepared a few moments after the library is there -
    // never during the opening itself.
    LaunchedEffect(library.loaded) {
        if (library.loaded) vm.warmTastes()
    }

    // bring the previous session's queue back once both sides are ready
    LaunchedEffect(library.loaded, playerState.connected) {
        vm.restoreQueueIfNeeded()
    }

    // Deleting someone's files is the platform's question to ask, not the
    // app's, and its dialog can only be launched from here. The view model
    // raises the request and waits for the answer.
    val pendingDelete by vm.deleteRequest.collectAsStateWithLifecycle()
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onDeleteResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(pendingDelete) {
        val request = pendingDelete ?: return@LaunchedEffect
        runCatching {
            deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
        }.onFailure { vm.onDeleteResult(false) }
    }

    val useRail = rememberMetrics().twoPane
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentSong = playerState.currentSongId?.let { library.songsById[it] }

    // The expanded player is an overlay rather than a NavHost destination, so the
    // system back button has to be answered here - otherwise back falls through to
    // the navigation underneath and the player stays stuck on screen.
    BackHandler(enabled = playerOpen) { playerOpen = false }

    // Once the queue empties there is nothing to expand to; clearing the flag stops
    // the player from springing open again by itself when the next song starts.
    LaunchedEffect(currentSong) {
        if (currentSong == null) playerOpen = false
    }

    // "Open the player when I start a song", for those who want it. Keyed on
    // the counter rather than on the current song, so that it fires on a
    // deliberate tap and stays quiet when the queue simply moves on - and so
    // that starting the same song twice opens it twice.
    // A folder asked for from the player: the player steps aside and the
    // folder view comes up, where the request itself is opened.
    val folderRequest by vm.folderRequest.collectAsStateWithLifecycle()
    LaunchedEffect(folderRequest) {
        if (folderRequest == null) return@LaunchedEffect
        playerOpen = false
        val target = if (Display.foldersTab) Routes.FOLDERS else Routes.LIBRARY
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    // The home screen's queue button: the player comes up, and opens its queue itself.
    val queueRequest by vm.queueRequest.collectAsStateWithLifecycle()
    LaunchedEffect(queueRequest) {
        if (queueRequest) playerOpen = true
    }

    // Writing a song's details into its file needs the system's own
    // permission dialog from Android 11 on, and only an activity can show it.
    // Here rather than on one screen, because the edit can start anywhere -
    // the player, a song's menu, a selection, the tag fixer.
    val writePermission by vm.writePermissionRequest.collectAsStateWithLifecycle()
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onWritePermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(writePermission) {
        writePermission?.let { writeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    // Below Android 11 there is no per file dialog, only the old storage permission.
    val legacyWrite by vm.legacyPermissionRequest.collectAsStateWithLifecycle()
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onWritePermissionResult(granted) }
    LaunchedEffect(legacyWrite) {
        if (legacyWrite) legacyLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    val startedCount by vm.playbackStarted.collectAsStateWithLifecycle()
    LaunchedEffect(startedCount) {
        if (startedCount > 0 && vm.prefs.openPlayerOnPlay) playerOpen = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The wash lives on the individual screens, which each paint their
            // own full-size surface; anything drawn here would sit under them
            // and never be seen.
            .background(AppBackground)
    ) {
        // Shown before anything else on a first run, and only while permission
        // is already granted - otherwise the permission prompt is the first
        // thing that matters and two explanations at once help nobody.
        if (!welcomeDone && hasPermission) {
            WelcomeScreen(
                songCount = library.songs.size,
                onStart = {
                    vm.prefs.welcomeSeen = true
                    welcomeDone = true
                }
            )
            return@Box
        }

        Scaffold(
            containerColor = Color.Transparent,
            // Transparent leaves Scaffold unable to derive a content colour, so
            // every child that does not name one falls back to near black. It
            // has to be stated here or the whole app goes unreadable.
            contentColor = TextPrimary,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(modifier = Modifier.background(BgElevated)) {
                    // Above the mini player and the tabs, and drawn for the
                    // whole app rather than by whichever screen started the
                    // selection. A selection made on the home page is still a
                    // selection after switching to the library, and the bar
                    // that acts on it has to follow rather than vanish with
                    // the screen it was born on. Empty selection draws
                    // nothing, so this costs a row only while it is wanted.
                    // Put away under the open player with the tabs, whose
                    // row gave it its room above the system navigation; the
                    // selection itself stays, and the bar is back with the
                    // list it acts on.
                    // The tastes take the whole screen, with a player of their
                    // own: the app's bars would only sit paused under them.
                    val tasting = currentRoute == Routes.SAMPLES
                    val fullScreen = playerOpen
                    if (!fullScreen && !tasting) SelectionBar(vm)
                    // Hidden while the full player is up - it is the same controls.
                    if (currentSong != null && !fullScreen && !tasting) {
                        val miniState by vm.player.state.collectAsStateWithLifecycle()
                        MiniPlayer(
                            song = currentSong,
                            isPlaying = miniState.isPlaying,
                            progress = if (miniState.durationMs > 0)
                                miniState.positionMs.toFloat() / miniState.durationMs else 0f,
                            liked = library.stats[currentSong.id]?.liked ?: 0,
                            onToggle = { vm.player.togglePlayPause() },
                            onNext = { vm.player.next() },
                            onPrevious = { vm.player.previous() },
                            onLike = { vm.like(currentSong.id) },
                            onExpand = { playerOpen = true }
                        )
                    }
                    // Not under the open player: it is put away by a swipe down
                    // from anywhere, so the tabs would only take room from it.
                    if (!fullScreen && !useRail) RhythmBottomBar(
                        navController = navController,
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            playerOpen = false
                            if (route == Routes.HOME && currentRoute == Routes.HOME) {
                                vm.requestHomeTop()
                            }
                        }
                    )
                }
            }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = inner.calculateBottomPadding())
            ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // On a wide, short window the tabs stand down the side: along
                // the bottom they took height that a landscape screen has least
                // of. Put away under the open player, as the bottom bar is.
                if (useRail && !playerOpen) {
                    RhythmNavRail(
                        navController = navController,
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route == Routes.HOME && currentRoute == Routes.HOME) {
                                vm.requestHomeTop()
                            }
                        }
                    )
                }
                // Lists and settings keep a reading width on a wide screen,
                // centred, instead of stretching a row of text across a whole
                // tablet. The home feed and the equalizer use every bit of
                // width they are given.
                val fullWidth = currentRoute == null || currentRoute == Routes.HOME ||
                    currentRoute == Routes.EQUALIZER
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = if (fullWidth) Dp.Unspecified else READING_WIDTH)
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    // Navigation's own default is a 700 ms crossfade on every change of
                    // screen. Nothing here asked for it, and it was most of what a tap
                    // on a mix felt like: the tile answered at once, and then the page
                    // took the better part of a second to arrive - where the three dots,
                    // which open a sheet instead of a screen, answered straight away.
                    // A short fade keeps the change readable without the wait.
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeIn(tween(SCREEN_FADE_MS)) },
                        exitTransition = { fadeOut(tween(SCREEN_FADE_MS)) },
                        popEnterTransition = { fadeIn(tween(SCREEN_FADE_MS)) },
                        popExitTransition = { fadeOut(tween(SCREEN_FADE_MS)) }
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                vm = vm,
                                hasPermission = hasPermission,
                                onRequestPermission = onRequestPermission,
                                onOpenDetail = { navController.navigate(Routes.DETAIL) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onOpenRatings = { navController.navigate(Routes.RATINGS) },
                                onOpenRecap = { navController.navigate(Routes.RECAP) },
                                onOpenTagFix = { navController.navigate(Routes.TAGS) },
                                onOpenSearch = {
                                    navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                                }
                            )
                        }
                        composable(Routes.SEARCH) {
                            SearchScreen(
                                vm = vm,
                                onOpenArtist = { navController.navigate(Routes.ARTIST) },
                                onOpenDetail = { navController.navigate(Routes.DETAIL) },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                vm = vm,
                                onOpenDetail = { navController.navigate(Routes.DETAIL) },
                                onOpenArtist = { navController.navigate(Routes.ARTIST) },
                                onOpenAlbums = { navController.navigate(Routes.ALBUMS) }
                            )
                        }
                        composable(Routes.FOLDERS) {
                            LibraryScreen(
                                vm = vm,
                                onOpenDetail = { navController.navigate(Routes.DETAIL) },
                                onOpenArtist = { navController.navigate(Routes.ARTIST) },
                                onOpenAlbums = { navController.navigate(Routes.ALBUMS) },
                                foldersOnly = true
                            )
                        }
                        composable(Routes.RATINGS) {
                            ArtistRatingsScreen(
                                vm = vm,
                                onOpenArtist = { navController.navigate(Routes.ARTIST) }
                            )
                        }
                        composable(Routes.RECAP) {
                            RecapScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { navController.navigate(Routes.DETAIL) }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                language = com.elchanan.rhythm.ui.theme.UiLanguage.code,
                                onLanguageChange = { choice ->
                                    vm.prefs.language = choice
                                    com.elchanan.rhythm.ui.theme.UiLanguage.code = choice
                                },
                                onOpenHomeSettings = { navController.navigate(Routes.HOME_SETTINGS) },
                                onOpenPlayerSettings = { navController.navigate(Routes.PLAYER_SETTINGS) },
                                onOpenAlgorithmSettings = { navController.navigate(Routes.ALGORITHM_SETTINGS) },
                                onOpenLibrarySettings = { navController.navigate(Routes.LIBRARY_SETTINGS) },
                                onOpenTagSettings = { navController.navigate(Routes.TAG_SETTINGS) },
                                onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                                onOpenRecap = { navController.navigate(Routes.RECAP) }
                            )
                        }
                        composable(Routes.LIBRARY_SETTINGS) {
                            LibrarySettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.TAG_SETTINGS) {
                            TagSettingsScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenTagFix = { navController.navigate(Routes.TAGS) },
                                onOpenHebrewNames = { navController.navigate(Routes.HEBREW_NAMES) }
                            )
                        }
                        composable(Routes.TRANSFER) {
                            TransferScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.ABOUT) {
                            AboutScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.ALGORITHM_SETTINGS) {
                            AlgorithmSettingsScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { navController.navigate(Routes.DETAIL) }
                            )
                        }
                        composable(Routes.HOME_SETTINGS) {
                            HomeSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.PLAYER_SETTINGS) {
                            PlayerSettingsScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) }
                            )
                        }
                        composable(Routes.TAGS) {
                            TagFixScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenHebrewNames = { navController.navigate(Routes.HEBREW_NAMES) }
                            )
                        }
                        composable(Routes.SAMPLES) {
                            SamplesScreen(
                                vm = vm,
                                // The whole song goes to the app's player, on the
                                // home screen with the mini player under it.
                                onLeave = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        composable(Routes.HEBREW_NAMES) {
                            HebrewNamesScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.EQUALIZER) {
                            EqualizerScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.DETAIL) {
                            DetailListScreen(vm = vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.ARTIST) {
                            ArtistDetailScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { navController.navigate(Routes.DETAIL) }
                            )
                        }
                        composable(Routes.ALBUMS) {
                            AlbumsScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { navController.navigate(Routes.DETAIL) }
                            )
                        }
                    }
                }
                }
            }

            // Inside the Scaffold body rather than over the whole window, so the
            // navigation bar stays on top and reachable. Covering it meant a tap on
            // a tab hit the player instead - which swallows taps so they do not
            // reach the list underneath - and nothing happened at all.
            AnimatedVisibility(
                visible = playerOpen && currentSong != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                PlayerScreen(
                    vm = vm,
                    onCollapse = { playerOpen = false },
                    onOpenEqualizer = {
                        // Collapsed first: the player is an overlay above the
                        // navigation, so leaving it open would hide whatever
                        // it navigated to.
                        playerOpen = false
                        navController.navigate(Routes.EQUALIZER)
                    }
                )
            }
            }
        }
    }
}

@Composable
private fun RhythmBottomBar(
    navController: NavHostController,
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = Color.Transparent,
        // Compact mode: a lower bar. Icons and labels stay; the air around
        // them is what a small screen cannot spare.
        //
        // The 62dp are the tabs' own. They used to include the room the
        // system keeps for its navigation buttons, which the bar pads itself
        // by: with three-button navigation - most Samsungs - that is 48dp,
        // more in compact mode's smaller units, and the icons were left a few
        // dp and spilled up over the mini player. The system's room is now
        // added outside the 62, as it is without compact mode.
        windowInsets = if (Display.compact) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets,
        modifier = Modifier.fillMaxWidth().then(
            if (Display.compact) {
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(62.dp)
            } else {
                Modifier
            }
        )
    ) {
        val tabs = if (Display.foldersTab) {
            TABS.flatMap { if (it.route == Routes.LIBRARY) listOf(it, FOLDERS_TAB) else listOf(it) }
        } else {
            TABS
        }
        tabs.forEach { tab ->
            // Home is the root of the app, so its tab ignores what was saved for
            // it. Leaving a settings screen files that whole stack under home's
            // own id, and restoring it hands the settings screen straight back
            // instead of landing on the feed. The other tabs do restore - that is
            // what makes each of them remember where it was.
            val restoresSavedState = tab.route != Routes.HOME
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    // Always navigate, even when the tab looks current: a detail
                    // screen pushed on top counts as a different route, and tapping
                    // the tab you are already on should still take you to its root
                    // rather than doing nothing.
                    onNavigate(tab.route)
                    navController.navigate(tab.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = restoresSavedState
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

/** Widest a list or settings page grows on a big screen. */
private val READING_WIDTH = 900.dp

/** The tabs down the side of a wide, short window; the same tabs as [RhythmBottomBar]. */
@Composable
private fun RhythmNavRail(
    navController: NavHostController,
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationRail(containerColor = BgElevated) {
        Spacer(Modifier.weight(1f))
        val tabs = if (Display.foldersTab) {
            TABS.flatMap { if (it.route == Routes.LIBRARY) listOf(it, FOLDERS_TAB) else listOf(it) }
        } else {
            TABS
        }
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = {
                    onNavigate(tab.route)
                    navController.navigate(tab.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = tab.route != Routes.HOME
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Color.Transparent
                )
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** How long a change of screen takes; see the NavHost. */
private const val SCREEN_FADE_MS = 120
