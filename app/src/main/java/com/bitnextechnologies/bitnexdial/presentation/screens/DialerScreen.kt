package com.bitnextechnologies.bitnexdial.presentation.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnextechnologies.bitnexdial.domain.repository.SipRegistrationState
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.DialerViewModel
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import com.bitnextechnologies.bitnexdial.util.SpeedDialEntry

@Composable
fun DialerScreen(
    viewModel: DialerViewModel = hiltViewModel(),
    onMakeCall: (String) -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onAddContact: (String) -> Unit = {}
) {
    val phoneNumber by viewModel.phoneNumber.collectAsState()
    val sipState by viewModel.sipRegistrationState.collectAsState()
    val speedDialEntries by viewModel.speedDialEntries.collectAsState()
    val lastDialedNumber by viewModel.lastDialedNumber.collectAsState()
    val formattedNumber = if (phoneNumber.isNotEmpty()) {
        PhoneNumberUtils.formatForDisplay(phoneNumber)
    } else ""

    // Validate phone number for calling
    val isValidNumber = phoneNumber.isNotEmpty() && PhoneNumberUtils.isValidNumber(phoneNumber)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Double-tap detection for redial
    var lastTapTime by remember { mutableStateOf(0L) }
    val doubleTapThreshold = 300L // milliseconds

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar with contacts button and connection status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateToContacts) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = "Contacts"
                )
            }

            // Title with connection status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Keypad",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                ConnectionStatusIndicator(sipState = sipState)
            }

            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Open Settings"
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Phone number display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formattedNumber.ifEmpty { "Enter number" },
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = if (phoneNumber.length > 12) 28.sp else 36.sp,
                    fontWeight = FontWeight.Light
                ),
                color = if (phoneNumber.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Dialpad
        DialPad(
            onDigitClick = { digit ->
                viewModel.appendDigit(digit)
            },
            onSpeedDial = { number ->
                // Speed dial triggered - make call directly
                if (PhoneNumberUtils.isValidNumber(number)) {
                    onMakeCall(number)
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar("Invalid speed dial number")
                    }
                }
            },
            speedDialEntries = speedDialEntries
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Call button row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add contact button (left side)
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                if (phoneNumber.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onAddContact(phoneNumber)
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add Contact",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Main call button - large and prominent
            // Double-tap when empty = redial last number
            // Single tap with number = make call
            FloatingActionButton(
                onClick = {
                    val currentTime = System.currentTimeMillis()
                    val isDoubleTap = (currentTime - lastTapTime) < doubleTapThreshold
                    lastTapTime = currentTime

                    if (phoneNumber.isEmpty()) {
                        // Double-tap on empty dialpad = restore last dialed number
                        if (isDoubleTap && lastDialedNumber.isNotEmpty()) {
                            viewModel.restoreLastDialedNumber()
                            scope.launch {
                                snackbarHostState.showSnackbar("Last dialed: ${PhoneNumberUtils.formatForDisplay(lastDialedNumber)}")
                            }
                        } else if (!isDoubleTap) {
                            // First tap on empty - hint about double tap
                            scope.launch {
                                if (lastDialedNumber.isNotEmpty()) {
                                    snackbarHostState.showSnackbar("Double-tap to redial")
                                } else {
                                    snackbarHostState.showSnackbar("Enter a phone number")
                                }
                            }
                        }
                    } else if (!isValidNumber) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Please enter a valid phone number")
                        }
                    } else if (viewModel.isOwnNumber(phoneNumber)) {
                        scope.launch {
                            snackbarHostState.showSnackbar("You cannot call your own number")
                        }
                    } else {
                        // Save as last dialed and make call
                        viewModel.saveAsLastDialed(phoneNumber)
                        onMakeCall(phoneNumber)
                        // Clear dialpad after initiating call
                        viewModel.clearNumber()
                    }
                },
                modifier = Modifier.size(80.dp),
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isValidNumber) 1f else 0.5f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 12.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Backspace button (right side) - for quick delete
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                if (phoneNumber.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.deleteLastDigit() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Backspace,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))
    }
    }
}

@Composable
fun DialPad(
    onDigitClick: (String) -> Unit,
    onSpeedDial: (String) -> Unit = {},
    speedDialEntries: Map<String, SpeedDialEntry> = emptyMap()
) {
    val haptic = LocalHapticFeedback.current

    // Memoize dialpad keys to prevent recreation on every recomposition
    val dialPadKeys = remember {
        listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
    }

    // Speed dial eligible digits (2-9)
    val speedDialDigits = remember { setOf("2", "3", "4", "5", "6", "7", "8", "9") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dialPadKeys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { (digit, letters) ->
                    val speedDialEntry = speedDialEntries[digit]
                    val hasSpeedDial = speedDialEntry != null

                    DialPadKey(
                        digit = digit,
                        letters = letters,
                        hasSpeedDial = hasSpeedDial,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDigitClick(digit)
                        },
                        onLongClick = when {
                            // Digit 0 long-press enters "+"
                            digit == "0" -> {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDigitClick("+")
                                }
                            }
                            // Digits 2-9 with speed dial assigned
                            digit in speedDialDigits && hasSpeedDial -> {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSpeedDial(speedDialEntry!!.phoneNumber)
                                }
                            }
                            else -> null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialPadKey(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    hasSpeedDial: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Speed dial indicator dot
        if (hasSpeedDial) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Normal
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/**
 * Connection status indicator showing SIP registration state
 */
@Composable
fun ConnectionStatusIndicator(
    sipState: SipRegistrationState
) {
    // Use theme colors for consistency
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error

    val (color, icon, description) = when (sipState) {
        SipRegistrationState.REGISTERED -> Triple(
            primaryColor,
            Icons.Default.CheckCircle,
            "Connected"
        )
        SipRegistrationState.REGISTERING -> Triple(
            tertiaryColor,
            Icons.Default.Sync,
            "Connecting..."
        )
        SipRegistrationState.UNREGISTERING -> Triple(
            tertiaryColor,
            Icons.Default.Sync,
            "Disconnecting..."
        )
        SipRegistrationState.UNREGISTERED -> Triple(
            outlineColor,
            Icons.Default.CloudOff,
            "Disconnected"
        )
        SipRegistrationState.FAILED -> Triple(
            errorColor,
            Icons.Default.Error,
            "Connection Failed"
        )
        SipRegistrationState.MAX_CONTACTS_REACHED -> Triple(
            errorColor,
            Icons.Default.Error,
            "Max Devices Reached"
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = when (sipState) {
                SipRegistrationState.REGISTERED -> "Online"
                SipRegistrationState.REGISTERING -> "..."
                SipRegistrationState.UNREGISTERING -> "..."
                SipRegistrationState.UNREGISTERED -> "Offline"
                SipRegistrationState.FAILED -> "Error"
                SipRegistrationState.MAX_CONTACTS_REACHED -> "Limit"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}
