package com.bitnextechnologies.bitnexdial.presentation.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallStatus
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexDialTheme
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexGreen
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexRed
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class InCallActivity : ComponentActivity() {

    companion object {
        private const val TAG = "InCallActivity"
    }

    private val viewModel: InCallViewModel by viewModels()

    // Proximity wake lock to turn off screen when phone is near ear
    private var proximityWakeLock: PowerManager.WakeLock? = null

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

        // Keep screen on during call
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize proximity wake lock to turn off screen when phone is near ear
        initProximityWakeLock()

        // Handle back button - move to background instead of ending call
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Move activity to background instead of finishing
                // User can return via notification or recent apps
                moveTaskToBack(true)
            }
        })

        // Get call info from intent
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: ""
        val phoneNumber = intent.getStringExtra(Constants.EXTRA_CALLER_NUMBER) ?: ""
        val isIncoming = intent.getBooleanExtra(Constants.EXTRA_IS_INCOMING, false)
        val contactName = intent.getStringExtra(Constants.EXTRA_CALLER_NAME)

        viewModel.setCallInfo(callId, phoneNumber, isIncoming, contactName)

        setContent {
            BitNexDialTheme {
                val callStatus by viewModel.callStatus.collectAsState()
                InCallScreen(
                    viewModel = viewModel,
                    onEndCall = {
                        // Only call endCall if the call is still active
                        if (callStatus != CallStatus.DISCONNECTED && callStatus != CallStatus.FAILED) {
                            viewModel.endCall()
                        }
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProximityWakeLock()
    }

    /**
     * Initialize proximity wake lock to turn off screen when phone is near ear.
     * This provides a professional call experience like standard phone apps.
     */
    @Suppress("DEPRECATION")
    private fun initProximityWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            // Check if device supports proximity wake lock
            if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "BitNexDial:ProximityWakeLock"
                )
                proximityWakeLock?.acquire()
                Log.d(TAG, "Proximity wake lock acquired")
            } else {
                Log.d(TAG, "Proximity wake lock not supported on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize proximity wake lock", e)
        }
    }

    /**
     * Release proximity wake lock when call ends or activity is destroyed
     */
    private fun releaseProximityWakeLock() {
        try {
            proximityWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.d(TAG, "Proximity wake lock released")
                }
            }
            proximityWakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release proximity wake lock", e)
        }
    }
}

