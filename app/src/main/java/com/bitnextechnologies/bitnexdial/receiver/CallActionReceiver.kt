package com.bitnextechnologies.bitnexdial.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.sip.SipCallManager
import com.bitnextechnologies.bitnexdial.presentation.call.InCallActivity
import com.bitnextechnologies.bitnexdial.service.fcm.PushCallService
import com.bitnextechnologies.bitnexdial.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receiver for handling call actions from notifications.
 * Processes answer, decline, end call, and mute actions.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
    }

    @Inject
    lateinit var sipCallManager: SipCallManager

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
        val callerNumber = intent.getStringExtra(Constants.EXTRA_CALLER_NUMBER) ?: ""
        val callerName = intent.getStringExtra(Constants.EXTRA_CALLER_NAME)

        Log.d(TAG, "Received action: ${intent.action} for call: $callId")

        // Cancel the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PushCallService.NOTIFICATION_ID_INCOMING_CALL)

        // Dismiss IncomingCallActivity if it's running
        val dismissIntent = Intent(Constants.ACTION_CALL_CANCELLED).apply {
            setPackage(context.packageName)
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        context.sendBroadcast(dismissIntent)

        when (intent.action) {
            Constants.ACTION_ANSWER_CALL -> {
                handleAnswerCall(context, callId, callerNumber, callerName)
            }
            Constants.ACTION_DECLINE_CALL -> {
                handleDeclineCall(callId)
            }
            Constants.ACTION_END_CALL -> {
                handleEndCall(callId)
            }
            Constants.ACTION_MUTE_CALL -> {
                handleMuteCall(callId)
            }
        }
    }

    private fun handleAnswerCall(context: Context, callId: String, callerNumber: String, callerName: String?) {
        Log.d(TAG, "Answering call: $callId")

        // Answer the call via SIP
        sipCallManager.answerCall(callId)

        // Launch InCallActivity
        val activityIntent = Intent(context, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(Constants.EXTRA_CALLER_NAME, callerName)
            putExtra(Constants.EXTRA_IS_INCOMING, true)
        }
        context.startActivity(activityIntent)
    }

    private fun handleDeclineCall(callId: String) {
        Log.d(TAG, "Declining call: $callId")
        sipCallManager.rejectCall(callId)
    }

    private fun handleEndCall(callId: String) {
        Log.d(TAG, "Ending call: $callId")
        sipCallManager.endCall(callId)
    }

    private fun handleMuteCall(callId: String) {
        Log.d(TAG, "Toggling mute for call: $callId")
        sipCallManager.toggleMute(callId)
    }
}
