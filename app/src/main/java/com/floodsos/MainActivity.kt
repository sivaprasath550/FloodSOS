package com.floodsos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.floodsos.ui.FloodSosTheme
import com.floodsos.ui.SosScreen
import com.floodsos.ui.SosViewModel

class MainActivity : ComponentActivity() {

    // Survives screen rotation; created through our Factory so it gets its dependencies.
    private val viewModel: SosViewModel by viewModels { SosViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloodSosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SosScreen(viewModel, padding)
                }
            }
        }
    }
}
