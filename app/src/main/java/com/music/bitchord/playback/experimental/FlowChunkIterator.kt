package com.music.bitchord.playback.experimental

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.io.IOException

class FlowChunkIterator(
    source: Flow<ByteArray>,
    capacity: Int = 8
) : Iterator<ByteArray>, Closeable {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val channel = Channel<ByteArray>(capacity)

    @Volatile
    private var closed = false

    private var pending: ByteArray? = null

    init {
        scope.launch {
            try {
                source.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        channel.send(chunk)
                    }
                }
                channel.close()
            } catch (error: Throwable) {
                channel.close(error)
            }
        }
    }

    override fun hasNext(): Boolean {
        if (closed) return false
        if (pending != null) return true

        val result = runBlocking {
            channel.receiveCatching()
        }

        if (closed) return false

        result.exceptionOrNull()?.let {
            throw IOException("Audio stream failed", it)
        }

        pending = result.getOrNull()
        return pending != null
    }

    override fun next(): ByteArray {
        if (!hasNext()) {
            throw NoSuchElementException()
        }

        return pending!!.also {
            pending = null
        }
    }

    override fun close() {
        closed = true
        channel.cancel()
        scope.cancel()
    }
}
