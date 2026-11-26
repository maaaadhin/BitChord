package com.music.bitchord.network.cronet

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.chromium.net.CronetEngine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CronetInterceptor(
    private val engine: CronetEngine
) : Interceptor {

    override fun intercept(
        chain: Interceptor.Chain
    ): Response {
        val request = chain.request()

        // Restrict the experimental transport to a test endpoint.
        if (request.url.host != "example.com" ||
            !request.url.isHttps ||
            request.method != "GET" ||
            request.body != null ||
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

            val started = System.currentTimeMillis()
            val code = connection.responseCode

            val input = if (code >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }

            val bytes = input?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)

                while (true) {
                    if (chain.call().isCanceled()) {
                        throw IOException("Request canceled")
                    }

                    val count = stream.read(buffer)
                    if (count == -1) break

                    if (output.size() + count > 1048576) {
                        throw IOException("Response exceeds test limit")
                    }

                    output.write(buffer, 0, count)
                }

                output.toByteArray()
            } ?: ByteArray(0)

            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(connection.responseMessage ?: "")
                .sentRequestAtMillis(started)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .body(bytes.toResponseBody(
                    connection.contentType?.toMediaTypeOrNull()
                ))

            return builder.build()
        } finally {
            connection.disconnect()
        }
    }
}
