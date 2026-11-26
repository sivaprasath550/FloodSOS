package com.floodsos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.floodsos.ui.FloodSosTheme
import com.floodsos.ui.SosScreen
import com.floodsos.ui.SosViewModel
import com.floodsos.ui.StatsScreen
import com.floodsos.ui.StatsViewModel

class MainActivity : ComponentActivity() {

    // Survive screen rotation; created through Factories so they get their dependencies.
    private val viewModel: SosViewModel by viewModels { SosViewModel.Factory }
    private val statsViewModel: StatsViewModel by viewModels { StatsViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloodSosTheme {
                // Two screens only, so a boolean beats adding a navigation library.
                var showStats by rememberSaveable { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    if (showStats) {
                        BackHandler { showStats = false } // system back returns to SOS
                        StatsScreen(statsViewModel, padding, onBack = { showStats = false })
                    } else {
                        SosScreen(viewModel, padding, onOpenStats = { showStats = true })
                    }
                }
            }
        }
    }
}
