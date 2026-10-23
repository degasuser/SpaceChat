package dev.spacechat.domain

import kotlinx.coroutines.flow.Flow

/** Provider adapters opt into capabilities only after they have been verified. */
data class ProviderCapabilities(val imageUrl: Boolean = false, val research: Boolean = false)
data class ResearchSource(val url: String, val title: String)
data class ResearchProgress(val step: String, val completed: Int, val total: Int?, val sources: List<ResearchSource>)
interface ResearchAgent {
    val available: Boolean
    fun research(question: String): Flow<ResearchProgress>
    suspend fun cancel()
}
interface HistoryArchive {
    suspend fun exportTo(destination: java.io.OutputStream)
    suspend fun importFrom(source: java.io.InputStream)
}
