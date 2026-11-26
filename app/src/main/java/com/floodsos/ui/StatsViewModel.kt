package com.floodsos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.floodsos.BuildConfig
import com.floodsos.FloodSosApp
import com.floodsos.core.FieldExportCodec
import com.floodsos.core.FieldStats
import com.floodsos.data.SosRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class StatsViewModel(
    private val repository: SosRepository,
    private val deviceId: String,
) : ViewModel() {

    val deviceShortId: String = deviceId.take(8)

    /** Recomputed live whenever a message or an exchange is stored. null = still loading. */
    val stats: StateFlow<FieldStats?> =
        combine(repository.observeHeld(), repository.observeExchanges()) { held, exchanges ->
            FieldStats.compute(held, exchanges, deviceId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun exportJson(): String = FieldExportCodec.toJson(
        FieldExportCodec.build(
            deviceId = deviceId,
            now = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            held = repository.allHeld(),
            exchanges = repository.allExchanges(),
        )
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FloodSosApp
                StatsViewModel(app.repository, app.deviceId)
            }
        }
    }
}