@Composable
fun InCallScreen(
    viewModel: InCallViewModel,
    onEndCall: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val phoneNumber by viewModel.phoneNumber.collectAsState()
    val contactName by viewModel.contactName.collectAsState()
    val callStatus by viewModel.callStatus.collectAsState()
    val callDuration by viewModel.callDuration.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()
    val isOnHold by viewModel.isOnHold.collectAsState()
    val showDialpad by viewModel.showDialpad.collectAsState()

    // Recording states
    val isRecording by viewModel.isRecording.collectAsState()
    val isRecordingPaused by viewModel.isRecordingPaused.collectAsState()

    // Transfer and conference states
    val isTransferMode by viewModel.isTransferMode.collectAsState()
    val transferState by viewModel.transferState.collectAsState()
    val isConferenceMode by viewModel.isConferenceMode.collectAsState()
    val conferenceState by viewModel.conferenceState.collectAsState()

    // Call waiting state
    val waitingCall by viewModel.waitingCall.collectAsState()
    val hasWaitingCall by viewModel.hasWaitingCall.collectAsState()

    // Held call state (for call switching)
    val heldCall by viewModel.heldCall.collectAsState()
    val hasHeldCall by viewModel.hasHeldCall.collectAsState()

    // Dialog states
    var showTransferDialog by remember { mutableStateOf(false) }
    var showAddCallDialog by remember { mutableStateOf(false) }
    var targetNumber by remember { mutableStateOf("") }

    // Auto-close activity when call ends (DISCONNECTED or FAILED)
    LaunchedEffect(callStatus) {
        if (callStatus == CallStatus.DISCONNECTED || callStatus == CallStatus.FAILED) {
            // Brief delay to show the status before closing
            kotlinx.coroutines.delay(800)
            onEndCall()
        }
    }

    // Transfer dialog
    if (showTransferDialog) {
        NumberInputDialog(
            title = "Transfer Call",
            placeholder = "Enter number to transfer to",
            value = targetNumber,
            onValueChange = { targetNumber = it },
            onConfirm = {
                if (targetNumber.isNotBlank()) {
                    viewModel.startTransfer(targetNumber)
                    showTransferDialog = false
                    targetNumber = ""
                }
            },
            onDismiss = {
                showTransferDialog = false
                targetNumber = ""
            }
        )
    }

    // Add call dialog
    if (showAddCallDialog) {
        NumberInputDialog(
            title = "Add Call",
            placeholder = "Enter number to add",
            value = targetNumber,
            onValueChange = { targetNumber = it },
            onConfirm = {
                if (targetNumber.isNotBlank()) {
                    viewModel.startAddCall(targetNumber)
                    showAddCallDialog = false
                    targetNumber = ""
                }
            },
            onDismiss = {
                showAddCallDialog = false
                targetNumber = ""
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1A1A2E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Call waiting banner with answer/decline buttons
            if (hasWaitingCall && waitingCall != null) {
                CallWaitingBanner(
                    callerName = waitingCall?.contactName ?: waitingCall?.phoneNumber ?: "Unknown",
                    callerNumber = waitingCall?.phoneNumber ?: "",
                    onAnswerWaiting = { viewModel.answerWaitingCall() },
                    onDeclineWaiting = { viewModel.declineWaitingCall() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Held call banner with switch/end buttons
            if (hasHeldCall && heldCall != null) {
                HeldCallBanner(
                    callerName = heldCall?.contactName ?: heldCall?.phoneNumber ?: "Unknown",
                    callerNumber = heldCall?.phoneNumber ?: "",
                    onSwitch = { viewModel.switchCalls() },
                    onEndHeld = { viewModel.endHeldCall() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recording indicator banner
            if (isRecording) {
                RecordingIndicatorBanner(isPaused = isRecordingPaused)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Caller info
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = PhoneNumberUtils.getInitialsFromNumber(phoneNumber),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = contactName ?: PhoneNumberUtils.formatForDisplay(phoneNumber),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (contactName != null) {
                Text(
                    text = PhoneNumberUtils.formatForDisplay(phoneNumber),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Call status/duration - includes transfer and conference states
            Text(
                text = when {
                    isTransferMode -> when (transferState) {
                        "calling" -> "Calling transfer target..."
                        "ringing" -> "Transfer target ringing..."
                        "connected" -> "Connected - Tap Complete to transfer"
                        "completed" -> "Transfer completed"
                        "failed" -> "Transfer failed"
                        else -> "Transferring..."
                    }
                    isConferenceMode -> when (conferenceState) {
                        "calling" -> "Adding call..."
                        "ringing" -> "New party ringing..."
                        "connected" -> "Connected - Tap Merge"
                        "active" -> "Conference active"
                        "failed" -> "Add call failed"
                        else -> "Conference..."
                    }
                    callStatus == CallStatus.CONNECTING || callStatus == CallStatus.DIALING -> "Connecting..."
                    callStatus == CallStatus.RINGING -> "Ringing..."
                    callStatus == CallStatus.CONNECTED -> formatDuration(callDuration)
                    callStatus == CallStatus.ON_HOLD -> "On Hold"
                    callStatus == CallStatus.FAILED -> "Call Failed"
                    callStatus == CallStatus.DISCONNECTING -> "Ending..."
                    else -> "Call Ended"
                },
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    callStatus == CallStatus.FAILED -> BitNexRed.copy(alpha = 0.9f)
                    transferState == "failed" || conferenceState == "failed" -> BitNexRed.copy(alpha = 0.9f)
                    transferState == "connected" || conferenceState == "connected" -> BitNexGreen.copy(alpha = 0.9f)
                    else -> Color.White.copy(alpha = 0.7f)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            if (showDialpad) {
                // DTMF Dialpad
                InCallDialpad(
                    onDigitClick = { viewModel.sendDtmf(it) }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Transfer mode controls
            if (isTransferMode && (transferState == "connected" || transferState == "ringing")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = Icons.Default.Check,
                        label = "Complete",
                        isActive = true,
                        onClick = { viewModel.completeTransfer() }
                    )
                    CallControlButton(
                        icon = Icons.Default.Close,
                        label = "Cancel",
                        onClick = { viewModel.cancelTransfer() }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Conference mode controls
            if (isConferenceMode && (conferenceState == "connected" || conferenceState == "ringing")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = Icons.Default.CallMerge,
                        label = "Merge",
                        isActive = true,
                        onClick = { viewModel.mergeConference() }
                    )
                    CallControlButton(
                        icon = Icons.Default.Close,
                        label = "Cancel",
                        onClick = { viewModel.endConference() }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Conference active controls
            if (isConferenceMode && conferenceState == "active") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CallControlButton(
                        icon = Icons.Default.GroupRemove,
                        label = "End Conf",
                        onClick = { viewModel.endConference() }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Normal call controls (hide when in transfer/conference mode with active states)
            if (!isTransferMode && !isConferenceMode) {
                // Call controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        isActive = isMuted,
                        onClick = { viewModel.toggleMute() }
                    )

                    CallControlButton(
                        icon = Icons.Default.Dialpad,
                        label = "Keypad",
                        isActive = showDialpad,
                        onClick = { viewModel.toggleDialpad() }
                    )

                    CallControlButton(
                        icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        label = "Speaker",
                        isActive = isSpeaker,
                        onClick = { viewModel.toggleSpeaker() }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (isOnHold) "Resume" else "Hold",
                        isActive = isOnHold,
                        onClick = { viewModel.toggleHold() }
                    )

                    CallControlButton(
                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        label = if (isRecording) "Stop Rec" else "Record",
                        isActive = isRecording,
                        activeColor = BitNexRed,
                        onClick = { viewModel.toggleRecording() }
                    )

                    CallControlButton(
                        icon = Icons.Default.PersonAdd,
                        label = "Add Call",
                        onClick = { showAddCallDialog = true }
                    )

                    CallControlButton(
                        icon = Icons.Default.SwapCalls,
                        label = "Transfer",
                        onClick = { showTransferDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // End call button
            FloatingActionButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEndCall()
                },
                modifier = Modifier.size(72.dp),
                containerColor = BitNexRed,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun NumberInputDialog(
    title: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Call")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean = false,
    activeColor: Color? = null,
    onClick: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val activeContainerColor = activeColor ?: MaterialTheme.colorScheme.primary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isActive)
                    activeContainerColor
                else
                    Color.White.copy(alpha = 0.2f),
                contentColor = if (isActive)
                    Color.White
                else
                    Color.White
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive && activeColor != null) activeColor else Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun InCallDialpad(
    onDigitClick: (String) -> Unit
) {
    // DTMF keypad layout with letters (matches web dialer)
    val dialpadKeys = listOf(
        listOf(
            DialpadKey("1", ""),
            DialpadKey("2", "ABC"),
            DialpadKey("3", "DEF")
        ),
        listOf(
            DialpadKey("4", "GHI"),
            DialpadKey("5", "JKL"),
            DialpadKey("6", "MNO")
        ),
        listOf(
            DialpadKey("7", "PQRS"),
            DialpadKey("8", "TUV"),
            DialpadKey("9", "WXYZ")
        ),
        listOf(
            DialpadKey("*", ""),
            DialpadKey("0", "+"),
            DialpadKey("#", "")
        )
    )

    // Track entered digits
    var enteredDigits by remember { mutableStateOf("") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display entered DTMF digits
        if (enteredDigits.isNotEmpty()) {
            Text(
                text = enteredDigits,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        dialpadKeys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = {
                            enteredDigits += key.digit
                            onDigitClick(key.digit)
                        },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = key.digit,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (key.letters.isNotEmpty()) {
                                Text(
                                    text = key.letters,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private data class DialpadKey(
    val digit: String,
    val letters: String
)

/**
 * Banner showing a call on hold while talking to another caller
 * Includes switch and end buttons for call management
 */
@Composable
fun HeldCallBanner(
    callerName: String,
    callerNumber: String,
    onSwitch: () -> Unit = {},
    onEndHeld: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhonePaused,
                        contentDescription = "On Hold",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "On Hold",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = callerName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (callerNumber.isNotBlank() && callerNumber != callerName) {
                            Text(
                                text = PhoneNumberUtils.formatForDisplay(callerNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                // Pause indicator
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = "Paused",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Switch/End buttons for held call
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // End held call button
                FilledTonalButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEndHeld()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BitNexRed.copy(alpha = 0.2f),
                        contentColor = BitNexRed
                    ),
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("End")
                }

                // Switch to held call button
                FilledTonalButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSwitch()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapCalls,
                        contentDescription = "Switch",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Switch")
                }
            }
        }
    }
}

/**
 * Banner showing an incoming call waiting while on another call
 * Includes answer and decline buttons for call waiting interaction
 */
@Composable
fun CallWaitingBanner(
    callerName: String,
    callerNumber: String,
    onAnswerWaiting: () -> Unit = {},
    onDeclineWaiting: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneInTalk,
                        contentDescription = "Call Waiting",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Call Waiting",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = callerName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        if (callerNumber.isNotBlank() && callerNumber != callerName) {
                            Text(
                                text = PhoneNumberUtils.formatForDisplay(callerNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                // Pulsing indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(BitNexGreen)
                )
            }

            // Answer/Decline buttons for call waiting
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Decline waiting call button
                FilledTonalButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDeclineWaiting()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BitNexRed.copy(alpha = 0.2f),
                        contentColor = BitNexRed
                    ),
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Decline",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Decline")
                }

                // Answer waiting call (hold current) button
                FilledTonalButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnswerWaiting()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BitNexGreen.copy(alpha = 0.2f),
                        contentColor = BitNexGreen
                    ),
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Answer",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Answer")
                }
            }
        }
    }
}

/**
 * Banner showing recording indicator during active call recording.
 * Features a pulsing animation on the recording dot for visual feedback.
 */
@Composable
fun RecordingIndicatorBanner(
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    // Pulsing animation for recording indicator (industry standard UX)
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording_alpha"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isPaused) Color(0xFF444444) else BitNexRed.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.FiberManualRecord,
                contentDescription = if (isPaused) "Recording paused" else "Recording in progress",
                tint = if (isPaused) Color.Gray else Color.White.copy(alpha = alpha),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isPaused) "Recording Paused" else "Recording",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}
