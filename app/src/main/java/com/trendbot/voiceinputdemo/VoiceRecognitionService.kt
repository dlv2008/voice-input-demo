package com.trendbot.voiceinputdemo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trendbot.voiceinputdemo.speech.AudioFrame
import com.trendbot.voiceinputdemo.speech.FinalSource
import com.trendbot.voiceinputdemo.speech.SegmentPcmBuffer
import com.trendbot.voiceinputdemo.speech.TranscriptDocument
import com.trendbot.voiceinputdemo.speech.TranscriptSanitizer
import com.trendbot.voiceinputdemo.speech.TranscriptSegment
import com.trendbot.voiceinputdemo.speech.TranscriptionEvent
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Owns the complete active voice session.
 *
 * This is both a started foreground service and a locally bound service:
 * - started + foreground: recording survives Activity onStop / screen off;
 * - bound: a visible Activity receives immutable snapshots and sends Stop;
 * - recognizer and recorder ownership never moves across components.
 */
class VoiceRecognitionService : Service() {
    companion object {
        private const val TAG = LogTags.SERVICE

        private const val ACTION_START =
            "com.trendbot.voiceinputdemo.action.START_RECOGNITION"
        private const val ACTION_STOP =
            "com.trendbot.voiceinputdemo.action.STOP_RECOGNITION"

        private const val NOTIFICATION_CHANNEL_ID = "voice_recognition"
        private const val NOTIFICATION_CHANNEL_NAME = "持续语音识别"
        private const val NOTIFICATION_ID = 1401
        private const val WAKE_LOCK_TAG = "VoiceInputDemo:Recording"

        private const val MIN_SECOND_PASS_SECONDS = 0.3
        private const val MAX_PENDING_FRAMES = 20
        private const val FRAME_WAIT_TIMEOUT_MS = 200L
        private const val MAX_SEGMENT_SECONDS = 20.0
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val NOTIFICATION_INTERVAL_NANOS = 5_000_000_000L

        fun startIntent(context: Context): Intent {
            return Intent(context, VoiceRecognitionService::class.java)
                .setAction(ACTION_START)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, VoiceRecognitionService::class.java)
                .setAction(ACTION_STOP)
        }
    }

    private enum class SegmentBoundaryReason {
        ENDPOINT,
        MAX_DURATION,
    }

    inner class LocalBinder : Binder() {
        fun registerListener(listener: VoiceRecognitionListener) {
            listenerRef.set(listener)
            scheduleSnapshotDispatch()
        }

        fun unregisterListener(listener: VoiceRecognitionListener) {
            listenerRef.compareAndSet(listener, null)
        }

        fun currentSnapshot(): VoiceSessionSnapshot = snapshotRef.get()

        fun requestStop() {
            requestStopInternal("activity")
        }

        fun requestClearTranscript() {
            requestClearTranscriptInternal()
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerRef =
        AtomicReference<VoiceRecognitionListener?>(null)
    private val dispatchPosted = AtomicBoolean(false)
    private val snapshotRef = AtomicReference(VoiceSessionSnapshot())

    private val onlineWorker = Executors.newSingleThreadExecutor()
    private val recorder = StreamPcmRecorder()
    private val frameSlots = Semaphore(MAX_PENDING_FRAMES, true)

    private val maxPendingFrames = AtomicLong(0L)
    private val backpressureTimeouts = AtomicLong(0L)
    private val endpointSegments = AtomicLong(0L)
    private val forcedSegments = AtomicLong(0L)
    private val lastProgressNanos = AtomicLong(0L)
    private val lastNotificationNanos = AtomicLong(0L)
    private val submittedFrames = AtomicLong(0L)
    private val processedFrames = AtomicLong(0L)
    private val sessionFailed = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    // These objects are confined to onlineWorker except for null/readiness checks.
    private val segmentBuffer = SegmentPcmBuffer()
    private val committedSegments = mutableListOf<TranscriptSegment>()
    private var latestPartialText = ""
    private var activeSegmentId = 0L

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var foregroundActive = false

    @Volatile
    private var onlineEngine: AndroidOnlineAsrEngine? = null

    @Volatile
    private var voiceCore: AndroidVoiceCore? = null

    // Accessed only on the main thread.
    private var pendingStartAfterModelLoad = false

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager =
            getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
        }

        updateSnapshot {
            it.copy(
                state = VoiceServiceState.MODEL_LOADING,
                statusText = "正在加载在线和离线模型……",
                progressText = "模型和解码都在服务工作线程",
                canStart = false,
            )
        }
        initializeModels()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Activity bound to voice service")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Activity unbound; recording=${recorder.isRecording}")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStopInternal("notification")
            ACTION_START -> handleStartCommand()
            else -> Log.w(TAG, "Ignoring unknown service action: ${intent?.action}")
        }

        // A microphone FGS must not be recreated silently without a visible,
        // user-initiated start and valid while-in-use RECORD_AUDIO permission.
        return START_NOT_STICKY
    }

    private fun handleStartCommand() {
        if (shuttingDown) return

        val state = snapshotRef.get().state
        if (
            state == VoiceServiceState.STARTING ||
            state == VoiceServiceState.STREAMING ||
            state == VoiceServiceState.STOPPING
        ) {
            updateForegroundNotification(notificationText())
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateSnapshot {
                it.copy(
                    state = VoiceServiceState.ERROR,
                    statusText = "没有麦克风权限，无法启动识别",
                    progressText = "请回到应用授予 RECORD_AUDIO 权限",
                    canStart = onlineEngine != null && voiceCore != null,
                    errorMessage = "RECORD_AUDIO denied",
                )
            }
            stopSelf()
            return
        }

        if (!promoteToForeground("正在准备语音识别……")) {
            return
        }

        val snapshot = snapshotRef.get()
        if (!snapshot.canStart) {
            if (snapshot.state == VoiceServiceState.MODEL_LOADING) {
                pendingStartAfterModelLoad = true
                updateForegroundNotification("正在等待模型加载……")
            } else {
                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.ERROR,
                        statusText = "模型不可用，无法开始录音",
                        progressText = it.errorMessage ?: "请检查模型文件和日志",
                    )
                }
                stopForegroundAndStartedState()
            }
            return
        }

        pendingStartAfterModelLoad = false
        beginOnlineSession()
    }

    private fun initializeModels() {
        submitOnline {
            var createdOnlineEngine: AndroidOnlineAsrEngine? = null
            var createdVoiceCore: AndroidVoiceCore? = null

            try {
                val startedAt = System.nanoTime()
                createdOnlineEngine = AndroidOnlineAsrEngine(assets).also {
                    it.initialize()
                }
                createdVoiceCore = AndroidVoiceCore(assets).also {
                    it.initialize()
                }
                val elapsedSeconds =
                    (System.nanoTime() - startedAt) / 1_000_000_000.0

                if (shuttingDown) {
                    createdOnlineEngine.close()
                    createdVoiceCore.close()
                    return@submitOnline
                }

                onlineEngine = createdOnlineEngine
                voiceCore = createdVoiceCore
                Log.i(TAG, "both models ready; elapsed=$elapsedSeconds")

                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.READY,
                        statusText = String.format(
                            Locale.US,
                            "在线和离线模型已就绪，加载耗时 %.2f 秒",
                            elapsedSeconds,
                        ),
                        progressText = "点击开始；离开界面或熄屏后仍继续识别",
                        canStart = true,
                        errorMessage = null,
                    )
                }

                mainHandler.post {
                    if (
                        pendingStartAfterModelLoad &&
                        !stopRequested.get() &&
                        !shuttingDown
                    ) {
                        pendingStartAfterModelLoad = false
                        beginOnlineSession()
                    }
                }
            } catch (error: Throwable) {
                createdOnlineEngine?.close()
                createdVoiceCore?.close()
                Log.e(TAG, "model initialization failed", error)
                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.ERROR,
                        statusText = "模型加载失败：${error.message ?: error.javaClass.simpleName}",
                        progressText = "检查 assets 模型目录和 Logcat",
                        canStart = false,
                        errorMessage = error.stackTraceToString(),
                    )
                }
                mainHandler.post {
                    pendingStartAfterModelLoad = false
                    if (foregroundActive) {
                        stopForegroundAndStartedState()
                    }
                }
            }
        }
    }

    private fun beginOnlineSession() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "beginOnlineSession must run on main thread"
        }
        if (shuttingDown || onlineEngine == null || voiceCore == null) return

        if (frameSlots.availablePermits() != MAX_PENDING_FRAMES) {
            val error = IllegalStateException(
                "Frame permits leaked: available=${frameSlots.availablePermits()}",
            )
            Log.e(TAG, "cannot start a new session", error)
            updateSnapshot {
                it.copy(
                    state = VoiceServiceState.ERROR,
                    statusText = "上一会话的音频槽位没有完整释放",
                    progressText = "重启应用并检查 Semaphore 日志",
                    canStart = false,
                    errorMessage = error.stackTraceToString(),
                )
            }
            stopForegroundAndStartedState()
            return
        }

        maxPendingFrames.set(0L)
        backpressureTimeouts.set(0L)
        endpointSegments.set(0L)
        forcedSegments.set(0L)
        lastProgressNanos.set(0L)
        lastNotificationNanos.set(0L)
        submittedFrames.set(0L)
        processedFrames.set(0L)
        sessionFailed.set(false)
        finishRequested.set(false)
        stopRequested.set(false)

        acquireRecordingWakeLock()
        updateSnapshot {
            it.copy(
                state = VoiceServiceState.STARTING,
                statusText = "正在创建在线 Stream……",
                progressText = "0.0 秒",
                transcript = TranscriptDocument(),
                isRecording = false,
                audioSeconds = 0.0,
                peak = 0.0f,
                rms = 0.0f,
                pendingFrames = 0L,
                maxPendingFrames = 0L,
                backpressureTimeouts = 0L,
                endpointSegments = 0L,
                forcedSegments = 0L,
                availablePermits = MAX_PENDING_FRAMES,
                errorMessage = null,
            )
        }
        updateForegroundNotification("正在创建在线 Stream……")

        submitOnline {
            try {
                segmentBuffer.clear()
                committedSegments.clear()
                latestPartialText = ""
                activeSegmentId = 0L

                checkNotNull(onlineEngine) {
                    "Online engine is not initialized"
                }.startSession()

                if (shuttingDown || stopRequested.get()) {
                    onlineEngine?.stopSession()
                    mainHandler.post { completeCancelledStart() }
                    return@submitOnline
                }

                mainHandler.post { startRecorderAfterStreamReady() }
            } catch (error: Throwable) {
                handleSessionFailure("无法创建在线 Stream", error)
            }
        }
    }

    private fun startRecorderAfterStreamReady() {
        if (shuttingDown) return
        if (stopRequested.get()) {
            finishOnlineSession(0.0)
            return
        }
        if (snapshotRef.get().state != VoiceServiceState.STARTING) return

        updateSnapshot {
            it.copy(
                state = VoiceServiceState.STREAMING,
                statusText = "正在聆听并流式识别……",
                progressText = "录音 0.0 秒 · 峰值 0.00 · pending 0",
                isRecording = true,
            )
        }
        updateForegroundNotification("正在录音和识别；点“停止”结束")

        try {
            recorder.start(
                onFrame = { frame, seconds, peak ->
                    val rms = calculateRms(frame.samples)
                    handleAudioFrame(frame, seconds, peak, rms)
                },
                onStopped = { audioSeconds ->
                    if (!shuttingDown) {
                        finishOnlineSession(audioSeconds)
                    }
                },
                onError = { error ->
                    if (!shuttingDown) {
                        handleSessionFailure("录音失败", error)
                    }
                },
            )
        } catch (error: Throwable) {
            handleSessionFailure("无法启动录音", error)
        }
    }

    private fun handleAudioFrame(
        frame: AudioFrame,
        seconds: Double,
        peak: Float,
        rms: Float,
    ) {
        if (
            shuttingDown ||
            sessionFailed.get() ||
            stopRequested.get()
        ) {
            return
        }

        val slotAcquired = try {
            frameSlots.tryAcquire(
                FRAME_WAIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            handleSessionFailure(
                "录音线程被中断",
                IllegalStateException(
                    "Audio frame submission thread was interrupted",
                    error,
                ),
            )
            return
        }

        if (!slotAcquired) {
            backpressureTimeouts.incrementAndGet()
            handleSessionFailure(
                "音频处理速度不足",
                IllegalStateException(
                    "Audio backlog exceeded $MAX_PENDING_FRAMES frames",
                ),
            )
            return
        }

        submittedFrames.incrementAndGet()
        val submitted = submitOnline {
            try {
                if (shuttingDown || sessionFailed.get()) return@submitOnline

                segmentBuffer.append(frame)
                val decodeResult = checkNotNull(onlineEngine) {
                    "Online engine is not initialized"
                }.accept(frame)

                activeSegmentId = decodeResult.segmentId
                decodeResult.partial?.let { event ->
                    latestPartialText = event.text
                    publishTranscript()
                }

                val boundaryReason = when {
                    segmentBuffer.durationSeconds >= MAX_SEGMENT_SECONDS ->
                        SegmentBoundaryReason.MAX_DURATION
                    decodeResult.endpointDetected ->
                        SegmentBoundaryReason.ENDPOINT
                    else -> null
                }

                if (boundaryReason != null) {
                    finalizeCurrentSegment(
                        segmentId = decodeResult.segmentId,
                        boundaryReason = boundaryReason,
                    )
                }
            } catch (error: Throwable) {
                handleSessionFailure("在线解码失败", error)
            } finally {
                processedFrames.incrementAndGet()
                frameSlots.release()
            }
        }

        if (!submitted) {
            submittedFrames.decrementAndGet()
            frameSlots.release()
            if (!shuttingDown) recorder.stop()
            return
        }

        val pending = submittedFrames.get() - processedFrames.get()
        maxPendingFrames.updateAndGet { current ->
            maxOf(current, pending)
        }
        publishLevelAndProgress(
            seconds = seconds,
            peak = peak,
            rms = rms,
            pending = pending,
        )
    }

    private fun publishLevelAndProgress(
        seconds: Double,
        peak: Float,
        rms: Float,
        pending: Long,
    ) {
        val now = System.nanoTime()
        val previous = lastProgressNanos.get()
        val refreshText =
            now - previous >= PROGRESS_INTERVAL_NANOS &&
                lastProgressNanos.compareAndSet(previous, now)

        val maxPending = maxPendingFrames.get()
        updateSnapshot {
            it.copy(
                audioSeconds = seconds,
                peak = peak,
                rms = rms,
                pendingFrames = pending,
                maxPendingFrames = maxPending,
                backpressureTimeouts = backpressureTimeouts.get(),
                endpointSegments = endpointSegments.get(),
                forcedSegments = forcedSegments.get(),
                availablePermits = frameSlots.availablePermits(),
                progressText = if (refreshText) {
                    String.format(
                        Locale.US,
                        "录音 %.1f秒 · 峰值 %.2f · pending %d/%d",
                        seconds,
                        peak,
                        pending,
                        MAX_PENDING_FRAMES,
                    )
                } else {
                    it.progressText
                },
            )
        }
        maybeUpdateForegroundNotification(seconds)
    }

    private fun finalizeCurrentSegment(
        segmentId: Long,
        boundaryReason: SegmentBoundaryReason,
    ) {
        val segmentSeconds = segmentBuffer.durationSeconds
        val pcm = segmentBuffer.takeAndClear()
        val onlineFallback = latestPartialText

        when (boundaryReason) {
            SegmentBoundaryReason.ENDPOINT ->
                endpointSegments.incrementAndGet()
            SegmentBoundaryReason.MAX_DURATION ->
                forcedSegments.incrementAndGet()
        }

        activeSegmentId = checkNotNull(onlineEngine) {
            "Online engine is not initialized"
        }.resetAfterBoundary(
            expectedSegmentId = segmentId,
            reason = boundaryReason.name,
        )

        Log.i(
            TAG,
            "segment boundary; segment=$segmentId; " +
                "reason=$boundaryReason; audio=$segmentSeconds",
        )

        if (pcm.isEmpty()) {
            latestPartialText = ""
            publishTranscript()
            return
        }

        val finalEvent = runSenseVoiceSecondPass(
            segmentId = segmentId,
            samples = pcm,
            onlineFallback = onlineFallback,
        )
        commitFinal(
            event = finalEvent,
            durationSeconds = segmentSeconds,
        )
    }

    private fun runSenseVoiceSecondPass(
        segmentId: Long,
        samples: FloatArray,
        onlineFallback: String,
    ): TranscriptionEvent.Final {
        return try {
            val output = checkNotNull(voiceCore)
                .decodePcm(samples, StreamPcmRecorder.SAMPLE_RATE)
            val finalText = output.text.trim()

            Log.i(
                TAG,
                "second pass completed; segment=$segmentId; " +
                    "audio=${output.audioSeconds}; elapsed=${output.elapsedSeconds}; " +
                    "rtf=${output.rtf}; lang=${output.language}",
            )

            TranscriptionEvent.Final(
                segmentId = segmentId,
                text = finalText.ifBlank { onlineFallback },
                source = if (finalText.isNotBlank()) {
                    FinalSource.OFFLINE_SECOND_PASS
                } else {
                    FinalSource.ONLINE_FALLBACK
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "second pass failed; segment=$segmentId", error)
            TranscriptionEvent.Final(
                segmentId = segmentId,
                text = onlineFallback,
                source = FinalSource.ONLINE_FALLBACK,
            )
        }
    }

    private fun commitFinal(
        event: TranscriptionEvent.Final,
        durationSeconds: Double,
    ) {
        val cleanedText = TranscriptSanitizer.sanitize(
            rawText = event.text,
            durationSeconds = durationSeconds,
        )
        if (cleanedText != null) {
            committedSegments.add(
                TranscriptSegment(
                    segmentId = event.segmentId,
                    text = cleanedText,
                    source = event.source,
                    durationSeconds = durationSeconds,
                ),
            )
        } else {
            Log.i(
                TAG,
                "discarded empty or low-value final; " +
                    "segment=${event.segmentId}; duration=$durationSeconds",
            )
        }
        latestPartialText = ""

        Log.i(
            TAG,
            "final processed; segment=${event.segmentId}; " +
                "source=${event.source}; accepted=${cleanedText != null}; " +
                "text=${event.text}",
        )
        publishTranscript()
        updateForegroundNotification(
            "正在录音；已确认 ${committedSegments.size} 段",
        )
    }

    private fun publishTranscript() {
        val committedCopy = committedSegments.toList()
        val partialCopy = latestPartialText
        updateSnapshot {
            it.copy(
                transcript = TranscriptDocument(
                    committedSegments = committedCopy,
                    partialSegmentId = activeSegmentId.takeIf {
                        partialCopy.isNotBlank()
                    },
                    partialText = partialCopy,
                    revision = it.transcript.revision + 1L,
                ),
                endpointSegments = endpointSegments.get(),
                forcedSegments = forcedSegments.get(),
            )
        }
    }

    private fun requestStopInternal(source: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestStopInternal(source) }
            return
        }

        Log.i(TAG, "stop requested; source=$source")
        stopRequested.set(true)
        pendingStartAfterModelLoad = false

        when (snapshotRef.get().state) {
            VoiceServiceState.STARTING -> {
                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.STOPPING,
                        statusText = "正在取消启动并释放 Stream……",
                        progressText = "请稍候",
                        isRecording = false,
                    )
                }
                // The start task observes stopRequested and performs the flush.
            }

            VoiceServiceState.STREAMING -> {
                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.STOPPING,
                        statusText = "正在停止麦克风并刷新剩余结果……",
                        progressText = "等待已提交音频处理完成",
                        isRecording = false,
                    )
                }
                updateForegroundNotification("正在停止并刷新最终结果……")
                recorder.stop()
            }

            VoiceServiceState.MODEL_LOADING,
            VoiceServiceState.READY,
            VoiceServiceState.ERROR -> {
                updateSnapshot {
                    it.copy(
                        state = if (onlineEngine != null && voiceCore != null) {
                            VoiceServiceState.READY
                        } else {
                            it.state
                        },
                        statusText = if (onlineEngine != null && voiceCore != null) {
                            "已取消；模型仍可使用"
                        } else {
                            it.statusText
                        },
                    )
                }
                stopForegroundAndStartedState()
            }

            VoiceServiceState.STOPPING,
            VoiceServiceState.CLOSED -> Unit
        }
    }

    private fun requestClearTranscriptInternal() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestClearTranscriptInternal() }
            return
        }

        when (snapshotRef.get().state) {
            VoiceServiceState.READY,
            VoiceServiceState.ERROR -> {
                submitOnline {
                    committedSegments.clear()
                    latestPartialText = ""
                    activeSegmentId = 0L
                    publishTranscript()
                    updateSnapshot {
                        it.copy(
                            statusText = "已新建空白转录",
                            progressText = "点击底部按钮开始录音",
                        )
                    }
                    Log.i(TAG, "transcript cleared by user")
                }
            }

            VoiceServiceState.MODEL_LOADING,
            VoiceServiceState.STARTING,
            VoiceServiceState.STREAMING,
            VoiceServiceState.STOPPING,
            VoiceServiceState.CLOSED -> Unit
        }
    }

    private fun finishOnlineSession(audioSeconds: Double) {
        if (!finishRequested.compareAndSet(false, true)) return

        updateSnapshot {
            it.copy(
                state = VoiceServiceState.STOPPING,
                statusText = if (sessionFailed.get()) {
                    it.statusText
                } else {
                    "正在刷新剩余结果……"
                },
                isRecording = false,
                audioSeconds = maxOf(audioSeconds, it.audioSeconds),
            )
        }

        submitOnline {
            var flushedEvent: TranscriptionEvent.Partial? = null
            try {
                flushedEvent = onlineEngine?.stopSession()
            } catch (error: Throwable) {
                sessionFailed.set(true)
                Log.e(TAG, "unable to finish online session", error)
            }

            flushedEvent?.let { event ->
                activeSegmentId = event.segmentId
                latestPartialText = event.text
            }

            val residualSeconds = segmentBuffer.durationSeconds
            val residualPcm = segmentBuffer.takeAndClear()
            if (
                residualPcm.isNotEmpty() &&
                residualSeconds >= MIN_SECOND_PASS_SECONDS
            ) {
                commitFinal(
                    event = runSenseVoiceSecondPass(
                        segmentId = activeSegmentId,
                        samples = residualPcm,
                        onlineFallback = latestPartialText,
                    ),
                    durationSeconds = residualSeconds,
                )
            } else {
                Log.i(
                    TAG,
                    "residual segment skipped; segment=$activeSegmentId; " +
                        "duration=$residualSeconds",
                )
                latestPartialText = ""
                publishTranscript()
            }

            val submitted = submittedFrames.get()
            val processed = processedFrames.get()
            val pending = submitted - processed
            val maxPending = maxPendingFrames.get()
            val timeoutCount = backpressureTimeouts.get()
            val endpointCount = endpointSegments.get()
            val forcedCount = forcedSegments.get()
            val availablePermits = frameSlots.availablePermits()

            Log.i(
                TAG,
                "Session stopped; audio=$audioSeconds; submitted=$submitted; " +
                    "processed=$processed; pending=$pending; " +
                    "failed=${sessionFailed.get()}",
            )
            Log.i(
                TAG,
                "session metrics; maxPending=$maxPending; " +
                    "backpressureTimeouts=$timeoutCount; " +
                    "endpointSegments=$endpointCount; forcedSegments=$forcedCount; " +
                    "availablePermits=$availablePermits/$MAX_PENDING_FRAMES",
            )

            val failed = sessionFailed.get()
            mainHandler.post {
                releaseRecordingWakeLock()
                updateSnapshot {
                    it.copy(
                        state = if (failed) {
                            VoiceServiceState.ERROR
                        } else {
                            VoiceServiceState.READY
                        },
                        statusText = if (failed) {
                            it.statusText
                        } else {
                            "已停止，共确认 ${committedSegments.size} 段最终结果"
                        },
                        progressText = String.format(
                            Locale.US,
                            "音频 %.1f秒 · 帧 %d · 最大pending %d/%d",
                            maxOf(audioSeconds, it.audioSeconds),
                            submitted,
                            maxPending,
                            MAX_PENDING_FRAMES,
                        ),
                        canStart = onlineEngine != null && voiceCore != null,
                        isRecording = false,
                        peak = 0.0f,
                        rms = 0.0f,
                        pendingFrames = pending,
                        maxPendingFrames = maxPending,
                        backpressureTimeouts = timeoutCount,
                        endpointSegments = endpointCount,
                        forcedSegments = forcedCount,
                        availablePermits = availablePermits,
                    )
                }
                stopRequested.set(false)
                stopForegroundAndStartedState()
            }
        }
    }

    private fun completeCancelledStart() {
        releaseRecordingWakeLock()
        updateSnapshot {
            it.copy(
                state = VoiceServiceState.READY,
                statusText = "启动已取消",
                progressText = "模型保持就绪",
                canStart = onlineEngine != null && voiceCore != null,
                isRecording = false,
            )
        }
        stopRequested.set(false)
        stopForegroundAndStartedState()
    }

    private fun handleSessionFailure(prefix: String, error: Throwable) {
        if (!sessionFailed.compareAndSet(false, true)) return

        Log.e(TAG, prefix, error)
        updateSnapshot {
            it.copy(
                state = VoiceServiceState.STOPPING,
                statusText = "$prefix：${error.message ?: error.javaClass.simpleName}",
                progressText = "正在停止并释放本次会话",
                isRecording = false,
                errorMessage = error.stackTraceToString(),
            )
        }
        updateForegroundNotification("识别发生错误，正在停止……")
        recorder.stop()
        finishOnlineSession(snapshotRef.get().audioSeconds)
    }

    private fun submitOnline(task: () -> Unit): Boolean {
        return try {
            onlineWorker.execute(task)
            true
        } catch (error: RejectedExecutionException) {
            if (!shuttingDown) {
                Log.e(TAG, "online worker rejected task", error)
                updateSnapshot {
                    it.copy(
                        state = VoiceServiceState.ERROR,
                        statusText = "在线工作线程不可用",
                        progressText = "需要重新启动应用",
                        canStart = false,
                        isRecording = false,
                        errorMessage = error.stackTraceToString(),
                    )
                }
                mainHandler.post {
                    releaseRecordingWakeLock()
                    stopForegroundAndStartedState()
                }
            }
            false
        }
    }

    private fun updateSnapshot(
        transform: (VoiceSessionSnapshot) -> VoiceSessionSnapshot,
    ) {
        while (true) {
            val old = snapshotRef.get()
            val updated = transform(old).copy(version = old.version + 1L)
            if (snapshotRef.compareAndSet(old, updated)) break
        }
        scheduleSnapshotDispatch()
    }

    private fun scheduleSnapshotDispatch() {
        if (listenerRef.get() == null) return
        if (!dispatchPosted.compareAndSet(false, true)) return

        mainHandler.post {
            val delivered = snapshotRef.get()
            listenerRef.get()?.onSnapshot(delivered)
            dispatchPosted.set(false)

            if (
                listenerRef.get() != null &&
                snapshotRef.get().version != delivered.version
            ) {
                scheduleSnapshotDispatch()
            }
        }
    }

    private fun promoteToForeground(contentText: String): Boolean {
        return try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(contentText),
                serviceType,
            )
            foregroundActive = true
            Log.i(TAG, "microphone foreground service promoted")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "foreground promotion rejected", error)
            updateSnapshot {
                it.copy(
                    state = VoiceServiceState.ERROR,
                    statusText = "系统拒绝麦克风前台服务",
                    progressText = error.message ?: "检查权限和启动时机",
                    canStart = onlineEngine != null && voiceCore != null,
                    errorMessage = error.stackTraceToString(),
                )
            }
            stopSelf()
            false
        } catch (error: IllegalStateException) {
            Log.e(TAG, "foreground promotion not allowed", error)
            updateSnapshot {
                it.copy(
                    state = VoiceServiceState.ERROR,
                    statusText = "当前状态不允许启动前台服务",
                    progressText = "请回到可见界面后再点击开始",
                    canStart = onlineEngine != null && voiceCore != null,
                    errorMessage = error.stackTraceToString(),
                )
            }
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示持续录音与端侧语音识别状态"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openActivityPendingIntent = PendingIntent.getActivity(
            this,
            1401,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1402,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("端侧转录助手正在运行")
            .setContentText(contentText)
            .setContentIntent(openActivityPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(
                R.drawable.ic_mic_notification,
                "停止",
                stopPendingIntent,
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateForegroundNotification(contentText: String) {
        if (!foregroundActive) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                updateForegroundNotification(contentText)
            }
            return
        }

        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(contentText),
            )
        } catch (error: SecurityException) {
            // Android 13+ may hide drawer notifications when permission is denied.
            Log.w(TAG, "notification update not visible", error)
        }
    }

    private fun maybeUpdateForegroundNotification(seconds: Double) {
        val now = System.nanoTime()
        val previous = lastNotificationNanos.get()
        if (now - previous < NOTIFICATION_INTERVAL_NANOS) return
        if (!lastNotificationNanos.compareAndSet(previous, now)) return

        updateForegroundNotification(
            String.format(
                Locale.US,
                "已录音 %.0f 秒 · 已确认 %d 段",
                seconds,
                snapshotRef.get().transcript.committedSegments.size,
            ),
        )
    }

    private fun notificationText(): String {
        val snapshot = snapshotRef.get()
        return if (snapshot.isRecording) {
            String.format(
                Locale.US,
                "已录音 %.0f 秒 · 已确认 %d 段",
                snapshot.audioSeconds,
                snapshot.transcript.committedSegments.size,
            )
        } else {
            snapshot.statusText
        }
    }

    /**
     * The lock is intentionally session-scoped rather than timeout-scoped:
     * the user requested a potentially long recording. Every normal/error
     * completion path releases it, and force-stop lets the OS release it.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireRecordingWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            lock.acquire()
            Log.i(TAG, "partial wake lock acquired")
        }
    }

    private fun releaseRecordingWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            Log.i(TAG, "partial wake lock released")
        }
    }

    private fun stopForegroundAndStartedState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopForegroundAndStartedState() }
            return
        }

        releaseRecordingWakeLock()
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
            Log.i(TAG, "foreground notification removed")
        }
        stopSelf()
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f
        var squareSum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            squareSum += value * value
        }
        return sqrt(squareSum / samples.size.toDouble()).toFloat()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; foreground session remains active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shuttingDown = true
        pendingStartAfterModelLoad = false
        listenerRef.set(null)
        dispatchPosted.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        recorder.close()
        releaseRecordingWakeLock()

        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }

        updateSnapshot {
            it.copy(
                state = VoiceServiceState.CLOSED,
                statusText = "识别服务已关闭",
                canStart = false,
                isRecording = false,
            )
        }

        submitOnline {
            try {
                onlineEngine?.close()
                onlineEngine = null
            } finally {
                voiceCore?.close()
                voiceCore = null
            }
            Log.i(TAG, "online and offline engines released once")
        }
        onlineWorker.shutdown()
        super.onDestroy()
    }
}
