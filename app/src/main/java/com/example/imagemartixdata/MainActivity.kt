package com.example.imagemartixdata

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var matrixTextView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private val isProcessing = AtomicBoolean(false)

    companion object {
        private const val CAMERA_PERMISSION_CODE = 101
        private const val MATRIX_SAMPLE_SIZE = 5 // Sample every 5th pixel
        private const val MAX_MATRIX_ROWS = 10 // Limit number of rows displayed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        matrixTextView = findViewById(R.id.matrixTextView)

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
        // Sample only a portion of the image for display
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

        // Calculate sample dimensions
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
            // For better performance, consider using RenderScript or other optimized methods
            // This is a simplified version that works with YUV_420_888 format

            val planes = this.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Y
            yBuffer.get(nv21, 0, ySize)

            // VU
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