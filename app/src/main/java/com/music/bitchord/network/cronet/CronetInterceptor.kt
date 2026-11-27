package com.music.bitchord.network.cronet

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.source
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.chromium.net.CronetEngine
import okio.buffer
import okio.sink
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class CronetInterceptor(
    private val engine: CronetEngine
) : Interceptor {

    override fun intercept(
        chain: Interceptor.Chain
    ): Response {
        val request = chain.request()

        // Restrict the experimental transport to a test endpoint.
        val allowedGet =
            (request.url.host == "example.com" ||
                (request.url.host == "httpbin.org" &&
                 request.url.encodedPath == "/stream-bytes/102400")) &&
            request.method == "GET" &&
            request.body == null

        val allowedPost =
            request.url.host == "httpbin.org" &&
            request.url.encodedPath == "/post" &&
            request.method == "POST" &&
            request.body != null

        if ((!allowedGet && !allowedPost) ||
            !request.url.isHttps ||
            request.header("Authorization") != null ||
            request.header("Cookie") != null
        ) {
            return chain.proceed(request)
        }

        if (chain.call().isCanceled()) {
            throw IOException("Request canceled")
        }

        val connection = engine.openConnection(
            URL(request.url.toString())
        ) as HttpURLConnection

        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false

            val excludedHeaders = setOf(
                "host",
                "connection",
                "content-length",
                "transfer-encoding",
                "accept-encoding",
                "proxy-authorization",
                "authorization",
                "cookie",
                "upgrade",
                "keep-alive",
                "te",
                "trailer"
            )

            for ((name, value) in request.headers) {
                if (name.lowercase(java.util.Locale.ROOT)
                    in excludedHeaders
                ) continue

                connection.addRequestProperty(name, value)
            }


            val started = System.currentTimeMillis()

            request.body?.let { body ->
                val length = body.contentLength()

                if (length < 0 || length > 65536 ||
                    body.isDuplex() || body.isOneShot()
                ) {
                    throw IOException("Unsupported test request body")
                }

                connection.doOutput = true

                body.contentType()?.let {
                    connection.setRequestProperty(
                        "Content-Type", it.toString()
                    )
                }

                connection.setFixedLengthStreamingMode(length)

                connection.outputStream.use { output ->
                    val sink = output.sink().buffer()
                    body.writeTo(sink)
                    sink.flush()
                }
            }
            val code = connection.responseCode

            val input = if (code >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }

            val stream = input
                ?: java.io.ByteArrayInputStream(ByteArray(0))

            val mediaType =
                connection.contentType?.toMediaTypeOrNull()

            val source = object : ForwardingSource(
                stream.source()
            ) {
                private val closed = AtomicBoolean(false)
                private var exhausted = false

                override fun read(
                    sink: Buffer,
                    byteCount: Long
                ): Long {
                    if (chain.call().isCanceled()) {
                        close()
                        throw IOException("Request canceled")
                    }

                    return try {
                        if (byteCount == 0L) return 0L
                        if (exhausted) return -1L

                        val count = super.read(sink, byteCount)

                        if (count == -1L) {
                            exhausted = true
                            close()
                        }

                        count
                    } catch (e: IOException) {
                        close()
                        throw e
                    }
                }

                override fun close() {
                    if (!closed.compareAndSet(false, true)) return

                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }.buffer()

            val responseBody = object : ResponseBody() {
                override fun contentType(): MediaType? =
                    mediaType

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource =
                    source
            }

            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(connection.responseMessage ?: "")
                .sentRequestAtMillis(started)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .body(responseBody)

            for ((name, values) in connection.headerFields) {
                if (name == null) continue

                if (name.equals("Content-Encoding", true) ||
                    name.equals("Content-Length", true) ||
                    name.equals("Transfer-Encoding", true)
                ) continue

                for (value in values) {
                    builder.addHeader(name, value)
                }
            }



            return builder.build()
        } catch (e: Throwable) {
            connection.disconnect()
            throw e
        }
    }
}
