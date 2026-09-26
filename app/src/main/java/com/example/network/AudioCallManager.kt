package com.example.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

enum class CallState {
    IDLE,
    CALLING,  // Outgoing call initiated
    RINGING,  // Incoming call received
    CONNECTED, // Voice stream active
    ENDED,
    FAILED
}

class AudioCallManager {

    private val TAG = "AudioCallManager"
    
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds

    private var durationJob: Job? = null
    private var isMuted = false
    private var isSpeakerOn = false

    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var audioServerSocket: ServerSocket? = null
    private var audioSocket: Socket? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    fun setCallState(state: CallState) {
        _callState.value = state
        if (state == CallState.CONNECTED) {
            startDurationCounter()
        } else if (state == CallState.IDLE || state == CallState.ENDED || state == CallState.FAILED) {
            stopDurationCounter()
        }
    }

    private fun startDurationCounter() {
        _durationSeconds.value = 0
        durationJob?.cancel()
        durationJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _durationSeconds.value += 1
            }
        }
    }

    private fun stopDurationCounter() {
        durationJob?.cancel()
        durationJob = null
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        return isMuted
    }

    fun toggleSpeaker(audioManager: AudioManager): Boolean {
        isSpeakerOn = !isSpeakerOn
        audioManager.isSpeakerphoneOn = isSpeakerOn
        return isSpeakerOn
    }

    /**
     * Start the Server to receive audio data (Typically run on the Call Receiver side)
     */
    fun startAudioServer(onServerBound: (Int) -> Unit, onConnected: () -> Unit, onError: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting Audio server socket...")
                audioServerSocket?.close()
                // Let OS allocate dynamic port if 8889 is busy
                audioServerSocket = try {
                    ServerSocket(8889)
                } catch (e: Exception) {
                    ServerSocket(0)
                }
                val boundPort = audioServerSocket?.localPort ?: 8889
                Log.d(TAG, "Audio server bound to port: $boundPort")
                onServerBound(boundPort)
                
                _callState.value = CallState.RINGING
                
                val socket = audioServerSocket?.accept()
                if (socket != null) {
                    audioSocket = socket
                    Log.d(TAG, "Audio call socket connected!")
                    _callState.value = CallState.CONNECTED
                    onConnected()
                    startStreaming(socket.getInputStream(), socket.getOutputStream())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio server error: ${e.message}", e)
                _callState.value = CallState.FAILED
                onError(e.message ?: "Audio connection failed")
                cleanup()
            }
        }
    }

    /**
     * Connect to the Peer's Audio Server (Typically run on the Call Initiator side)
     */
    fun connectToAudioServer(peerIp: String, port: Int, onConnected: () -> Unit, onError: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Connecting to Peer Audio server on $peerIp:$port...")
                _callState.value = CallState.CALLING
                
                // Retry connection a few times
                var retries = 5
                var socket: Socket? = null
                while (retries > 0 && socket == null) {
                    try {
                        socket = Socket(peerIp, port)
                    } catch (e: Exception) {
                        retries--
                        Log.d(TAG, "Audio socket connection retrying to port $port... remaining: $retries")
                        kotlinx.coroutines.delay(800)
                    }
                }

                if (socket != null) {
                    audioSocket = socket
                    Log.d(TAG, "Audio call connected to server!")
                    _callState.value = CallState.CONNECTED
                    onConnected()
                    startStreaming(socket.getInputStream(), socket.getOutputStream())
                } else {
                    throw Exception("Could not reach audio stream port of peer")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio client error: ${e.message}", e)
                _callState.value = CallState.FAILED
                onError(e.message ?: "Could not connect call audio")
                cleanup()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming(inputStream: InputStream, outputStream: OutputStream) {
        val minRecordBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_ENCODING)
        val minPlayBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_ENCODING)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_ENCODING,
                minRecordBufSize
            )

            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_ENCODING,
                minPlayBufSize,
                AudioTrack.MODE_STREAM
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("AudioRecord mic input failed to initialize")
            }
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw Exception("AudioTrack speaker stream failed to initialize")
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            // Start Audio Transmission Loop
            recordJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(minRecordBufSize)
                try {
                    while (callState.value == CallState.CONNECTED) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            if (isMuted) {
                                // Send empty bytes if muted
                                outputStream.write(ByteArray(read))
                            } else {
                                outputStream.write(buffer, 0, read)
                            }
                            outputStream.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Record streaming error: ${e.message}")
                }
            }

            // Start Audio Playback Loop
            playJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(minPlayBufSize)
                try {
                    while (callState.value == CallState.CONNECTED) {
                        val read = inputStream.read(buffer)
                        if (read > 0) {
                            audioTrack?.write(buffer, 0, read)
                        } else if (read == -1) {
                            Log.d(TAG, "Incoming voice stream closed.")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Play playback error: ${e.message}")
                } finally {
                    endCall()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord or AudioTrack: ${e.message}", e)
            _callState.value = CallState.FAILED
            cleanup()
        }
    }

    fun endCall() {
        if (_callState.value != CallState.IDLE) {
            _callState.value = CallState.ENDED
            cleanup()
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(2000)
                _callState.value = CallState.IDLE
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up call resources...")
        stopDurationCounter()
        
        try {
            recordJob?.cancel()
            recordJob = null
            playJob?.cancel()
            playJob = null

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            audioSocket?.close()
            audioSocket = null

            audioServerSocket?.close()
            audioServerSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AudioCallManager: ${e.message}")
        }
    }
}
