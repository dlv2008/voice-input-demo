package com.trendbot.voiceinputdemo.speech

/**
 * start        开始一次持续的语音会话                 不会释放长期资源如Recognizer
 * accept       接受一帧PCM                         不会释放长期资源如Recognizer
 * stop         结束本次会话并提交剩余段               不会释放长期资源如Recognizer
 * close        应用不在使用core, 释放Recognizer      会释放长期资源
 */
interface SpeechSession : AutoCloseable {
    val state: SessionState

    fun start(onEvent: (TranscriptionEvent) -> Unit)

    fun accept(frame: AudioFrame)

    fun stop()

    override fun close()
}