package com.multiplayer.gbalink.core

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class GbaEmulator(private val nativeCore: GbaNative) {

    companion object {
        private const val TAG = "GbaEmulator"
        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160
        private const val SAMPLE_RATE = 32000
        private const val BASE_FRAME_TIME_NS = 16_742_706L // 59.7275 FPS (1s / 59.7275)
    }

    private val isRunning = AtomicBoolean(false)
    private var emuThread: Thread? = null

    val screenBitmap: Bitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private var audioTrack: AudioTrack? = null
    private val audioBuffer = ShortArray(4096)

    @Volatile
    var currentKeyMask: Int = 0

    @Volatile
    var speedMultiplier: Float = 1.0f

    var onFrameRendered: ((Bitmap) -> Unit)? = null
    var onFpsUpdate: ((Int) -> Unit)? = null

    init {
        nativeCore.nativeInit()
        initAudio()
    }

    private fun initAudio() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // ~4 frames of stereo audio: enough to absorb jitter, small enough for low latency
            val bufferSize = (minBufSize * 2).coerceAtLeast(SAMPLE_RATE / 60 * 2 * 2 * 4)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
        }
    }

    fun loadRom(romBytes: ByteArray): Boolean {
        pause()
        return nativeCore.nativeLoadRom(romBytes)
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        audioTrack?.play()
        emuThread = Thread({
            var lastTime = System.nanoTime()
            var frameCount = 0
            var fpsTimer = System.currentTimeMillis()

            while (isRunning.get()) {
                val now = System.nanoTime()
                val delta = now - lastTime

                // Run 1 Emulated Frame
                val samples = nativeCore.nativeRunFrame(screenBitmap, audioBuffer, currentKeyMask)

                // At 1x the AudioTrack paces emulation (blocking write = audio clock, no crackles).
                // When fast-forwarding, audio is dropped and the sleep below throttles instead.
                val audioSync = speedMultiplier <= 1.0f && audioTrack != null
                if (samples > 0 && audioSync) {
                    audioTrack?.write(audioBuffer, 0, samples, AudioTrack.WRITE_BLOCKING)
                }

                // Notify UI to render bitmap
                onFrameRendered?.invoke(screenBitmap)

                frameCount++
                if (System.currentTimeMillis() - fpsTimer >= 1000) {
                    onFpsUpdate?.invoke(frameCount)
                    frameCount = 0
                    fpsTimer = System.currentTimeMillis()
                }

                if (!(audioSync && samples > 0)) {
                    val elapsed = System.nanoTime() - now
                    val targetFrameTime = (BASE_FRAME_TIME_NS / speedMultiplier.coerceAtLeast(0.25f)).toLong()
                    val sleepNs = targetFrameTime - elapsed
                    if (sleepNs > 0) {
                        try {
                            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }
                lastTime = System.nanoTime()
            }
        }, "GbaEmulatorThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() {
        if (!isRunning.getAndSet(false)) return
        emuThread?.join(500)
        emuThread = null
        audioTrack?.pause()
    }

    fun resume() {
        start()
    }

    fun stop() {
        pause()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun setLinkRole(role: Int) {
        nativeCore.nativeSetLinkRole(role)
    }


    fun getSaveData(): ByteArray? = nativeCore.nativeGetSaveData()

    fun loadSaveData(data: ByteArray) = nativeCore.nativeLoadSaveData(data)

    /** Emulator save state (snapshot of the whole machine, MyBoy-style slot). */
    fun saveState(): ByteArray? = nativeCore.nativeSaveState()

    fun loadState(data: ByteArray): Boolean = nativeCore.nativeLoadState(data)

    fun reset() = nativeCore.nativeReset()
}
