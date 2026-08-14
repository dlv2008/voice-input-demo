package com.trendbot.voiceinputdemo.speech

enum class FinalSource {
    OFFLINE_SECOND_PASS,
    ONLINE_FALLBACK,
}

sealed interface TranscriptionEvent {
    data class Partial(
        val segmentId: Long,
        val text: String,
    ) : TranscriptionEvent

    data class Final (
        val segmentId: Long,
        val text: String,
        val source: FinalSource,
    ) : TranscriptionEvent

    data class Error (
        val message: String,
        val cause: Throwable? = null,
    ) : TranscriptionEvent
}

