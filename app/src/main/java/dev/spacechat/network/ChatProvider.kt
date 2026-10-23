package dev.spacechat.network

import dev.spacechat.data.ApiSettings
import kotlinx.coroutines.flow.Flow
import java.io.IOException

data class WireMessage(val role: String, val text: String, val imageData: String? = null)
interface ChatProvider {
    fun stream(settings: ApiSettings, model: String, messages: List<WireMessage>): Flow<String>
    suspend fun models(settings: ApiSettings): List<String>
}
class ApiFailure(val status: Int?, val retryAfterSeconds: Long? = null, message: String) : IOException(message)
