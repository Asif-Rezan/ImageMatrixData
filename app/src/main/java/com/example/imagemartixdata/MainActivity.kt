package com.example.imagemartixdata

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import kotlin.math.*
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var matrixTextView: TextView
    private lateinit var cameraParamsTextView: TextView
    private lateinit var movementDataTextView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private val isProcessing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var frameCount = 0

    // Simulated camera parameters that will change over time
    private var fx = 1000.0
    private var fy = 1000.0
    private var cx = 640.0
    private var cy = 480.0
    private var k1 = 0.1
    private var k2 = -0.2
    private var p1 = 0.01
    private var p2 = 0.01
    private var k3 = 0.05

    // Simulated movement data
    private var dx = 0.0
    private var dy = 0.0
    private var dz = 0.0
    private var roll = 0.0
    private var pitch = 0.0
    private var yaw = 0.0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 101
        private const val MATRIX_SAMPLE_SIZE = 5
        private const val MAX_MATRIX_ROWS = 10
        private const val UPDATE_INTERVAL_MS = 100L // Update params every 100ms
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        matrixTextView = findViewById(R.id.matrixTextView)
        cameraParamsTextView = findViewById(R.id.cameraParamsTextView)
        movementDataTextView = findViewById(R.id.movementDataTextView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start updating parameters periodically
        startParameterUpdates()
    }

    private fun startParameterUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateSimulatedParameters()
                updateDisplays()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }, UPDATE_INTERVAL_MS)
    }

    private fun updateSimulatedParameters() {
        frameCount++

        // Simulate small changes in camera parameters
        fx += sin(frameCount * 0.05) * 0.5
        fy += cos(frameCount * 0.05) * 0.5
        cx += sin(frameCount * 0.03) * 0.3
        cy += cos(frameCount * 0.03) * 0.3

        // Simulate distortion coefficient changes
        k1 = 0.1 + sin(frameCount * 0.02) * 0.02
        k2 = -0.2 + cos(frameCount * 0.02) * 0.02
        p1 = 0.01 + sin(frameCount * 0.01) * 0.005
        p2 = 0.01 + cos(frameCount * 0.01) * 0.005
        k3 = 0.05 + sin(frameCount * 0.015) * 0.01

        // Simulate movement data (dx, dy, dz in mm)
        dx = 100 * sin(frameCount * 0.1)
        dy = 50 * cos(frameCount * 0.07)
        dz = 200 + 30 * sin(frameCount * 0.05)

        // Simulate orientation (in degrees)
        roll = 5 * sin(frameCount * 0.08)
        pitch = 10 * cos(frameCount * 0.06)
        yaw = 15 * sin(frameCount * 0.04)
    }

    private fun updateDisplays() {
        val paramsText = """
            Camera Intrinsic Parameters:
            fx: ${"%.2f".format(fx)}
            fy: ${"%.2f".format(fy)}
            cx: ${"%.1f".format(cx)}
            cy: ${"%.1f".format(cy)}
            
            Distortion Coefficients:
            k1: ${"%.4f".format(k1)}
            k2: ${"%.4f".format(k2)}
            p1: ${"%.4f".format(p1)}
            p2: ${"%.4f".format(p2)}
            k3: ${"%.4f".format(k3)}
        """.trimIndent()

        val movementText = """
            Movement Data:
            dx: ${"%.1f".format(dx)} mm
            dy: ${"%.1f".format(dy)} mm
            dz: ${"%.1f".format(dz)} mm
            
            Orientation:
            Roll: ${"%.1f".format(roll)}°
            Pitch: ${"%.1f".format(pitch)}°
            Yaw: ${"%.1f".format(yaw)}°
            
            Frame: $frameCount
        """.trimIndent()

        cameraParamsTextView.text = paramsText
        movementDataTextView.text = movementText
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyzer { bitmap ->
                        if (!isProcessing.getAndSet(true)) {
                            processImage(bitmap)
                            isProcessing.set(false)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(bitmap: Bitmap) {
        val sampledMatrix = getSampledImageMatrix(bitmap)

        runOnUiThread {
            matrixTextView.text = sampledMatrix.joinToString("\n") { row ->
                row.joinToString(" ") { pixel ->
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    "($r,$g,$b)"
                }
            }
        }
    }

    private fun getSampledImageMatrix(bitmap: Bitmap): Array<IntArray> {
        val width = bitmap.width
        val height = bitmap.height

        val sampleWidth = min(width / MATRIX_SAMPLE_SIZE, 20)
        val sampleHeight = min(height / MATRIX_SAMPLE_SIZE, MAX_MATRIX_ROWS)

        val matrix = Array(sampleHeight) { IntArray(sampleWidth) }

        for (y in 0 until sampleHeight) {
            val originalY = y * MATRIX_SAMPLE_SIZE
            for (x in 0 until sampleWidth) {
                val originalX = x * MATRIX_SAMPLE_SIZE
                matrix[y][x] = bitmap.getPixel(originalX, originalY)
            }
        }
        return matrix
    }

    private class ImageAnalyzer(private val onBitmap: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val bitmap = imageProxy.toBitmap()
                bitmap?.let { onBitmap(it) }
            } finally {
                imageProxy.close()
            }
        }

        private fun ImageProxy.toBitmap(): Bitmap? {
            val planes = this.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                this.width,
                this.height,
                null
            )

            val out = java.io.ByteArrayOutputStream()
            if (yuvImage.compressToJpeg(
                    android.graphics.Rect(0, 0, this.width, this.height),
                    75,
                    out
                )) {
                val jpegArray = out.toByteArray()
                return android.graphics.BitmapFactory.decodeByteArray(jpegArray, 0, jpegArray.size)
            }
            return null
        }
    }
}