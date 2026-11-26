package com.music.bitchord.playback.experimental

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExperimentalAudioDataSourceTest {

    @Test
    fun readsAudioChunks() {
        val source = ExperimentalAudioDataSource {
            listOf(
                byteArrayOf(1, 2),
                byteArrayOf(3, 4)
            ).iterator()
        }

        val uri = Uri.parse("test://audio")
        val spec = DataSpec.Builder()
            .setUri(uri)
            .build()

        try {
            source.open(spec)

            assertEquals(uri, source.uri)

            val buffer = ByteArray(4)

            assertEquals(2, source.read(buffer, 0, 4))
            assertEquals(2, source.read(buffer, 2, 2))

            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                buffer
            )

            assertEquals(
                C.RESULT_END_OF_INPUT,
                source.read(buffer, 0, 4)
            )
        } finally {
            source.close()
        }

        assertNull(source.uri)
    }

    @Test
    fun respectsRequestedLength() {
        val source = ExperimentalAudioDataSource {
            listOf(byteArrayOf(1, 2, 3, 4)).iterator()
        }

        val spec = DataSpec.Builder()
            .setUri(Uri.parse("test://audio"))
            .setLength(2L)
            .build()

        try {
            assertEquals(2L, source.open(spec))

            val buffer = ByteArray(4)

            assertEquals(2, source.read(buffer, 0, 4))
            assertEquals(
                C.RESULT_END_OF_INPUT,
                source.read(buffer, 0, 4)
            )
        } finally {
            source.close()
        }
    }
}
