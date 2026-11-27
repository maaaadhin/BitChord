package com.music.bitchord.network.cronet

import android.content.Context
import android.util.Pair
import com.music.bitchord.data.proxy.ProxyConfig
import okhttp3.Credentials
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.Proxy
import org.chromium.net.ProxyOptions
import java.util.concurrent.Executor

object CronetEngineFactory {

    fun create(
        context: Context,
        config: ProxyConfig.Settings,
        onConnect: (Int) -> Unit = {}
    ): CronetEngine {

        require(config.enabled)
        require(config.host.isNotBlank())
        require(config.port in 1..65535)

        val auth = Credentials.basic(
            config.username,
            config.password
        )

        val callback = object : Proxy.HttpConnectCallback() {

            override fun onBeforeRequest(request: Request) {
                request.proceed(
                    listOf(Pair("Proxy-Authorization", auth))
                )
            }

            override fun onResponseReceived(
                headers: MutableList<Pair<String, String>>,
                responseCode: Int
            ): Int {
                onConnect(responseCode)

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
            .getAllProviders(context.applicationContext)
            .first {
                it.isEnabled &&
                it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED
            }

        return provider.createBuilder()
            .enableHttp2(true)
            .enableQuic(false)
            .enableHttpCache(
                CronetEngine.Builder.HTTP_CACHE_DISABLED,
                0
            )
            .setProxyOptions(options)
            .build()
    }
}
