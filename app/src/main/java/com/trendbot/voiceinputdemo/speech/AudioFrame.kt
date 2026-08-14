package com.trendbot.voiceinputdemo.speech

/**
 *  A normalized mono PCM frame
 *
 *  samples use the range [-1.0, 1.0].
 *  timestampNanos is monotonic session time, not wall-clock time.
 */

class AudioFrame(
    val samples: FloatArray,
    val sampleRate: Int,
    val timestampNanos: Long,
) {
    init {
        require(samples.isNotEmpty()) {"AudioFrame samples must not be empty"}
        require(sampleRate > 0) {"AudioFrame sampleRate must be positive"}
        require(timestampNanos >= 0L) {"AudioFrame timestamp must not negative"}
    }

    val durationSeconds: Double
        get() = samples.size.toDouble() / sampleRate.toDouble()
}

