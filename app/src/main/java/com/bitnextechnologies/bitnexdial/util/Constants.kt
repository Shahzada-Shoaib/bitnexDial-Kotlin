package com.bitnextechnologies.bitnexdial.util

import com.bitnextechnologies.bitnexdial.BuildConfig

/**
 * Application-wide constants
 */
object Constants {
    // API Configuration
    const val API_BASE_URL = BuildConfig.API_BASE_URL
    const val SOCKET_URL = BuildConfig.SOCKET_URL
    const val SIP_DOMAIN = BuildConfig.SIP_DOMAIN
    const val WSS_SERVER = BuildConfig.WSS_SERVER
    const val WSS_PORT = BuildConfig.WSS_PORT
    const val WSS_PATH = BuildConfig.WSS_PATH

    // Network Timeouts (in seconds)
    const val CONNECT_TIMEOUT = 30L
    const val READ_TIMEOUT = 30L
    const val WRITE_TIMEOUT = 30L

    // SIP Configuration
    const val SIP_REGISTRATION_TIMEOUT = 300 // 5 minutes
    const val SIP_RETRY_INTERVAL = 60 // 1 minute
    const val SIP_MAX_CALLS = 4

    // Notification Channel IDs
    const val CHANNEL_ID_INCOMING_CALLS = "incoming_calls"
    const val CHANNEL_ID_MISSED_CALLS = "missed_calls"
    const val CHANNEL_ID_MESSAGES = "messages"
    const val CHANNEL_ID_SERVICE = "voip_service"
    const val CHANNEL_ID_ACTIVE_CALL = "active_call"

    // Channel names (used in code)
    const val CHANNEL_INCOMING_CALLS = CHANNEL_ID_INCOMING_CALLS
    const val CHANNEL_MISSED_CALLS = CHANNEL_ID_MISSED_CALLS
    const val CHANNEL_MESSAGES = CHANNEL_ID_MESSAGES
    const val CHANNEL_VOIP_SERVICE = CHANNEL_ID_SERVICE
    const val CHANNEL_ACTIVE_CALL = CHANNEL_ID_ACTIVE_CALL

    // Notification IDs
    const val NOTIFICATION_ID_INCOMING_CALL = 1001
    const val NOTIFICATION_ID_ACTIVE_CALL = 1002
    const val NOTIFICATION_ID_MISSED_CALL = 1003
    const val NOTIFICATION_ID_MESSAGE = 1004
    const val NOTIFICATION_ID_SERVICE = 2001

    // Request Codes
    const val REQUEST_CODE_CALL = 100
    const val REQUEST_CODE_ANSWER = 101
    const val REQUEST_CODE_DECLINE = 102
    const val REQUEST_CODE_END_CALL = 103
    const val REQUEST_CODE_PERMISSIONS = 200

    // Intent Actions
    const val ACTION_ANSWER_CALL = "com.bitnextechnologies.bitnexdial.ACTION_ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "com.bitnextechnologies.bitnexdial.ACTION_DECLINE_CALL"
    const val ACTION_END_CALL = "com.bitnextechnologies.bitnexdial.ACTION_END_CALL"
    const val ACTION_MUTE_CALL = "com.bitnextechnologies.bitnexdial.ACTION_MUTE_CALL"
    const val ACTION_HOLD_CALL = "com.bitnextechnologies.bitnexdial.ACTION_HOLD_CALL"
    const val ACTION_SPEAKER_CALL = "com.bitnextechnologies.bitnexdial.ACTION_SPEAKER_CALL"
    const val ACTION_CALL_CANCELLED = "com.bitnextechnologies.bitnexdial.ACTION_CALL_CANCELLED"

    // Intent Extras
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CALLER_NUMBER = "extra_caller_number"
    const val EXTRA_CALLER_NAME = "extra_caller_name"
    const val EXTRA_IS_INCOMING = "extra_is_incoming"
    const val EXTRA_LINE_NUMBER = "extra_line_number"

