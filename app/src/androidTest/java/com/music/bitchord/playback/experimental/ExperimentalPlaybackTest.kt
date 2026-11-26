package com.music.bitchord.playback.experimental

import android.net.Uri
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExperimentalPlaybackTest {

    @Test
    fun playsGeneratedAudio() {
        val instrumentation =
            InstrumentationRegistry.getInstrumentation()

        val audio = instrumentation.context.assets
            .open("test-tone.wav").use { it.readBytes() }

        val finished = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        val ended = AtomicReference(false)

        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(
                instrumentation.targetContext
            ).build()

            player.volume = 0f

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        ended.set(true)
                        finished.countDown()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    finished.countDown()
                }
            })

            val factory = DataSource.Factory {
                ExperimentalAudioDataSource {
                    FlowChunkIterator(flow {
                        var offset = 0

                        while (offset < audio.size) {
                            val end = minOf(offset + 4096, audio.size)
                            emit(audio.copyOfRange(offset, end))
                            offset = end
                            delay(10)
                        }
                    })
                }
            }

            val source = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(
                    MediaItem.fromUri(
                        Uri.parse("test://audio/test-tone.wav")
                    )
                )

            player.setMediaSource(source)
            player.prepare()
            player.play()
        }

        try {
            assertTrue(
                "Playback timed out",
                finished.await(30, TimeUnit.SECONDS)
            )

            assertNull(failure.get())
            assertTrue(ended.get())
        } finally {
            instrumentation.runOnMainSync {
                player.release()
            }
        }
    }
}
