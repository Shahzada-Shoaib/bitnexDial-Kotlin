package com.bitnextechnologies.bitnexdial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitnextechnologies.bitnexdial.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Foreground service for recording phone calls.
 *
 * Note: Call recording has legal restrictions in many jurisdictions.
 * Ensure compliance with local laws and always inform all parties that the call is being recorded.
 *
 * Recording limitations on Android:
 * - Android 9+: Can only record from microphone (not the remote party's audio on most devices)
 * - Some OEMs provide additional APIs for full call recording
 * - VoIP calls (like this app) can potentially access both audio streams
 */
@AndroidEntryPoint
class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "call_recording_channel"

        const val ACTION_START_RECORDING = "com.bitnextechnologies.bitnexdial.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.bitnextechnologies.bitnexdial.STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.bitnextechnologies.bitnexdial.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.bitnextechnologies.bitnexdial.RESUME_RECORDING"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CONTACT_NAME = "contact_name"

        fun startRecording(context: Context, callId: String, phoneNumber: String, contactName: String?) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
            context.startForegroundService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }

        fun pauseRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_PAUSE_RECORDING
            }
            context.startService(intent)
        }

        fun resumeRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_RESUME_RECORDING
            }
            context.startService(intent)
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var isRecording = false
    private var isPaused = false
    private var currentCallId: String? = null
    private var currentPhoneNumber: String? = null
    private var currentContactName: String? = null
    private var recordingStartTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return START_NOT_STICKY
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
                startRecording(callId, phoneNumber, contactName)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
                stopSelf()
            }
            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }
            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording(callId: String, phoneNumber: String, contactName: String?) {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring start request")
            return
        }

        currentCallId = callId
        currentPhoneNumber = phoneNumber
        currentContactName = contactName

        try {
            // Create recordings directory
            val recordingsDir = File(filesDir, "recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            // Generate filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cleanNumber = phoneNumber.replace(Regex("[^\\d]"), "")
            val filename = "call_${cleanNumber}_$timestamp.m4a"
            currentRecordingFile = File(recordingsDir, filename)

            // Configure MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // Use VOICE_COMMUNICATION for VoIP calls - may capture both sides
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentRecordingFile?.absolutePath)

                prepare()
                start()
            }

            isRecording = true
            isPaused = false
            recordingStartTime = System.currentTimeMillis()

            // Start foreground with notification
            startForeground(NOTIFICATION_ID, createNotification())

            Log.d(TAG, "Recording started: ${currentRecordingFile?.absolutePath}")

            // Broadcast recording started (explicit broadcast for Android 13+ compatibility)
            sendBroadcast(Intent("com.bitnextechnologies.bitnexdial.RECORDING_STARTED").apply {
                setPackage(packageName)
                putExtra("call_id", callId)
                putExtra("file_path", currentRecordingFile?.absolutePath)
            })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording, ignoring stop request")
            return
        }

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            isPaused = false

            val duration = System.currentTimeMillis() - recordingStartTime

            Log.d(TAG, "Recording stopped: ${currentRecordingFile?.absolutePath}, duration: ${duration}ms")

            // Broadcast recording completed (explicit broadcast for Android 13+ compatibility)
            sendBroadcast(Intent("com.bitnextechnologies.bitnexdial.RECORDING_COMPLETED").apply {
                setPackage(packageName)
                putExtra("call_id", currentCallId)
                putExtra("phone_number", currentPhoneNumber)
                putExtra("contact_name", currentContactName)
                putExtra("file_path", currentRecordingFile?.absolutePath)
                putExtra("duration_ms", duration)
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            releaseRecorder()
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused = true
                updateNotification()
                Log.d(TAG, "Recording paused")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing recording", e)
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                isPaused = false
                updateNotification()
                Log.d(TAG, "Recording resumed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
        }
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder", e)
        }
        mediaRecorder = null
        isRecording = false
        isPaused = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when a call is being recorded"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val title = if (isPaused) "Recording Paused" else "Recording Call"
        val text = currentContactName ?: currentPhoneNumber ?: "Unknown"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
        releaseRecorder()
    }
}
