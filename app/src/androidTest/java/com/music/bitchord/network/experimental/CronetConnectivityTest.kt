package com.music.bitchord.network.experimental

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CronetConnectivityTest {

    @Test
    fun bundledNativeEngineWorks() {
        val context = InstrumentationRegistry
            .getInstrumentation().targetContext

        val providers = CronetProvider.getAllProviders(context)

        providers.forEach {
            println(
                "Cronet provider: ${it.name}, " +
                "version=${it.version}, enabled=${it.isEnabled}"
            )
        }

        val provider = providers.firstOrNull {
            it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED &&
                it.isEnabled
        } ?: error("Bundled Cronet provider unavailable")

        val engine = provider.createBuilder()
            .enableHttp2(true)
            .enableQuic(false)
            .enableHttpCache(
                CronetEngine.Builder.HTTP_CACHE_DISABLED, 0L
            )
            .build()

        try {
            println("Cronet engine: ${engine.versionString}")

            assertFalse(
                engine.versionString.startsWith(
                    "CronetHttpURLConnection/"
                )
            )

            val connection = engine.openConnection(
                URL("https://example.com")
            ) as HttpURLConnection

            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false

                val status = connection.responseCode

                println("Cronet HTTP status: $status")

                assertEquals(200, status)

                connection.inputStream.use {
                    assertTrue(it.read() >= 0)
                }
            } finally {
                connection.disconnect()
            }
        } finally {
            engine.shutdown()
        }
    }
}
