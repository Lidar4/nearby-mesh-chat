package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class PeerConnection(
    val peerId: String,
    val peerName: String,
    val socket: Socket,
    val writer: PrintWriter,
    val ipAddress: String,
    val port: Int,
    var isIncoming: Boolean
)

class P2PManager(private val context: Context) {

    private val TAG = "P2PManager"
    private val SERVICE_TYPE = "_nearbychat._tcp."
    private val PROTOCOL_VERSION = 1
    private val MAX_HOPS = 8

    private var currentServerSocketPort: Int = 8888

    // Device Profile
    var localDeviceId: String = ""
    var localDeviceName: String = ""

    // Multi-Peer Sockets Pool
    val activePeers = ConcurrentHashMap<String, PeerConnection>()

    // Routing layer
    lateinit var meshRouter: MeshRouter

    // Connection States
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    // Incoming Connection Request Details
    private val _incomingRequest = MutableStateFlow<Pair<String, String>?>(null)
    val incomingRequest: StateFlow<Pair<String, String>?> = _incomingRequest

    // Active Connected Peer Details
    private val _connectedPeerId = MutableStateFlow<String?>(null)
    val connectedPeerId: StateFlow<String?> = _connectedPeerId

    private val _connectedPeerName = MutableStateFlow<String?>(null)
    val connectedPeerName: StateFlow<String?> = _connectedPeerName

    private val _connectedPeerIp = MutableStateFlow<String?>(null)
    val connectedPeerIp: StateFlow<String?> = _connectedPeerIp

    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping

    // Sockets and Background Jobs
    private var serverSocket: ServerSocket? = null
    private val p2pScope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private val clientReadJobs = ConcurrentHashMap<String, Job>()

    // Map to prevent duplicates during resolution and prune stale services
    private val resolvedServicesMap = ConcurrentHashMap<String, DiscoveredDevice>()

