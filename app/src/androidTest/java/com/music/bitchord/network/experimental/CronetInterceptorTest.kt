package com.music.bitchord.network.experimental

import android.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.proxy.ProxyStorage
import com.music.bitchord.network.cronet.CronetInterceptor
import okhttp3.Credentials
import okhttp3.OkHttpClient
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

            assertTrue(connects.get() > 0)
            assertEquals(200, connectStatus.get())

        } finally {
            engine.shutdown()
        }
    }
}
