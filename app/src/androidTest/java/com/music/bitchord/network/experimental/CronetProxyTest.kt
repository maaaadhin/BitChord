package com.music.bitchord.network.experimental

import android.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.proxy.ProxyStorage
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.Proxy
import org.chromium.net.ProxyOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.Credentials
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CronetProxyTest {

    @Test
    fun authenticatedProxyWorks() {
        val context = InstrumentationRegistry
            .getInstrumentation().targetContext

        val config = ProxyStorage.load(context)

        assertTrue("Proxy must be enabled", config.enabled)
        assertTrue("Proxy host missing", config.host.isNotBlank())
        assertTrue(config.port in 1..65535)
        assertTrue("Username missing", config.username.isNotBlank())
        assertTrue("Password missing", config.password.isNotBlank())

        val auth = Credentials.basic(
            config.username,
            config.password
        )

        val connectRequests = AtomicInteger(0)
        val connectStatus = AtomicInteger(0)

        val callback = object : Proxy.HttpConnectCallback() {

            override fun onBeforeRequest(request: Request) {
                connectRequests.incrementAndGet()

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
                it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED &&
                    it.isEnabled
            }

        val engine = provider.createBuilder()
            .enableHttp2(true)
            .enableQuic(false)
            .enableHttpCache(
                CronetEngine.Builder.HTTP_CACHE_DISABLED, 0L
            )
            .setProxyOptions(options)
            .build()

        try {
            val connection = engine.openConnection(
                URL("https://example.com")
            ) as HttpURLConnection

            try {
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = false

                val status = connection.responseCode

                assertEquals("HTTPS response", 200, status)

                connection.inputStream.use {
                    assertTrue(it.read() >= 0)
                }

                assertTrue(
                    "Proxy CONNECT callback not invoked",
                    connectRequests.get() > 0
                )

                assertEquals(
                    "Proxy CONNECT response",
                    200,
                    connectStatus.get()
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            engine.shutdown()
        }
    }
}
