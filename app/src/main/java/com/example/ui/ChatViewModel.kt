package com.example.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.network.AudioCallManager
import com.example.network.CallState
import com.example.network.ConnectionState
import com.example.network.DiscoveredDevice
import com.example.network.P2PManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen {
    SPLASH,
    HOME,
    CHAT,
    AUDIO_CALL
}

enum class HomeTab {
    CHATS,
    NEARBY,
    SETTINGS
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ChatViewModel"

    private val database = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(database)
    
    val p2pManager = P2PManager(application)
    val audioCallManager = AudioCallManager()

    // Navigation state
    private val _currentScreen = MutableStateFlow(Screen.SPLASH)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _currentHomeTab = MutableStateFlow(HomeTab.CHATS)
    val currentHomeTab: StateFlow<HomeTab> = _currentHomeTab.asStateFlow()

    // Active conversation
    private val _activePeerId = MutableStateFlow<String?>(null)
    val activePeerId: StateFlow<String?> = _activePeerId.asStateFlow()

    private val _activePeerName = MutableStateFlow<String?>(null)
    val activePeerName: StateFlow<String?> = _activePeerName.asStateFlow()

    // Observe DB conversations
    val conversations: StateFlow<List<ConversationEntity>> = repository.allConversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Observe messages dynamically for the active conversation
    val activeMessages: StateFlow<List<MessageEntity>> = _activePeerId
        .flatMapLatest { peerId ->
            if (peerId != null) {
                repository.getMessages(peerId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Exposed network states from P2PManager
    val connectionState: StateFlow<ConnectionState> = p2pManager.connectionState
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = p2pManager.discoveredDevices
    val incomingRequest: StateFlow<Pair<String, String>?> = p2pManager.incomingRequest
    val isPeerTyping: StateFlow<Boolean> = p2pManager.isPeerTyping

    // Exposed audio call states
    val callState: StateFlow<CallState> = audioCallManager.callState
    val callDurationSeconds: StateFlow<Int> = audioCallManager.durationSeconds

    // Local profile properties
    private val _localDeviceName = MutableStateFlow(p2pManager.localDeviceName)
    val localDeviceName: StateFlow<String> = _localDeviceName.asStateFlow()

    val localDeviceId: String = p2pManager.localDeviceId

    // Audio states
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    init {
        // Reset all database connection statuses on app start so we don't display stale connected peers
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetConnectionStatuses()
        }

        // Wire callbacks from network layer
        p2pManager.onMessageReceived = { msgId, senderId, text, type, timestamp ->
            handleReceivedMessage(msgId, senderId, text, type, timestamp)
        }

        p2pManager.onMessageAckReceived = { msgId, status ->
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateMessageStatus(msgId, status)
            }
        }

        p2pManager.onPeerConnected = { peerId, peerName ->
            viewModelScope.launch(Dispatchers.IO) {
                val existing = repository.getConversation(peerId)
                repository.insertOrUpdateConversation(
                    ConversationEntity(
                        peerId = peerId,
                        peerName = peerName,
                        lastMessageText = existing?.lastMessageText ?: "Connected",
                        lastMessageTime = existing?.lastMessageTime ?: System.currentTimeMillis(),
                        unreadCount = existing?.unreadCount ?: 0,
                        isConnected = true,
                        isGroup = existing?.isGroup ?: false,
                        groupOwnerId = existing?.groupOwnerId,
                        groupMembers = existing?.groupMembers
                    )
                )
            }
        }

        p2pManager.onCallRequested = { peerName ->
            // Update audio call state
            audioCallManager.setCallState(CallState.RINGING)
            navigateTo(Screen.AUDIO_CALL)
            vibrateDevice()
        }

        p2pManager.onCallAcceptedWithPort = { port ->
            viewModelScope.launch(Dispatchers.IO) {
                audioCallManager.setCallState(CallState.CONNECTED)
                val peerIp = p2pManager.connectedPeerIp.value ?: ""
                audioCallManager.connectToAudioServer(
                    peerIp = peerIp,
                    port = port,
                    onConnected = {
                        Log.d(TAG, "VoIP audio call stream connected!")
                    },
                    onError = { err ->
                        Log.e(TAG, "Audio call error: $err")
                        endCall()
                    }
                )
            }
        }

        p2pManager.onCallRejected = {
            audioCallManager.setCallState(CallState.FAILED)
            endCallSilently()
        }

        p2pManager.onCallEnded = {
            audioCallManager.setCallState(CallState.ENDED)
            endCallSilently()
        }

        // Start listening Server Socket
        p2pManager.startServer()

        // Store-and-Forward periodic background retry engine
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(10000) // retry every 10 seconds
                try {
                    if (p2pManager.activePeers.isNotEmpty()) {
                        val pending = repository.getPendingMessages()
                        for (msg in pending) {
                            Log.d(TAG, "Store-and-Forward retry: sending ${msg.id} to ${msg.receiverId}")
                            val conv = repository.getConversation(msg.receiverId)
                            val rName = conv?.peerName ?: "Unknown Device"
                            p2pManager.sendTextMessage(msg.id, msg.text, msg.receiverId, rName)
                            repository.updateMessageStatus(msg.id, "sent")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Store-and-Forward error: ${e.message}")
                }
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setHomeTab(tab: HomeTab) {
        _currentHomeTab.value = tab
    }

    fun selectConversation(peerId: String, peerName: String) {
        _activePeerId.value = peerId
        _activePeerName.value = peerName
        
        // Clear unreads locally in database
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearUnreads(peerId)
        }
        navigateTo(Screen.CHAT)
    }

    fun goBackFromChat() {
        _activePeerId.value = null
        _activePeerName.value = null
        navigateTo(Screen.HOME)
    }

    fun updateProfileName(name: String) {
        p2pManager.updateProfile(name)
        _localDeviceName.value = p2pManager.localDeviceName
    }

    fun setConversationPassphrase(peerId: String, passphrase: String) {
        p2pManager.setConversationPassphrase(peerId, passphrase)
    }

    fun getConversationPassphrase(peerId: String): String? {
        return p2pManager.getConversationPassphrase(peerId)
    }

    fun startDiscovery() {
        p2pManager.startDiscovery()
    }

    fun stopDiscovery() {
        p2pManager.stopDiscovery()
    }

    fun connectToDevice(device: DiscoveredDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            p2pManager.connectToDevice(device)
        }
    }

    fun disconnect() {
        p2pManager.disconnect()
        viewModelScope.launch(Dispatchers.IO) {
            val peerId = _activePeerId.value
            if (peerId != null) {
                repository.updateConnectionStatus(peerId, false)
            }
        }
    }

    fun acceptIncomingConnection() {
        p2pManager.acceptIncomingRequest()
        viewModelScope.launch(Dispatchers.IO) {
            val request = p2pManager.incomingRequest.value
            if (request != null) {
                val conversation = ConversationEntity(
                    peerId = request.first,
                    peerName = request.second,
                    lastMessageText = "Connected offline",
                    lastMessageTime = System.currentTimeMillis(),
                    unreadCount = 0,
                    isConnected = true
                )
                repository.insertOrUpdateConversation(conversation)
            }
        }
    }

    fun rejectIncomingConnection() {
        p2pManager.rejectIncomingRequest()
    }

    /**
     * Send Message Action from UI
     */
    fun sendMessage(text: String) {
        val peerId = _activePeerId.value ?: return
        val peerName = _activePeerName.value ?: "Unknown Device"
        val msgId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Create entity
        val msgEntity = MessageEntity(
            id = msgId,
            conversationId = peerId,
            senderId = localDeviceId,
            receiverId = peerId,
            text = text,
            timestamp = timestamp,
            messageType = "text",
            status = "sending"
        )

        viewModelScope.launch(Dispatchers.IO) {
            // Save to local DB first
            repository.saveSentMessage(msgEntity)

            // Send via multi-hop mesh transport
            if (p2pManager.activePeers.isNotEmpty()) {
                p2pManager.sendTextMessage(msgId, text, peerId, peerName)
                // Mark as sent (dispatched to local transport pool)
                repository.updateMessageStatus(msgId, "sent")
            } else {
                Log.d(TAG, "No active connections. Kept in store-and-forward queue.")
            }
        }
    }

    fun sendTypingIndicator(isTyping: Boolean) {
        p2pManager.sendTypingIndicator(isTyping)
    }

    private fun handleReceivedMessage(msgId: String, senderId: String, text: String, type: String, timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val isCurrentChat = _activePeerId.value == senderId
            val status = if (isCurrentChat) "read" else "delivered"

            val message = MessageEntity(
                id = msgId,
                conversationId = senderId,
                senderId = senderId,
                receiverId = localDeviceId,
                text = text,
                timestamp = timestamp,
                messageType = type,
                status = status
            )

            // Save in DB
            val peerName = p2pManager.connectedPeerName.value ?: "Device $senderId"
            repository.saveReceivedMessage(message, peerName, updateUnread = !isCurrentChat)

            // Send ACK back according to the versioned protocol specification
            if (status == "read") {
                p2pManager.sendReadAck(msgId)
            } else {
                p2pManager.sendDeliveryAck(msgId)
            }
        }
    }

    /**
     * Delete entire Conversation thread locally
     */
    fun deleteConversation(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteConversation(peerId)
        }
    }

