package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import com.bitnextechnologies.bitnexdial.domain.model.Voicemail
import com.bitnextechnologies.bitnexdial.data.local.dao.VoicemailDao
import com.bitnextechnologies.bitnexdial.data.local.entity.VoicemailEntity
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.remote.dto.MarkVoicemailReadRequest
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketEvent
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import com.bitnextechnologies.bitnexdial.data.repository.ContactRepository
import com.bitnextechnologies.bitnexdial.domain.repository.IContactRepository
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.BuildConfig
import com.bitnextechnologies.bitnexdial.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VoicemailViewModel"

@HiltViewModel
class VoicemailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voicemailDao: VoicemailDao,
    private val apiService: BitnexApiService,
    private val socketManager: SocketManager,
    private val secureCredentialManager: SecureCredentialManager,
    private val apiContactRepository: IContactRepository,
    private val deviceContactRepository: ContactRepository
) : ViewModel() {

    /**
     * Get the SIP username (extension/mailbox) from SecureCredentialManager
     */
    private fun getMailbox(): String {
        return secureCredentialManager.getSipCredentials()?.username ?: ""
    }

    // Track if initial data load is complete - prevents showing loading on subsequent visits
    private var hasInitiallyLoaded = false

    private val _isLoading = MutableStateFlow(true) // Start true only for first load
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Separate refresh state for pull-to-refresh indicator
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _playingVoicemailId = MutableStateFlow<String?>(null)
    val playingVoicemailId: StateFlow<String?> = _playingVoicemailId.asStateFlow()

    // Loading state for audio buffering - shows spinner while audio is loading
    private val _isAudioLoading = MutableStateFlow(false)
    val isAudioLoading: StateFlow<Boolean> = _isAudioLoading.asStateFlow()

    // Playback progress (0.0 - 1.0)
    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    // Current position in milliseconds
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    // Duration in milliseconds
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // Playback speed (1.0, 1.5, 2.0)
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // Track voicemails that are visually read (playing) but not yet marked in database
    private val _visuallyReadVoicemails = MutableStateFlow<Set<String>>(emptySet())
    val visuallyReadVoicemails: StateFlow<Set<String>> = _visuallyReadVoicemails.asStateFlow()

    // ExoPlayer for fast audio streaming
    private var exoPlayer: ExoPlayer? = null
    private var progressUpdateJob: kotlinx.coroutines.Job? = null

    /**
     * Contact name lookup map from API contacts (normalized phone number -> contact name)
     * This matches the approach used in MessagesViewModel for consistent contact name display
     */
    private val contactNameMap: StateFlow<Map<String, String>> = apiContactRepository.getAllContacts()
        .map { contacts ->
            val nameMap = mutableMapOf<String, String>()
            contacts.forEach { contact ->
                // Only use contacts that have an actual name (not just phone number fallback)
                val name = contact.name?.takeIf { it.isNotBlank() }
                    ?: "${contact.firstName ?: ""} ${contact.lastName ?: ""}".trim().takeIf { it.isNotBlank() }

                if (name != null && contact.phoneNumbers.isNotEmpty()) {
                    // Add all phone numbers for this contact
                    contact.phoneNumbers.forEach { phone ->
                        val normalized = normalizePhoneNumber(phone)
                        if (normalized.isNotEmpty()) {
                            nameMap[normalized] = name
                        }
                    }
                }
            }
            Log.d(TAG, "Built contact name map with ${nameMap.size} entries for voicemail")
            nameMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    /**
     * Normalize phone number for comparison (strip to last 10 digits)
     */
    private fun normalizePhoneNumber(phone: String): String {
        return phone.replace(Regex("[^\\d]"), "").removePrefix("1").takeLast(10)
    }

    val voicemails: StateFlow<List<Voicemail>> = combine(
        voicemailDao.getAllVoicemails(),
        contactNameMap
    ) { entities, nameMap ->
        entities.map { entity ->
            val voicemail = entity.toDomain()
            // Enrich with contact name if not already set
            // Priority: 1) API contacts (nameMap), 2) Device contacts
            if (voicemail.callerName.isNullOrBlank()) {
                val normalizedPhone = normalizePhoneNumber(voicemail.callerNumber)
                // First try API contacts
                val lookedUpName = nameMap[normalizedPhone]
                    // Fallback to device contacts
                    ?: deviceContactRepository.getContactName(voicemail.callerNumber)
                if (lookedUpName != null) {
                    voicemail.copy(callerName = lookedUpName)
                } else {
                    voicemail
                }
            } else {
                voicemail
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val unreadCount: StateFlow<Int> = voicemailDao.getUnreadVoicemailCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Filter state: All, Read, Unread
    enum class FilterType {
        ALL, READ, UNREAD
    }

    private val _selectedFilter = MutableStateFlow(FilterType.ALL)
    val selectedFilter: StateFlow<FilterType> = _selectedFilter.asStateFlow()

    // Filtered voicemails based on selected filter
    val filteredVoicemails: StateFlow<List<Voicemail>> = combine(
        voicemails,
        selectedFilter
    ) { allVoicemails, filter ->
        when (filter) {
            FilterType.ALL -> allVoicemails
            FilterType.READ -> allVoicemails.filter { it.isRead }
            FilterType.UNREAD -> allVoicemails.filter { !it.isRead }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setFilter(filter: FilterType) {
        _selectedFilter.value = filter
    }

    init {
        loadInitialData()
        observeSocketEvents()
        observeMailboxAvailability()

        // Mark initial load complete when data arrives
        viewModelScope.launch {
            voicemails.collect { voicemailList ->
                if (voicemailList.isNotEmpty() && !hasInitiallyLoaded) {
                    hasInitiallyLoaded = true
                    _isLoading.value = false
                    Log.d(TAG, "Initial voicemails loaded: ${voicemailList.size}")
                } else if (hasInitiallyLoaded) {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Observe mailbox availability - triggers load when mailbox becomes available after login
     * This ensures badge shows immediately after login
     */
    private fun observeMailboxAvailability() {
        viewModelScope.launch {
            // Check periodically if mailbox becomes available (after login)
            var lastMailbox = getMailbox()
            while (true) {
                kotlinx.coroutines.delay(1000) // Check every second
                val currentMailbox = getMailbox()
                
                // If mailbox just became available and we haven't loaded yet
                if (currentMailbox.isNotEmpty() && currentMailbox != lastMailbox && !hasInitiallyLoaded) {
                    Log.d(TAG, "Mailbox became available after login, triggering loadInitialData")
                    loadInitialData()
                    lastMailbox = currentMailbox
                    // Stop checking after mailbox is available and loaded
                    break
                }
                lastMailbox = currentMailbox
                
                // Stop checking after 30 seconds to avoid infinite loop
                if (hasInitiallyLoaded) {
                    break
                }
            }
        }
    }

    /**
     * Load initial data - only shows loading indicator on first app launch
     */
    private fun loadInitialData() {
        val mailbox = getMailbox()
        if (mailbox.isEmpty()) {
            Log.w(TAG, "loadInitialData: No mailbox available, skipping")
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "loadInitialData: Fetching voicemails for mailbox=$mailbox")
                val response = apiService.getVoicemails(mailbox = mailbox)
                if (response.isSuccessful && response.body() != null) {
                    val voicemailList = response.body() ?: return@launch
                    Log.d(TAG, "loadInitialData: Got ${voicemailList.size} voicemails")

                    val voicemails = voicemailList.mapNotNull { dto ->
                        val id = dto.id ?: dto.msgnum?.toString() ?: return@mapNotNull null

                        val audioUrl = dto.audioUrl?.let { url ->
                            if (url.startsWith("/")) BuildConfig.API_BASE_URL + url else url
                        }

                        val rawCallerId = dto.callerNumber ?: dto.callerId ?: ""
                        val cleanCallerNumber = PhoneNumberUtils.parseSipCallerId(rawCallerId)
                        val callerNameFromSip = PhoneNumberUtils.parseSipCallerName(rawCallerId)

                        VoicemailEntity(
                            id = id,
                            callerNumber = cleanCallerNumber,
                            callerName = callerNameFromSip,
                            contactId = null,
                            duration = dto.duration ?: 0,
                            isRead = (dto.read ?: 0) == 1,
                            transcription = dto.transcription,
                            audioUrl = audioUrl,
                            localAudioPath = null,
                            receivedAt = dto.origTime?.let { it * 1000 } ?: dto.receivedAt?.let { parseDateTime(it) } ?: System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            syncedAt = System.currentTimeMillis()
                        )
                    }

                    if (voicemails.isNotEmpty()) {
                        voicemailDao.insertVoicemails(voicemails)
                    }
                    hasInitiallyLoaded = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial voicemails", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Listen to Socket.IO events for real-time voicemail updates.
     * This is the professional event-driven approach - no polling needed.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.NewVoicemail -> {
                        Log.d(TAG, "New voicemail received from: ${event.callerNumber}")
                        handleNewVoicemail(event)
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    /**
     * Handle new voicemail event - add to local database immediately (optimistic update)
     */
    private fun handleNewVoicemail(event: SocketEvent.NewVoicemail) {
        viewModelScope.launch {
            try {
                // Convert audio URL to absolute URL if needed
                val audioUrl = event.audioUrl?.let { url ->
                    if (url.startsWith("/")) {
                        BuildConfig.API_BASE_URL + url
                    } else {
                        url
                    }
                }

                // Create voicemail entity and save to local database
                val voicemailEntity = VoicemailEntity(
                    id = event.voicemailId,
                    callerNumber = event.callerNumber,
                    callerName = event.callerName,
                    contactId = null,
                    duration = event.duration,
                    isRead = false, // New voicemails are unread
                    transcription = event.transcription,
                    audioUrl = audioUrl,
                    localAudioPath = null,
                    receivedAt = event.receivedAt,
                    createdAt = System.currentTimeMillis(),
                    syncedAt = System.currentTimeMillis()
                )

                voicemailDao.insertVoicemail(voicemailEntity)
                Log.d(TAG, "New voicemail saved: ${event.voicemailId} from ${event.callerNumber}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving new voicemail", e)
                // Fall back to full refresh
                refresh()
            }
        }
    }

    fun refresh() {
        val mailbox = getMailbox()
        if (mailbox.isEmpty()) {
            Log.w(TAG, "refresh: No mailbox (extension) available, skipping refresh")
            return
        }

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Log.d(TAG, "refresh: Fetching voicemails for mailbox=$mailbox")
                val response = apiService.getVoicemails(mailbox = mailbox)
                if (response.isSuccessful && response.body() != null) {
                    // The API returns an array directly: [{ id: "...", ... }, ...]
                    val voicemailList = response.body() ?: return@launch

                    Log.d(TAG, "refresh: Got ${voicemailList.size} voicemails from API")

                    val voicemails = voicemailList.mapNotNull { dto ->
                        val id = dto.id ?: dto.msgnum?.toString() ?: return@mapNotNull null

                        // Convert relative audio URL to absolute URL
                        val audioUrl = dto.audioUrl?.let { url ->
                            val fullUrl = if (url.startsWith("/")) {
                                BuildConfig.API_BASE_URL + url
                            } else {
                                url
                            }
                            Log.d(TAG, "refresh: Voicemail $id audio URL: $fullUrl")
                            fullUrl
                        }

                        if (audioUrl == null) {
                            Log.w(TAG, "refresh: Voicemail $id has no audio URL! dto.audioUrl=${dto.audioUrl}")
                        }

                        // Parse SIP-style caller ID to get clean phone number
                        // Handles formats like "2102012856" <2102012856>
                        val rawCallerId = dto.callerNumber ?: dto.callerId ?: ""
                        val cleanCallerNumber = PhoneNumberUtils.parseSipCallerId(rawCallerId)
                        val callerNameFromSip = PhoneNumberUtils.parseSipCallerName(rawCallerId)

                        Log.d(TAG, "refresh: Voicemail $id - raw: '$rawCallerId' -> clean: '$cleanCallerNumber', name: '$callerNameFromSip'")

                        com.bitnextechnologies.bitnexdial.data.local.entity.VoicemailEntity(
                            id = id,
                            callerNumber = cleanCallerNumber,
                            callerName = callerNameFromSip,
                            contactId = null,
                            duration = dto.duration ?: 0,
                            isRead = (dto.read ?: 0) == 1,
                            transcription = dto.transcription,
                            audioUrl = audioUrl,
                            localAudioPath = null,
                            receivedAt = dto.origTime?.let { it * 1000 } ?: dto.receivedAt?.let { parseDateTime(it) } ?: System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            syncedAt = System.currentTimeMillis()
                        )
                    }

                    if (voicemails.isNotEmpty()) {
                        voicemailDao.insertVoicemails(voicemails)
                        Log.d(TAG, "refresh: Saved ${voicemails.size} voicemails to local database")
                    }
                } else {
                    Log.e(TAG, "refresh: API error - ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh: Error fetching voicemails", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun togglePlayback(voicemailId: String) {
        viewModelScope.launch {
            if (_playingVoicemailId.value == voicemailId) {
                // Stop playback
                stopPlayback()
            } else {
                // Start playback
                stopPlayback()
                playVoicemail(voicemailId)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun playVoicemail(voicemailId: String) {
        try {
            val voicemail = voicemailDao.getVoicemailById(voicemailId)
            if (voicemail == null) {
                Log.e(TAG, "playVoicemail: Voicemail not found: $voicemailId")
                return
            }

            val audioUrl = voicemail.audioUrl
            if (audioUrl.isNullOrBlank()) {
                Log.e(TAG, "playVoicemail: No audio URL for voicemail: $voicemailId")
                return
            }

            Log.d(TAG, "playVoicemail: Starting playback for $voicemailId, url=$audioUrl")

            // Show loading immediately for user feedback
            _isAudioLoading.value = true
            _playingVoicemailId.value = voicemailId

            // ExoPlayer MUST be created on the main thread
            withContext(Dispatchers.Main) {
                // Create ExoPlayer with optimized load control for fast start
                // Note: minBuffer must be >= bufferForPlaybackAfterRebuffer
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        2500,   // Min buffer (must be >= rebuffer buffer)
                        5000,   // Max buffer
                        500,    // Buffer for playback (fast start)
                        2000    // Buffer for rebuffer
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()

                exoPlayer = ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        // Set up listener for playback state changes
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                when (playbackState) {
                                    Player.STATE_READY -> {
                                        Log.d(TAG, "ExoPlayer: Ready - starting playback")
                                        _isAudioLoading.value = false
                                        _durationMs.value = duration
                                        play()
                                        startProgressTracking()
                                    }
                                    Player.STATE_BUFFERING -> {
                                        Log.d(TAG, "ExoPlayer: Buffering")
                                        _isAudioLoading.value = true
                                    }
                                    Player.STATE_ENDED -> {
                                        Log.d(TAG, "ExoPlayer: Playback ended")
                                        stopPlayback()
                                    }
                                    Player.STATE_IDLE -> {
                                        Log.d(TAG, "ExoPlayer: Idle")
                                    }
                                }
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                                _isAudioLoading.value = false
                                stopPlayback()
                            }
                        })

                        // Set media and prepare
                        val mediaItem = MediaItem.fromUri(audioUrl)
                        setMediaItem(mediaItem)
                        prepare()
                    }
            }

            // Mark as visually read (UI will show as read, but stays in unread tab until screen leaves)
            if (!voicemail.isRead) {
                _visuallyReadVoicemails.value = _visuallyReadVoicemails.value + voicemailId
            }
        } catch (e: Exception) {
            Log.e(TAG, "playVoicemail: Error playing voicemail", e)
            _isAudioLoading.value = false
            stopPlayback()
        }
    }

    /**
     * Start tracking playback progress
     */
    private fun startProgressTracking() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (exoPlayer != null) {
                exoPlayer?.let { player ->
                    val position = player.currentPosition
                    val duration = player.duration
                    _currentPositionMs.value = position
                    if (duration > 0) {
                        _playbackProgress.value = position.toFloat() / duration.toFloat()
                    }
                }
                kotlinx.coroutines.delay(100) // Update every 100ms
            }
        }
    }

    /**
     * Seek to a position (0.0 - 1.0)
     */
    fun seekTo(progress: Float) {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                val position = (progress * duration).toLong()
                player.seekTo(position)
                _currentPositionMs.value = position
                _playbackProgress.value = progress
            }
        }
    }

    /**
     * Seek forward by 10 seconds
     */
    fun seekForward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration)
            player.seekTo(newPosition)
        }
    }

    /**
     * Seek backward by 10 seconds
     */
    fun seekBackward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(newPosition)
        }
    }

    /**
     * Cycle playback speed: 1.0x -> 1.5x -> 2.0x -> 1.0x
     */
    fun cyclePlaybackSpeed() {
        val newSpeed = when (_playbackSpeed.value) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _playbackSpeed.value = newSpeed
        exoPlayer?.setPlaybackSpeed(newSpeed)
        Log.d(TAG, "Playback speed changed to ${newSpeed}x")
    }

    /**
     * Set specific playback speed
     */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        exoPlayer?.setPlaybackSpeed(speed)
    }

    private fun stopPlayback() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
        exoPlayer?.release()
        exoPlayer = null
        _playingVoicemailId.value = null
        _isAudioLoading.value = false
        _playbackProgress.value = 0f
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        _playbackSpeed.value = 1.0f  // Reset speed when stopping
    }

    /**
     * Mark visually read voicemails as actually read in database
     * Called when user leaves voicemail screen or switches to read filter
     * This ensures voicemails move from unread to read list and badge updates immediately
     */
    fun markVisuallyReadAsRead() {
        viewModelScope.launch {
            val toMarkAsRead = _visuallyReadVoicemails.value.toSet() // Create a copy
            if (toMarkAsRead.isNotEmpty()) {
                Log.d(TAG, "markVisuallyReadAsRead: Marking ${toMarkAsRead.size} voicemails as read")
                val mailbox = getMailbox()
                toMarkAsRead.forEach { voicemailId ->
                    // Mark as read in database - this will trigger Flow updates
                    voicemailDao.markAsRead(voicemailId)
                    if (mailbox.isNotEmpty()) {
                        try {
                            apiService.markVoicemailAsRead(
                                MarkVoicemailReadRequest(
                                    mailbox = mailbox,
                                    messageId = voicemailId
                                )
                            )
                            Log.d(TAG, "markVisuallyReadAsRead: Marked voicemail $voicemailId as read on server")
                        } catch (e: Exception) {
                            Log.e(TAG, "markVisuallyReadAsRead: Failed to mark voicemail as read on server", e)
                        }
                    }
                }
                // Clear the set after marking - this ensures UI updates immediately
                _visuallyReadVoicemails.value = emptySet()
                Log.d(TAG, "markVisuallyReadAsRead: Completed. Badge and filtered list will update automatically")
            }
        }
    }

    fun deleteVoicemail(voicemailId: String) {
        viewModelScope.launch {
            try {
                stopPlayback()
                // Server doesn't support delete, only delete locally
                voicemailDao.deleteVoicemailById(voicemailId)
                Log.d(TAG, "deleteVoicemail: Deleted voicemail $voicemailId locally")
            } catch (e: Exception) {
                Log.e(TAG, "deleteVoicemail: Error deleting voicemail", e)
            }
        }
    }

    private fun parseDateTime(dateString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
