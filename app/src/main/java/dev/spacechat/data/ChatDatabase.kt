package dev.spacechat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val id: String, val title: String, val model: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)], indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    val text: String,
    val imagePath: String? = null,
    val status: String = "complete",
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE title LIKE '%' || :query || '%' OR id IN (SELECT chatId FROM messages WHERE text LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun chats(query: String): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chats WHERE id = :id") suspend fun chat(id: String): ChatEntity?
    @Query("SELECT * FROM chats WHERE id = :id") fun observeChat(id: String): Flow<ChatEntity?>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id") fun messages(chatId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id") suspend fun snapshot(chatId: String): List<MessageEntity>
    @Insert suspend fun insert(chat: ChatEntity)
    @Insert suspend fun insert(message: MessageEntity): Long
    @Query("UPDATE chats SET title = :title, updatedAt = :time WHERE id = :id") suspend fun rename(id: String, title: String, time: Long = System.currentTimeMillis())
    @Query("UPDATE chats SET model = :model WHERE id = :id") suspend fun setModel(id: String, model: String)
    @Query("UPDATE chats SET updatedAt = :time WHERE id = :id") suspend fun touch(id: String, time: Long = System.currentTimeMillis())
    @Query("UPDATE messages SET text = :text, status = :status WHERE id = :id") suspend fun finish(id: Long, text: String, status: String)
    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'") suspend fun recoverInterrupted()
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id >= :fromId") suspend fun deleteFrom(chatId: String, fromId: Long)
    @Query("DELETE FROM chats WHERE id = :id") suspend fun deleteChat(id: String)
    @Query("DELETE FROM chats") suspend fun clear()
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() { abstract fun chats(): ChatDao }
