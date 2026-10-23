package dev.spacechat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// No generated toString: credentials must never accidentally appear in diagnostics.
class ApiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val timeoutSeconds: Int = 90,
    val theme: String = "system",
    val accent: String = "cyan",
    val dynamicColors: Boolean = false,
    val imagesEnabled: Boolean = false,
) {
    override fun toString() = "ApiSettings(redacted)"
}

class SecureSettings(context: Context, private val storageName: String = "settings") {
    init { require(storageName.matches(Regex("[a-zA-Z0-9-]+"))) }
    private val alias = if(storageName == "settings") "spacechat.api.v1" else "spacechat.test.$storageName"
    private val file = AtomicFile(File(context.noBackupFilesDir, "$storageName.enc"))
    private val gson = Gson()
    private val mutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow(ApiSettings())
    val state = mutable.asStateFlow()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            generateKey()
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        if (loaded) return@withLock
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size > 28) { "Invalid encrypted settings" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            mutable.value = gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), ApiSettings::class.java)
        }
        loaded = true
    } }

    suspend fun save(settings: ApiSettings) = withContext(Dispatchers.IO) { mutex.withLock {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(gson.toJson(settings).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = settings
        loaded = true
    } }

    // Explicit user action can recover settings after Keystore invalidation.
    suspend fun reset() = withContext(Dispatchers.IO) { mutex.withLock {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        mutable.value = ApiSettings(); loaded = true
    } }

}
