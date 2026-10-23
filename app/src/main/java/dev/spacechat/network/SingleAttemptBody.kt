package dev.spacechat.network

import okhttp3.RequestBody
import okio.BufferedSink

/** A paid request must not be replayed by OkHttp's HTTP follow-up machinery. */
internal class SingleAttemptBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    override fun isOneShot() = true
}
