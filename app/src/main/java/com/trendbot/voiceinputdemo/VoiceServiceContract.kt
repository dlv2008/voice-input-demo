package com.trendbot.voiceinputdemo

import com.trendbot.voiceinputdemo.speech.TranscriptDocument

/** The lifecycle visible to the Activity; recognizer internals stay in Service. */
enum class VoiceServiceState {
    MODEL_LOADING,
    READY,
    STARTING,
    STREAMING,
    STOPPING,
    ERROR,
    CLOSED,
}

/**
 * An immutable, same-process UI snapshot.
 *
 * The Service may update it from recorder/recognizer threads. The Activity can
 * therefore render it without reading any Service-owned mutable collection.
 */
data class VoiceSessionSnapshot(
    val version: Long = 0L,
    val state: VoiceServiceState = VoiceServiceState.MODEL_LOADING,
    val statusText: String = "正在创建识别服务……",
    val progressText: String = "",
    val transcript: TranscriptDocument = TranscriptDocument(),
    val canStart: Boolean = false,
    val isRecording: Boolean = false,
    val audioSeconds: Double = 0.0,
    val peak: Float = 0.0f,
    val rms: Float = 0.0f,
    val pendingFrames: Long = 0L,
    val maxPendingFrames: Long = 0L,
    val backpressureTimeouts: Long = 0L,
    val endpointSegments: Long = 0L,
    val forcedSegments: Long = 0L,
    val availablePermits: Int = 20,
    val errorMessage: String? = null,
)

fun interface VoiceRecognitionListener {
    fun onSnapshot(snapshot: VoiceSessionSnapshot)
}