    // SharedPreferences Keys
    const val PREFS_NAME = "bitnexdial_prefs"
    const val PREF_USER_ID = "user_id"
    const val PREF_SESSION_ID = "session_id"
    const val PREF_PHONE_NUMBER = "phone_number"
    const val PREF_SIP_USERNAME = "sip_username"
    const val PREF_SIP_PASSWORD = "sip_password"
    const val PREF_SIP_DOMAIN = "sip_domain"
    const val PREF_WSS_SERVER = "wss_server"
    const val PREF_FCM_TOKEN = "fcm_token"
    const val KEY_FCM_TOKEN = PREF_FCM_TOKEN
    const val KEY_SIP_USERNAME = PREF_SIP_USERNAME
    const val KEY_SIP_PASSWORD = PREF_SIP_PASSWORD
    const val KEY_SIP_DOMAIN = PREF_SIP_DOMAIN
    const val KEY_SIP_SERVER = PREF_WSS_SERVER
    const val KEY_SIP_PORT = "sip_port"
    const val KEY_SIP_PATH = "sip_path"
    const val KEY_SENDER_PHONE = "sender_phone"
    const val PREF_IS_LOGGED_IN = "is_logged_in"
    const val PREF_AUDIO_OUTPUT_ID = "audio_output_id"
    const val PREF_AUDIO_INPUT_ID = "audio_input_id"
    const val PREF_RINGTONE_URI = "ringtone_uri"
    const val PREF_VIBRATE_ON_RING = "vibrate_on_ring"
    const val PREF_AUTO_ANSWER = "auto_answer"

    // Database
    const val DATABASE_NAME = "bitnexdial_database"
    const val DATABASE_VERSION = 1

    // Phone Account
    const val PHONE_ACCOUNT_ID = "BitNexDial"
    const val PHONE_ACCOUNT_LABEL = "BitNex Dial"

    // Call States
    object CallState {
        const val IDLE = "idle"
        const val DIALING = "dialing"
        const val RINGING = "ringing"
        const val CONNECTING = "connecting"
        const val CONNECTED = "connected"
        const val HOLDING = "holding"
        const val DISCONNECTING = "disconnecting"
        const val DISCONNECTED = "disconnected"
        const val FAILED = "failed"
    }

    // Registration States
    object RegistrationState {
        const val UNREGISTERED = "unregistered"
        const val REGISTERING = "registering"
        const val REGISTERED = "registered"
        const val FAILED = "failed"
    }

    // Message Status
    object MessageStatus {
        const val SENDING = "sending"
        const val SENT = "sent"
        const val DELIVERED = "delivered"
        const val READ = "read"
        const val FAILED = "failed"
    }

    // Call Direction
    object CallDirection {
        const val INCOMING = "inbound"
        const val OUTGOING = "outbound"
    }

    // Audio Route
    object AudioRoute {
        const val EARPIECE = 0
        const val SPEAKER = 1
        const val BLUETOOTH = 2
        const val WIRED_HEADSET = 3
    }

    // API Endpoints
    object Endpoints {
        const val LOGIN = "/admin/login"
        const val LOGOUT = "/admin/logout"
        const val VALIDATE_SESSION = "/api/validate-session"
        const val GET_CONTACTS = "/api/get-contacts"
        const val SAVE_CONTACT = "/api/save-contact"
        const val DELETE_CONTACT = "/api/delete-contact"
        const val BLOCK_CONTACT = "/api/block-contact"
        const val GET_BLOCKED_CONTACTS = "/api/blocked-contacts"
        const val CALL_HISTORY = "/api/call-history"
        const val SAVE_CALL = "/api/save-call"
        const val MARK_CALLS_READ = "/api/mark-calls-read"
        const val SMS_HISTORY = "/sms-history"
        const val SMS_LATEST_SUMMARY = "/sms-latest-summary"
        const val MARK_SMS_READ = "/api/mark-sms-read"
        const val UNREAD_SMS_COUNTS = "/api/unread-sms-counts"
        const val VOICEMAILS = "/api/voicemails"
        const val MARK_VOICEMAIL_READ = "/api/mark-voicemail-read"
        const val RECORDINGS = "/api/call-recordings"
        const val PHONE_NUMBERS = "/api/numbers/list"
        const val SWITCH_NUMBER = "/api/numbers/switch"
        const val REGISTER_FCM_TOKEN = "/api/register-fcm-token"
    }

    // Socket Events
    object SocketEvents {
        const val CONNECT = "connect"
        const val DISCONNECT = "disconnect"
        const val NEW_SMS = "new_sms"
        const val NEW_SMS_ALT = "new-sms"
        const val REGISTER_PHONE = "register_phone"
        const val INCOMING_CALL = "incoming_call"
        const val CALL_CANCELLED = "call_cancelled"
    }
}
