package com.bitnextechnologies.bitnexdial.presentation.call

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.data.sip.SipCallState
import com.bitnextechnologies.bitnexdial.data.sip.SipEngine
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexDialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexGreen
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexRed
import com.bitnextechnologies.bitnexdial.service.fcm.PushCallService
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
    }

    @Inject
    lateinit var sipCallManager: SipCallManager

    @Inject
    lateinit var sipEngine: SipEngine

    @Inject
    lateinit var apiContactRepository: IContactRepository

    @Inject
    lateinit var deviceContactRepository: ContactRepository

    private var callId: String = ""
    private var callerNumber: String = ""
    private var callerName: String? = null
    private var resolvedCallerName = mutableStateOf<String?>(null)

    private val callCancelledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cancelledCallId = intent?.getStringExtra(Constants.EXTRA_CALL_ID)
            Log.d(TAG, "Call cancelled broadcast received: $cancelledCallId")
            // Close on any call cancelled event
            dismissAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow showing over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Get call info from intent
        callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: "1"
        callerNumber = intent.getStringExtra(Constants.EXTRA_CALLER_NUMBER) ?: ""
        callerName = intent.getStringExtra(Constants.EXTRA_CALLER_NAME)
        resolvedCallerName.value = callerName

        Log.d(TAG, "IncomingCallActivity started for: $callerNumber (callId=$callId)")

        // Handle action from notification
        when (intent.action) {
            Constants.ACTION_ANSWER_CALL -> {
                answerCall()
                return
            }
            Constants.ACTION_DECLINE_CALL -> {
                declineCall()
                return
            }
        }

        // Look up contact name if not provided
        if (callerName.isNullOrBlank() && callerNumber.isNotBlank()) {
            lifecycleScope.launch {
                try {
                    // Try API contacts first
                    val apiContact = withContext(Dispatchers.IO) {
                        apiContactRepository.getContactByPhoneNumber(callerNumber)
                    }
                    val displayName = apiContact?.displayName
                        // Fall back to device contacts
                        ?: deviceContactRepository.getContactName(callerNumber)
                    if (!displayName.isNullOrBlank()) {
                        resolvedCallerName.value = displayName
                        callerName = displayName
                        Log.d(TAG, "Resolved caller name: $displayName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error looking up contact name", e)
                }
            }
        }

        // Register for call cancelled broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callCancelledReceiver, IntentFilter(Constants.ACTION_CALL_CANCELLED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callCancelledReceiver, IntentFilter(Constants.ACTION_CALL_CANCELLED))
        }

        // Listen to SIP call state to detect remote hangup
        observeCallState()

        setContent {
            BitNexDialTheme {
                val displayName by resolvedCallerName
                IncomingCallScreen(
                    callerNumber = callerNumber,
                    callerName = displayName,
                    onAnswer = { answerCall() },
                    onDecline = { declineCall() }
                )
            }
        }
    }

    /**
     * Observe SIP call state to detect when the remote party hangs up
     * This ensures the UI closes even if the call ends remotely
     *
     * IMPORTANT: We use SipCallManager's state flow instead of setting a callback
     * on SipEngine directly, because SipEngine only supports one callback and
     * SipCallManager needs it for call management.
     */
    private fun observeCallState() {
        // Listen for call state changes from SipCallManager's flow
        lifecycleScope.launch {
            sipCallManager.currentCallState.collectLatest { state ->
                Log.d(TAG, "Call state changed: $state")
                when (state) {
                    SipCallState.DISCONNECTED, SipCallState.FAILED, SipCallState.REJECTED -> {
                        Log.d(TAG, "Call ended ($state), closing incoming call UI")
                        dismissAndFinish()
                    }
                    else -> { /* Keep showing UI */ }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(callCancelledReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun dismissAndFinish() {
        // Dismiss notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PushCallService.NOTIFICATION_ID_INCOMING_CALL)
        finish()
    }

    private fun answerCall() {
        // Dismiss notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PushCallService.NOTIFICATION_ID_INCOMING_CALL)

        // Answer the call
        sipCallManager.answerCall(callId)

        // Start InCallActivity
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(Constants.EXTRA_CALLER_NAME, callerName)
            putExtra(Constants.EXTRA_IS_INCOMING, true)
        }
        startActivity(intent)
        finish()
    }

    private fun declineCall() {
        Log.d(TAG, "=== declineCall() called ===")
        Log.d(TAG, "callId: $callId")
        Log.d(TAG, "sipCallManager initialized: ${::sipCallManager.isInitialized}")

        // Dismiss notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PushCallService.NOTIFICATION_ID_INCOMING_CALL)

        // Reject the call
        try {
            Log.d(TAG, "Calling sipCallManager.rejectCall($callId)")
            sipCallManager.rejectCall(callId)
            Log.d(TAG, "sipCallManager.rejectCall completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in rejectCall", e)
        }
        finish()
    }
}

@Composable
fun IncomingCallScreen(
    callerNumber: String,
    callerName: String?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1A1A2E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Caller info
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = PhoneNumberUtils.getInitialsFromNumber(callerNumber),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = callerName ?: PhoneNumberUtils.formatForDisplay(callerNumber),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (callerName != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = PhoneNumberUtils.formatForDisplay(callerNumber),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Incoming Call",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Answer/Decline buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Decline button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FloatingActionButton(
                        onClick = onDecline,
                        modifier = Modifier.size(72.dp),
                        containerColor = BitNexRed,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Decline",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Answer button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FloatingActionButton(
                        onClick = onAnswer,
                        modifier = Modifier.size(72.dp),
                        containerColor = BitNexGreen,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Answer",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Answer",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
