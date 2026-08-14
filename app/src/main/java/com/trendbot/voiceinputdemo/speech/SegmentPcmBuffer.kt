package com.trendbot.voiceinputdemo.speech

class SegmentPcmBuffer (
    private val sampleRate: Int = 16_000,
    private val maxDurationSeconds: Int  =30,
) {
    private val chunks = ArrayList<FloatArray>()
    private var sampleCount = 0

    val isEmpty: Boolean
        get() = sampleCount == 0

    val durationSeconds: Double
        get() = sampleCount.toDouble() / sampleRate.toDouble()

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(maxDurationSeconds > 0) {
            "maxDurationSeconds must be positive"
        }
    }

    fun append(frame: AudioFrame) {
        require(frame.sampleRate == sampleRate) {
            "Expected $sampleRate Hz, got ${frame.sampleRate} Hz"
        }

        val nextCount = sampleCount + frame.samples.size
        val maximum = sampleRate * maxDurationSeconds
        check(nextCount <= maximum) {
            "Segment PCM exceeded $maxDurationSeconds seconds"
        }

        chunks.add(frame.samples)
        sampleCount = nextCount
    }

    fun takeAndClear(): FloatArray {
        val output = FloatArray(sampleCount)
        var offset = 0

        for (chunk in chunks) {
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }

        clear()
        return output
    }

    fun clear() {
        chunks.clear()
        sampleCount = 0
    }
}