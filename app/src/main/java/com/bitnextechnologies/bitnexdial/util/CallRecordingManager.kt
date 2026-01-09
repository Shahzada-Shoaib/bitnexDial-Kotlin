package com.bitnextechnologies.bitnexdial.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.bitnextechnologies.bitnexdial.service.CallRecordingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for call recording functionality.
 * Provides state tracking and control methods for recording calls.
 */
@Singleton
class CallRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CallRecordingManager"
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _currentRecordingPath = MutableStateFlow<String?>(null)
    val currentRecordingPath: StateFlow<String?> = _currentRecordingPath.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private var recordingReceiver: BroadcastReceiver? = null

    init {
        registerRecordingReceiver()
    }

    /**
     * Start recording a call.
     * @param callId Unique identifier for the call
     * @param phoneNumber Phone number of the other party
     * @param contactName Contact name if available
     */
    fun startRecording(callId: String, phoneNumber: String, contactName: String? = null) {
        Log.d(TAG, "Starting recording for call: $callId")
        CallRecordingService.startRecording(context, callId, phoneNumber, contactName)
        _isRecording.value = true
        _isPaused.value = false
    }

    /**
     * Stop the current recording.
     */
    fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        CallRecordingService.stopRecording(context)
        _isRecording.value = false
        _isPaused.value = false
    }

    /**
     * Pause the current recording (Android N+ only).
     */
    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "Pausing recording")
            CallRecordingService.pauseRecording(context)
            _isPaused.value = true
        }
    }

    /**
     * Resume a paused recording (Android N+ only).
     */
    fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "Resuming recording")
            CallRecordingService.resumeRecording(context)
            _isPaused.value = false
        }
    }

    /**
     * Toggle recording state.
     */
    fun toggleRecording(callId: String, phoneNumber: String, contactName: String? = null) {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording(callId, phoneNumber, contactName)
        }
    }

    /**
     * Get list of all recordings.
     */
    fun getRecordings(): List<RecordingInfo> {
        val recordingsDir = File(context.filesDir, "recordings")
        if (!recordingsDir.exists()) return emptyList()

        return recordingsDir.listFiles()
            ?.filter { it.extension == "m4a" }
            ?.map { file ->
                // Parse filename: call_PHONENUMBER_YYYYMMDD_HHMMSS.m4a
                val parts = file.nameWithoutExtension.split("_")
                val phoneNumber = if (parts.size >= 2) parts[1] else "Unknown"
                val dateStr = if (parts.size >= 3) parts[2] else ""
                val timeStr = if (parts.size >= 4) parts[3] else ""

                RecordingInfo(
                    filePath = file.absolutePath,
                    phoneNumber = phoneNumber,
                    timestamp = file.lastModified(),
                    durationMs = 0, // Could be determined by reading the file
                    fileSize = file.length()
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Delete a recording.
     */
    fun deleteRecording(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording: $filePath", e)
            false
        }
    }

    /**
     * Get recordings directory size in bytes.
     */
    fun getRecordingsSize(): Long {
        val recordingsDir = File(context.filesDir, "recordings")
        if (!recordingsDir.exists()) return 0

        return recordingsDir.listFiles()
            ?.sumOf { it.length() }
            ?: 0
    }

    /**
     * Delete all recordings.
     */
    fun deleteAllRecordings(): Boolean {
        return try {
            val recordingsDir = File(context.filesDir, "recordings")
            if (recordingsDir.exists()) {
                recordingsDir.listFiles()?.forEach { it.delete() }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all recordings", e)
            false
        }
    }

    private fun registerRecordingReceiver() {
        recordingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.bitnextechnologies.bitnexdial.RECORDING_STARTED" -> {
                        _isRecording.value = true
                        _currentRecordingPath.value = intent.getStringExtra("file_path")
                        Log.d(TAG, "Recording started: ${_currentRecordingPath.value}")
                    }
                    "com.bitnextechnologies.bitnexdial.RECORDING_COMPLETED" -> {
                        _isRecording.value = false
                        _isPaused.value = false
                        _recordingDuration.value = intent.getLongExtra("duration_ms", 0)
                        Log.d(TAG, "Recording completed, duration: ${_recordingDuration.value}ms")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.bitnextechnologies.bitnexdial.RECORDING_STARTED")
            addAction("com.bitnextechnologies.bitnexdial.RECORDING_COMPLETED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(recordingReceiver, filter)
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        try {
            recordingReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        recordingReceiver = null
    }
}

/**
 * Data class representing a recording file.
 */
data class RecordingInfo(
    val filePath: String,
    val phoneNumber: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileSize: Long
)
