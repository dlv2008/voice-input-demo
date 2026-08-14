package com.trendbot.voiceinputdemo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.trendbot.voiceinputdemo.speech.AudioFrame
import kotlin.math.abs

/**
 * Android-only microphone adapter that emits about 100ms of normalized PCM
 * at a time. It does not contain ASR, endpoint, VAD, or UI logic.
 */
class StreamPcmRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MILLIS = 100
    }

    @Volatile
    private var recording = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    val isRecording: Boolean
        get() = recording

    /**
     * The caller must hold RECORD_AUDIO permission.
     * All callbacks run on the recorder thread, not the UI thread.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(
        onFrame: (
            frame: AudioFrame,
            elapsedSeconds: Double,
            peak: Float
        ) -> Unit,
        onStopped: (audioSeconds: Double) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(!recording) { "Recorder is already running" }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize failed: $minBufferBytes"
        }

        val frameSamples = sampleRate * FRAME_MILLIS / 1000
        val frameBytes = frameSamples * 2
        val nativeBufferBytes = maxOf(minBufferBytes, frameBytes) * 2

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            nativeBufferBytes,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "AudioRecord initialization failed"
        }

        audioRecord = recorder
        recording = true

        Thread(
            {
                captureLoop(
                    recorder = recorder,
                    frameSamples = frameSamples,
                    onFrame = onFrame,
                    onStopped = onStopped,
                    onError = onError,
                )
            },
            "voice-stream-recorder"
        ).start()
    }

    private fun captureLoop(
        recorder: AudioRecord,
        frameSamples: Int,
        onFrame: (AudioFrame, Double, Float) -> Unit,
        onStopped: (Double) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val pcm16 = ShortArray(frameSamples)
        var totalSamples = 0L
        var failure: Throwable? = null

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDSTATE_RECORDING"
            }
            while (recording) {
                val read = recorder.read(
                    pcm16,
                    0,
                    pcm16.size,
                    AudioRecord.READ_BLOCKING,
                )

                if (read > 0) {
                    val frameStartNanos =
                        totalSamples * 1_000_000_000L / sampleRate.toLong()
                    val normalized = FloatArray(read)
                    var peakPcm16 = 0

                    for (index in 0 until read) {
                        val value = pcm16[index].toInt()
                        peakPcm16 = maxOf(peakPcm16, abs(value))
                        normalized[index] = value.toFloat() / 32768.0f
                    }

                    totalSamples += read.toLong()
                    onFrame(
                        AudioFrame(
                            samples = normalized,
                            sampleRate = sampleRate,
                            timestampNanos = frameStartNanos,
                        ),
                        totalSamples.toDouble() / sampleRate.toDouble(),
                        peakPcm16.toFloat() / 32768.0f
                    )
                } else if (read < 0 && recording) {
                    error("AudioRecord.read failed: $read")
                }
            }
        } catch (error: Throwable) {
            if (recording) {
                failure = error
            }
        } finally {
            recording = false
            try {
                if (
                    recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // stop() my already have been called by the UI thread.
            }
            recorder.release()
            synchronized(this) {
                if (audioRecord === recorder) {
                    audioRecord = null
                }
            }
        }

        if (failure != null) {
            onError(failure)
        } else {
            onStopped(
                totalSamples.toDouble() / sampleRate.toDouble()
            )
        }
    }

    fun stop() {
        recording = false
        val recorder = audioRecord ?: return
        try {
            if (
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder.stop()
            }
        } catch (_: IllegalStateException) {
            // The capture thread is responsible for final release.
        }
    }

    override fun close() {
        stop()
    }
}
