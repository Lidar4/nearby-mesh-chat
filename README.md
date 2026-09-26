# Nearby Chat - Real P2P Offline Chat & Audio Calling

Nearby Chat is a completely serverless, 100% offline peer-to-peer messenger and VoIP audio calling application built natively using **Kotlin**, **Jetpack Compose**, and **Android SDK APIs**. It is designed for flights, remote field research, music festivals, natural disasters, or any environment completely devoid of Cellular, Wi-Fi router internet, or cloud servers.

---

## 1. Project Architecture

The application is structured around clean MVVM and Repository principles:

```
app/src/main/java/com/example/
├── data/
│   ├── AppDatabase.kt        # Room database builder for local SQLite persistence
│   ├── Daos.kt               # DAOs providing reactive Flow updates for chats/messages
│   ├── Entities.kt           # Room table definitions for Conversations & ChatMessages
│   └── ChatRepository.kt     # Coordinating DB operations & status tracking
├── network/
│   ├── Models.kt             # Connection states & Peer definitions
│   ├── AudioCallManager.kt   # Real-time PCM microphone recording & playback loops over sockets
│   └── P2PManager.kt         # mDNS/NSD Zero-Config advertisement, discovery & TCP sockets
└── ui/
    ├── theme/                # Material 3 colors, typography, & shapes
    ├── AudioCallScreen.kt    # Responsive VoIP calling interface with timer & mute/speaker controls
    ├── ChatScreen.kt         # Thread bubble screen with ticking/read checks & typing indicators
    ├── HomeScreen.kt         # Modern WhatsApp-inspired three-tab structure (Chats, Nearby, Settings)
    ├── SplashScreen.kt       # Onboarding intro displaying custom splash asset & name setup
    └── ChatViewModel.kt      # Central state synchronizer mapping socket payloads to database and UI
```

---

## 2. Offline Network Architecture (Zero-Config)

Nearby Chat leverages standard local area network technology to communicate directly.

### A. Discovery via mDNS / Network Service Discovery (NSD)
- On launching Nearby, the app starts Android's `NsdManager` to register a local service under the type `_nearbychat._tcp`.
- **Zero-Handshake Meta Extraction**: The service name is registered as:
  `NC_[DeviceId]_[Display_Name]`
- This allows searching devices to immediately parse the peer's unique ID and nickname *directly from the resolved mDNS records in under a second*, before establishing any socket connections.

### B. TCP Socket Communication Pipe
- Each device starts a continuous background `ServerSocket` on port `8888` (or falls back to an ephemeral port).
- Outgoing connection requests are initiated using standard `Socket` channels. Handshakes are negotiated securely via JSON control payloads directly over the connection.

### C. Live Voice Streaming (VoIP)
- When a voice call is accepted, the receiver starts a TCP server socket on port `8889`. The caller connects as a client.
- Both sides launch concurrent Kotlin coroutine threads:
  1. **AudioRecord Reader**: Captures raw voice from the microphone in 16kHz 16-bit Mono PCM format. Bytes are piped in real-time into the socket stream.
  2. **AudioTrack Writer**: Reads raw incoming PCM bytes directly from the socket and feeds them straight to the device's voice call earpiece or speakerphone with extremely low latency.
- There are no central STUN, TURN, SIP, or Signaling servers involved. All signaling (Ringing, Accept, Reject, End Call) is routed as JSON controls over the primary chat socket.

---

## 3. Required Android Permissions

The application requests the following permission blocks:
1. **Network Connectivity**: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` (to host TCP streams, join Wi-Fi networks, and manage sockets).
2. **Local Peer Discovery**: 
   - *Android 13+ (API 33)*: `NEARBY_WIFI_DEVICES` (allows offline P2P connection and Wi-Fi scanning without requiring GPS location).
   - *Android 12 & below*: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (mandated by older versions of the OS to list Wi-Fi endpoints).
3. **P2P Audio Call**: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` (to record speech and route output to earpiece or speakerphone).

---

## 4. Physical Two-Device Testing Protocol

Follow these instructions to test the actual peer-to-peer capabilities using two physical Android devices:

1. **Disable Internet**: Turn off Cellular Mobile Data and Wi-Fi Internet Access on **both** devices (Device A and Device B).
2. **Form a Local Network**:
   - *Method 1 (Easiest anywhere)*: On **Device A**, enable your **Mobile Hotspot/Wi-Fi Hotspot** (this creates a Local Area Network). On **Device B**, turn on Wi-Fi and connect to Device A's hotspot.
   - *Method 2*: Connect both devices to the same local Wi-Fi router (even if the router has no active DSL/Fiber internet line).
3. **Launch the App**: Open **Nearby Chat** on both devices.
4. **Onboard**: Set custom display names (e.g., "Alice" and "Bob") and tap **Start Messaging**.
5. **Request Permissions**: Accept the system dialog prompt (Microphone + Nearby Devices/Location).
6. **Discover**: Go to the **Nearby** tab on Alice's device. Tap **Refresh/Scan**. Alice will instantly see "Bob" listed with his IP address.
7. **Connect**: Alice taps **Connect** on Bob.
8. **Accept Connection**: Bob receives an instant full-screen alert dialog: *"Alice wants to connect... Accept / Reject"*. Bob taps **Accept**. Alice's screen transitions to "Connected".
9. **Chat**: Select the chat thread. Type messages in both directions. Verify:
   - Status indicators: Message transitions from `sending` (clock) ➔ `sent` (single check) ➔ `delivered` (double checks) ➔ `read` (double blue checks) when Alice opens the thread.
   - Typing feedback: Alice sees *"typing..."* at the top when Bob starts typing.
10. **Audio Call**: Alice taps the **Phone Call** icon inside Alice and Bob's chat screen.
    - Bob's screen rings with an incoming offline call interface. Bob taps **Accept**.
    - The timer counts up. Talk into the microphone—Alice and Bob can hear each other in real-time over the direct 16kHz PCM link.
    - Tap **Mute** or **Speaker** to verify audio routes properly.
    - Alice or Bob taps the RED **Hangup** button. The call terminates on both sides and cleanly returns to the chat thread.
