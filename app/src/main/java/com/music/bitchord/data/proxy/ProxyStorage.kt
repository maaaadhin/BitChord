package com.music.bitchord.data.proxy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ProxyStorage {

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "proxy_secure_settings",
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun load(context: Context): ProxyConfig.Settings {
        val p = prefs(context)

        return ProxyConfig.Settings(
            enabled = p.getBoolean("enabled", false),
            host = p.getString("host", "") ?: "",
            port = p.getInt("port", 80),
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: ""
        )
    }

    fun save(
        context: Context,
        config: ProxyConfig.Settings
    ) {
        require(!config.enabled || config.host.isNotBlank())
        require(config.port in 1..65535)

        check(
            prefs(context).edit()
                .putBoolean("enabled", config.enabled)
                .putString("host", config.host)
                .putInt("port", config.port)
                .putString("username", config.username)
                .putString("password", config.password)
                .commit()
        )
    }

    fun initialize(context: Context) {
        ProxyConfig.update(load(context))
    }
}
