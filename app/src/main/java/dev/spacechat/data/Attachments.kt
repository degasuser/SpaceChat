package dev.spacechat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

interface AttachmentHandler {
    fun accepts(mime: String): Boolean
    suspend fun importAttachment(uri: Uri): String
}

/** SAF content is copied once into bounded app-private storage; no broad storage permission. */
class ImageAttachments(private val context: Context) : AttachmentHandler {
    private val directory = File(context.noBackupFilesDir, "images").apply { mkdirs() }
    override fun accepts(mime: String) = mime in setOf("image/jpeg", "image/png", "image/webp")
    private fun resolve(name: String): File {
        require(name.matches(Regex("[a-f0-9-]+\\.jpg"))) { "Некорректный путь изображения." }
        return File(directory, name)
    }
    override suspend fun importAttachment(uri: Uri): String = withContext(Dispatchers.IO) {
        require(accepts(context.contentResolver.getType(uri).orEmpty())) { "Поддерживаются JPEG, PNG и WebP. Документы пока не отправляются в API." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val n = input.read(buffer); if(n < 0) break
                require(output.size() + n <= 12 * 1024 * 1024) { "Изображение больше 12 МБ." }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: error("Не удалось открыть изображение.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является изображением." }
        var sample = 1
        while(maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Не удалось декодировать изображение.")
        val name = "${UUID.randomUUID()}.jpg"
        try { resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) } } finally { bitmap.recycle() }
        name
    }
    suspend fun asDataUrl(name: String): String = withContext(Dispatchers.IO) {
        val file = resolve(name)
        require(file.exists()) { "Изображение больше недоступно. Отредактируйте сообщение или начните новый чат." }
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
    suspend fun delete(name: String) = withContext(Dispatchers.IO) { resolve(name).delete(); Unit }
    suspend fun deleteAll() = withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() }; Unit }
}

class UnsupportedDocumentHandler : AttachmentHandler {
    override fun accepts(mime: String) = !mime.startsWith("image/")
    override suspend fun importAttachment(uri: Uri): String = error("Документы PDF, TXT, Markdown и исходный код пока не поддерживаются как вложения. Вставьте текст в сообщение.")
}

/** Routes SAF documents before checking provider-specific image capabilities. */
class AttachmentRouter(private val context: Context, private val images: ImageAttachments) {
    private val handlers: List<AttachmentHandler> = listOf(images, UnsupportedDocumentHandler())
    suspend fun importAttachment(uri: Uri, imageUrlEnabled: Boolean): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val handler = handlers.firstOrNull { it.accepts(mime) }
            ?: error("Этот тип изображения не поддерживается. Выберите JPEG, PNG или WebP.")
        require(handler !== images || imageUrlEnabled) { "Включите image_url в настройках, если API и модель поддерживают изображения." }
        handler.importAttachment(uri)
    }
}
