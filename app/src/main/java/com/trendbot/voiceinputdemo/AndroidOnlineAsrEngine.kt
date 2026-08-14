package com.trendbot.voiceinputdemo

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.trendbot.voiceinputdemo.speech.AudioFrame
import com.trendbot.voiceinputdemo.speech.TranscriptionEvent

data class OnlineDecodeResult(
    val segmentId: Long,
    val partial: TranscriptionEvent.Partial?,
    val endpointDetected: Boolean,
)

/**
 * Android / Sherpa adapter for the first-pass online recognizer.
 *
 * Threading Rule: initialize(), startSession(), accept(), stopSession(), and close()
 * must all be invoked serially on the worker executor.
 */

class AndroidOnlineAsrEngine(
    private val assetManager: AssetManager,
) : AutoCloseable {
    companion object {
        private const val TAG = LogTags.ONLINE_ASR
        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var closed = false
    private var nextSegmentId = 1L
    private var currentSegmentId = 0L
    private var lastText = ""

    fun initialize() {
        check(!closed) { "Engine is already closed" }
        check(recognizer == null) { "Recognizer is already initialized" }

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                joiner = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
            ),
            tokens = "$MODEL_DIR/tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = "zipformer",
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
            ),
            modelConfig = modelConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )

        recognizer = OnlineRecognizer(
            assetManager = assetManager,
            config = config,
        )

        Log.i(TAG, "online recognizer initialized once")
    }

    fun startSession() {
        check(!closed) { "Engine is already closed" }
        val activeRecognizer = checkNotNull(recognizer) {
            "Recognizer is not initialized"
        }
        check(stream == null) { "An online stream is already active" }

        currentSegmentId = nextSegmentId++
        lastText = ""
        stream = activeRecognizer.createStream()
        Log.i(TAG, "online stream created; segment=$currentSegmentId")
    }

    fun accept(frame: AudioFrame): OnlineDecodeResult {
        check(!closed) { "Engine is already closed" }
        require(frame.sampleRate == SAMPLE_RATE) {
            "Expected $SAMPLE_RATE Hz, got ${frame.sampleRate} Hz"
        }
        val activeRecognizer = checkNotNull(recognizer) {
            "Recognizer is not initialized"
        }
        val activeStream = checkNotNull(stream) {
            "No active online stream"
        }

        activeStream.acceptWaveform(samples = frame.samples, sampleRate = frame.sampleRate)

        while (activeRecognizer.isReady(activeStream)) {
            activeRecognizer.decode(activeStream)
        }

        val text = activeRecognizer.getResult(activeStream).text.trim()
        val endpointDetected = activeRecognizer.isEndpoint(activeStream)
        val partial = if (text.isNotBlank() && text != lastText) {
            lastText = text
            TranscriptionEvent.Partial(segmentId = currentSegmentId, text = text)
        } else {
            null
        }

        return OnlineDecodeResult(
            segmentId = currentSegmentId,
            partial = partial,
            endpointDetected = endpointDetected,
        )
    }

    fun resetAfterBoundary(
        expectedSegmentId: Long,
        reason: String,
    ): Long {
        check(!closed) { "Engine is already closed" }
        check(expectedSegmentId == currentSegmentId) {
            "Segment mismatch: expected=$expectedSegmentId, current=$currentSegmentId"
        }

        val activeRecognizer = checkNotNull(recognizer) {
            "Recognizer is not initialized"
        }

        val activeStream = checkNotNull(stream) {
            "No active online stream"
        }

        activeRecognizer.reset(activeStream)
        lastText = ""
        currentSegmentId = nextSegmentId++

        Log.i(TAG, "online stream reset; reason = $reason; nextSegment=$currentSegmentId")
        return currentSegmentId
    }

    /**
     * Flushes the remaining online audio and releases only the current stream.
     * The returned event is still Partial; the caller runs the second pass.
     */
    fun stopSession(): TranscriptionEvent.Partial? {
        val activeStream = stream ?: return null
        val activeRecognizer = checkNotNull(recognizer) {
            "Recognizer is not initialized"
        }
        try {
            activeStream.inputFinished()
            while (activeRecognizer.isReady(activeStream)) {
                activeRecognizer.decode(activeStream)
            }

            val text = activeRecognizer.getResult(activeStream).text.trim()
            return if (text.isNotBlank() && text != lastText) {
                lastText = text
                TranscriptionEvent.Partial(
                    segmentId = currentSegmentId,
                    text = text
                )
            } else {
                null
            }
        } finally {
            activeStream.release()
            stream = null
            Log.i(TAG, "online stream released; segment = $currentSegmentId")
        }
    }

    override fun close() {
        if (closed) return
        closed = true

        stream?.release()
        stream = null

        recognizer?.release()
        recognizer = null

        Log.i(TAG, "online recognizer released once")
    }
}

