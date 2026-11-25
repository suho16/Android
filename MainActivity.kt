package com.suho.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var instructionText: TextView
    private lateinit var startButton: Button
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var measuring = false
    private val signal = ArrayDeque<Sample>()
    private var lastUiUpdate = 0L
    private var startedAt = 0L

    private data class Sample(val time: Long, val value: Double)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "카메라 권한이 필요합니다."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()

        startButton.setOnClickListener {
            if (!measuring) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                stopMeasurement()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 24, 28, 20)
            setBackgroundColor(Color.rgb(16, 17, 22))
        }

        val title = TextView(this).apply {
            text = "HEART RATE"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        root.addView(title, matchWrap())

        bpmText = TextView(this).apply {
            text = "--"
            textSize = 66f
            setTextColor(Color.rgb(235, 77, 104))
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 0)
        }
        root.addView(bpmText, matchWrap())

        val bpmLabel = TextView(this).apply {
            text = "BPM · 분당 심박수"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        root.addView(bpmLabel, matchWrap())

        statusText = TextView(this).apply {
            text = "측정을 시작해 주세요."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 14)
        }
        root.addView(statusText, matchWrap())

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        instructionText = TextView(this).apply {
            text = "측정할 때 손가락 끝을 후면 카메라와 플래시에 가볍게 대세요.\n손가락을 너무 세게 누르거나 움직이지 마세요."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 14)
        }
        root.addView(instructionText, matchWrap())

        startButton = Button(this).apply {
            text = "측정 시작"
            isAllCaps = false
        }
        root.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val disclaimer = TextView(this).apply {
            text = "참고용 측정 앱이며 의료 진단용이 아닙니다."
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        root.addView(disclaimer, matchWrap())
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun startCamera() {
        statusText.text = "카메라 준비 중..."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        if (measuring) {
                            val red = averageCenterRed(image)
                            if (red != null) processSample(red)
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    camera?.cameraControl?.enableTorch(true)
                }
                synchronized(signal) { signal.clear() }
                measuring = true
                startedAt = SystemClock.elapsedRealtime()
                runOnUiThread {
                    bpmText.text = "--"
                    statusText.text = "측정 중 · 손가락을 카메라에 대세요"
                    startButton.text = "측정 중지"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "카메라를 시작하지 못했습니다: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // YUV 프레임 중앙 영역의 R 성분을 근사해 PPG 신호로 사용합니다.
    private fun averageCenterRed(image: androidx.camera.core.ImageProxy): Double? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPixel = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPixel = vPlane.pixelStride

        val left = width * 2 / 5
        val right = width * 3 / 5
        val top = height * 2 / 5
        val bottom = height * 3 / 5
        var total = 0.0
        var count = 0

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val yIndex = y * yRow + x * yPixel
                val uvX = x / 2
                val uvY = y / 2
                val uIndex = uvY * uRow + uvX * uPixel
                val vIndex = uvY * vRow + uvX * vPixel
                val yy = (yBuffer.get(yIndex).toInt() and 0xff).toDouble()
                val vv = (vBuffer.get(vIndex).toInt() and 0xff).toDouble() - 128.0
                val red = yy + 1.402 * vv
                total += red
                count++
                x += 4
            }
            y += 4
        }
        return if (count > 0) total / count else null
    }

    private fun processSample(value: Double) {
        val now = SystemClock.elapsedRealtime()
        synchronized(signal) {
            signal.addLast(Sample(now, value))
            while (signal.isNotEmpty() && now - signal.first.time > 12_000L) {
                signal.removeFirst()
            }
            if (now - lastUiUpdate < 500L) return
            lastUiUpdate = now

            if (now - startedAt < 5_000L || signal.size < 40) {
                runOnUiThread { statusText.text = "신호 분석 중... 손가락을 가만히 유지하세요." }
                return
            }

            val samples = signal.toList()
            val recent = samples.filter { now - it.time <= 8_000L }
            if (recent.size < 30) return

            val values = recent.map { it.value }
            val minV = values.minOrNull() ?: return
            val maxV = values.maxOrNull() ?: return
            val range = maxV - minV
            if (range < 2.0) {
                runOnUiThread { statusText.text = "신호가 약합니다. 손가락 위치를 조정하세요." }
                return
            }

            val threshold = minV + range * 0.55
            val peaks = mutableListOf<Long>()
            for (i in 1 until recent.size - 1) {
                val prev = recent[i - 1]
                val cur = recent[i]
                val next = recent[i + 1]
                if (cur.value > prev.value && cur.value >= next.value &&
                    cur.value > threshold
                ) {
                    if (peaks.isEmpty() || cur.time - peaks.last() > 400L) {
                        peaks.add(cur.time)
                    } else if (cur.time - peaks.last() < 1_500L) {
                        // 가까운 후보 중 더 높은 봉우리를 선택
                        val lastIndex = recent.indexOfFirst { it.time == peaks.last() }
                        if (lastIndex >= 0 && cur.value > recent[lastIndex].value) {
                            peaks[peaks.lastIndex] = cur.time
                        }
                    }
                }
            }

            if (peaks.size >= 3) {
                val intervals = peaks.zipWithNext { a, b -> b - a }
                    .filter { it in 400L..1_500L }
                if (intervals.size >= 2) {
                    val sorted = intervals.sorted()
                    val median = sorted[sorted.size / 2].toDouble()
                    val bpm = (60_000.0 / median).toInt()
                    if (bpm in 40..180) {
                        runOnUiThread {
                            bpmText.text = bpm.toString()
                            statusText.text = "측정 중 · 값은 참고용 추정치입니다."
                        }
                        return
                    }
                }
            }
            runOnUiThread { statusText.text = "맥박 신호를 찾는 중... 손가락을 가만히 대세요." }
        }
    }

    private fun stopMeasurement() {
        measuring = false
        camera?.cameraControl?.enableTorch(false)
        camera = null
        synchronized(signal) { signal.clear() }
        bpmText.text = "--"
        statusText.text = "측정이 중지되었습니다."
        startButton.text = "측정 시작"
    }

    override fun onDestroy() {
        measuring = false
        camera?.cameraControl?.enableTorch(false)
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
