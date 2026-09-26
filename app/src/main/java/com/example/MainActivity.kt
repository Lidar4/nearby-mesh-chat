package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.AudioCallScreen
import com.example.ui.ChatScreen
import com.example.ui.ChatViewModel
import com.example.ui.HomeScreen
import com.example.ui.Screen
import com.example.ui.SplashScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ChatViewModel

    // Permissions launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        
        val localDiscoveryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.NEARBY_WIFI_DEVICES] ?: false
        } else {
            (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
            (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)
        }

        if (recordAudioGranted && localDiscoveryGranted) {
            // Permissions successfully granted! Navigate to Home.
            viewModel.navigateTo(Screen.HOME)
            viewModel.startDiscovery()
        } else {
            // Inform the user gracefully
            var errorMessage = "Permissions denied! "
            if (!recordAudioGranted) errorMessage += "Microphone is required for calling. "
            if (!localDiscoveryGranted) errorMessage += "Nearby/Location permission is required to discover devices."
            
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            // Continue to Home anyway so the user can browse, but warn them
            viewModel.navigateTo(Screen.HOME)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize shared ViewModel
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]

        setContent {
            MyApplicationTheme {
                val screenState by viewModel.currentScreen.collectAsState()

                when (screenState) {
                    Screen.SPLASH -> {
                        SplashScreen(
                            viewModel = viewModel,
                            onStart = {
                                requestAppPermissions()
                            }
                        )
                    }
                    Screen.HOME -> {
                        HomeScreen(viewModel = viewModel)
                    }
                    Screen.CHAT -> {
                        ChatScreen(viewModel = viewModel)
                    }
                    Screen.AUDIO_CALL -> {
                        AudioCallScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure background threads are shutdown cleanly
        viewModel.p2pManager.stopAll()
        viewModel.audioCallManager.endCall()
    }
}
