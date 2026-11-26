package com.music.bitchord.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it. If Innertube and ExoPlayer used
 * separate HTTP stacks they could resolve to different addresses (v4 vs v6)
 * and the media fetch would come back 403. Sharing the client keeps DNS,
 * address family and connection pooling identical for both.
 *
 * That sharing has a cost the defaults don't budget for. Every request the
 * app makes — [Innertube]'s player/browse calls, NewPipe's own signature and
 * `next`-endpoint fetches, [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]
 * and [AudioCache][com.music.bitchord.playback.AudioCache]'s multi-megabyte
 * chunk downloads, and every [StreamResolver] probe — funnels through this
 * one client, and OkHttp's stock [Dispatcher] allows only 5 requests in
 * flight to a single host at a time. A track streaming while its successor
 * pre-caches is two or three of those requests already; a resolve running
 * alongside them queues behind whichever is occupying the rest — invisibly,
 * since a request stuck in OkHttp's queue and a slow server both just look
 * like a request that took several extra seconds. Sized well past anything
 * this app actually drives concurrently, so the queue is never the reason a
 * request was slow.
 */
object Http {

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Added to answer, with real numbers instead of code-reading, which of
    // this app's network callers actually account for the reported data
    // usage (issue #48): every request funnels through this one client, so
    // a network interceptor here sees every byte the app receives,
    // regardless of which subsystem asked for it. Categorised by host —
    // googlevideo.com is YouTube's own audio bytes (and StreamResolver's
    // probes, distinguishable by their small actual read against a 2MB
    // Range ask), a canvas provider's CDN is motion artwork, youtube.com /
    // youtubei is API/extraction traffic. Grep logcat for "BCDataUsage"
    // during a play session; each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Left in place rather than deleted — the investigation this served is
    // not the last one this app will need — but off by default: flip to
    // true only while actively measuring. Wrapping every response body in a
    // counting source and writing a log line per request is not free, and
    // paying that on every install for a question already answered is the
    // wrong trade.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    /**
     * Adds the WebDAV Basic credential to requests aimed at the configured
     * server, so both PROPFIND listings and ExoPlayer's media fetches (which
     * share this client via OkHttpDataSource) authenticate the same way.
     *
     * Read off [com.music.bitchord.data.webdav.WebDavAuth] rather than prefs
     * so the network layer never touches storage: AppSettings publishes here.
     */
    private val webDavInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") == null &&
            com.music.bitchord.data.webdav.WebDavAuth.shouldAuthorize(request.url.host)
        ) {
            val header = com.music.bitchord.data.webdav.WebDavAuth.authHeader
            if (!header.isNullOrBlank()) {
                return@Interceptor chain.proceed(
                    request.newBuilder().header("Authorization", header).build(),
                )
            }
        }
        chain.proceed(request)
    }


    private val playerDiagnosticInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (
            com.music.bitchord.data.proxy.ProxyConfig.shouldProxy(request.url.host) &&
            request.url.encodedPath == "/youtubei/v1/player" &&
            response.isSuccessful
        ) {
            try {
                val payload = response.peekBody(4L * 1024 * 1024).string()
                val root = kotlinx.serialization.json.Json
                    .parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject

                val playability = root?.get("playabilityStatus")
                    as? kotlinx.serialization.json.JsonObject

                val status = (playability?.get("status")
                    as? kotlinx.serialization.json.JsonPrimitive)?.content

                val reason = (playability?.get("reason")
                    as? kotlinx.serialization.json.JsonPrimitive)?.content

                val streaming = root?.get("streamingData")
                    as? kotlinx.serialization.json.JsonObject

                TrackLog.d(
                    "ProxyDiag",
                    "PlayerStatus: status=$status reason=${reason?.take(250)} streamingPresent=${streaming != null}"
                )

                if (streaming != null) {
                    fun fieldPresent(
                        obj: kotlinx.serialization.json.JsonObject,
                        key: String
                    ): Boolean {
                        val value = obj[key]
                        return value != null &&
                            value != kotlinx.serialization.json.JsonNull &&
                            (value !is kotlinx.serialization.json.JsonPrimitive ||
                                value.content.isNotBlank())
                    }

                    val formats = listOf("formats", "adaptiveFormats")
                        .flatMap { key ->
                            (streaming[key] as? kotlinx.serialization.json.JsonArray)
                                ?.filterIsInstance<kotlinx.serialization.json.JsonObject>()
                                .orEmpty()
                        }

                    val audio = formats.filter {
                        (it["mimeType"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.startsWith("audio/") == true
                    }

                    val video = formats.filter {
                        (it["mimeType"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.startsWith("video/") == true
                    }

                    TrackLog.d(
                        "ProxyDiag",
                        "FormatDiag: total=${formats.size} " +
                            "audio=${audio.size} video=${video.size} " +
                            "audioUrls=${audio.count { fieldPresent(it, "url") }} " +
                            "audioCipher=${audio.count { fieldPresent(it, "cipher") || fieldPresent(it, "signatureCipher") }} " +
                            "hls=${fieldPresent(streaming, "hlsManifestUrl")} " +
                            "dash=${fieldPresent(streaming, "dashManifestUrl")} " +
                            "sabr=${fieldPresent(streaming, "serverAbrStreamingUrl")}"
                    )
                }

            } catch (e: Exception) {
                TrackLog.d(
                    "ProxyDiag",
                    "Player diagnostic unavailable: ${e.javaClass.simpleName}"
                )
            }
        }

        response
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .proxySelector(com.music.bitchord.data.proxy.ProxyConfig.selector)
        .proxyAuthenticator { route, response ->
            val config = com.music.bitchord.data.proxy.ProxyConfig.settings
            val address = route?.proxy?.address() as? java.net.InetSocketAddress
            val header = com.music.bitchord.data.proxy.ProxyConfig.authHeader()

            if (!config.enabled ||
                route?.proxy?.type() != java.net.Proxy.Type.HTTP ||
                address == null ||
                !address.hostString.equals(config.host, true) ||
                address.port != config.port ||
                header == null ||
                response.request.header("Proxy-Authorization") != null
            ) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", header)
                    .build()
            }
        }
        .addInterceptor(webDavInterceptor)
        .addInterceptor(playerDiagnosticInterceptor)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val route = chain.connection()?.route()
            val proxyType = route?.proxy?.type()?.name ?: "UNKNOWN"

            val response = chain.proceed(request)

            if (com.music.bitchord.data.proxy.ProxyConfig.shouldProxy(host)) {
                TrackLog.d(
                    "ProxyDiag",
                    "$host ${request.method} ${request.url.encodedPath} route=$proxyType status=${response.code}"
                )
            }


            if (
                com.music.bitchord.data.proxy.ProxyConfig.shouldProxy(host) &&
                response.code in 300..399
            ) {
                val target = response.header("Location")
                    ?.let { request.url.resolve(it) }

                TrackLog.d(
                    "ProxyDiag",
                    "Redirect: $host -> ${target?.host ?: "unknown"} status=${response.code}"
                )
            }

            response
        }

        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()
}
