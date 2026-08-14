package com.trendbot.voiceinputdemo

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader

data class RecognitionOutput(
    val text: String,
    val language: String,
    val emotion: String,
    val event: String,
    val audioSeconds: Double,
    val elapsedSeconds: Double,
    val rtf: Double,
)

class AndroidVoiceCore(
    private val assetManager: AssetManager,
) : AutoCloseable {
    companion object {
        private const val TAG = LogTags.OFFLINE_ASR
        const val SAMPLE_RATE = 16_000
        const val MODEL_DIR =
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    }

    private var recognizer: OfflineRecognizer? = null
    private var closed = false

    @Synchronized
    fun initialize() {
        check(!closed) { "AndroidVoiceCore is already closed" }
        check(recognizer == null) { "AndroidVoiceCore is already initialized" }

        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "$MODEL_DIR/model.int8.onnx",
                language = "auto",
                useInverseTextNormalization = true,
            ),
            numThreads = 2,
            debug = false,
            provider = "cpu",
            tokens = "$MODEL_DIR/tokens.txt",
        )

        recognizer = OfflineRecognizer(
            assetManager = assetManager,
            config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            ),
        )
        Log.i(TAG, "offline recognizer initialized once")
    }

    @Synchronized
    fun decodePcm(
        samples: FloatArray,
        sampleRate: Int,
    ): RecognitionOutput {
        check(!closed) { "AndroidVoiceCore is already closed" }
        require(samples.isNotEmpty()) { "PCM samples must not be empty" }
        require(sampleRate == SAMPLE_RATE) {
            "Expected $SAMPLE_RATE Hz, got $sampleRate Hz"
        }

        val activeRecognizer =
            checkNotNull(recognizer) { "AndroidVoiceCore is not initialized" }
        val stream = activeRecognizer.createStream()

        try {
            stream.acceptWaveform(samples, sampleRate)

            val startedAt = SystemClock.elapsedRealtimeNanos()
            activeRecognizer.decode(stream)
            val finishedAt = SystemClock.elapsedRealtimeNanos()

            val nativeResult = activeRecognizer.getResult(stream)
            val elapsedSeconds = (finishedAt - startedAt) / 1_000_000_000.0
            val audioSeconds = samples.size.toDouble() / sampleRate.toDouble()

            return RecognitionOutput(
                text = nativeResult.text,
                language = nativeResult.lang,
                emotion = nativeResult.emotion,
                event = nativeResult.event,
                audioSeconds = audioSeconds,
                elapsedSeconds = elapsedSeconds,
                rtf = if (audioSeconds > 0.0) elapsedSeconds / audioSeconds else 0.0,
            )
        } finally {
            stream.release()
        }
    }

    fun decodeAsset(wavAssetPath: String): RecognitionOutput {
        val wave = WaveReader.readWave(assetManager, wavAssetPath)
        return decodePcm(wave.samples, wave.sampleRate)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer?.release()
        recognizer = null
        Log.i(TAG, "offline recognizer released once")
    }
}
