package com.elchanan.rhythm.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.elchanan.rhythm.ui.screens.AlbumsScreen
import com.elchanan.rhythm.ui.screens.AlgorithmSettingsScreen
import com.elchanan.rhythm.ui.screens.ArtistDetailScreen
import com.elchanan.rhythm.ui.screens.ArtistRatingsScreen
import com.elchanan.rhythm.ui.screens.DetailListScreen
import com.elchanan.rhythm.ui.screens.EqualizerScreen
import com.elchanan.rhythm.ui.screens.HomeScreen
import com.elchanan.rhythm.ui.screens.HomeSettingsScreen
import com.elchanan.rhythm.ui.screens.LibraryScreen
import com.elchanan.rhythm.ui.screens.MiniPlayer
import com.elchanan.rhythm.ui.screens.PlayerScreen
import com.elchanan.rhythm.ui.screens.PlayerSettingsScreen
import com.elchanan.rhythm.ui.screens.RecapScreen
import com.elchanan.rhythm.ui.screens.SearchScreen
import com.elchanan.rhythm.ui.screens.SettingsScreen
import com.elchanan.rhythm.ui.screens.TagFixScreen
import com.elchanan.rhythm.ui.screens.WelcomeScreen
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val RATINGS = "ratings"
    const val SETTINGS = "settings"
    const val ALGORITHM_SETTINGS = "algorithmsettings"
    const val HOME_SETTINGS = "homesettings"
    const val PLAYER_SETTINGS = "playersettings"
    const val DETAIL = "detail"
    const val ARTIST = "artist"
    const val ALBUMS = "albums"
    const val RECAP = "recap"
    const val TAGS = "tags"
    const val EQUALIZER = "equalizer"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "בית", Icons.Filled.Home),
    Tab(Routes.SEARCH, "חיפוש", Icons.Filled.Search),
    Tab(Routes.LIBRARY, "ספרייה", Icons.Filled.LibraryMusic),
    Tab(Routes.RATINGS, "אמנים", Icons.Filled.Star)
)

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
    val playerState by vm.player.state.collectAsStateWithLifecycle()
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
                    // Hidden while the full player is up - it is the same controls.
                    if (currentSong != null && !playerOpen) {
                        MiniPlayer(
                            song = currentSong,
                            isPlaying = playerState.isPlaying,
                            progress = if (playerState.durationMs > 0)
                                playerState.positionMs.toFloat() / playerState.durationMs else 0f,
                            liked = library.stats[currentSong.id]?.liked ?: 0,
                            onToggle = { vm.player.togglePlayPause() },
                            onNext = { vm.player.next() },
                            onPrevious = { vm.player.previous() },
                            onLike = { vm.like(currentSong.id) },
                            onExpand = { playerOpen = true }
                        )
                    }
                    RhythmBottomBar(
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
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        hasPermission = hasPermission,
                        onRequestPermission = onRequestPermission,
                        onOpenDetail = { navController.navigate(Routes.DETAIL) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenRatings = { navController.navigate(Routes.RATINGS) },
                        onOpenRecap = { navController.navigate(Routes.RECAP) }
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        vm = vm,
                        onOpenArtist = { navController.navigate(Routes.ARTIST) },
                        onOpenDetail = { navController.navigate(Routes.DETAIL) }
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        vm = vm,
                        onOpenDetail = { navController.navigate(Routes.DETAIL) },
                        onOpenArtist = { navController.navigate(Routes.ARTIST) },
                        onOpenAlbums = { navController.navigate(Routes.ALBUMS) },
                        onOpenRatings = { navController.navigate(Routes.RATINGS) }
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
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        onOpenTagFix = { navController.navigate(Routes.TAGS) },
                        onOpenHomeSettings = { navController.navigate(Routes.HOME_SETTINGS) },
                        onOpenPlayerSettings = { navController.navigate(Routes.PLAYER_SETTINGS) },
                        onOpenAlgorithmSettings = { navController.navigate(Routes.ALGORITHM_SETTINGS) }
                    )
                }
                composable(Routes.ALGORITHM_SETTINGS) {
                    AlgorithmSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
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
                    TagFixScreen(vm = vm, onBack = { navController.popBackStack() })
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
        modifier = Modifier.fillMaxWidth()
    ) {
        TABS.forEach { tab ->
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
