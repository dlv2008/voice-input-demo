package com.trendbot.voiceinputdemo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.KeyListener
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.trendbot.voiceinputdemo.speech.FinalSource
import com.trendbot.voiceinputdemo.speech.TranscriptDocument
import com.trendbot.voiceinputdemo.speech.TranscriptTextFormatter
import java.util.Locale

/** Thin UI client. The active voice session remains owned by the Service. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var stateText: TextView
    private lateinit var editHintText: TextView
    private lateinit var resultText: EditText
    private lateinit var transcriptScroll: ScrollView
    private lateinit var recordButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var newButton: MaterialButton
    private lateinit var autoScrollSwitch: SwitchMaterial
    private lateinit var waveformView: WaveformView

    private var serviceBinder: VoiceRecognitionService.LocalBinder? = null
    private var serviceBound = false
    private var latestSnapshot = VoiceSessionSnapshot()
    private var lastRenderedVersion = -1L
    private var lastTranscriptRevision = -1L
    private var waveformRecording = false

    private var applyingServiceText = false
    private var transcriptEditable = true
    private var originalKeyListener: KeyListener? = null
    private var localEditedText: String? = null

    private val serviceListener = VoiceRecognitionListener { snapshot ->
        if (!isDestroyed) render(snapshot)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? VoiceRecognitionService.LocalBinder
                ?: error("Unexpected binder from $name")
            serviceBinder = binder
            serviceBound = true
            lastRenderedVersion = -1L
            binder.registerListener(serviceListener)
            Log.i(LogTags.UI, "bound to recognition service")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            serviceBinder = null
            waveformView.setRecording(false)
            waveformRecording = false
            statusText.text = "识别服务连接已断开"
            stateText.text = "连接断开"
            recordButton.isEnabled = false
            Log.w(LogTags.UI, "recognition service disconnected: $name")
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }
    }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ensureNotificationPermissionThenStart()
            } else {
                showLocalError("没有麦克风权限，无法录音。")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "通知权限未授予；系统可能不会在通知抽屉显示录音状态。",
                    Toast.LENGTH_LONG,
                ).show()
            }
            startForegroundRecognition()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        statusText = findViewById(R.id.statusText)
        progressText = findViewById(R.id.progressText)
        stateText = findViewById(R.id.stateText)
        editHintText = findViewById(R.id.editHintText)
        resultText = findViewById(R.id.resultText)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        recordButton = findViewById(R.id.recordButton)
        copyButton = findViewById(R.id.copyButton)
        newButton = findViewById(R.id.newButton)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        waveformView = findViewById(R.id.waveformView)

        originalKeyListener = resultText.keyListener
        setTranscriptEditable(false)
        recordButton.isEnabled = false
        autoScrollSwitch.isChecked = true

        recordButton.setOnClickListener { onRecordButtonClicked() }
        copyButton.setOnClickListener { copyTranscript() }
        newButton.setOnClickListener { confirmAndClearTranscript() }
        resultText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                if (applyingServiceText || !transcriptEditable || text == null) return

                if (localEditedText == null) {
                    text.getSpans(
                        0,
                        text.length,
                        ForegroundColorSpan::class.java,
                    ).forEach { span -> text.removeSpan(span) }
                    text.getSpans(
                        0,
                        text.length,
                        StyleSpan::class.java,
                    ).forEach { span -> text.removeSpan(span) }
                }
                localEditedText = text.toString()
                editHintText.text = "已人工编辑 · 原始识别文本仍保留在本次会话中"
                updateActionAvailability()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val bound = bindService(
            Intent(this, VoiceRecognitionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) showLocalError("无法绑定识别服务")
    }

    override fun onStop() {
        serviceBinder?.unregisterListener(serviceListener)
        serviceBinder = null

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        waveformView.setRecording(false)
        waveformRecording = false
        super.onStop()
    }

    private fun onRecordButtonClicked() {
        when (latestSnapshot.state) {
            VoiceServiceState.READY,
            VoiceServiceState.ERROR -> {
                localEditedText = null
                ensureMicrophonePermissionAndStart()
            }

            VoiceServiceState.STARTING,
            VoiceServiceState.STREAMING -> serviceBinder?.requestStop()
                ?: showLocalError("服务尚未连接，请稍后重试")

            VoiceServiceState.MODEL_LOADING,
            VoiceServiceState.STOPPING,
            VoiceServiceState.CLOSED -> Unit
        }
    }

    private fun ensureMicrophonePermissionAndStart() {
        if (!latestSnapshot.canStart) {
            showLocalError("在线或离线模型尚未就绪")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            ensureNotificationPermissionThenStart()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startForegroundRecognition()
        }
    }

    private fun startForegroundRecognition() {
        try {
            ContextCompat.startForegroundService(
                this,
                VoiceRecognitionService.startIntent(this),
            )
            Log.i(LogTags.UI, "user requested foreground recognition")
        } catch (error: SecurityException) {
            showLocalError("系统拒绝启动麦克风前台服务：${error.message}")
        } catch (error: IllegalStateException) {
            showLocalError("当前后台状态不允许启动前台服务：${error.message}")
        }
    }

    private fun render(snapshot: VoiceSessionSnapshot) {
        if (snapshot.version < lastRenderedVersion) return
        lastRenderedVersion = snapshot.version
        latestSnapshot = snapshot

        statusText.text = snapshot.statusText
        progressText.text = snapshot.progressText
        stateText.text = stateLabel(snapshot.state)

        val transcriptChanged =
            snapshot.transcript.revision != lastTranscriptRevision
        if (transcriptChanged) {
            lastTranscriptRevision = snapshot.transcript.revision
            renderTranscript(snapshot.transcript)
        }

        if (snapshot.isRecording != waveformRecording) {
            waveformRecording = snapshot.isRecording
            if (waveformRecording) waveformView.reset()
            waveformView.setRecording(waveformRecording)
        }
        if (waveformRecording) {
            waveformView.submitLevel(snapshot.peak, snapshot.rms)
        }

        val hasStableText = snapshot.transcript.committedSegments.isNotEmpty()
        val allowEditing = hasStableText &&
            !snapshot.isRecording &&
            (snapshot.state == VoiceServiceState.READY ||
                snapshot.state == VoiceServiceState.ERROR)
        setTranscriptEditable(allowEditing)

        editHintText.text = when {
            localEditedText != null ->
                "已人工编辑 · 本页内容尚未保存"
            snapshot.state == VoiceServiceState.STREAMING ->
                "灰色为在线结果，黑色为二遍确认结果"
            snapshot.state == VoiceServiceState.STOPPING ->
                "正在整理最后一段，请稍候再编辑"
            allowEditing ->
                "录音已结束，可直接点击正文编辑"
            else ->
                "内容仅保存在当前会话，目标2再加入笔记存储"
        }

        updateRecordButton(snapshot)
        updateActionAvailability()
    }

    private fun renderTranscript(document: TranscriptDocument) {
        if (localEditedText != null && transcriptEditable) return

        val styledText = buildStyledTranscript(document)
        applyingServiceText = true
        try {
            resultText.setText(styledText)
        } finally {
            applyingServiceText = false
        }

        if (autoScrollSwitch.isChecked && !transcriptEditable) {
            transcriptScroll.post {
                transcriptScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun buildStyledTranscript(document: TranscriptDocument): CharSequence {
        val result = SpannableStringBuilder()
        val confirmedColor = ContextCompat.getColor(this, R.color.voice_confirmed)
        val partialColor = ContextCompat.getColor(this, R.color.voice_partial)

        document.committedSegments.forEach { segment ->
            result.append(TranscriptTextFormatter.separator(result, segment.text))
            val start = result.length
            result.append(segment.text)
            val color = when (segment.source) {
                FinalSource.OFFLINE_SECOND_PASS -> confirmedColor
                FinalSource.ONLINE_FALLBACK -> partialColor
            }
            result.setSpan(
                ForegroundColorSpan(color),
                start,
                result.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val partial = document.partialText.trim()
        if (partial.isNotEmpty()) {
            result.append(TranscriptTextFormatter.separator(result, partial))
            val start = result.length
            result.append(partial)
            result.setSpan(
                ForegroundColorSpan(partialColor),
                start,
                result.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            result.setSpan(
                StyleSpan(Typeface.ITALIC),
                start,
                result.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }

    private fun setTranscriptEditable(editable: Boolean) {
        if (transcriptEditable == editable) return
        transcriptEditable = editable

        if (editable) {
            resultText.keyListener = originalKeyListener
            resultText.isFocusable = true
            resultText.isFocusableInTouchMode = true
            resultText.isCursorVisible = true
        } else {
            resultText.keyListener = null
            resultText.isFocusable = false
            resultText.isFocusableInTouchMode = false
            resultText.isCursorVisible = false
            resultText.clearFocus()
        }
        resultText.isLongClickable = true
    }

    private fun updateRecordButton(snapshot: VoiceSessionSnapshot) {
        val recordingColor = ContextCompat.getColor(this, R.color.voice_recording)
        val primaryColor = ContextCompat.getColor(this, R.color.voice_primary)
        recordButton.backgroundTintList = ColorStateList.valueOf(
            if (
                snapshot.state == VoiceServiceState.STARTING ||
                snapshot.state == VoiceServiceState.STREAMING
            ) {
                recordingColor
            } else {
                primaryColor
            },
        )

        when (snapshot.state) {
            VoiceServiceState.MODEL_LOADING -> {
                recordButton.text = "等待"
                recordButton.setIconResource(R.drawable.ic_mic_action)
                recordButton.isEnabled = false
            }
            VoiceServiceState.READY -> {
                recordButton.text = "开始"
                recordButton.setIconResource(R.drawable.ic_mic_action)
                recordButton.isEnabled = snapshot.canStart && serviceBound
            }
            VoiceServiceState.STARTING -> {
                recordButton.text = "取消"
                recordButton.setIconResource(R.drawable.ic_stop_action)
                recordButton.isEnabled = serviceBound
            }
            VoiceServiceState.STREAMING -> {
                recordButton.text = "停止"
                recordButton.setIconResource(R.drawable.ic_stop_action)
                recordButton.isEnabled = serviceBound
            }
            VoiceServiceState.STOPPING -> {
                recordButton.text = "整理中"
                recordButton.setIconResource(R.drawable.ic_stop_action)
                recordButton.isEnabled = false
            }
            VoiceServiceState.ERROR -> {
                recordButton.text = "重试"
                recordButton.setIconResource(R.drawable.ic_mic_action)
                recordButton.isEnabled = snapshot.canStart && serviceBound
            }
            VoiceServiceState.CLOSED -> {
                recordButton.text = "关闭"
                recordButton.setIconResource(R.drawable.ic_mic_action)
                recordButton.isEnabled = false
            }
        }
    }

    private fun updateActionAvailability() {
        val hasText = resultText.text?.isNotBlank() == true
        val busy = latestSnapshot.state == VoiceServiceState.STARTING ||
            latestSnapshot.state == VoiceServiceState.STREAMING ||
            latestSnapshot.state == VoiceServiceState.STOPPING
        copyButton.isEnabled = hasText
        newButton.isEnabled = hasText && !busy && serviceBound
    }

    private fun copyTranscript() {
        val text = resultText.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("转录文本", text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, "已复制转录文本", Toast.LENGTH_SHORT).show()
        }
        Log.i(LogTags.UI, "transcript copied; chars=${text.length}")
    }

    private fun confirmAndClearTranscript() {
        if (resultText.text.isNullOrBlank()) return

        AlertDialog.Builder(this)
            .setTitle("新建转录？")
            .setMessage("当前内容尚未保存。新建后将清空本页文字。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空并新建") { _, _ ->
                localEditedText = null
                serviceBinder?.requestClearTranscript()
                    ?: showLocalError("服务尚未连接，无法新建")
            }
            .show()
    }

    private fun stateLabel(state: VoiceServiceState): String {
        return when (state) {
            VoiceServiceState.MODEL_LOADING -> "模型加载"
            VoiceServiceState.READY -> "就绪"
            VoiceServiceState.STARTING -> "启动中"
            VoiceServiceState.STREAMING -> "录音中"
            VoiceServiceState.STOPPING -> "整理中"
            VoiceServiceState.ERROR -> "错误"
            VoiceServiceState.CLOSED -> "已关闭"
        }
    }

    private fun showLocalError(message: String) {
        statusText.text = message
        progressText.text = String.format(
            Locale.US,
            "服务状态：%s",
            latestSnapshot.state,
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(LogTags.UI, message)
    }
}
