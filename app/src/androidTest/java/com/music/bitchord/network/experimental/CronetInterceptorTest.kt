package com.music.bitchord.network.experimental

import android.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.proxy.ProxyStorage
import com.music.bitchord.network.cronet.CronetInterceptor
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.Proxy
import org.chromium.net.ProxyOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CronetInterceptorTest {

    @Test
    fun okhttpThroughCronetProxy() {
        val context = InstrumentationRegistry
            .getInstrumentation().targetContext

        val config = ProxyStorage.load(context)

        assertTrue(config.enabled)
        assertTrue(config.host.isNotBlank())
        assertTrue(config.username.isNotBlank())

        val auth = Credentials.basic(
            config.username,
            config.password
        )

        val connects = AtomicInteger(0)
        val connectStatus = AtomicInteger(0)

        val callback = object : Proxy.HttpConnectCallback() {
            override fun onBeforeRequest(request: Request) {
                connects.incrementAndGet()
                request.proceed(
                    listOf(Pair("Proxy-Authorization", auth))
                )
            }

            override fun onResponseReceived(
                headers: MutableList<Pair<String, String>>,
                responseCode: Int
            ): Int {
                connectStatus.set(responseCode)

                return if (responseCode == 200) {
                    RESPONSE_ACTION_PROCEED
                } else {
                    RESPONSE_ACTION_CLOSE
                }
            }
        }

        val proxy = Proxy.createHttpProxy(
            Proxy.SCHEME_HTTP,
            config.host,
            config.port,
            Executor { it.run() },
            callback
        )

        val options = ProxyOptions.fromProxyList(
            listOf(proxy),
            ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
        )

        val provider = CronetProvider
            .getAllProviders(context)
            .first {
                it.isEnabled &&
                it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED
            }

        val engine = provider.createBuilder()
            .enableHttp2(true)
            .enableQuic(false)
            .enableHttpCache(
                CronetEngine.Builder.HTTP_CACHE_DISABLED,
                0
            )
            .setProxyOptions(options)
            .build()

        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(CronetInterceptor(engine))
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://example.com/")
                .build()

            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertTrue(
                    it.body!!.string().contains("Example Domain")
                )
            }

            val payload = """{"test":"cronet-bridge"}"""

            val post = okhttp3.Request.Builder()
                .url("https://httpbin.org/post")
                .header("X-Bridge-Test", "cronet")
                .post(payload.toRequestBody(
                    "application/json".toMediaType()
                ))
                .build()

            client.newCall(post).execute().use {
                assertEquals(200, it.code)

                val result = JSONObject(it.body!!.string())

                assertEquals(
                    "cronet-bridge",
                    result.getJSONObject("json").getString("test")
                )

                assertEquals(
                    "cronet",
                    result.getJSONObject("headers")
                        .getString("X-Bridge-Test")
                )

                assertTrue(
                    it.header("Content-Type")
                        ?.contains("application/json") == true
                )
            }

            val streaming = okhttp3.Request.Builder()
                .url("https://httpbin.org/stream-bytes/102400")
                .build()

            client.newCall(streaming).execute().use {
                assertEquals(200, it.code)

                val body = it.body!!
                assertEquals(-1L, body.contentLength())

                val source = body.source()
                val buffer = okio.Buffer()
                var total = 0L

                while (true) {
                    val count = source.read(buffer, 4096)
                    if (count == -1L) break
                    total += count
                    buffer.clear()
                }

                assertEquals(102400L, total)
                assertEquals(-1L, source.read(buffer, 4096))
                assertEquals(-1L, source.read(buffer, 4096))

            }

            client.newCall(streaming).execute().use {
                assertEquals(200, it.code)
                val buffer = okio.Buffer()
                assertTrue(it.body!!.source().read(buffer, 4096) > 0)
            }

            assertTrue(connects.get() > 0)
            assertEquals(200, connectStatus.get())

        } finally {
            engine.shutdown()
        }
    }
}
