package dev.lelonio.square.backend.youtube

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.lelonio.square.backend.lyrics.LyricsLibrary
import dev.lelonio.square.data.DjAudioFrame
import dev.lelonio.square.data.DjBoundarySelector
import dev.lelonio.square.data.DjWindow
import dev.lelonio.square.data.DjSampleClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.sqrt

internal const val DJ_ANALYSIS_FRAGMENT = "dj-analysis"

/** Decodes only a potential mixing segment into measurements, never into a speaker or the live mixer. */
@UnstableApi
internal class DjAudioAnalyzer(private val context: Context, private val dataSources: DataSource.Factory) {
    private val cache = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 40
    }

    suspend fun analyze(item: MediaItem, range: DjWindow): Long? {
        val key = "${item.mediaId}:${range.startMs}:${range.endMs}"
        synchronized(cache) { cache[key] }?.let { return it }
        val lyrics = LyricsLibrary.cachedLyrics(item.mediaId)
        val control = HandlerThread("SquareDjControl", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val applicationHandler = Handler(control.looper)
        return try {
            withTimeoutOrNull(15_000) {
            withContext(applicationHandler.asCoroutineDispatcher().immediate) {
                suspendCancellableCoroutine { continuation ->
                    val thread = HandlerThread("SquareDjAnalysis", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
                    var scanner: ExoPlayer? = null
                    var released = false
                    val finished = AtomicBoolean()
                    val scanStart = maxOf(0, range.startMs - 8_000, range.endMs - 90_000)
                    val collector = EnergyCollector(scanStart, range.endMs)
                    fun release() {
                        if (released) return
                        released = true
                        try { runCatching { scanner?.release() } } finally { thread.quitSafely() }
                    }
                    fun complete(success: Boolean) {
                        if (!finished.compareAndSet(false, true)) return
                        val measurements = if (success) collector.snapshot() else emptyList()
                        val choice = if (success && measurements.size >= 40)
                            DjBoundarySelector.choose(measurements, range, lyrics) else null
                        if (success && measurements.size < 40) android.util.Log.w("SquareDjAnalysis",
                            "no usable PCM: ${collector.observedFrames} samples, first=${collector.firstMs} last=${collector.lastMs} scan=$scanStart..${range.endMs}")
                        applicationHandler.post {
                            release()
                            if (continuation.isActive) {
                                if (choice != null) {
                                    synchronized(cache) { cache[key] = choice.timeMs }
                                    android.util.Log.i("SquareDjAnalysis", "${choice.reason} at ${choice.timeMs}ms (${measurements.size} measurements)")
                                }
                                continuation.resume(choice?.timeMs)
                            }
                        }
                    }
                    collector.onComplete = { complete(true) }
                    continuation.invokeOnCancellation {
                        finished.set(true)
                        applicationHandler.post { release() }
                    }
                    try {
                        val selector = DefaultTrackSelector(context).apply {
                            parameters = buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        }
                        val output = AnalysisOutputProvider(context, collector)
                        val renderers = object : DefaultRenderersFactory(context) {
                            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                enableAudioTrackPlaybackParams: Boolean) =
                                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                    .setEnableFloatOutput(enableFloatOutput)
                                    .setEnableAudioTrackPlaybackParams(false)
                                    .setAudioOutputProvider(output)
                                    .build()
                        }.setMediaCodecSelector { _, _, _ -> emptyList() }
                            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        scanner = ExoPlayer.Builder(context, renderers)
                            .setLooper(control.looper)
                            .setPlaybackLooper(thread.looper)
                            .setTrackSelector(selector)
                            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
                            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                            .setHandleAudioBecomingNoisy(false)
                            .build()
                        scanner.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                android.util.Log.w("SquareDjAnalysis", "analysis unavailable: ${error.errorCodeName}", error)
                                complete(false)
                            }
                            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                android.util.Log.i("SquareDjAnalysis", "audio selected=${tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)} groups=${tracks.groups.size}")
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) complete(true)
                            }
                        })
                        val uri = item.localConfiguration?.uri?.toString()?.substringBefore('#') ?: item.mediaId
                        scanner.setMediaItem(item.buildUpon().setUri("$uri#$DJ_ANALYSIS_FRAGMENT").build(), scanStart)
                        scanner.prepare()
                        scanner.play()
                    } catch (error: Exception) {
                        android.util.Log.w("SquareDjAnalysis", "analysis unavailable", error)
                        complete(false)
                    }
                }
            }
            }
        } finally {
            control.quitSafely()
        }
    }
}

/** One 25 ms RMS measurement; work is isolated on the analysis decoder thread. */
internal class EnergyCollector(val startMs: Long, private val endMs: Long) {
    var onComplete: (() -> Unit)? = null
    private val frames = mutableListOf<DjAudioFrame>()
    private var squares = 0.0
    private var samples = 0
    private var ended = false
    var observedFrames = 0L
        private set
    var firstMs: Long? = null
        private set
    var lastMs: Long? = null
        private set

    fun sample(timeMs: Long, square: Double, sampleRate: Int) {
        observedFrames++
        if (firstMs == null) firstMs = timeMs
        lastMs = timeMs
        if (ended || timeMs < startMs) return
        if (timeMs >= endMs) {
            ended = true
            onComplete?.invoke()
            return
        }
        squares += square
        samples++
        if (samples >= sampleRate / 40) {
            frames += DjAudioFrame(timeMs, sqrt(squares / samples))
            squares = 0.0
            samples = 0
        }
    }

    fun snapshot(): List<DjAudioFrame> = frames.toList()
}

/** Consults normal PCM format support, but NEVER creates an AudioTrack or falls back to audible output. */
@UnstableApi
private class AnalysisOutputProvider(context: Context, private val collector: EnergyCollector) :
    ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        check(config.encoding == C.ENCODING_PCM_16BIT || config.encoding == C.ENCODING_PCM_FLOAT)
        check(!config.isOffload && !config.isTunneling && config.sampleRate > 0)
        android.util.Log.i("SquareDjAnalysis", "PCM output ${config.sampleRate}Hz enc=${config.encoding}")
        return AnalysisOutput(config, collector)
    }
}

@UnstableApi
private class AnalysisOutput(
    private val config: AudioOutputProvider.OutputConfig,
    private val collector: EnergyCollector,
) : AudioOutput {
    private val channels = Integer.bitCount(config.channelMask).also { check(it in 1..8) }
    private val floatInput = config.encoding == C.ENCODING_PCM_FLOAT
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val clock = DjSampleClock(collector.startMs)
    @Volatile private var writtenFrames = 0L

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = data.remaining() / (channels * if (floatInput) 4 else 2)
        val startUs = clock.bufferStartUs(presentationTimeUs.takeUnless { it == C.TIME_UNSET })
        for (i in 0 until frames) {
            var square = 0.0
            repeat(channels) {
                val value = if (floatInput) data.float.toDouble() else data.short / 32768.0
                square += value * value
            }
            collector.sample((startUs + i * 1_000_000L / config.sampleRate) / 1_000, square / channels, config.sampleRate)
        }
        clock.advance(startUs, frames, config.sampleRate)
        buffer.position(data.position())
        writtenFrames += frames
        return !buffer.hasRemaining()
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun flush() { writtenFrames = 0 }
    override fun release() { listeners.forEach { it.onReleased() } }
    override fun getPositionUs() = writtenFrames * 1_000_000L / config.sampleRate
    override fun getSampleRate() = config.sampleRate
    override fun getBufferSizeInFrames() = config.sampleRate.toLong()
    override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isOffloadedPlayback() = false
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setVolume(volume: Float) = Unit
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}
