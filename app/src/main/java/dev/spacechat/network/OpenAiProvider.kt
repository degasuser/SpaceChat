package dev.spacechat.network

import com.google.gson.*
import dev.spacechat.data.ApiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiProvider(private val allowHttpForTests: Boolean = false) : ChatProvider {
    private val baseClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp otherwise follows 503 + Retry-After: 0 even when connection retries
            // are disabled. GET /models must also report this error for a manual retry.
            if (chain.request().method == "GET" && response.code == 503 &&
                response.header("Retry-After")?.toLongOrNull() == 0L) {
                response.newBuilder().removeHeader("Retry-After").build()
            } else response
        }.build()

    fun endpoint(baseUrl: String, resource: String): HttpUrl {
        val url = baseUrl.trim().toHttpUrl()
        require(url.scheme == "https" || (allowHttpForTests && url.scheme == "http")) { "Нужен HTTPS Base URL." }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Base URL не должен содержать пароль, query или fragment." }
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath(if(path.endsWith("/v1")) "$path/$resource" else "$path/v1/$resource").build()
    }

    private fun client(settings: ApiSettings): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(settings.timeoutSeconds.coerceIn(10, 600).toLong(), TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS).build()

    private fun request(settings: ApiSettings, resource: String): Request.Builder {
        require(settings.apiKey.isNotBlank()) { "Укажите API-ключ в настройках." }
        require(!settings.apiKey.contains('\n') && !settings.apiKey.contains('\r')) { "Некорректный API-ключ." }
        return Request.Builder().url(endpoint(settings.baseUrl, resource)).header("Authorization", "Bearer ${settings.apiKey.trim()}")
    }

    internal fun payload(settings: ApiSettings, model: String, messages: List<WireMessage>): String {
        require(model.isNotBlank()) { "Укажите Model ID." }
        val array = JsonArray()
        if(settings.systemPrompt.isNotBlank()) array.add(JsonObject().apply { addProperty("role", "system"); addProperty("content", settings.systemPrompt) })
        messages.forEach { m -> array.add(JsonObject().apply {
            addProperty("role", m.role)
            if(m.imageData == null) addProperty("content", m.text) else {
                add("content", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", m.text) })
                    add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply { addProperty("url", m.imageData) }) })
                })
            }
        }) }
        return JsonObject().apply { addProperty("model", model.trim()); addProperty("stream", true); add("messages", array) }.toString()
    }

    override fun stream(settings: ApiSettings, model: String, messages: List<WireMessage>): Flow<String> = callbackFlow {
        val request = request(settings, "chat/completions").header("Accept", "text/event-stream")
            .post(SingleAttemptBody(payload(settings, model, messages).toRequestBody("application/json; charset=utf-8".toMediaType()))).build()
        var completed = false
        var finished = false
        val source = EventSources.createFactory(client(settings)).newEventSource(request, object: EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if(completed) return
                if(data.trim() == "[DONE]") { completed = true; close(); eventSource.cancel(); return }
                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    if(json.has("error")) throw ApiFailure(null, message = "API вернул ошибку потока. Проверьте модель и поддержку формата запроса.")
                    val choices = json.getAsJsonArray("choices") ?: return
                    if(choices.isEmpty) return
                    val choice = choices[0].asJsonObject
                    if(choice.has("finish_reason") && !choice["finish_reason"].isJsonNull) finished = true
                    val delta = choice.getAsJsonObject("delta")
                    val content = delta?.get("content")
                    if(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) trySendBlocking(content.asString)
                } catch(e: Exception) {
                    completed = true
                    close(if(e is ApiFailure) e else ApiFailure(null, message = "Неподдерживаемый формат SSE-ответа."))
                    eventSource.cancel()
                }
            }
            override fun onClosed(eventSource: EventSource) {
                if(!completed) { completed = true; if(finished) close() else close(ApiFailure(null, message = "Поток оборвался до завершения. Повторите запрос вручную.")) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if(!completed) { completed = true; close(if(response != null && !response.isSuccessful) failure(response) else ApiFailure(null, message = "Нет ответа API: проверьте сеть, TLS, таймаут и поддержку streaming.")) }
                response?.close()
            }
        })
        awaitClose { source.cancel() }
    }.buffer(capacity = 64).flowOn(Dispatchers.IO)

    override suspend fun models(settings: ApiSettings): List<String> {
        val request = request(settings, "models").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client(settings).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if(continuation.isActive) continuation.resumeWithException(ApiFailure(null, message = "Не удалось подключиться к API. Проверьте сеть и Base URL."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if(!it.isSuccessful) throw failure(it)
                            val body = it.body ?: throw IOException()
                            // Refuse oversized lists; providers must not exhaust application memory.
                            val source = body.source(); if(source.request(2L * 1024 * 1024 + 1)) throw IOException()
                            val json = JsonParser.parseString(source.readUtf8()).asJsonObject
                            val data = json.getAsJsonArray("data") ?: throw IOException()
                            data.mapNotNull { entry -> entry.asJsonObject.get("id")?.takeIf { id -> id.isJsonPrimitive && id.asJsonPrimitive.isString }?.asString }
                                .filter { id -> id.isNotBlank() }.distinct().sorted()
                        }
                        if(continuation.isActive) continuation.resume(result)
                    } catch(e: Exception) {
                        if(continuation.isActive) continuation.resumeWithException(if(e is ApiFailure) e else ApiFailure(null, message = "Список моделей недоступен или имеет другой формат. Введите Model ID вручную."))
                    }
                }
            })
        }
    }

    private fun failure(response: Response): ApiFailure {
        val retry = response.header("Retry-After")?.let { value -> value.toLongOrNull() ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - System.currentTimeMillis()/1000).coerceAtLeast(0) }.getOrNull() }
        val message = when(response.code) {
            401, 403 -> "API отклонил доступ (${response.code}). Проверьте ключ и права модели."
            404 -> "Endpoint не найден (404). Проверьте Base URL; Model ID можно ввести вручную."
            429 -> "Лимит API (429). ${retry?.let { "Повторите через $it сек." } ?: "Повторите позже вручную."}"
            in 500..599 -> "Временная ошибка API (${response.code}). Повторите запрос вручную."
            else -> "Ошибка API (${response.code}). Проверьте модель, контекст и поддержку вложений."
        }
        return ApiFailure(response.code, retry, message)
    }
}
