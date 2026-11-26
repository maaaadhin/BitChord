package com.music.bitchord.data.proxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import okhttp3.Credentials

object ProxyConfig {

    data class Settings(
        val enabled: Boolean = false,
        val host: String = "",
        val port: Int = 80,
        val username: String = "",
        val password: String = ""
    )

    @Volatile
    var settings = Settings()
        private set

    fun update(value: Settings) {
        settings = value
    }

    private fun matches(host: String, domain: String): Boolean {
        return host.equals(domain, true) ||
            host.endsWith(".$domain", true)
    }

    fun shouldProxy(host: String): Boolean {
        return listOf(
            "youtube.com",
            "googlevideo.com",
            "youtubei.googleapis.com",
            "ytimg.com",
            "youtube-nocookie.com"
        ).any { matches(host, it) }
    }

    val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val config = settings
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)

            if (!config.enabled || !shouldProxy(host)) {
                return listOf(Proxy.NO_PROXY)
            }

            require(config.host.isNotBlank())
            require(config.port in 1..65535)

            return listOf(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(
                        config.host, config.port
                    )
                )
            )
        }

        override fun connectFailed(
            uri: URI,
            sa: SocketAddress,
            ioe: IOException
        ) {
            // No direct fallback for proxied requests.
        }
    }

    fun authHeader(): String? {
        val config = settings
        if (!config.enabled || config.username.isBlank()) {
            return null
        }
        return Credentials.basic(
            config.username,
            config.password
        )
    }
}
