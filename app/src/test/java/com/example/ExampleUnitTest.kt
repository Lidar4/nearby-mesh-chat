package com.example

import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.network.ConnectionState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun test_entities_creation_and_fields() {
        val conversation = ConversationEntity(
            peerId = "NC-TEST01",
            peerName = "Alice",
            lastMessageText = "Hello offline world",
            lastMessageTime = 1695686400000L,
            unreadCount = 2,
            isConnected = true
        )

        assertEquals("NC-TEST01", conversation.peerId)
        assertEquals("Alice", conversation.peerName)
        assertEquals("Hello offline world", conversation.lastMessageText)
        assertEquals(1695686400000L, conversation.lastMessageTime)
        assertEquals(2, conversation.unreadCount)
        assertTrue(conversation.isConnected)

        val message = MessageEntity(
            id = "msg-uuid-999",
            conversationId = "NC-TEST01",
            senderId = "NC-TEST01",
            receiverId = "NC-SELF01",
            text = "Welcome to P2P messenger",
            timestamp = 1695686405000L,
            messageType = "text",
            status = "delivered"
        )

        assertEquals("msg-uuid-999", message.id)
        assertEquals("NC-TEST01", message.conversationId)
        assertEquals("NC-TEST01", message.senderId)
        assertEquals("NC-SELF01", message.receiverId)
        assertEquals("Welcome to P2P messenger", message.text)
        assertEquals(1695686405000L, message.timestamp)
        assertEquals("text", message.messageType)
        assertEquals("delivered", message.status)
    }

    @Test
    fun test_connection_state_transitions() {
        val states = ConnectionState.values()
        assertTrue(states.contains(ConnectionState.DISCONNECTED))
        assertTrue(states.contains(ConnectionState.DISCOVERING))
        assertTrue(states.contains(ConnectionState.CONNECTING))
        assertTrue(states.contains(ConnectionState.WAITING_FOR_ACCEPT))
        assertTrue(states.contains(ConnectionState.CONNECTED))
        assertTrue(states.contains(ConnectionState.RECONNECTING))
        assertTrue(states.contains(ConnectionState.REJECTED))
        assertTrue(states.contains(ConnectionState.ERROR))
    }

    @Test
    fun test_protocol_json_serialization_and_versioning() {
        // Build valid JSON according to versioned protocol
        val textPayload = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "text")
            put("messageId", "test-uuid-abc")
            put("senderId", "NC-SENDER")
            put("payload", "Direct message payload")
            put("timestamp", 123456789L)
        }

        assertEquals(1, textPayload.getInt("protocolVersion"))
        assertEquals("text", textPayload.getString("type"))
        assertEquals("test-uuid-abc", textPayload.getString("messageId"))
        assertEquals("NC-SENDER", textPayload.getString("senderId"))
        assertEquals("Direct message payload", textPayload.getString("payload"))
        assertEquals(123456789L, textPayload.getLong("timestamp"))
    }

    @Test
    fun test_malformed_json_handling_and_robustness() {
        val malformedString = "{ malformed JSON text, incomplete: "
        
        // Simulating robust parsing in our try-catch block
        val parseResult = try {
            JSONObject(malformedString)
            null
        } catch (e: Exception) {
            "caught_exception"
        }

        assertEquals("caught_exception", parseResult)
    }

    @Test
    fun test_delivery_and_read_ack_json_structure() {
        val deliveryAck = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "delivery_ack")
            put("messageId", "msg-id-123")
        }

        assertEquals(1, deliveryAck.getInt("protocolVersion"))
        assertEquals("delivery_ack", deliveryAck.getString("type"))
        assertEquals("msg-id-123", deliveryAck.getString("messageId"))

        val readAck = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "read_ack")
            put("messageId", "msg-id-123")
        }

        assertEquals(1, readAck.getInt("protocolVersion"))
        assertEquals("read_ack", readAck.getString("type"))
        assertEquals("msg-id-123", readAck.getString("messageId"))
    }

    @Test
    fun test_typing_indicators_json_structure() {
        val typingStart = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "typing_start")
        }

        assertEquals(1, typingStart.getInt("protocolVersion"))
        assertEquals("typing_start", typingStart.getString("type"))

        val typingStop = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "typing_stop")
        }

        assertEquals(1, typingStop.getInt("protocolVersion"))
        assertEquals("typing_stop", typingStop.getString("type"))
    }

    @Test
    fun test_call_accept_dynamic_port_json_structure() {
        val callAccept = JSONObject().apply {
            put("protocolVersion", 1)
            put("type", "call_accept")
            put("audioPort", 49152) // Example dynamic port
        }

        assertEquals(1, callAccept.getInt("protocolVersion"))
        assertEquals("call_accept", callAccept.getString("type"))
        assertEquals(49152, callAccept.getInt("audioPort"))
    }
}