    /**
     * Outgoing Call Request
     */
    fun startAudioCall() {
        if (connectionState.value != ConnectionState.CONNECTED) return
        
        viewModelScope.launch(Dispatchers.IO) {
            audioCallManager.setCallState(CallState.CALLING)
            navigateTo(Screen.AUDIO_CALL)
            p2pManager.sendCallRequest()
        }
    }

    /**
     * Incoming Call Accept
     */
    fun acceptIncomingCall() {
        viewModelScope.launch(Dispatchers.IO) {
            audioCallManager.setCallState(CallState.CONNECTED)
            
            // Start audio PCM server immediately, which allocates a dynamic port if needed
            audioCallManager.startAudioServer(
                onServerBound = { boundPort ->
                    // Return the dynamic audio port details to the caller
                    p2pManager.sendCallAccept(boundPort)
                },
                onConnected = {
                    Log.d(TAG, "VoIP Audio Server listening and streaming PCM")
                },
                onError = { err ->
                    Log.e(TAG, "Audio call server streaming failed: $err")
                    endCall()
                }
            )
        }
    }

    /**
     * Incoming Call Reject
     */
    fun rejectIncomingCall() {
        p2pManager.sendCallReject()
        audioCallManager.setCallState(CallState.IDLE)
        navigateTo(Screen.CHAT)
    }

    /**
     * End ongoing audio call
     */
    fun endCall() {
        p2pManager.sendCallEnd()
        audioCallManager.endCall()
        navigateTo(Screen.CHAT)
    }

    private fun endCallSilently() {
        audioCallManager.endCall()
        viewModelScope.launch(Dispatchers.Main) {
            if (_currentScreen.value == Screen.AUDIO_CALL) {
                navigateTo(Screen.CHAT)
            }
        }
    }

    fun toggleMute() {
        _isMuted.value = audioCallManager.toggleMute()
    }

    fun toggleSpeaker() {
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isSpeakerOn.value = audioCallManager.toggleSpeaker(audioManager)
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice() {
        try {
            val vibrator = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(500)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onCleared() {
        super.onCleared()
        p2pManager.stopAll()
        audioCallManager.endCall()
    }
}
