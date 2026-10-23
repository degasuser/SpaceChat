package dev.spacechat

import android.app.Application
import androidx.room.Room
import dev.spacechat.data.*
import dev.spacechat.network.OpenAiProvider

class SpaceChatApplication : Application() {
    val settings by lazy { SecureSettings(this) }
    val repository by lazy {
        ChatRepository(Room.databaseBuilder(this, ChatDatabase::class.java, "spacechat.db").build(), OpenAiProvider(), ImageAttachments(this))
    }
}
