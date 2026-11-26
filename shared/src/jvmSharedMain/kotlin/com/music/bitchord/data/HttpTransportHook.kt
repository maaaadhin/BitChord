package com.music.bitchord.data

import okhttp3.Interceptor

object HttpTransportHook {

    @Volatile
    var transport: Interceptor? = null

    val interceptor = Interceptor { chain ->
        val installed = transport

        if (installed == null) {
            chain.proceed(chain.request())
        } else {
            installed.intercept(chain)
        }
    }
}
