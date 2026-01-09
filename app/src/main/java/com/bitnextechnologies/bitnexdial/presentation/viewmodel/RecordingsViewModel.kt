package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.util.CallRecordingManager
import com.bitnextechnologies.bitnexdial.util.RecordingInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "RecordingsViewModel"

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val callRecordingManager: CallRecordingManager
) : ViewModel() {

    private val _recordings = MutableStateFlow<List<RecordingInfo>>(emptyList())
    val recordings: StateFlow<List<RecordingInfo>> = _recordings.asStateFlow()

    private val _currentlyPlaying = MutableStateFlow<String?>(null)
    val currentlyPlaying: StateFlow<String?> = _currentlyPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun loadRecordings() {
        _recordings.value = callRecordingManager.getRecordings()
        Log.d(TAG, "Loaded ${_recordings.value.size} recordings")
    }

    fun togglePlayback(filePath: String) {
        if (_currentlyPlaying.value == filePath && _isPlaying.value) {
            // Pause current playback
            pausePlayback()
        } else if (_currentlyPlaying.value == filePath) {
            // Resume paused playback
            resumePlayback()
        } else {
            // Start new playback
            startPlayback(filePath)
        }
    }

    private fun startPlayback(filePath: String) {
        // Stop any existing playback
        stopPlayback()

        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Recording file not found: $filePath")
                return
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()

                setOnCompletionListener {
                    stopPlayback()
                }
            }

            _currentlyPlaying.value = filePath
            _isPlaying.value = true
            startProgressTracking()

            Log.d(TAG, "Started playback: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            stopPlayback()
        }
    }

    private fun pausePlayback() {
        try {
            mediaPlayer?.pause()
            _isPlaying.value = false
            progressJob?.cancel()
            Log.d(TAG, "Paused playback")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing playback", e)
        }
    }

    private fun resumePlayback() {
        try {
            mediaPlayer?.start()
            _isPlaying.value = true
            startProgressTracking()
            Log.d(TAG, "Resumed playback")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming playback", e)
        }
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
        }

        mediaPlayer = null
        _currentlyPlaying.value = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive && mediaPlayer != null && _isPlaying.value) {
                try {
                    val player = mediaPlayer ?: break
                    val duration = player.duration.toFloat()
                    val position = player.currentPosition.toFloat()
                    if (duration > 0) {
                        _playbackProgress.value = position / duration
                    }
                } catch (e: Exception) {
                    break
                }
                delay(100)
            }
        }
    }

    fun deleteRecording(filePath: String) {
        // Stop if this recording is playing
        if (_currentlyPlaying.value == filePath) {
            stopPlayback()
        }

        val success = callRecordingManager.deleteRecording(filePath)
        if (success) {
            loadRecordings()
            Log.d(TAG, "Deleted recording: $filePath")
        } else {
            Log.e(TAG, "Failed to delete recording: $filePath")
        }
    }

    fun deleteAllRecordings() {
        stopPlayback()
        val success = callRecordingManager.deleteAllRecordings()
        if (success) {
            _recordings.value = emptyList()
            Log.d(TAG, "Deleted all recordings")
        } else {
            Log.e(TAG, "Failed to delete all recordings")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
