package dev.spacechat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import dev.spacechat.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun historySurvivesReopenAndCascadesOnDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            db.chats().insert(ChatEntity("chat", "First", "actual-selected-id"))
            db.chats().insert(MessageEntity(chatId = "chat", role = "user", text = "Searchable message"))
            db.chats().insert(MessageEntity(chatId = "chat", role = "assistant", text = "partial", status = "streaming"))
            db.close()
            db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
            db.chats().recoverInterrupted()
            assertEquals("actual-selected-id", db.chats().chat("chat")!!.model)
            assertEquals("interrupted", db.chats().snapshot("chat").last().status)
            assertEquals(1, db.chats().chats("Searchable").first().size)
            db.chats().rename("chat", "Renamed")
            assertEquals("Renamed", db.chats().chat("chat")!!.title)
            db.chats().deleteChat("chat")
            assertTrue(db.chats().snapshot("chat").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun secretsAreEncryptedAndReloadFromKeystore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val namespace = "test-${UUID.randomUUID()}"
        val storage = SecureSettings(context, namespace)
        val key = "instrumentation-only-key-${UUID.randomUUID()}"
        try {
            storage.save(ApiSettings("https://example.com", key, "selected-id"))
            val bytes = File(context.noBackupFilesDir, "$namespace.enc").readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(key))
            val reopened = SecureSettings(context, namespace)
            reopened.load()
            assertEquals(key, reopened.state.value.apiKey)
            assertEquals("selected-id", reopened.state.value.model)
            val damaged = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            File(context.noBackupFilesDir, "$namespace.enc").writeBytes(damaged)
            assertTrue(runCatching { SecureSettings(context, namespace).load() }.isFailure)
        } finally { storage.reset() }
    }
}
