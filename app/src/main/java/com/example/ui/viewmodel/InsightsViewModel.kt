package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusLockApp
import com.example.data.model.ScreenTimeAppUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FocusLockApp
    private val usageRepo = app.usageStatsRepository
    private val focusRepo = app.focusRepository

    private val _dailyAppUsage = MutableStateFlow<List<ScreenTimeAppUsage>>(emptyList())
    val dailyAppUsage: StateFlow<List<ScreenTimeAppUsage>> = _dailyAppUsage.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _totalScreenTimeMinutes = MutableStateFlow(0)
    val totalScreenTimeMinutes: StateFlow<Int> = _totalScreenTimeMinutes.asStateFlow()

    private val _productiveMinutes = MutableStateFlow(0)
    val productiveMinutes: StateFlow<Int> = _productiveMinutes.asStateFlow()

    private val _distractedMinutes = MutableStateFlow(0)
    val distractedMinutes: StateFlow<Int> = _distractedMinutes.asStateFlow()

    init {
        refreshUsageData()
    }

    fun refreshUsageData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stats = usageRepo.getDailyScreenTimeStats()
                _dailyAppUsage.value = stats

                var totalMs = 0L
                var distMs = 0L
                var prodMs = 0L

                for (s in stats) {
                    totalMs += s.usageMillis
                    if (s.isDistracting) {
                        distMs += s.usageMillis
                    } else {
                        prodMs += s.usageMillis
                    }
                }

                _totalScreenTimeMinutes.value = (totalMs / (60 * 1000)).toInt()
                _distractedMinutes.value = (distMs / (60 * 1000)).toInt()
                _productiveMinutes.value = (prodMs / (60 * 1000)).toInt()
            } catch (e: Exception) {
                // Handled
            } finally {
                _isLoading.value = false
            }
        }
    }
}
