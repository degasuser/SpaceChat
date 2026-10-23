package dev.spacechat.network

import com.google.gson.JsonParser
import dev.spacechat.data.ApiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiProviderTest {
    private lateinit var server: MockWebServer
    private val provider = OpenAiProvider(allowHttpForTests = true)
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun settings() = ApiSettings(server.url("/").toString(), "test-only-secret", "provider-returned-id", systemPrompt = "Be concise")
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test fun endpointNormalizesVersionAndPreservesGatewayPrefix() {
        val cases = mapOf("https://api.example" to "/v1/models", "https://api.example/v1/" to "/v1/models", "https://api.example/gateway" to "/gateway/v1/models", "https://api.example/gateway/v1" to "/gateway/v1/models")
        cases.forEach { (base, expected) -> assertEquals(expected, provider.endpoint(base, "models").encodedPath) }
    }
    @Test fun productionRejectsHttpAndCredentialsInUrl() {
        listOf("http://example.com", "https://user:password@example.com", "https://example.com?token=secret", "https://example.com#secret").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) { OpenAiProvider().endpoint(base, "models") }
        }
    }
    @Test fun streamsUnicodeChunksAndSendsContext() = runBlocking {
        server.enqueue(sse(": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Привет \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"🚀\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n").throttleBody(19, 2, TimeUnit.MILLISECONDS))
        val chunks = withTimeout(5000) { provider.stream(settings(), "chosen-id", listOf(WireMessage("user", "Hi"), WireMessage("assistant", "Hello"), WireMessage("user", "Again"))).toList() }
        assertEquals("Привет 🚀", chunks.joinToString(""))
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-only-secret", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertTrue(body["stream"].asBoolean); assertEquals("chosen-id", body["model"].asString)
        assertEquals(listOf("system", "user", "assistant", "user"), body.getAsJsonArray("messages").map { it.asJsonObject["role"].asString })
    }
    @Test fun finishReasonAllowsCleanCloseWithoutDone() = runBlocking {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"))
        assertEquals("ok", withTimeout(5000) { provider.stream(settings(), "id", emptyList()).toList().joinToString("") })
    }
    @Test fun truncatedStreamIsReportedAndPartialTextPreserved() = runBlocking {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
        val received = mutableListOf<String>()
        val failure = runCatching { withTimeout(5000) { provider.stream(settings(), "id", emptyList()).collect { received.add(it) } } }.exceptionOrNull()
        assertTrue(failure is ApiFailure); assertEquals("partial", received.joinToString(""))
    }
    @Test fun rateLimitExposesRetryAfterAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody("test-only-secret private prompt"))
        val failure = runCatching { withTimeout(5000) { provider.stream(settings(), "id", emptyList()).toList() } }.exceptionOrNull() as ApiFailure
        assertEquals(429, failure.status); assertEquals(12L, failure.retryAfterSeconds)
        assertFalse(failure.message!!.contains("test-only-secret")); assertFalse(failure.message!!.contains("private prompt"))
        assertEquals(1, server.requestCount)
    }
    @Test fun modelsAreReadFromProviderAndDeduplicated() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"second\"},{\"id\":\"first\"},{\"id\":\"second\"}]}"))
        assertEquals(listOf("first", "second"), withTimeout(5000) { provider.models(settings()) })
    }
    @Test fun missingModelsEndpointReturnsActionableFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = runCatching { withTimeout(5000) { provider.models(settings()) } }.exceptionOrNull() as ApiFailure
        assertEquals(404, e.status); assertTrue(e.message!!.contains("вручную"))
    }
    @Test fun cancellationClosesStreamingRequest() = runBlocking {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n").setBodyDelay(100, TimeUnit.MILLISECONDS))
        assertEquals("first", withTimeout(5000) { provider.stream(settings(), "id", emptyList()).take(1).single() })
    }
    @Test fun imageUsesStandardImageUrlParts() {
        val body = JsonParser.parseString(provider.payload(settings(), "id", listOf(WireMessage("user", "Describe", "data:image/jpeg;base64,TEST")))).asJsonObject
        val content = body.getAsJsonArray("messages")[1].asJsonObject.getAsJsonArray("content")
        assertEquals("text", content[0].asJsonObject["type"].asString)
        assertEquals("image_url", content[1].asJsonObject["type"].asString)
        assertEquals("data:image/jpeg;base64,TEST", content[1].asJsonObject.getAsJsonObject("image_url")["url"].asString)
    }
    @Test fun remoteErrorAndMalformedSseDoNotLeakResponseBody() = runBlocking {
        listOf("{\"error\":{\"message\":\"test-only-secret\"}}", "not-json test-only-secret").forEach { body ->
            server.enqueue(sse("data: $body\n\n"))
            val e = runCatching { withTimeout(5000) { provider.stream(settings(), "id", emptyList()).toList() } }.exceptionOrNull()
            assertTrue(e is ApiFailure); assertFalse(e!!.message!!.contains("test-only-secret"))
        }
    }
    @Test fun redirectIsNotFollowedWithAuthorization() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/stolen"))
        val e = runCatching { withTimeout(5000) { provider.models(settings()) } }.exceptionOrNull() as ApiFailure
        assertEquals(302, e.status); assertEquals(1, server.requestCount)
    }

    @Test fun temporaryServerFailureIsNeverAutomaticallyReplayed() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
        val e = runCatching { withTimeout(5000) { provider.stream(settings(), "id", emptyList()).toList() } }.exceptionOrNull() as ApiFailure
        assertEquals(503, e.status)
        assertEquals("Paid completion requests must only be retried by the user", 1, server.requestCount)
    }
    @Test fun modelDiscoveryDoesNotAutomaticallyRetryTemporaryFailure() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
        val e = runCatching { withTimeout(5000) { provider.models(settings()) } }.exceptionOrNull() as ApiFailure
        assertEquals(503, e.status)
        assertEquals(1, server.requestCount)
    }
}
