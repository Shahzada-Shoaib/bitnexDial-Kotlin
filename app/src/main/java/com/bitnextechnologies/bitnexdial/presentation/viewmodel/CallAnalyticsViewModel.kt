package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.data.local.dao.CallDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

private const val TAG = "CallAnalyticsViewModel"

data class CallAnalytics(
    val totalCalls: Int = 0,
    val incomingCalls: Int = 0,
    val outgoingCalls: Int = 0,
    val missedCalls: Int = 0,
    val answeredCalls: Int = 0,
    val totalDurationMs: Long = 0,
    val averageDurationMs: Long = 0,
    val longestCallMs: Long = 0,
    val callsToday: Int = 0,
    val callsThisWeek: Int = 0,
    val callsThisMonth: Int = 0,
    val durationToday: Long = 0,
    val durationThisWeek: Long = 0,
    val durationThisMonth: Long = 0
)

@HiltViewModel
class CallAnalyticsViewModel @Inject constructor(
    private val callDao: CallDao
) : ViewModel() {

    private val _analytics = MutableStateFlow(CallAnalytics())
    val analytics: StateFlow<CallAnalytics> = _analytics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadAnalytics()
    }

    fun refresh() {
        loadAnalytics()
    }

    private fun loadAnalytics() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Get time boundaries
                val now = System.currentTimeMillis()
                val todayStart = getStartOfDay()
                val weekStart = getStartOfWeek()
                val monthStart = getStartOfMonth()

                // Fetch all analytics data
                val totalCalls = callDao.getCallCount()
                val incomingCalls = callDao.getIncomingCallCount()
                val outgoingCalls = callDao.getOutgoingCallCount()
                val missedCalls = callDao.getMissedCallCount()
                val answeredCalls = callDao.getAnsweredCallCount()
                val totalDuration = callDao.getTotalCallDuration() ?: 0L
                val averageDuration = callDao.getAverageCallDuration()?.toLong() ?: 0L
                val longestCall = callDao.getLongestCallDuration() ?: 0L

                // Time-based analytics
                val callsToday = callDao.getCallCountSince(todayStart)
                val callsThisWeek = callDao.getCallCountSince(weekStart)
                val callsThisMonth = callDao.getCallCountSince(monthStart)
                val durationToday = callDao.getTotalDurationSince(todayStart) ?: 0L
                val durationThisWeek = callDao.getTotalDurationSince(weekStart) ?: 0L
                val durationThisMonth = callDao.getTotalDurationSince(monthStart) ?: 0L

                _analytics.value = CallAnalytics(
                    totalCalls = totalCalls,
                    incomingCalls = incomingCalls,
                    outgoingCalls = outgoingCalls,
                    missedCalls = missedCalls,
                    answeredCalls = answeredCalls,
                    totalDurationMs = totalDuration,
                    averageDurationMs = averageDuration,
                    longestCallMs = longestCall,
                    callsToday = callsToday,
                    callsThisWeek = callsThisWeek,
                    callsThisMonth = callsThisMonth,
                    durationToday = durationToday,
                    durationThisWeek = durationThisWeek,
                    durationThisMonth = durationThisMonth
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading analytics", e)
                // Keep current state on error
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun getStartOfDay(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getStartOfWeek(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getStartOfMonth(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
