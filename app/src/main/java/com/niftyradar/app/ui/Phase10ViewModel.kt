package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import com.niftyradar.app.storage.RadarSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 10 (PROJECT_SPEC.md section 20, steps 10-11: daily session locking +
 * historical review): [RadarSessionStore] now keeps every locked day instead
 * of only the latest one. This screen lists those dates and, for whichever
 * one is picked, reloads that day's 23 charts exactly the way Phase 9 shows
 * today's — same [RadarChartItem]/[LiveTickChart], just for ticks stored
 * under a past date instead of today's.
 */
class Phase10ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _lockedDates = MutableStateFlow<List<String>>(emptyList())
    val lockedDates: StateFlow<List<String>> = _lockedDates.asStateFlow()

    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate.asStateFlow()

    private val _items = MutableStateFlow<List<RadarChartItem>>(emptyList())
    val items: StateFlow<List<RadarChartItem>> = _items.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    /** Call once when this screen opens: which dates have a locked session at all? */
    fun loadDates() {
        _lockedDates.value = sessionStore.listLockedDates()
    }

    /** Reload that date's locked contracts + stored ticks, same shape as Phase 9. */
    fun selectDate(date: String) {
        _selectedDate.value = date

        val session = sessionStore.loadForDate(date)
        if (session == null) {
            _items.value = emptyList()
            _ticksByInstrument.value = emptyMap()
            return
        }

        val items = mutableListOf<RadarChartItem>()
        items.add(RadarChartItem("NIFTY 50 SPOT", UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY))
        for (strike in session.strikes) {
            val marker = if (strike == session.atmStrike) " (ATM)" else ""
            val ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
            val peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
            if (ceKey != null) {
                items.add(RadarChartItem("$strike$marker CE", ceKey))
            }
            if (peKey != null) {
                items.add(RadarChartItem("$strike$marker PE", peKey))
            }
        }
        _items.value = items

        viewModelScope.launch {
            val result = mutableMapOf<String, List<LiveTickEntity>>()
            for (item in items) {
                result[item.instrumentKey] = liveTickStore.ticksFor(date, item.instrumentKey)
            }
            _ticksByInstrument.value = result
        }
    }

    /** Back to the date list. */
    fun clearSelection() {
        _selectedDate.value = null
        _items.value = emptyList()
        _ticksByInstrument.value = emptyMap()
    }
}
