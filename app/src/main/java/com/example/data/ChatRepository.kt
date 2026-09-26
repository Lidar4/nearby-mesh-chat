package com.example.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val database: AppDatabase) {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()

    val allConversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversationsFlow()

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversationFlow(conversationId)

    suspend fun getConversation(peerId: String): ConversationEntity? =
        conversationDao.getConversationById(peerId)

    suspend fun insertOrUpdateConversation(conversation: ConversationEntity) =
        conversationDao.insertOrUpdateConversation(conversation)

    suspend fun updateConnectionStatus(peerId: String, isConnected: Boolean) =
        conversationDao.updateConnectionStatus(peerId, isConnected)

    suspend fun resetConnectionStatuses() =
        conversationDao.resetAllConnectionStatuses()

    suspend fun clearUnreads(peerId: String) =
        conversationDao.clearUnreads(peerId)

    suspend fun saveSentMessage(message: MessageEntity) {
        // Save the message
        messageDao.insertMessage(message)
        
        // Update or insert conversation
        val existing = conversationDao.getConversationById(message.conversationId)
        val peerName = existing?.peerName ?: "Unknown Device"
        val lastText = if (message.messageType == "audio_call") "📞 ${message.text}" else message.text
        
        val conversation = ConversationEntity(
            peerId = message.conversationId,
            peerName = peerName,
            lastMessageText = lastText,
            lastMessageTime = message.timestamp,
            unreadCount = existing?.unreadCount ?: 0,
            isConnected = existing?.isConnected ?: false
        )
        conversationDao.insertOrUpdateConversation(conversation)
    }

    suspend fun saveReceivedMessage(message: MessageEntity, senderName: String, updateUnread: Boolean) {
        // Save the message
        messageDao.insertMessage(message)

        // Update or insert conversation
        val existing = conversationDao.getConversationById(message.conversationId)
        val lastText = if (message.messageType == "audio_call") "📞 ${message.text}" else message.text
        val newUnread = if (updateUnread) (existing?.unreadCount ?: 0) + 1 else (existing?.unreadCount ?: 0)

        val conversation = ConversationEntity(
            peerId = message.conversationId,
            peerName = senderName,
            lastMessageText = lastText,
            lastMessageTime = message.timestamp,
            unreadCount = newUnread,
            isConnected = true // Since we received a message, it is active
        )
        conversationDao.insertOrUpdateConversation(conversation)
    }

    suspend fun getMessage(id: String): MessageEntity? =
        messageDao.getMessageById(id)

    suspend fun deleteMessage(id: String) {
        messageDao.deleteMessage(id)
    }

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    suspend fun updateMessageStatus(id: String, status: String) {
        messageDao.updateMessageStatus(id, status)
    }

    suspend fun getPendingMessages(): List<MessageEntity> =
        messageDao.getPendingMessages()

    suspend fun deleteConversation(peerId: String) {
        conversationDao.deleteConversation(peerId)
        messageDao.deleteMessagesForConversation(peerId)
    }
}
