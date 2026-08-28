package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 8 (PROJECT_SPEC.md section 20, step 9): the same "read ticks back
 * from Room, draw a line" idea as Phase 6/7, applied to the NIFTY 50 spot
 * index instead of an option contract. No locked-radar check needed here —
 * Phase 4 always subscribes to the spot index alongside whatever contracts
 * are locked, so this screen just shows whatever ticks exist for today
 * (possibly none yet), same as [LiveTickChart]'s own empty-state message.
 */
class Phase8ViewModel(application: Application) : AndroidViewModel(application) {

    private val liveTickStore = LiveTickStore(application)

    private val _ticks = MutableStateFlow<List<LiveTickEntity>>(emptyList())
    val ticks: StateFlow<List<LiveTickEntity>> = _ticks.asStateFlow()

    /** IST trading-day key — same convention as the other ViewModels. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call when this screen opens, and again any time to pick up new ticks. */
    fun load() {
        viewModelScope.launch {
            _ticks.value = liveTickStore.ticksFor(todaySessionDate(), UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY)
        }
    }
}