    // NSD Managers
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Callbacks
    var onMessageReceived: ((msgId: String, senderId: String, text: String, type: String, timestamp: Long) -> Unit)? = null
    var onMessageAckReceived: ((msgId: String, status: String) -> Unit)? = null
    var onCallRequested: ((peerName: String) -> Unit)? = null
    var onCallAcceptedWithPort: ((Int) -> Unit)? = null
    var onCallRejected: (() -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onPeerConnected: ((peerId: String, peerName: String) -> Unit)? = null

    // End-to-End Conversation symmetric passphrases: peerId -> passphrase
    private val conversationPasswords = ConcurrentHashMap<String, String>()

    init {
        // Initialize SharedPreferences and fetch or create Device ID and Name
        val prefs = context.getSharedPreferences("nearby_chat_prefs", Context.MODE_PRIVATE)
        localDeviceId = prefs.getString("device_id", "") ?: ""
        if (localDeviceId.isEmpty()) {
            localDeviceId = "NC-" + java.util.UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("device_id", localDeviceId).apply()
        }
        localDeviceName = prefs.getString("device_name", "") ?: ""
        if (localDeviceName.isEmpty()) {
            localDeviceName = "Device " + android.os.Build.MODEL.take(10)
            prefs.edit().putString("device_name", localDeviceName).apply()
        }

        // Initialize Routing Layer
        meshRouter = MeshRouter(localDeviceId)

        // Load persisted passphrases
        loadSavedPassphrases()
    }

    private fun loadSavedPassphrases() {
        try {
            val prefs = context.getSharedPreferences("nearby_chat_passwords", Context.MODE_PRIVATE)
            prefs.all.forEach { (key, value) ->
                if (value is String) {
                    conversationPasswords[key] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved passphrases: ${e.message}")
        }
    }

    fun setConversationPassphrase(peerId: String, passphrase: String) {
        if (passphrase.isBlank()) {
            conversationPasswords.remove(peerId)
            context.getSharedPreferences("nearby_chat_passwords", Context.MODE_PRIVATE)
                .edit().remove(peerId).apply()
        } else {
            conversationPasswords[peerId] = passphrase
            context.getSharedPreferences("nearby_chat_passwords", Context.MODE_PRIVATE)
                .edit().putString(peerId, passphrase).apply()
        }
    }

    fun getConversationPassphrase(peerId: String): String? {
        return conversationPasswords[peerId]
    }

    // Persist Peer Public Keys in secure private preferences
    fun getPeerPublicKey(peerId: String): String? {
        val prefs = context.getSharedPreferences("nearby_chat_pubkeys", Context.MODE_PRIVATE)
        return prefs.getString(peerId, null)
    }

    fun savePeerPublicKey(peerId: String, publicKey: String) {
        val prefs = context.getSharedPreferences("nearby_chat_pubkeys", Context.MODE_PRIVATE)
        prefs.edit().putString(peerId, publicKey).apply()
    }

    fun updateProfile(name: String) {
        localDeviceName = name
        val prefs = context.getSharedPreferences("nearby_chat_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("device_name", name).apply()

        // Restart advertising if we are currently running
        if (registrationListener != null) {
            stopAdvertising()
            startAdvertising(currentServerSocketPort)
        }
    }

    /**
     * Start local Server socket to listen for incoming connection requests
     */
    fun startServer() {
        if (serverJob != null && serverSocket != null && !serverSocket!!.isClosed) return
        
        serverJob?.cancel()
        serverJob = p2pScope.launch {
            try {
                serverSocket = try {
                    ServerSocket(8888)
                } catch (e: Exception) {
                    ServerSocket(0)
                }
                val port = serverSocket?.localPort ?: 8888
                currentServerSocketPort = port
                Log.d(TAG, "Server socket bound to port: $port")

                // Start advertising over NSD
                startAdvertising(port)

                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Incoming TCP socket connection from: ${socket.inetAddress.hostAddress}")
                    handleIncomingHandshake(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error: ${e.message}")
            }
        }
    }

    private fun handleIncomingHandshake(socket: Socket) {
        p2pScope.launch {
            try {
                socket.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val line = reader.readLine()
                if (line == null) {
                    socket.close()
                    return@launch
                }

                val json = JSONObject(line)
                val type = json.optString("type")

                if (type == "connect_request") {
                    val peerId = json.getString("senderId")
                    val peerName = json.getString("senderName")
                    val peerPubKey = json.optString("publicKey", "")
                    Log.d(TAG, "Handshake request from: $peerName ($peerId)")

                    if (peerId.isBlank() || peerName.isBlank()) {
                        socket.close()
                        return@launch
                    }

                    // Save Peer Public Key
                    if (peerPubKey.isNotBlank()) {
                        savePeerPublicKey(peerId, peerPubKey)
                    }

                    // Include our Local Public Key in Handshake Accept frame
                    val acceptPayload = JSONObject().apply {
                        put("protocolVersion", PROTOCOL_VERSION)
                        put("type", "connect_accept")
                        put("senderId", localDeviceId)
                        put("senderName", localDeviceName)
                        put("publicKey", EncryptionHelper.getLocalPublicKey())
                    }
                    writer.println(acceptPayload.toString())

                    val connection = PeerConnection(
                        peerId = peerId,
                        peerName = peerName,
                        socket = socket,
                        writer = writer,
                        ipAddress = socket.inetAddress.hostAddress ?: "",
                        port = socket.port,
                        isIncoming = true
                    )

                    activePeers[peerId] = connection
                    _connectedPeerId.value = peerId
                    _connectedPeerName.value = peerName
                    _connectedPeerIp.value = connection.ipAddress
                    _connectionState.value = ConnectionState.CONNECTED
                    onPeerConnected?.invoke(peerId, peerName)

                    // Learn route
                    meshRouter.learnRoute(peerId, peerId, MAX_HOPS, MAX_HOPS)

                    socket.soTimeout = 0
                    startReading(connection, reader)
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Incoming handshake error: ${e.message}")
                socket.close()
            }
        }
    }

    /**
     * Initiate connection to a discovered device (Client side)
     */
    fun connectToDevice(device: DiscoveredDevice) {
        if (activePeers.containsKey(device.id)) return

        p2pScope.launch {
            try {
                Log.d(TAG, "Connecting client socket to: ${device.ipAddress}:${device.port}")
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(device.ipAddress, device.port), 8000)
                
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Include our local Public Key in Handshake request
                val requestPayload = JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("type", "connect_request")
                    put("senderId", localDeviceId)
                    put("senderName", localDeviceName)
                    put("publicKey", EncryptionHelper.getLocalPublicKey())
                }
                writer.println(requestPayload.toString())

                socket.soTimeout = 10000
                val line = reader.readLine()
                socket.soTimeout = 0

                if (line != null) {
                    val json = JSONObject(line)
                    if (json.optString("type") == "connect_accept") {
                        val peerId = json.getString("senderId")
                        val peerName = json.getString("senderName")
                        val peerPubKey = json.optString("publicKey", "")

                        if (peerPubKey.isNotBlank()) {
                            savePeerPublicKey(peerId, peerPubKey)
                        }

                        val connection = PeerConnection(
                            peerId = peerId,
                            peerName = peerName,
                            socket = socket,
                            writer = writer,
                            ipAddress = device.ipAddress,
                            port = device.port,
                            isIncoming = false
                        )

                        activePeers[peerId] = connection
                        _connectedPeerId.value = peerId
                        _connectedPeerName.value = peerName
                        _connectedPeerIp.value = device.ipAddress
                        _connectionState.value = ConnectionState.CONNECTED
                        onPeerConnected?.invoke(peerId, peerName)

                        // Learn route
                        meshRouter.learnRoute(peerId, peerId, MAX_HOPS, MAX_HOPS)

                        Log.d(TAG, "Successfully connected to mesh neighbor: $peerName")
                        startReading(connection, reader)
                    } else {
                        socket.close()
                    }
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed client connection to ${device.name}: ${e.message}")
            }
        }
    }

    /**
     * Dedicated background message reader loop for an active PeerConnection
     */
    private fun startReading(connection: PeerConnection, reader: BufferedReader) {
        val job = p2pScope.launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type")

                        if (type == "mesh_route") {
                            handleMeshRoutePacket(json, connection.peerId)
                        } else if (type == "mesh_route_ack") {
                            handleMeshRouteAckPacket(json, connection.peerId)
                        } else if (type == "typing") {
                            _isPeerTyping.value = json.optBoolean("isTyping", false)
                        }
                    } catch (jsonEx: Exception) {
                        Log.e(TAG, "Safely caught malformed packet: ${jsonEx.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Read loop finished for peer ${connection.peerId}: ${e.message}")
            } finally {
                disconnectPeer(connection.peerId)
            }
        }
        clientReadJobs[connection.peerId] = job
    }

    /**
     * Processes incoming routed messages (Mesh Packets)
     */
    private fun handleMeshRoutePacket(json: JSONObject, previousHopId: String) {
        val messageId = json.getString("messageId")
        val senderId = json.getString("senderId")
        val senderName = json.getString("senderName")
        val receiverId = json.getString("receiverId")
        val receiverName = json.getString("receiverName")
        val ttl = json.getInt("ttl")
        val timestamp = json.getLong("timestamp")
        val encryptedPayload = json.getString("payload")
        val messageType = json.optString("messageType", "text")

        // 1. Loop Prevention and Duplicate Suppression
        if (!meshRouter.checkAndRecordMessage(messageId)) {
            return
        }

        // 2. Learn route back to sender
        meshRouter.learnRoute(senderId, previousHopId, ttl, MAX_HOPS)

        // 3. Process destinations
        if (receiverId == localDeviceId) {
            when (messageType) {
                "text" -> {
                    // Decrypt hybrid digital envelope or symmetric passphrase fallback
                    val decryptedText = when {
                        encryptedPayload.startsWith("plain:") -> encryptedPayload.removePrefix("plain:")
                        encryptedPayload.startsWith("sym:") -> {
                            val passphrase = conversationPasswords[senderId] ?: ""
                            EncryptionHelper.decryptSymmetric(encryptedPayload, passphrase)
                        }
                        else -> EncryptionHelper.decryptWithPrivateKey(encryptedPayload)
                    }

                    Log.d(TAG, "Mesh text packet arrived! Sender: $senderName, Decrypted: $decryptedText")
                    onMessageReceived?.invoke(messageId, senderId, decryptedText, "text", timestamp)

                    // Automated end-to-end delivery acknowledgement
                    sendRoutedAck(messageId, senderId, "delivered")
                }
                "mesh_key_request" -> {
                    Log.d(TAG, "Received multi-hop mesh public key request from $senderName. Sending response...")
                    sendMeshKeyResponse(senderId, senderName)
                }
                "mesh_key_response" -> {
                    val peerPubKey = json.optString("peerPublicKey", "")
                    if (peerPubKey.isNotBlank()) {
                        Log.d(TAG, "Received multi-hop mesh public key response from $senderName!")
                        savePeerPublicKey(senderId, peerPubKey)
                    }
                }
            }
        } else {
            // Forwarding engine
            if (ttl > 1) {
                val updatedJson = JSONObject(json.toString()).apply {
                    put("ttl", ttl - 1)
                    put("previousHopId", localDeviceId)
                }
                forwardMeshPacket(receiverId, updatedJson, previousHopId)
            }
        }
    }

    /**
     * Processes incoming routed acknowledgements
     */
    private fun handleMeshRouteAckPacket(json: JSONObject, previousHopId: String) {
        val ackId = json.getString("ackId")
        val senderId = json.getString("senderId")
        val receiverId = json.getString("receiverId")
        val targetMessageId = json.getString("targetMessageId")
        val status = json.getString("status")
        val ttl = json.getInt("ttl")

        if (!meshRouter.checkAndRecordMessage(ackId)) {
            return
        }

        meshRouter.learnRoute(senderId, previousHopId, ttl, MAX_HOPS)

        if (receiverId == localDeviceId) {
            Log.d(TAG, "Mesh ACK arrived for message $targetMessageId -> status: $status")
            onMessageAckReceived?.invoke(targetMessageId, status)
        } else {
            if (ttl > 1) {
                val updatedJson = JSONObject(json.toString()).apply {
                    put("ttl", ttl - 1)
                    put("previousHopId", localDeviceId)
                }
                forwardMeshPacket(receiverId, updatedJson, previousHopId)
            }
        }
    }

    /**
     * Raw transmission helper to forward mesh routing frames
     */
    private fun forwardMeshPacket(receiverId: String, payloadJson: JSONObject, excludeNeighborId: String? = null) {
        val nextHop = meshRouter.getNextHopFor(receiverId)
        if (nextHop != null && activePeers.containsKey(nextHop)) {
            sendRawToPeer(nextHop, payloadJson.toString())
        } else {
            activePeers.forEach { (peerId, conn) ->
                if (peerId != excludeNeighborId) {
                    sendRawToPeer(peerId, payloadJson.toString())
                }
            }
        }
    }

    /**
     * Transmit a fresh text message into the multi-hop mesh network
     */
    fun sendTextMessage(msgId: String, text: String, receiverId: String, receiverName: String) {
        p2pScope.launch {
            try {
                val passphrase = conversationPasswords[receiverId]
                val recipientPubKey = getPeerPublicKey(receiverId)

                val encryptedText = if (!passphrase.isNullOrBlank()) {
                    // 1. If symmetric passphrase is set, use PBKDF2 + AES-GCM
                    EncryptionHelper.encryptSymmetric(text, passphrase)
                } else if (!recipientPubKey.isNullOrBlank()) {
                    // 2. If asymmetric Public Key is available, use digital envelope (RSA-OAEP + AES-GCM)
                    EncryptionHelper.encryptWithPublicKey(text, recipientPubKey)
                } else {
                    Log.d(TAG, "Recipient public key is unknown; requesting key and sending first message reliably.")
                    sendMeshKeyRequest(receiverId, receiverName)
                    "plain:$text"
                }

                val routeEnvelope = JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("type", "mesh_route")
                    put("messageId", msgId)
                    put("senderId", localDeviceId)
                    put("senderName", localDeviceName)
                    put("receiverId", receiverId)
                    put("receiverName", receiverName)
                    put("previousHopId", localDeviceId)
                    put("payload", encryptedText)
                    put("messageType", "text")
                    put("ttl", MAX_HOPS)
                    put("timestamp", System.currentTimeMillis())
                }

                meshRouter.checkAndRecordMessage(msgId)
                forwardMeshPacket(receiverId, routeEnvelope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build or send mesh message: ${e.message}")
            }
        }
    }

    /**
     * Sends routed public key query request over the mesh network
     */
    private fun sendMeshKeyRequest(receiverId: String, receiverName: String) {
        p2pScope.launch {
            try {
                val requestId = java.util.UUID.randomUUID().toString()
                val routeEnvelope = JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("type", "mesh_route")
                    put("messageId", requestId)
                    put("senderId", localDeviceId)
                    put("senderName", localDeviceName)
                    put("receiverId", receiverId)
                    put("receiverName", receiverName)
                    put("previousHopId", localDeviceId)
                    put("payload", "")
                    put("messageType", "mesh_key_request")
                    put("ttl", MAX_HOPS)
                    put("timestamp", System.currentTimeMillis())
                }
                meshRouter.checkAndRecordMessage(requestId)
                forwardMeshPacket(receiverId, routeEnvelope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send mesh public key request: ${e.message}")
            }
        }
    }

    /**
     * Sends routed public key response containing our public key back to the sender
     */
    private fun sendMeshKeyResponse(receiverId: String, receiverName: String) {
        p2pScope.launch {
            try {
                val responseId = java.util.UUID.randomUUID().toString()
                val routeEnvelope = JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("type", "mesh_route")
                    put("messageId", responseId)
                    put("senderId", localDeviceId)
                    put("senderName", localDeviceName)
                    put("receiverId", receiverId)
                    put("receiverName", receiverName)
                    put("previousHopId", localDeviceId)
                    put("payload", "")
                    put("peerPublicKey", EncryptionHelper.getLocalPublicKey())
                    put("messageType", "mesh_key_response")
                    put("ttl", MAX_HOPS)
                    put("timestamp", System.currentTimeMillis())
                }
                meshRouter.checkAndRecordMessage(responseId)
                forwardMeshPacket(receiverId, routeEnvelope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send mesh public key response: ${e.message}")
            }
        }
    }

    /**
     * Sends routed delivery or read acknowledgement across the mesh
     */
    private fun sendRoutedAck(targetMessageId: String, receiverId: String, status: String) {
        p2pScope.launch {
            try {
                val ackId = java.util.UUID.randomUUID().toString()
                val routeEnvelope = JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("type", "mesh_route_ack")
                    put("ackId", ackId)
                    put("senderId", localDeviceId)
                    put("receiverId", receiverId)
                    put("targetMessageId", targetMessageId)
                    put("status", status)
                    put("ttl", MAX_HOPS)
                    put("timestamp", System.currentTimeMillis())
                }

                meshRouter.checkAndRecordMessage(ackId)
                forwardMeshPacket(receiverId, routeEnvelope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send routed ACK: ${e.message}")
            }
        }
    }

    fun sendReadAck(msgId: String) {}
    fun sendDeliveryAck(msgId: String) {}

    /**
     * Transmits a typing indicator to all directly connected active neighbors
     */
    fun sendTypingIndicator(isTyping: Boolean) {
        p2pScope.launch {
            val payload = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("type", "typing")
                put("isTyping", isTyping)
                put("senderId", localDeviceId)
            }
            activePeers.forEach { (peerId, conn) ->
                try {
                    conn.writer.println(payload.toString())
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun sendRawToPeer(peerId: String, rawJsonString: String) {
        val conn = activePeers[peerId] ?: return
        p2pScope.launch {
            try {
                conn.writer.println(rawJsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Raw send failed to peer $peerId: ${e.message}")
                disconnectPeer(peerId)
            }
        }
    }

    private fun disconnectPeer(peerId: String) {
        val conn = activePeers.remove(peerId) ?: return
        Log.d(TAG, "Disconnecting from peer $peerId...")
        
        clientReadJobs.remove(peerId)?.cancel()

        try {
            conn.writer.close()
            conn.socket.close()
        } catch (e: Exception) {
            // ignore
        }

        if (activePeers.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedPeerId.value = null
            _connectedPeerName.value = null
            _connectedPeerIp.value = null
        } else {
            val nextConn = activePeers.values.firstOrNull()
            if (nextConn != null) {
                _connectedPeerId.value = nextConn.peerId
                _connectedPeerName.value = nextConn.peerName
                _connectedPeerIp.value = nextConn.ipAddress
            }
        }
    }

    /**
     * Disconnects all active peer sessions cleanly
     */
    fun disconnect() {
        activePeers.keys.forEach { peerId ->
            disconnectPeer(peerId)
        }
    }

    fun acceptIncomingRequest() {}
    fun rejectIncomingRequest() {}

    /**
     * NSD (mDNS) Service Registration/Advertising
     */
    private fun startAdvertising(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            val sanitizedName = localDeviceName.replace("[^a-zA-Z0-9 ]".toRegex(), "").take(15)
            serviceName = "NC_${localDeviceId}_$sanitizedName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD Service Registered successfully: ${info.serviceName} on port ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Registration Failed: error code $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD Service Unregistered successfully: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Unregistration Failed: error code $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        try {
            if (registrationListener != null) {
                nsdManager.unregisterService(registrationListener)
                registrationListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NSD service: ${e.message}")
        }
    }

    /**
     * NSD mDNS Discovery
     */
    fun startDiscovery() {
        if (discoveryListener != null) {
            stopDiscovery()
        }
        _discoveredDevices.value = emptyList()
        resolvedServicesMap.clear()
        
        _connectionState.value = ConnectionState.DISCOVERING

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Start Discovery Failed: error $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Stop Discovery Failed: error $errorCode")
                stopDiscovery()
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD Discovery Started successfully")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD Discovery Stopped successfully")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service Found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType == SERVICE_TYPE || serviceInfo.serviceType.startsWith(SERVICE_TYPE)) {
                    val sName = serviceInfo.serviceName
                    if (sName.startsWith("NC_")) {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.e(TAG, "NSD Resolve Failed: error $errorCode")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                Log.d(TAG, "NSD Service Resolved: ${info.serviceName} at ${info.host.hostAddress}:${info.port}")
                                handleServiceResolved(info)
                            }
                        })
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service Lost: ${serviceInfo.serviceName}")
                handleServiceLost(serviceInfo.serviceName)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovering NSD services: ${e.message}")
        }
    }

    private fun handleServiceResolved(info: NsdServiceInfo) {
        val sName = info.serviceName
        val parts = sName.split("_")
        if (parts.size >= 3) {
            val peerId = parts[1]
            val peerName = parts.subList(2, parts.size).joinToString("_")

            if (peerId == localDeviceId) return

            val resolvedDevice = DiscoveredDevice(
                id = peerId,
                name = peerName,
                ipAddress = info.host.hostAddress ?: "",
                port = info.port,
                status = ConnectionState.AVAILABLE
            )

            resolvedServicesMap[peerId] = resolvedDevice
            _discoveredDevices.value = resolvedServicesMap.values.toList()

            // AUTO-MESH CONNECTION SCHEDULER
            if (localDeviceId < peerId) {
                Log.d(TAG, "Auto-connecting to mesh neighbor: $peerName (Symmetry ordering: $localDeviceId < $peerId)")
                connectToDevice(resolvedDevice)
            }
        }
    }

    private fun handleServiceLost(serviceName: String) {
        val parts = serviceName.split("_")
        if (parts.size >= 3) {
            val peerId = parts[1]
            resolvedServicesMap.remove(peerId)
            _discoveredDevices.value = resolvedServicesMap.values.toList()
        }
    }

    fun stopDiscovery() {
        try {
            if (discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during NSD stopServiceDiscovery: ${e.message}")
        } finally {
            discoveryListener = null
            if (_connectionState.value == ConnectionState.DISCOVERING) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP address", ex)
        }
        return null
    }

    fun sendCallRequest() {}
    fun sendCallAccept(audioPort: Int) {}
    fun sendCallReject() {}
    fun sendCallEnd() {}

    fun stopAll() {
        stopDiscovery()
        stopAdvertising()
        disconnect()
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
