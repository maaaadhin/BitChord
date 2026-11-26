package com.music.bitchord.playback.experimental

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FlowChunkIteratorTest {

    @Test
    fun deliversChunksInOrder() {
        FlowChunkIterator(
            flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4))
        ).use { iterator ->
            assertTrue(iterator.hasNext())
            assertTrue(iterator.hasNext())
            assertArrayEquals(byteArrayOf(1, 2), iterator.next())
            assertArrayEquals(byteArrayOf(3, 4), iterator.next())
            assertFalse(iterator.hasNext())
        }
    }

    @Test
    fun skipsEmptyChunks() {
        FlowChunkIterator(
            flowOf(byteArrayOf(), byteArrayOf(7))
        ).use { iterator ->
            assertArrayEquals(byteArrayOf(7), iterator.next())
            assertFalse(iterator.hasNext())
        }
    }

    @Test
    fun propagatesErrors() {
        FlowChunkIterator(flow {
            emit(byteArrayOf(1))
            throw IllegalStateException("Test failure")
        }).use { iterator ->
            assertArrayEquals(byteArrayOf(1), iterator.next())

            val error = assertThrows(IOException::class.java) {
                iterator.hasNext()
            }

            assertTrue(error.cause is IllegalStateException)
        }
    }

    @Test(timeout = 10000)
    fun cancelsProducerOnClose() {
        val stopped = CountDownLatch(1)

        val iterator = FlowChunkIterator(flow {
            try {
                emit(byteArrayOf(1))
                awaitCancellation()
            } finally {
                stopped.countDown()
            }
        })

        try {
            assertArrayEquals(byteArrayOf(1), iterator.next())
        } finally {
            iterator.close()
        }

        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertFalse(iterator.hasNext())
    }

    @Test(timeout = 10000)
    fun readerCloseCancelsStream() {
        val stopped = CountDownLatch(1)

        val iterator = FlowChunkIterator(flow {
            try {
                emit(byteArrayOf(1, 2))
                awaitCancellation()
            } finally {
                stopped.countDown()
            }
        })

        AudioChunkReader(iterator).use { reader ->
            val buffer = ByteArray(2)
            assertEquals(2, reader.read(buffer, 0, 2))
            assertArrayEquals(byteArrayOf(1, 2), buffer)
        }

        assertTrue(stopped.await(5, TimeUnit.SECONDS))
    }
}
