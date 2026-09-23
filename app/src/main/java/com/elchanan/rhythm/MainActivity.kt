package com.elchanan.rhythm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.elchanan.rhythm.ui.Display
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.RhythmRoot
import com.elchanan.rhythm.ui.theme.RhythmTheme
import com.elchanan.rhythm.ui.theme.UiLanguage
import com.elchanan.rhythm.data.Prefs

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            hasAudioPermission = result[audioPermission] ?: hasAudioPermission
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        hasAudioPermission = ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED

        val prefs = Prefs(this)
        UiLanguage.code = prefs.language
        Display.compact = prefs.compactMode
        Display.foldersTab = prefs.foldersTab

        setContent {
            RhythmTheme {
                val vm: MainViewModel = viewModel()
                // Compact mode: the whole app one size smaller, by scaling the
                // density everything is measured in. The layouts are the same
                // layouts; nothing is rearranged or hidden, it all just fits.
                val base = LocalDensity.current
                val density = if (Display.compact) {
                    Density(base.density * Display.COMPACT_SIZE, base.fontScale * Display.COMPACT_TEXT)
                } else {
                    base
                }
                CompositionLocalProvider(LocalDensity provides density) {
                    RhythmRoot(
                        vm = vm,
                        hasPermission = hasAudioPermission,
                        onRequestPermission = { requestPermissions() }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }
}
