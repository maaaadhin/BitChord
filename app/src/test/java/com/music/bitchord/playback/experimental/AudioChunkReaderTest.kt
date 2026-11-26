package com.music.bitchord.playback.experimental

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AudioChunkReaderTest {

    @Test
    fun readsAcrossChunks() {
        val reader = AudioChunkReader(
            listOf(
                byteArrayOf(1, 2),
                byteArrayOf(3, 4)
            ).iterator()
        )

        val buffer = ByteArray(4)

        assertEquals(2, reader.read(buffer, 0, 4))
        assertEquals(2, reader.read(buffer, 2, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)
        assertEquals(-1, reader.read(buffer, 0, 4))
    }

    @Test
    fun handlesPartialReads() {
        val reader = AudioChunkReader(
            listOf(byteArrayOf(1, 2, 3)).iterator()
        )

        val buffer = ByteArray(1)

        for (i in 1..3) {
            assertEquals(1, reader.read(buffer, 0, 1))
            assertEquals(i.toByte(), buffer[0])
        }

        assertEquals(-1, reader.read(buffer, 0, 1))
    }

    @Test
    fun skipsEmptyChunks() {
        val reader = AudioChunkReader(
            listOf(
                byteArrayOf(),
                byteArrayOf(7),
                byteArrayOf()
            ).iterator()
        )

        val buffer = ByteArray(1)

        assertEquals(1, reader.read(buffer, 0, 1))
        assertEquals(7.toByte(), buffer[0])
        assertEquals(-1, reader.read(buffer, 0, 1))
    }

    @Test
    fun handlesZeroLengthRead() {
        val reader = AudioChunkReader(
            emptyList<ByteArray>().iterator()
        )

        assertEquals(0, reader.read(ByteArray(1), 0, 0))
    }

    @Test(expected = IOException::class)
    fun rejectsReadsAfterClose() {
        val reader = AudioChunkReader(
            emptyList<ByteArray>().iterator()
        )

        reader.close()
        reader.read(ByteArray(1), 0, 1)
    }
}
