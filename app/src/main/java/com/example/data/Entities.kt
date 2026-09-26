package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String, // Device ID of the peer
    val peerName: String,
    val lastMessageText: String,
    val lastMessageTime: Long,
    val unreadCount: Int = 0,
    val isConnected: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String, // Unique message UUID
    val conversationId: String, // The peer's device ID
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long,
    val messageType: String, // "text", "audio_call"
    val status: String // "sending", "sent", "delivered", "read"
)
