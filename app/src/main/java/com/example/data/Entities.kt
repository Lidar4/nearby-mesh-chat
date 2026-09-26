package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String, // Device ID of the peer or Group ID
    val peerName: String,
    val lastMessageText: String,
    val lastMessageTime: Long,
    val unreadCount: Int = 0,
    val isConnected: Boolean = false,
    val isGroup: Boolean = false,
    val groupOwnerId: String? = null,
    val groupMembers: String? = null // Comma-separated list of peer IDs
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String, // Unique message UUID
    val conversationId: String, // The peer's device ID or Group ID
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long,
    val messageType: String, // "text", "audio_call", "image", "video", "file"
    val status: String, // "sending", "sent", "delivered", "read"
    val replyToMessageId: String? = null,
    val attachmentPath: String? = null,
    val attachmentType: String? = null, // "image", "video", "file"
    val attachmentName: String? = null,
    val attachmentSize: Long = 0L
)
