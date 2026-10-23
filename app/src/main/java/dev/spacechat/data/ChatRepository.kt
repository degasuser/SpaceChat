package dev.spacechat.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.spacechat.network.ChatProvider
import dev.spacechat.network.WireMessage
import java.util.UUID

class ChatRepository(val database: ChatDatabase, val provider: ChatProvider, val images: ImageAttachments, val attachments: AttachmentRouter) {
    val dao = database.chats()
    suspend fun newChat(model: String): String {
        val id = UUID.randomUUID().toString()
        dao.insert(ChatEntity(id, "Новый диалог", model))
        return id
    }
    suspend fun context(chatId: String): List<WireMessage> = withContext(Dispatchers.IO) {
        dao.snapshot(chatId).filter { it.role == "user" || it.status == "complete" }.map {
            WireMessage(it.role, it.text, it.imagePath?.let { path -> images.asDataUrl(path) })
        }
    }
    suspend fun addUser(chatId: String, text: String, image: String?) = database.withTransaction {
        val first = dao.snapshot(chatId).none { it.role == "user" }
        dao.insert(MessageEntity(chatId = chatId, role = "user", text = text, imagePath = image))
        if(first) dao.rename(chatId, text.ifBlank { "Изображение" }.take(60)) else dao.touch(chatId)
    }
    suspend fun truncate(chatId: String, fromId: Long, preserveImage: String? = null) {
        val removed = dao.snapshot(chatId).filter { it.id >= fromId }
        dao.deleteFrom(chatId, fromId)
        removed.mapNotNull { it.imagePath }.filter { it != preserveImage }.forEach { images.delete(it) }
    }
    suspend fun delete(chatId: String) {
        val paths = dao.snapshot(chatId).mapNotNull { it.imagePath }
        dao.deleteChat(chatId)
        paths.forEach { images.delete(it) }
    }
    suspend fun clear() { dao.clear(); images.deleteAll() }
}
