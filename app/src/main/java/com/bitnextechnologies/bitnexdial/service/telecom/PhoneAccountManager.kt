package com.bitnextechnologies.bitnexdial.service.telecom

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.bitnextechnologies.bitnexdial.R
import com.bitnextechnologies.bitnexdial.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the PhoneAccount registration with Android Telecom
 * This allows the app to integrate with the native phone system
 */
@Singleton
class PhoneAccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager
) {
    companion object {
        private const val TAG = "PhoneAccountManager"
        private const val PHONE_ACCOUNT_ID = "bitnex_sip_account"
    }

    private val telecomManager: TelecomManager? by lazy {
        try {
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TelecomManager", e)
            null
        }
    }

    private var phoneAccountHandle: PhoneAccountHandle? = null

    /**
     * Check if we have required permissions for Telecom operations
     */
    private fun hasRequiredPermissions(): Boolean {
        return permissionManager.hasPhoneStatePermission()
    }

    /**
     * Register the phone account with TelecomManager
     * Returns true on success, false if failed or permissions not granted
     */
    fun registerPhoneAccount(): Boolean {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Phone permissions not granted, skipping phone account registration")
            return false
        }

        val tm = telecomManager
        if (tm == null) {
            Log.w(TAG, "TelecomManager not available")
            return false
        }

        return try {
            val componentName = ComponentName(context, CallConnectionService::class.java)
            phoneAccountHandle = PhoneAccountHandle(componentName, PHONE_ACCOUNT_ID)

            // Note: Self-managed ConnectionServices cannot be CALL_PROVIDER
            // Use CAPABILITY_SELF_MANAGED only for VoIP apps
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "BitNex Dial")
                .setAddress(Uri.parse("sip:user@bitnexdial.com"))
                .setSubscriptionAddress(Uri.parse("sip:user@bitnexdial.com"))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
                .setShortDescription("BitNex VoIP Calls")
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .setHighlightColor(context.getColor(R.color.primary))
                .build()

            tm.registerPhoneAccount(phoneAccount)

            Log.d(TAG, "Phone account registered successfully")

            // Check if account is enabled (safely)
            val isEnabled = isPhoneAccountEnabled()
            Log.d(TAG, "Phone account enabled: $isEnabled")

            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception registering phone account - permissions may not be granted yet", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register phone account", e)
            false
        }
    }

    /**
     * Unregister the phone account
     */
    fun unregisterPhoneAccount() {
        val tm = telecomManager ?: return
        try {
            phoneAccountHandle?.let { handle ->
                tm.unregisterPhoneAccount(handle)
                Log.d(TAG, "Phone account unregistered")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception unregistering phone account", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister phone account", e)
        }
    }

    /**
     * Get the registered phone account handle
     */
    fun getPhoneAccountHandle(): PhoneAccountHandle? {
        if (phoneAccountHandle == null) {
            val componentName = ComponentName(context, CallConnectionService::class.java)
            phoneAccountHandle = PhoneAccountHandle(componentName, PHONE_ACCOUNT_ID)
        }
        return phoneAccountHandle
    }

    /**
     * Check if phone account is registered
     */
    fun isPhoneAccountRegistered(): Boolean {
        if (!hasRequiredPermissions()) return false
        val tm = telecomManager ?: return false
        return try {
            val handle = getPhoneAccountHandle() ?: return false
            val account = tm.getPhoneAccount(handle)
            account != null
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception checking phone account registration", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phone account registration", e)
            false
        }
    }

    /**
     * Check if phone account is enabled by user
     */
    fun isPhoneAccountEnabled(): Boolean {
        if (!hasRequiredPermissions()) return false
        val tm = telecomManager ?: return false
        return try {
            val handle = getPhoneAccountHandle() ?: return false
            val account = tm.getPhoneAccount(handle)
            account?.isEnabled == true
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception checking phone account status", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phone account status", e)
            false
        }
    }

    /**
     * Check if we can make calls
     */
    fun canMakeCalls(): Boolean {
        return isPhoneAccountRegistered() && isPhoneAccountEnabled()
    }

    /**
     * Place an outgoing call via TelecomManager
     */
    fun placeOutgoingCall(phoneNumber: String, extras: Bundle = Bundle()): Boolean {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot place call - permissions not granted")
            return false
        }
        val tm = telecomManager ?: return false
        return try {
            val handle = getPhoneAccountHandle()
            if (handle == null || !canMakeCalls()) {
                Log.w(TAG, "Cannot place call - phone account not ready")
                return false
            }

            val uri = Uri.parse("tel:$phoneNumber")
            val callExtras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                putAll(extras)
            }

            tm.placeCall(uri, callExtras)
            Log.d(TAG, "Outgoing call placed to: $phoneNumber")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception placing call - permission denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place outgoing call", e)
            false
        }
    }

    /**
     * Add an incoming call via TelecomManager
     */
    fun addIncomingCall(
        callId: String,
        callerNumber: String,
        callerName: String? = null
    ): Boolean {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot add incoming call - permissions not granted")
            return false
        }
        val tm = telecomManager ?: return false
        return try {
            val handle = getPhoneAccountHandle()
            if (handle == null) {
                Log.w(TAG, "Cannot add incoming call - phone account not registered")
                return false
            }

            val extras = Bundle().apply {
                putString(com.bitnextechnologies.bitnexdial.util.Constants.EXTRA_CALL_ID, callId)
                putString(com.bitnextechnologies.bitnexdial.util.Constants.EXTRA_CALLER_NUMBER, callerNumber)
                callerName?.let { putString(com.bitnextechnologies.bitnexdial.util.Constants.EXTRA_CALLER_NAME, it) }
                putBoolean(com.bitnextechnologies.bitnexdial.util.Constants.EXTRA_IS_INCOMING, true)
            }

            tm.addNewIncomingCall(handle, extras)
            Log.d(TAG, "Incoming call added: $callerNumber")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception adding incoming call", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add incoming call", e)
            false
        }
    }

    /**
     * Check if in a call
     */
    fun isInCall(): Boolean {
        val tm = telecomManager ?: return false
        return try {
            tm.isInCall
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception checking in-call state", e)
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get current call count
     */
    fun getCallCount(): Int {
        return CallConnectionService.getAllConnections().size
    }

    /**
     * End all calls
     */
    fun endAllCalls() {
        CallConnectionService.getAllConnections().forEach { (callId, connection) ->
            try {
                connection.onDisconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error ending call: $callId", e)
            }
        }
    }
}
