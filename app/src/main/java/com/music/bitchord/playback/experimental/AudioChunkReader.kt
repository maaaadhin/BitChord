package com.music.bitchord.playback.experimental

import java.io.Closeable
import java.io.IOException

class AudioChunkReader(
    private val chunks: Iterator<ByteArray>
) : Closeable {

    private var current = ByteArray(0)
    private var position = 0
    private var closed = false

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (closed) throw IOException("Reader closed")

        require(offset >= 0 && length >= 0 &&
            offset <= buffer.size &&
            length <= buffer.size - offset)

        if (length == 0) return 0

        while (position >= current.size) {
            if (!chunks.hasNext()) return -1
            current = chunks.next()
            position = 0
        }

        val count = minOf(
            length,
            current.size - position
        )

        current.copyInto(
            buffer,
            offset,
            position,
            position + count
        )

        position += count
        return count
    }

    override fun close() {
        closed = true
        current = ByteArray(0)
        position = 0
    }
}
