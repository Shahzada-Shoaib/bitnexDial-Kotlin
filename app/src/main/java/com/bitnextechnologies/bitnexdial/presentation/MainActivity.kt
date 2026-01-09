package com.bitnextechnologies.bitnexdial.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.bitnextechnologies.bitnexdial.data.preferences.AppLockPreferences
import com.bitnextechnologies.bitnexdial.presentation.call.InCallActivity
import com.bitnextechnologies.bitnexdial.presentation.navigation.AppNavigation
import com.bitnextechnologies.bitnexdial.presentation.screens.LockScreen
import com.bitnextechnologies.bitnexdial.presentation.theme.BitNexDialTheme
import com.bitnextechnologies.bitnexdial.presentation.viewmodel.MainViewModel
import com.bitnextechnologies.bitnexdial.service.SipForegroundService
import com.bitnextechnologies.bitnexdial.util.AutoStartHelper
import com.bitnextechnologies.bitnexdial.util.BiometricHelper
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MainActivity"

/**
 * Main activity for the BitNex Dial app.
 *
 * Note: SIP WebView is now managed by SipForegroundService for persistent
 * background connection. This allows receiving calls even when the app is closed.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var appLockPreferences: AppLockPreferences

    // Auto-start dialog state
    private var showAutoStartDialog = mutableStateOf(false)
    private var showBatteryOptDialog = mutableStateOf(false)

    // App lock state
    private var isAppLocked = mutableStateOf(false)
    private var lockErrorMessage = mutableStateOf<String?>(null)

    companion object {
        private const val PREFS_NAME = "auto_start_prefs"
        private const val KEY_AUTO_START_PROMPTED = "auto_start_prompted"
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Only start SIP service if user is logged in
            startSipServiceIfLoggedIn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request permissions
        if (!hasRequiredPermissions()) {
            requestPermissions()
        }

        // Check for manufacturer-specific auto-start settings
        checkAutoStartPermissions()

        // Check for battery optimization exemption
        checkBatteryOptimization()

        // Observe call UI events to launch InCallActivity
        observeCallUiEvents()

        // Observe login state to start SIP service when logged in
        observeLoginState()

        // Check if app should be locked on start
        checkAppLock()

        setContent {
            // Collect theme preferences from ViewModel
            val darkModeEnabled by viewModel.darkModeEnabled.collectAsState()
            val useSystemTheme by viewModel.useSystemTheme.collectAsState()

            // null = use system theme, true/false = force dark/light
            val forceDarkMode = if (useSystemTheme) null else darkModeEnabled

            BitNexDialTheme(useDarkTheme = forceDarkMode) {
                val navController = rememberNavController()
                val isLoggedIn by viewModel.isLoggedIn.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Show lock screen if app is locked
                    if (isAppLocked.value && isLoggedIn) {
                        LockScreen(
                            onUnlockClick = { promptBiometric() },
                            onEmergencyLogout = { emergencyLogout() },
                            errorMessage = lockErrorMessage.value
                        )
                    } else if (isLoading) {
                        LoadingScreen()
                    } else {
                        // Collect badge counts for WhatsApp-style navigation badges
                        val missedCallCount by viewModel.missedCallCount.collectAsState()
                        val unreadMessageCount by viewModel.unreadMessageCount.collectAsState()

                        // Observe conversation navigation events
                        LaunchedEffect(Unit) {
                            viewModel.conversationNavigationEvent.collect { event ->
                                navController.navigate(
                                    com.bitnextechnologies.bitnexdial.presentation.navigation.Screen.Conversation.createRoute(
                                        event.conversationId,
                                        event.contactName
                                    )
                                )
                            }
                        }

                        AppNavigation(
                            navController = navController,
                            isLoggedIn = isLoggedIn,
                            missedCallCount = missedCallCount,
                            unreadMessageCount = unreadMessageCount,
                            onMakeCall = { phoneNumber, contactName ->
                                viewModel.makeCall(phoneNumber, contactName)
                            },
                            onLoginSuccess = {
                                // Trigger sync and SIP initialization after login
                                viewModel.onLoginSuccess()
                            }
                        )
                    }

                    // Auto-start permission dialog
                    if (showAutoStartDialog.value) {
                        AutoStartDialog(
                            manufacturer = AutoStartHelper.getManufacturerName(),
                            onConfirm = {
                                showAutoStartDialog.value = false
                                markAutoStartPrompted()
                                AutoStartHelper.openAutoStartSettings(this@MainActivity)
                            },
                            onDismiss = {
                                showAutoStartDialog.value = false
                                markAutoStartPrompted()
                            }
                        )
                    }

                    // Battery optimization dialog
                    if (showBatteryOptDialog.value) {
                        BatteryOptimizationDialog(
                            onConfirm = {
                                showBatteryOptDialog.value = false
                                markBatteryOptPrompted()
                                AutoStartHelper.requestBatteryOptimizationExemption(this@MainActivity)
                            },
                            onDismiss = {
                                showBatteryOptDialog.value = false
                                markBatteryOptPrompted()
                            }
                        )
                    }
                }
            }
        }

        // Handle incoming intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            // Handle conversation navigation from message notifications
            if (it.action == "OPEN_CONVERSATION") {
                val conversationId = it.getStringExtra("conversation_id")
                val contactName = it.getStringExtra("contact_name")
                if (!conversationId.isNullOrBlank()) {
                    viewModel.navigateToConversation(conversationId, contactName)
                    return
                }
            }

            // Handle tab navigation from notifications
            val tab = it.getStringExtra("tab")
            tab?.let { tabName ->
                viewModel.navigateToTab(tabName)
            }

            // Handle dial intent
            val phoneNumber = it.data?.schemeSpecificPart
            phoneNumber?.let { number ->
                viewModel.setDialerNumber(number)
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return permissionManager.hasAllRequiredPermissions()
    }

    private fun requestPermissions() {
        val permissionsToRequest = permissionManager.getMissingPermissions().toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun startSipService() {
        // Use window.decorView.post to ensure UI is fully rendered before starting service
        // This is the proper way to defer work until after the first frame is drawn
        window.decorView.post {
            lifecycleScope.launch {
                try {
                    SipForegroundService.start(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start SIP service", e)
                }
            }
        }
    }

    private fun startSipServiceIfLoggedIn() {
        lifecycleScope.launch {
            if (viewModel.isLoggedIn.value) {
                startSipService()
            }
        }
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            viewModel.isLoggedIn.collect { isLoggedIn ->
                if (isLoggedIn && hasRequiredPermissions()) {
                    Log.d(TAG, "User logged in with permissions, starting SIP service")
                    startSipService()
                }
            }
        }
    }

    private fun observeCallUiEvents() {
        lifecycleScope.launch {
            viewModel.callUiEvent.collect { event ->
                Log.d(TAG, "observeCallUiEvents: Received call UI event - callId=${event.callId}, phone=${event.phoneNumber}, contactName=${event.contactName}")
                launchInCallActivity(event.callId, event.phoneNumber, event.isIncoming, event.contactName)
            }
        }
    }

    private fun launchInCallActivity(callId: String, phoneNumber: String, isIncoming: Boolean, contactName: String? = null) {
        val intent = Intent(this, InCallActivity::class.java).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, phoneNumber)
            putExtra(Constants.EXTRA_IS_INCOMING, isIncoming)
            putExtra(Constants.EXTRA_CALLER_NAME, contactName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Check if app should be locked when returning from background
        checkAppLock()
    }

    override fun onPause() {
        super.onPause()
        // Record when app went to background for lock timeout calculation
        lifecycleScope.launch {
            appLockPreferences.setLastBackgroundTime(System.currentTimeMillis())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // SIP service continues running in background
        Log.d(TAG, "MainActivity destroyed - SIP service continues in background")
    }

    /**
     * Check if app should be locked based on settings and timeout
     */
    private fun checkAppLock() {
        lifecycleScope.launch {
            val enabled = appLockPreferences.appLockEnabled.first()
            if (enabled) {
                val shouldLock = appLockPreferences.shouldLock()
                if (shouldLock) {
                    isAppLocked.value = true
                    // Auto-prompt authentication when app is locked (if any method available)
                    if (BiometricHelper.isAnyAuthAvailable(this@MainActivity)) {
                        promptBiometric()
                    }
                }
            } else {
                isAppLocked.value = false
            }
        }
    }

    /**
     * Show authentication prompt (biometric or device credential)
     */
    private fun promptBiometric() {
        lockErrorMessage.value = null
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock BitNex Dial",
            subtitle = if (BiometricHelper.isBiometricAvailable(this))
                "Use fingerprint to unlock"
            else
                "Use your PIN, pattern, or password",
            negativeButtonText = "Cancel",
            onSuccess = {
                isAppLocked.value = false
                lifecycleScope.launch {
                    appLockPreferences.setLocked(false)
                }
            },
            onError = { error ->
                lockErrorMessage.value = error
            },
            onFailed = {
                lockErrorMessage.value = "Authentication failed. Try again."
            }
        )
    }

    /**
     * Emergency logout for users who are locked out without any authentication method.
     * Disables app lock and logs out the user.
     */
    private fun emergencyLogout() {
        lifecycleScope.launch {
            // Disable app lock
            appLockPreferences.setAppLockEnabled(false)
            appLockPreferences.setLocked(false)
            isAppLocked.value = false

            // Logout the user
            viewModel.onLogout()

            Log.d(TAG, "Emergency logout completed - app lock disabled and user logged out")
        }
    }

    /**
     * Check if we need to prompt for auto-start permissions on devices with aggressive battery management
     */
    private fun checkAutoStartPermissions() {
        val manufacturer = AutoStartHelper.getManufacturerName()
        Log.d(TAG, "Checking auto-start permissions for manufacturer: $manufacturer")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean(KEY_AUTO_START_PROMPTED, false)

        // For Infinix/Transsion devices, always show on first launch
        val isTranssion = manufacturer.lowercase().let {
            it.contains("infinix") || it.contains("tecno") || it.contains("itel") || it.contains("transsion")
        }

        if (!alreadyPrompted && (AutoStartHelper.hasAutoStartManager(this) || isTranssion)) {
            Log.d(TAG, "Device has auto-start manager or is Transsion brand, showing prompt")
            showAutoStartDialog.value = true
        } else {
            Log.d(TAG, "No auto-start manager found or already prompted")
        }
    }

    /**
     * Show auto-start dialog again (can be called from settings)
     */
    fun showAutoStartDialogAgain() {
        // Reset the prompted flag and show dialog
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START_PROMPTED, false)
            .putBoolean(KEY_BATTERY_OPT_PROMPTED, false)
            .apply()
        showAutoStartDialog.value = true
    }

    /**
     * Check if we need to request battery optimization exemption
     */
    private fun checkBatteryOptimization() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)

        if (!alreadyPrompted && AutoStartHelper.needsBatteryOptimizationExemption(this)) {
            Log.d(TAG, "App needs battery optimization exemption, showing prompt")
            // Delay showing this dialog to not overwhelm the user
            window.decorView.postDelayed({
                if (!showAutoStartDialog.value) {
                    showBatteryOptDialog.value = true
                }
            }, 1500)
        }
    }

    private fun markAutoStartPrompted() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START_PROMPTED, true)
            .apply()
    }

    private fun markBatteryOptPrompted() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BATTERY_OPT_PROMPTED, true)
            .apply()
    }
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun AutoStartDialog(
    manufacturer: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isInfinix = manufacturer.lowercase().contains("infinix") ||
                    manufacturer.lowercase().contains("tecno") ||
                    manufacturer.lowercase().contains("itel")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Auto-Start") },
        text = {
            Column {
                if (isInfinix) {
                    Text(
                        "Your $manufacturer device requires these steps to receive calls after restart:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("1. Open Phone Master app")
                    Text("2. Go to 'Permission Manager' or 'App Management'")
                    Text("3. Find 'Auto-start' and enable BitNex Dial")
                    Text("4. Go to 'Battery' > 'App launch' and set to 'Manual'")
                    Text("5. Enable all toggles for BitNex Dial")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Also: Lock app in recents by swiping down on the app card",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Your $manufacturer device has aggressive battery optimization that may prevent this app from receiving calls after a restart."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Please enable auto-start permission for BitNex Dial to ensure you don't miss any calls."
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}

@Composable
fun BatteryOptimizationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    com.bitnextechnologies.bitnexdial.presentation.components.ConfirmationDialog(
        title = "Disable Battery Optimization",
        message = "To receive calls reliably, this app needs to be excluded from battery optimization.",
        secondaryMessage = "This ensures the app stays connected and can receive incoming calls even when your phone is idle.",
        confirmButtonText = "Allow",
        dismissButtonText = "Later",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
