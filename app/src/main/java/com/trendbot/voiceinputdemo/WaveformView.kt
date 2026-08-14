package com.trendbot.voiceinputdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max

data class WaveformMetrics(
    val submittedLevels: Long,
    val appliedLevels: Long,
    val coalescedLevels: Long,
)

/**
 * A lightweight PCM level history view.
 *
 * submitLevel() may be called from the recorder thread. Updates are coalesced so
 * at most one UI runnable is pending. The visual "明显/较低" state is only an
 * energy hint; it is not VAD and must not control ASR segment boundaries.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val BAR_COUNT = 64
        private const val FLOOR_DBFS = -72.0f
        private const val CEILING_DBFS = -12.0f
        private const val VOICE_HINT_DBFS = -42.0f
        private const val VOICE_HOLD_UPDATES = 3
        private const val MIN_AMPLITUDE = 0.000001f
    }

    private enum class EnergyState {
        STOPPED,
        QUIET,
        ACTIVE,
    }

    private data class LevelSample(
        val peak: Float,
        val rms: Float,
    )

    private val pendingLevel = AtomicReference<LevelSample?>(null)
    private val drainPosted = AtomicBoolean(false)
    private val submittedLevels = AtomicLong(0L)
    private val appliedLevels = AtomicLong(0L)
    private val coalescedLevels = AtomicLong(0L)

    @Volatile
    private var acceptingLevels = false

    private val history = FloatArray(BAR_COUNT)
    private val linePoints = FloatArray(BAR_COUNT * 4)

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.voice_wave_baseline)
        strokeWidth = dp(1.0f)
    }

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.voice_wave_stopped)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.voice_wave_label)
        textSize = sp(13.0f)
    }

    private var energyState = EnergyState.STOPPED
    private var activeHoldUpdates = 0
    private var latestRmsDbfs = FLOOR_DBFS
    private var label = "波形已停止"

    /** Must be cheap: called once for each approximately 100 ms PCM frame. */
    fun submitLevel(peak: Float, rms: Float) {
        if (!acceptingLevels) return

        submittedLevels.incrementAndGet()
        val replaced = pendingLevel.getAndSet(
            LevelSample(
                peak = peak.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f)
                    ?: 0.0f,
                rms = rms.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f)
                    ?: 0.0f,
            ),
        )
        if (replaced != null) {
            coalescedLevels.incrementAndGet()
        }
        requestUiDrain()
    }

    fun setRecording(recording: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setRecording(recording) }
            return
        }

        acceptingLevels = recording
        pendingLevel.set(null)
        activeHoldUpdates = 0
        energyState = if (recording) EnergyState.QUIET else EnergyState.STOPPED
        updateLabel()
        postInvalidateOnAnimation()
    }

    fun reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { reset() }
            return
        }

        history.fill(0.0f)
        pendingLevel.set(null)
        activeHoldUpdates = 0
        latestRmsDbfs = FLOOR_DBFS
        submittedLevels.set(0L)
        appliedLevels.set(0L)
        coalescedLevels.set(0L)
        energyState =
            if (acceptingLevels) EnergyState.QUIET else EnergyState.STOPPED
        updateLabel()
        postInvalidateOnAnimation()
    }

    fun snapshotMetrics(): WaveformMetrics {
        return WaveformMetrics(
            submittedLevels = submittedLevels.get(),
            appliedLevels = appliedLevels.get(),
            coalescedLevels = coalescedLevels.get(),
        )
    }

    private fun requestUiDrain() {
        if (!drainPosted.compareAndSet(false, true)) return

        val accepted = post {
            val level = pendingLevel.getAndSet(null)
            if (level != null && acceptingLevels) {
                applyLevel(level)
            }

            drainPosted.set(false)
            if (acceptingLevels && pendingLevel.get() != null) {
                requestUiDrain()
            }
        }

        if (!accepted) {
            drainPosted.set(false)
        }
    }

    private fun applyLevel(level: LevelSample) {
        appliedLevels.incrementAndGet()
        System.arraycopy(history, 1, history, 0, history.size - 1)

        val peakDbfs = amplitudeToDbfs(level.peak)
        latestRmsDbfs = amplitudeToDbfs(level.rms)

        val rmsVisual = normalizeDbfs(latestRmsDbfs)
        val peakVisual = normalizeDbfs(peakDbfs)
        history[history.lastIndex] =
            (rmsVisual * 0.75f + peakVisual * 0.25f).coerceIn(0.0f, 1.0f)

        if (latestRmsDbfs >= VOICE_HINT_DBFS) {
            activeHoldUpdates = VOICE_HOLD_UPDATES
        } else {
            activeHoldUpdates = max(0, activeHoldUpdates - 1)
        }

        energyState =
            if (activeHoldUpdates > 0) EnergyState.ACTIVE else EnergyState.QUIET
        updateLabel()
        postInvalidateOnAnimation()
    }

    private fun amplitudeToDbfs(amplitude: Float): Float {
        val safeAmplitude = amplitude.coerceAtLeast(MIN_AMPLITUDE)
        return (20.0 * log10(safeAmplitude.toDouble())).toFloat()
    }

    private fun normalizeDbfs(dbfs: Float): Float {
        return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS))
            .coerceIn(0.0f, 1.0f)
    }

    private fun updateLabel() {
        label = when (energyState) {
            EnergyState.STOPPED -> "波形已停止"
            EnergyState.QUIET -> String.format(
                Locale.US,
                "声音能量：较低 · %.0f dBFS",
                latestRmsDbfs,
            )
            EnergyState.ACTIVE -> String.format(
                Locale.US,
                "声音能量：明显 · %.0f dBFS",
                latestRmsDbfs,
            )
        }
        contentDescription = label
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val left = paddingLeft.toFloat() + dp(2.0f)
        val right = w.toFloat() - paddingRight.toFloat() - dp(2.0f)
        val step = if (BAR_COUNT > 1) {
            (right - left).coerceAtLeast(0.0f) / (BAR_COUNT - 1).toFloat()
        } else {
            0.0f
        }

        for (index in 0 until BAR_COUNT) {
            val x = left + index.toFloat() * step
            val offset = index * 4
            linePoints[offset] = x
            linePoints[offset + 2] = x
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = width.toFloat() - paddingRight.toFloat()
        val bottom = height.toFloat() - paddingBottom.toFloat()
        if (right <= left || bottom <= top) return

        val labelBaseline = top + dp(20.0f)
        canvas.drawText(label, left + dp(10.0f), labelBaseline, labelPaint)

        val waveformTop = top + dp(30.0f)
        val waveformBottom = bottom - dp(8.0f)
        val centerY = (waveformTop + waveformBottom) * 0.5f
        val maximumHalfHeight =
            ((waveformBottom - waveformTop) * 0.5f).coerceAtLeast(0.0f)

        canvas.drawLine(left + dp(4.0f), centerY, right - dp(4.0f), centerY, baselinePaint)

        waveformPaint.color = when (energyState) {
            EnergyState.STOPPED -> context.getColor(R.color.voice_wave_stopped)
            EnergyState.QUIET -> context.getColor(R.color.voice_wave_quiet)
            EnergyState.ACTIVE -> context.getColor(R.color.voice_wave_active)
        }

        val minimumHalfHeight = dp(0.75f)
        for (index in history.indices) {
            val halfHeight = max(
                minimumHalfHeight,
                history[index] * maximumHalfHeight,
            )
            val offset = index * 4
            linePoints[offset + 1] = centerY - halfHeight
            linePoints[offset + 3] = centerY + halfHeight
        }

        canvas.drawLines(linePoints, waveformPaint)
    }

    override fun onDetachedFromWindow() {
        acceptingLevels = false
        pendingLevel.set(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
