package com.music.bitchord.playback.experimental

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@UnstableApi
class ExperimentalAudioDataSource(
    private val chunkProvider: () -> Iterator<ByteArray>
) : BaseDataSource(false) {

    private var reader: AudioChunkReader? = null
    private var currentUri: Uri? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        if (opened) {
            throw IOException("Source already open")
        }

        if (dataSpec.position != 0L) {
            throw IOException("Seeking not supported yet")
        }

        transferInitializing(dataSpec)

        reader = AudioChunkReader(chunkProvider())
        currentUri = dataSpec.uri
        remaining = dataSpec.length
        opened = true

        transferStarted(dataSpec)

        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (length == 0) return 0

        val active = reader
            ?: throw IOException("Source not open")

        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val requested = if (remaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), remaining).toInt()
        }

        val count = active.read(buffer, offset, requested)

        if (count > 0) {
            if (remaining != C.LENGTH_UNSET.toLong()) {
                remaining -= count
            }
            bytesTransferred(count)
        }

        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            currentUri = null
            remaining = C.LENGTH_UNSET.toLong()

            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
