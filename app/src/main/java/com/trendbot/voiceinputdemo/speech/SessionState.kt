package com.trendbot.voiceinputdemo.speech

/**
 *  READY           模型已经准备, 可以开始
 *  LISTENING       正在接受PCM并且输出Partial
 *  FINALIZING      当前的段, 正在进行第二遍识别, 但Session可继续管理新输入
 *  STOPPING        用户要求停止, 正在收尾
 *  ERROR           当前会话发生不可继续的错误
 *  CLOSED          Recognizer等长期资源已经释放, 不可再次使用
 */

enum class SessionState {
    READY,
    LISTENING,
    FINALIZING,
    STOPPING,
    ERROR,
    CLOSED,
}

