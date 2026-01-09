package com.bitnextechnologies.bitnexdial.data.repository

import android.content.Context
import android.util.Log
import com.bitnextechnologies.bitnexdial.data.local.dao.CallDao
import com.bitnextechnologies.bitnexdial.data.local.entity.CallEntity
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.remote.dto.DeleteCallsRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.MarkCallsReadRequest
import com.bitnextechnologies.bitnexdial.data.remote.dto.SaveCallRequest
import com.bitnextechnologies.bitnexdial.domain.model.Call
import com.bitnextechnologies.bitnexdial.domain.model.CallDirection
import com.bitnextechnologies.bitnexdial.domain.model.CallStatus
import com.bitnextechnologies.bitnexdial.domain.model.CallType
import com.bitnextechnologies.bitnexdial.domain.repository.ICallRepository
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.util.Constants
import com.bitnextechnologies.bitnexdial.util.DateTimeUtils
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallRepositoryImpl"

@Singleton
class CallRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callDao: CallDao,
    private val apiService: BitnexApiService,
    private val secureCredentialManager: SecureCredentialManager
) : ICallRepository {

    /**
     * Get the SIP username (extension) from SecureCredentialManager
     */
    private fun getExtension(): String {
        return secureCredentialManager.getSipCredentials()?.username ?: ""
    }

    /**
     * Get the user's phone number from SecureCredentialManager
     * Returns cleaned 10-digit number (no country code prefix)
     */
    private fun getUserPhoneNumber(): String {
        val phone = secureCredentialManager.getSenderPhone() ?: ""
        // Remove non-digit characters and leading 1 to get clean 10-digit number
        return phone.replace(Regex("[^\\d]"), "").removePrefix("1")
    }

    override fun getCallHistory(): Flow<List<Call>> {
        return callDao.getAllCalls().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentCalls(limit: Int): Flow<List<Call>> {
        return callDao.getRecentCalls(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMissedCalls(): Flow<List<Call>> {
        return callDao.getMissedCalls().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getCallById(callId: String): Call? {
        return callDao.getCallById(callId)?.toDomain()
    }

    override suspend fun saveCall(call: Call) {
        // Check for duplicate before inserting to prevent multiple entries for same call
        val existingDuplicate = callDao.findDuplicateCall(call.phoneNumber, call.startTime)
        if (existingDuplicate != null) {
            // Update the existing call instead of creating a new one
            Log.d(TAG, "saveCall: Found duplicate call id=${existingDuplicate.id}, updating instead of inserting")
            val updatedEntity = CallEntity.fromDomain(call).copy(id = existingDuplicate.id)
            callDao.updateCall(updatedEntity)
        } else {
            callDao.insertCall(CallEntity.fromDomain(call))
        }

        // Sync to server using /api/save-call
        try {
            val directionStr = if (call.direction == CallDirection.INCOMING) "inbound" else "outbound"
            // Use full phone number (not SIP extension) so queries can find the record
            val userPhone = getUserPhoneNumber()

            // Determine caller and callee based on direction
            // Use userPhone (full number like "2164281997") instead of SIP extension
            val (caller, callee) = if (call.direction == CallDirection.INCOMING) {
                call.phoneNumber to userPhone
            } else {
                userPhone to call.phoneNumber
            }

            Log.d(TAG, "saveCall: Syncing to server - caller=$caller, callee=$callee, direction=$directionStr")

            apiService.saveCall(
                SaveCallRequest(
                    caller = caller,
                    callee = callee,
                    direction = directionStr,
                    startTime = DateTimeUtils.toUtcIsoString(call.startTime),
                    answerTime = if (call.type != CallType.MISSED) DateTimeUtils.toUtcIsoString(call.startTime) else null,
                    endTime = call.endTime?.let { DateTimeUtils.toUtcIsoString(it) },
                    duration = (call.duration / 1000).toInt(),
                    sessionId = call.id
                )
            )
            Log.d(TAG, "saveCall: Synced call ${call.id} to server")
        } catch (e: Exception) {
            Log.e(TAG, "saveCall: Failed to sync call to server", e)
            // Local save succeeded, server sync failed - will retry later
        }
    }

    override suspend fun deleteCall(callId: String) {
        // Delete from server first
        val phoneNumber = getUserPhoneNumber()
        if (phoneNumber.isNotEmpty()) {
            try {
                val response = apiService.deleteCall(callId, phoneNumber)
                if (response.isSuccessful) {
                    Log.d(TAG, "deleteCall: Deleted call $callId from server")
                } else {
                    Log.w(TAG, "deleteCall: Server returned ${response.code()} for call $callId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteCall: Failed to delete call from server", e)
                // Continue with local delete even if server fails
            }
        }

        // Delete locally
        callDao.deleteCallById(callId)
        Log.d(TAG, "deleteCall: Deleted call $callId locally")
    }

    override suspend fun clearCallHistory() {
        val userPhone = getUserPhoneNumber()
        if (userPhone.isEmpty()) {
            Log.w(TAG, "clearCallHistory: No user phone number, clearing locally only")
            callDao.deleteAllCalls()
            return
        }

        try {
            // Step 1: Get all call IDs from the server
            Log.d(TAG, "clearCallHistory: Fetching all call IDs from server")
            val idsResponse = apiService.getCallHistoryIds(extension = userPhone)

            if (idsResponse.isSuccessful && idsResponse.body()?.success == true) {
                val ids = idsResponse.body()?.ids ?: emptyList()

                if (ids.isNotEmpty()) {
                    // Step 2: Call bulk-delete API with all call IDs
                    Log.d(TAG, "clearCallHistory: Deleting ${ids.size} calls from server")
                    val deleteRequest = DeleteCallsRequest(
                        callIds = ids.map { it.toString() },
                        myPhoneNumber = userPhone
                    )
                    val deleteResponse = apiService.deleteCalls(deleteRequest)

                    if (deleteResponse.isSuccessful && deleteResponse.body()?.success == true) {
                        Log.d(TAG, "clearCallHistory: Successfully deleted ${ids.size} calls from server")
                    } else {
                        Log.w(TAG, "clearCallHistory: Server bulk-delete returned ${deleteResponse.code()}")
                    }
                } else {
                    Log.d(TAG, "clearCallHistory: No calls to delete on server")
                }
            } else {
                Log.w(TAG, "clearCallHistory: Failed to get call IDs from server: ${idsResponse.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearCallHistory: Server delete failed, clearing locally only", e)
        }

        // Step 3: Clear local database
        callDao.deleteAllCalls()
        Log.d(TAG, "clearCallHistory: Cleared all calls locally")
    }

    override suspend fun markAsRead(callId: String) {
        callDao.markAsRead(callId)
        try {
            val extension = getExtension()
            val userPhone = getUserPhoneNumber()
            apiService.markCallsAsRead(
                MarkCallsReadRequest(
                    callIds = listOf(callId.toIntOrNull() ?: 0),
                    extension = extension,
                    userNumber = userPhone
                )
            )
            Log.d(TAG, "markAsRead: Marked call $callId as read on server")
        } catch (e: Exception) {
            Log.e(TAG, "markAsRead: Failed to mark call as read on server", e)
            // Local update succeeded
        }
    }

    override suspend fun deleteCallsBulk(callIds: List<String>) {
        if (callIds.isEmpty()) return

        val userPhone = getUserPhoneNumber()
        Log.d(TAG, "deleteCallsBulk: Deleting ${callIds.size} calls")

        // Delete from server first
        if (userPhone.isNotEmpty()) {
            try {
                val deleteRequest = DeleteCallsRequest(
                    callIds = callIds,
                    myPhoneNumber = userPhone
                )
                val response = apiService.deleteCalls(deleteRequest)

                if (response.isSuccessful && response.body()?.success == true) {
                    Log.d(TAG, "deleteCallsBulk: Successfully deleted ${callIds.size} calls from server")
                } else {
                    Log.w(TAG, "deleteCallsBulk: Server returned ${response.code()} - ${response.body()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteCallsBulk: Failed to delete calls from server", e)
                // Continue with local delete even if server fails
            }
        }

        // Delete locally
        callIds.forEach { callId ->
            callDao.deleteCallById(callId)
        }
        Log.d(TAG, "deleteCallsBulk: Deleted ${callIds.size} calls locally")
    }

    override suspend fun markAllMissedAsRead() {
        Log.d(TAG, "markAllMissedAsRead: Marking all missed calls as read")
        // Update locally first for immediate UI feedback
        callDao.markAllMissedAsRead()

        // Sync to server like web version does (POST /api/mark-calls-read with userNumber)
        try {
            val userPhone = getUserPhoneNumber()
            if (userPhone.isNotEmpty()) {
                val formattedPhone = "+1$userPhone"
                Log.d(TAG, "markAllMissedAsRead: Syncing to server with userNumber=$formattedPhone")
                apiService.markCallsAsRead(
                    MarkCallsReadRequest(
                        callIds = null, // Don't specify callIds - mark ALL as read
                        extension = null,
                        userNumber = formattedPhone
                    )
                )
                Log.d(TAG, "markAllMissedAsRead: Successfully synced to server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "markAllMissedAsRead: Failed to sync to server", e)
            // Local update succeeded, server sync failed - will be consistent on next sync
        }
    }

    override fun getUnreadMissedCallCount(): Flow<Int> {
        return callDao.getUnreadMissedCallCount().map { count ->
            Log.d(TAG, "Unread missed call count changed: $count")
            count
        }
    }

    override suspend fun syncCallHistory() {
        // Use phone number like the web version does
        val phoneNumber = getUserPhoneNumber()
        val extension = getExtension()

        Log.d(TAG, "syncCallHistory: Starting sync")
        Log.d(TAG, "syncCallHistory: phoneNumber='$phoneNumber', extension='$extension'")

        if (phoneNumber.isEmpty()) {
            Log.w(TAG, "syncCallHistory: No phone number available, skipping sync")
            return
        }

        Log.d(TAG, "syncCallHistory: Fetching call history for phoneNumber=$phoneNumber")

        try {
            // Fetch all calls - use phone number as extension parameter (matches web behavior)
            Log.d(TAG, "syncCallHistory: Calling API with extension=$phoneNumber")
            val response = apiService.getCallHistory(extension = phoneNumber, limit = 100, offset = 0)
            Log.d(TAG, "syncCallHistory: API response code=${response.code()}, isSuccessful=${response.isSuccessful}")

            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null) {
                    Log.d(TAG, "syncCallHistory: Got ${apiResponse.allCalls?.size ?: 0} calls from API")

                    val calls = apiResponse.allCalls?.mapNotNull { dto ->
                        try {
                            val entity = mapApiCallToEntity(dto)
                            // Filter out calls where the other party is the user's own number
                            val otherPartyNormalized = entity.phoneNumber.replace(Regex("[^\\d]"), "").takeLast(10)
                            if (otherPartyNormalized == phoneNumber.takeLast(10)) {
                                Log.d(TAG, "syncCallHistory: Filtering out call to/from own number: ${entity.phoneNumber}")
                                null
                            } else {
                                entity
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "syncCallHistory: Error mapping call dto", e)
                            null
                        }
                    } ?: emptyList()

                    // Filter out duplicates before inserting
                    val nonDuplicateCalls = calls.filter { call ->
                        !isDuplicateCall(call.phoneNumber, call.startTime)
                    }

                    if (nonDuplicateCalls.isNotEmpty()) {
                        // IMPORTANT: Preserve local read status for calls already marked as read
                        val callsWithPreservedReadStatus = nonDuplicateCalls.map { call ->
                            val existingCall = callDao.getCallById(call.id)
                            if (existingCall != null && existingCall.isRead) {
                                call.copy(isRead = true)
                            } else {
                                call
                            }
                        }

                        callDao.insertCalls(callsWithPreservedReadStatus)
                        Log.d(TAG, "syncCallHistory: Saved ${callsWithPreservedReadStatus.size} calls to local database (filtered ${calls.size - nonDuplicateCalls.size} duplicates)")
                    } else if (calls.isNotEmpty()) {
                        Log.d(TAG, "syncCallHistory: All ${calls.size} calls were duplicates, nothing to insert")
                    }
                } else {
                    Log.w(TAG, "syncCallHistory: Response body is null")
                }
            } else {
                Log.e(TAG, "syncCallHistory: API error - ${response.code()} ${response.message()}")
            }

            // Also fetch missed calls from dedicated endpoint
            syncMissedCalls(phoneNumber)
        } catch (e: Exception) {
            Log.e(TAG, "syncCallHistory: Error syncing call history", e)
            throw e
        }
    }

    private suspend fun syncMissedCalls(phoneNumber: String) {
        try {
            Log.d(TAG, "syncMissedCalls: Fetching missed calls for phoneNumber=$phoneNumber")
            val response = apiService.getMissedCalls(extension = phoneNumber, limit = 50, offset = 0)
            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null) {
                    Log.d(TAG, "syncMissedCalls: Got ${apiResponse.allCalls?.size ?: 0} missed calls from API")

                    val calls = apiResponse.allCalls?.mapNotNull { dto ->
                        try {
                            // Force missed call type for calls from this endpoint
                            val entity = mapApiCallToEntity(dto, forceType = "MISSED")
                            // Filter out calls where the other party is the user's own number
                            val otherPartyNormalized = entity.phoneNumber.replace(Regex("[^\\d]"), "").takeLast(10)
                            if (otherPartyNormalized == phoneNumber.takeLast(10)) {
                                Log.d(TAG, "syncMissedCalls: Filtering out call to/from own number: ${entity.phoneNumber}")
                                null
                            } else {
                                entity
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "syncMissedCalls: Error mapping call dto", e)
                            null
                        }
                    } ?: emptyList()

                    // Filter out duplicates before inserting
                    val nonDuplicateCalls = calls.filter { call ->
                        !isDuplicateCall(call.phoneNumber, call.startTime)
                    }

                    if (nonDuplicateCalls.isNotEmpty()) {
                        // IMPORTANT: Preserve local read status for calls already marked as read
                        // This prevents badge accumulation after user has viewed missed calls
                        val callsWithPreservedReadStatus = nonDuplicateCalls.map { call ->
                            val existingCall = callDao.getCallById(call.id)
                            if (existingCall != null && existingCall.isRead) {
                                // Keep the local read status if user already marked as read
                                call.copy(isRead = true)
                            } else {
                                call
                            }
                        }

                        // Log each missed call's read status before saving
                        callsWithPreservedReadStatus.forEach { call ->
                            Log.d(TAG, "syncMissedCalls: Saving missed call id=${call.id}, phone=${call.phoneNumber}, isRead=${call.isRead}, type=${call.type}")
                        }
                        val unreadCount = callsWithPreservedReadStatus.count { !it.isRead }
                        Log.d(TAG, "syncMissedCalls: Total=${callsWithPreservedReadStatus.size}, Unread=$unreadCount (filtered ${calls.size - nonDuplicateCalls.size} duplicates)")

                        callDao.insertCalls(callsWithPreservedReadStatus)
                        Log.d(TAG, "syncMissedCalls: Saved ${callsWithPreservedReadStatus.size} missed calls to local database")
                    } else if (calls.isNotEmpty()) {
                        Log.d(TAG, "syncMissedCalls: All ${calls.size} missed calls were duplicates, nothing to insert")
                    }
                } else {
                    Log.w(TAG, "syncMissedCalls: Response body is null")
                }
            } else {
                Log.d(TAG, "syncMissedCalls: API returned ${response.code()} - missed calls endpoint may not be available")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncMissedCalls: Could not fetch missed calls", e)
            // Non-critical - don't throw
        }
    }

    /**
     * Check if a call with similar phone number and time already exists locally.
     * This prevents duplicate entries when API sync brings back calls we already saved locally.
     * Returns true if a duplicate exists.
     */
    private suspend fun isDuplicateCall(phoneNumber: String, startTime: Long): Boolean {
        val existing = callDao.findDuplicateCall(phoneNumber, startTime)
        if (existing != null) {
            Log.d(TAG, "isDuplicateCall: Found existing call id=${existing.id} for phone=$phoneNumber at time=$startTime")
            return true
        }
        return false
    }

    private fun mapApiCallToEntity(dto: com.bitnextechnologies.bitnexdial.data.remote.dto.CallRecordApiResponse, forceType: String? = null): CallEntity {
        // Determine phone number based on direction
        val isOutbound = dto.direction == "outbound"
        val rawPhoneNumber = if (isOutbound) dto.callee else dto.caller
        // Parse SIP-style caller ID to get clean phone number (handles "2102012856" <2102012856>)
        val phoneNumber = PhoneNumberUtils.parseSipCallerId(rawPhoneNumber)

        // Determine call type based on direction, duration, and answer_time
        // A missed call is ONLY an INBOUND call with no answer (answer_time is null or duration is 0)
        // Outbound calls should NEVER be marked as missed - they are either OUTGOING or ANSWERED
        val callType = when {
            // Outbound calls are never "missed" - they are outgoing regardless of whether answered
            isOutbound -> if ((dto.duration ?: 0) > 0) "ANSWERED" else "OUTGOING"
            // Inbound calls can be missed if not answered
            dto.direction == "inbound" && (dto.answerTime == null || (dto.duration ?: 0) == 0) ->
                forceType ?: "MISSED"
            // Answered inbound calls
            else -> "ANSWERED"
        }

        val isReadValue = if (callType == "MISSED") {
            dto.read == 1
        } else {
            (dto.read ?: 1) == 1
        }
        Log.d(TAG, "mapApiCallToEntity: id=${dto.id}, direction=${dto.direction}, duration=${dto.duration}, answerTime=${dto.answerTime}, callType=$callType, serverRead=${dto.read}, isRead=$isReadValue")

        // Parse caller and callee (they might be in SIP format too)
        val parsedCaller = PhoneNumberUtils.parseSipCallerId(dto.caller)
        val parsedCallee = PhoneNumberUtils.parseSipCallerId(dto.callee)

        return CallEntity(
            id = dto.id.toString(),
            phoneNumber = phoneNumber,
            contactName = dto.contactName,
            contactId = null,
            direction = if (dto.direction == "inbound") "INCOMING" else "OUTGOING",
            status = "DISCONNECTED",
            type = callType,
            duration = ((dto.duration ?: 0) * 1000).toLong(),
            startTime = dto.startTime?.let { parseDateTime(it) } ?: System.currentTimeMillis(),
            endTime = dto.endTime?.let { parseDateTime(it) },
            lineNumber = 1,
            fromNumber = parsedCaller,
            toNumber = parsedCallee,
            recordingUrl = null,
            recordingId = null,
            // For missed calls, default to unread (isRead=false) unless server explicitly says read=1
            // For other calls, default to read (isRead=true)
            isRead = if (callType == "MISSED") {
                dto.read == 1  // Only mark as read if server explicitly set read=1
            } else {
                (dto.read ?: 1) == 1  // Default to read for non-missed calls
            },
            isMuted = false,
            isOnHold = false,
            createdAt = System.currentTimeMillis(),
            syncedAt = System.currentTimeMillis()
        )
    }

    private fun parseDateTime(dateString: String): Long {
        return DateTimeUtils.parseUtcTimestamp(dateString)
    }

    override suspend fun syncCallHistoryWithFilter(filter: String) {
        // Use phone number like the web version does
        val phoneNumber = getUserPhoneNumber()
        if (phoneNumber.isEmpty()) {
            Log.w(TAG, "syncCallHistoryWithFilter: No phone number available, skipping sync")
            return
        }

        Log.d(TAG, "syncCallHistoryWithFilter: Fetching $filter calls for phoneNumber=$phoneNumber")

        try {
            val response = when (filter) {
                "outgoing" -> apiService.getOutgoingCalls(extension = phoneNumber, limit = 100, offset = 0)
                "incoming" -> apiService.getIncomingCalls(extension = phoneNumber, limit = 100, offset = 0)
                "missed" -> apiService.getMissedCalls(extension = phoneNumber, limit = 100, offset = 0)
                else -> apiService.getCallHistory(extension = phoneNumber, limit = 100, offset = 0)
            }

            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null) {
                    Log.d(TAG, "syncCallHistoryWithFilter: Got ${apiResponse.allCalls?.size ?: 0} $filter calls from API")

                    val calls = apiResponse.allCalls?.mapNotNull { dto ->
                        try {
                            // Force type for missed calls endpoint
                            val forceType = if (filter == "missed") "MISSED" else null
                            val entity = mapApiCallToEntity(dto, forceType)
                            // Filter out calls where the other party is the user's own number
                            val otherPartyNormalized = entity.phoneNumber.replace(Regex("[^\\d]"), "").takeLast(10)
                            if (otherPartyNormalized == phoneNumber.takeLast(10)) {
                                Log.d(TAG, "syncCallHistoryWithFilter: Filtering out call to/from own number: ${entity.phoneNumber}")
                                null
                            } else {
                                entity
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "syncCallHistoryWithFilter: Error mapping call dto", e)
                            null
                        }
                    } ?: emptyList()

                    // Filter out duplicates before inserting
                    val nonDuplicateCalls = calls.filter { call ->
                        !isDuplicateCall(call.phoneNumber, call.startTime)
                    }

                    if (nonDuplicateCalls.isNotEmpty()) {
                        callDao.insertCalls(nonDuplicateCalls)
                        Log.d(TAG, "syncCallHistoryWithFilter: Saved ${nonDuplicateCalls.size} $filter calls to local database (filtered ${calls.size - nonDuplicateCalls.size} duplicates)")
                    } else if (calls.isNotEmpty()) {
                        Log.d(TAG, "syncCallHistoryWithFilter: All ${calls.size} $filter calls were duplicates, nothing to insert")
                    }
                } else {
                    Log.w(TAG, "syncCallHistoryWithFilter: Response body is null for $filter")
                }
            } else {
                Log.e(TAG, "syncCallHistoryWithFilter: API error - ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncCallHistoryWithFilter: Error syncing $filter call history", e)
            throw e
        }
    }

    /**
     * Load more call history for pagination
     * Returns true if more calls are available
     */
    override suspend fun loadMoreCalls(offset: Int, limit: Int, filter: String): Boolean {
        val phoneNumber = getUserPhoneNumber()
        if (phoneNumber.isEmpty()) return false

        Log.d(TAG, "loadMoreCalls: Loading offset=$offset, limit=$limit, filter=$filter")

        try {
            val response = when (filter) {
                "outgoing" -> apiService.getOutgoingCalls(extension = phoneNumber, limit = limit, offset = offset)
                "incoming" -> apiService.getIncomingCalls(extension = phoneNumber, limit = limit, offset = offset)
                "missed" -> apiService.getMissedCalls(extension = phoneNumber, limit = limit, offset = offset)
                else -> apiService.getCallHistory(extension = phoneNumber, limit = limit, offset = offset)
            }

            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null) {
                    Log.d(TAG, "loadMoreCalls: Got ${apiResponse.allCalls?.size ?: 0} calls")

                    val calls = apiResponse.allCalls?.mapNotNull { dto ->
                        try {
                            val forceType = if (filter == "missed") "MISSED" else null
                            val entity = mapApiCallToEntity(dto, forceType)
                            val otherPartyNormalized = entity.phoneNumber.replace(Regex("[^\\d]"), "").takeLast(10)
                            if (otherPartyNormalized == phoneNumber.takeLast(10)) {
                                null
                            } else {
                                entity
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadMoreCalls: Error mapping call dto", e)
                            null
                        }
                    } ?: emptyList()

                    // Filter out duplicates before inserting
                    val nonDuplicateCalls = calls.filter { call ->
                        !isDuplicateCall(call.phoneNumber, call.startTime)
                    }

                    if (nonDuplicateCalls.isNotEmpty()) {
                        callDao.insertCalls(nonDuplicateCalls)
                        Log.d(TAG, "loadMoreCalls: Saved ${nonDuplicateCalls.size} calls (filtered ${calls.size - nonDuplicateCalls.size} duplicates)")
                    }

                    // Return true if we got a full page (more might be available)
                    return calls.size >= limit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadMoreCalls: Error loading more calls", e)
        }
        return false
    }

    /**
     * Save a missed call directly to the database with a unique ID.
     * Used by FCM push handler and call end detection for immediate persistence.
     * Also syncs to server to ensure data consistency across devices.
     */
    override suspend fun saveMissedCall(
        phoneNumber: String,
        contactName: String?,
        timestamp: Long
    ) {
        // Check for duplicate before inserting
        val existingDuplicate = callDao.findDuplicateCall(phoneNumber, timestamp)
        if (existingDuplicate != null) {
            Log.d(TAG, "saveMissedCall: Found duplicate call id=${existingDuplicate.id}, skipping insert")
            return
        }

        // Generate a unique ID using timestamp + random to avoid collisions
        val uniqueId = "missed_${timestamp}_${(1000..9999).random()}"
        val userPhone = getUserPhoneNumber()

        val entity = CallEntity(
            id = uniqueId,
            phoneNumber = phoneNumber,
            contactName = contactName,
            contactId = null,
            direction = "INCOMING",
            status = "DISCONNECTED",
            type = "MISSED",
            duration = 0L,
            startTime = timestamp,
            endTime = timestamp,
            lineNumber = 1,
            fromNumber = phoneNumber,
            toNumber = userPhone,
            recordingUrl = null,
            recordingId = null,
            isRead = false,  // CRITICAL: Missed calls are unread by default
            isMuted = false,
            isOnHold = false,
            createdAt = timestamp,
            syncedAt = null
        )

        callDao.insertCall(entity)
        Log.d(TAG, "Saved missed call locally: id=$uniqueId, phone=$phoneNumber, isRead=false")

        // Sync to server using /api/save-call
        try {
            // Use full phone number (not SIP extension) so queries can find the record
            val userPhone = getUserPhoneNumber()
            Log.d(TAG, "Attempting to sync missed call to server: caller=$phoneNumber, callee=$userPhone")

            val response = apiService.saveCall(
                SaveCallRequest(
                    caller = phoneNumber,
                    callee = userPhone,
                    direction = "inbound",
                    startTime = DateTimeUtils.toUtcIsoString(timestamp),
                    answerTime = null,  // Missed call has no answer time
                    endTime = DateTimeUtils.toUtcIsoString(timestamp),
                    duration = 0,  // Missed call has 0 duration
                    sessionId = uniqueId
                )
            )

            if (response.isSuccessful) {
                // Update syncedAt after successful sync
                callDao.insertCall(entity.copy(syncedAt = System.currentTimeMillis()))
                Log.d(TAG, "Synced missed call to server: id=$uniqueId, response=${response.body()}")
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Server rejected missed call sync: code=${response.code()}, error=$errorBody")
                // Check if it's an auth error
                if (response.code() == 401) {
                    Log.e(TAG, "Authentication error - session may have expired. User needs to re-login.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync missed call to server (network error)", e)
            // Local save succeeded, server sync failed - data still exists locally
        }
    }

    /**
     * Get the current unread missed call count directly (not as Flow).
     * Used for immediate badge updates from event handlers.
     */
    override suspend fun getUnreadMissedCallCountDirect(): Int {
        val count = callDao.getUnreadMissedCallCountDirect()
        Log.d(TAG, "Direct unread missed call count: $count")
        return count
    }

    /**
     * Update notes for a call
     */
    override suspend fun updateNotes(callId: String, notes: String?) {
        callDao.updateNotes(callId, notes)
        Log.d(TAG, "Updated notes for call $callId")
    }

    /**
     * Get notes for a call
     */
    override suspend fun getNotes(callId: String): String? {
        return callDao.getNotes(callId)
    }
}
