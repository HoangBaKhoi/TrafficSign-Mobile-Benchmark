package com.example.trafficsignapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView:
            PreviewView

    private lateinit var overlayView:
            OverlayView

    private lateinit var tvFrameInfo:
            TextView

    private lateinit var btnVerification:
            Button

    private lateinit var cameraExecutor:
            ExecutorService

    /*
     * Realtime mặc định dùng n640.
     * Khi benchmark 4 model, detector này sẽ được đóng
     * để RAM benchmark không bị cộng thêm model realtime.
     */
    private var detector:
            LiteRTDetector? = null

    private var frameCount =
        0

    private var lastFpsTime =
        System.currentTimeMillis()

    @Volatile
    private var verificationRunning =
        false

    private val requestCameraPermission =
        registerForActivityResult(
            ActivityResultContracts
                .RequestPermission()
        ) { granted ->

            if (
                granted
            ) {

                startCamera()

            } else {

                Toast.makeText(
                    this,
                    "Ứng dụng cần quyền Camera để hoạt động",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState:
        Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        previewView =
            findViewById(
                R.id.previewView
            )

        overlayView =
            findViewById(
                R.id.overlayView
            )

        tvFrameInfo =
            findViewById(
                R.id.tvFrameInfo
            )

        btnVerification =
            findViewById(
                R.id.btnVerification
            )

        previewView.scaleType =
            PreviewView
                .ScaleType
                .FILL_CENTER

        // Realtime mặc định: YOLO11n-640.
        createRealtimeDetector()

        cameraExecutor =
            Executors
                .newSingleThreadExecutor()

        btnVerification.text =
            "RUN 4-MODEL TEST"

        btnVerification
            .setOnClickListener {

                runOfflineVerification()
            }

        if (
            ContextCompat
                .checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) ==
            PackageManager
                .PERMISSION_GRANTED
        ) {

            startCamera()

        } else {

            requestCameraPermission
                .launch(
                    Manifest.permission.CAMERA
                )
        }
    }

    private fun createRealtimeDetector() {

        detector?.close()

        detector =
            LiteRTDetector(
                context =
                    this,

                config =
                    ModelConfig
                        .REALTIME_DEFAULT
            )
    }

    // =============================
    // 4-MODEL / 100-IMAGE TEST
    // =============================

    private fun runOfflineVerification() {

        if (
            verificationRunning
        ) {
            return
        }

        verificationRunning =
            true

        btnVerification
            .isEnabled =
            false

        btnVerification.text =
            "ĐANG TEST 4 MODEL..."

        overlayView.setResults(
            newDetections =
                emptyList(),

            newSourceWidth =
                1,

            newSourceHeight =
                1
        )

        tvFrameInfo.text =
            "4-MODEL BENCHMARK\n" +
                    "100 ảnh/model\n" +
                    "Đang chạy tuần tự..."

        /*
         * Chạy trên cùng executor với camera.
         * Như vậy Interpreter không chạy song song với camera.
         */
        cameraExecutor.execute {

            try {

                /*
                 * Đóng model realtime trước benchmark
                 * để RAM của 4 model không bị cộng thêm n640.
                 */
                detector?.close()

                detector =
                    null

                System.gc()

                val runner =
                    VerificationRunner(
                        context =
                            this
                    )

                val result =
                    runner.run()

                // Sau benchmark mở lại n640 cho camera realtime.
                createRealtimeDetector()

                runOnUiThread {

                    verificationRunning =
                        false

                    btnVerification
                        .isEnabled =
                        true

                    btnVerification.text =
                        "RUN 4-MODEL TEST"

                    frameCount =
                        0

                    lastFpsTime =
                        System.currentTimeMillis()

                    AlertDialog
                        .Builder(
                            this
                        )
                        .setTitle(
                            "4-model benchmark hoàn tất"
                        )
                        .setMessage(
                            "Models: ${result.modelCount}\n" +
                                    "Ảnh/model: ${result.imageCount}\n\n" +

                                    "Model summary:\n" +
                                    "${result.modelSummaryPath}\n\n" +

                                    "Per-image detail:\n" +
                                    "${result.detailPath}\n\n" +

                                    "All detections:\n" +
                                    "${result.detectionsPath}\n\n" +

                                    "Tổng thời gian: " +
                                    "%.1f phút".format(
                                        result.durationMs /
                                                60000.0
                                    )
                        )
                        .setPositiveButton(
                            "OK",
                            null
                        )
                        .show()
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MainActivity",
                    "4-model benchmark failed",
                    e
                )

                // Cố gắng khôi phục realtime detector.
                try {

                    createRealtimeDetector()

                } catch (
                    restoreError: Exception
                ) {

                    Log.e(
                        "MainActivity",
                        "Không khôi phục được realtime detector",
                        restoreError
                    )
                }

                runOnUiThread {

                    verificationRunning =
                        false

                    btnVerification
                        .isEnabled =
                        true

                    btnVerification.text =
                        "RUN 4-MODEL TEST"

                    AlertDialog
                        .Builder(
                            this
                        )
                        .setTitle(
                            "Benchmark lỗi"
                        )
                        .setMessage(
                            e.message
                                ?: "Không xác định được lỗi"
                        )
                        .setPositiveButton(
                            "OK",
                            null
                        )
                        .show()
                }
            }
        }
    }

    // =============================
    // CAMERA REALTIME
    // =============================

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider
                .getInstance(
                    this
                )

        cameraProviderFuture
            .addListener({

                val cameraProvider =
                    cameraProviderFuture
                        .get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also {

                            it.surfaceProvider =
                                previewView
                                    .surfaceProvider
                        }

                val imageAnalysis =
                    ImageAnalysis.Builder()

                        .setOutputImageFormat(
                            ImageAnalysis
                                .OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )

                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )

                        .build()

                imageAnalysis.setAnalyzer(
                    cameraExecutor
                ) analyzer@{ imageProxy ->

                    if (
                        verificationRunning
                    ) {

                        imageProxy.close()

                        return@analyzer
                    }

                    val activeDetector =
                        detector

                    if (
                        activeDetector == null
                    ) {

                        imageProxy.close()

                        return@analyzer
                    }

                    var originalBitmap:
                            Bitmap? =
                        null

                    var rotatedBitmap:
                            Bitmap? =
                        null

                    try {

                        val width =
                            imageProxy.width

                        val height =
                            imageProxy.height

                        val rotation =
                            imageProxy
                                .imageInfo
                                .rotationDegrees

                        originalBitmap =
                            imageProxy
                                .toBitmap()

                        rotatedBitmap =
                            rotateBitmap(
                                bitmap =
                                    originalBitmap,

                                rotationDegrees =
                                    rotation
                            )

                        val result =
                            activeDetector
                                .runOnBitmap(
                                    rotatedBitmap
                                )

                        frameCount++

                        val currentTime =
                            System
                                .currentTimeMillis()

                        val elapsedTime =
                            currentTime -
                                    lastFpsTime

                        runOnUiThread {

                            overlayView
                                .setResults(
                                    newDetections =
                                        result.detections,

                                    newSourceWidth =
                                        result.sourceWidth,

                                    newSourceHeight =
                                        result.sourceHeight
                                )
                        }

                        if (
                            elapsedTime >=
                            1000
                        ) {

                            val fps =
                                frameCount *
                                        1000f /
                                        elapsedTime

                            val bestDetection =
                                result
                                    .detections
                                    .maxByOrNull {
                                        it.confidence
                                    }

                            val detectionText =
                                if (
                                    bestDetection !=
                                    null
                                ) {

                                    "${bestDetection.label} | " +
                                            "%.2f".format(
                                                bestDetection
                                                    .confidence
                                            )

                                } else {

                                    "Không phát hiện"
                                }

                            runOnUiThread {

                                tvFrameInfo.text =
                                    "Camera → ${activeDetector.config.id}\n" +

                                            "${width}x${height} | " +
                                            "Rotation: ${rotation}°\n" +

                                            "Analyzer FPS: %.2f\n"
                                                .format(
                                                    fps
                                                ) +

                                            "Preprocess: %.1f ms\n"
                                                .format(
                                                    result.preprocessMs
                                                ) +

                                            "Inference: %.1f ms\n"
                                                .format(
                                                    result.inferenceMs
                                                ) +

                                            "Total: %.1f ms\n"
                                                .format(
                                                    result.totalMs
                                                ) +

                                            "Detections: " +
                                            "${result.detections.size}\n" +

                                            "Best: " +
                                            detectionText
                            }

                            frameCount =
                                0

                            lastFpsTime =
                                currentTime
                        }

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            "MainActivity",
                            "Camera inference failed",
                            e
                        )

                    } finally {

                        if (
                            rotatedBitmap !=
                            null &&
                            rotatedBitmap !==
                            originalBitmap
                        ) {

                            rotatedBitmap
                                .recycle()
                        }

                        originalBitmap
                            ?.recycle()

                        imageProxy
                            .close()
                    }
                }

                val cameraSelector =
                    CameraSelector
                        .DEFAULT_BACK_CAMERA

                try {

                    cameraProvider
                        .unbindAll()

                    cameraProvider
                        .bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        "MainActivity",
                        "Camera binding failed",
                        e
                    )
                }

            },
                ContextCompat
                    .getMainExecutor(
                        this
                    )
            )
    }

    private fun rotateBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int
    ): Bitmap {

        if (
            rotationDegrees ==
            0
        ) {

            return bitmap
        }

        val matrix =
            Matrix().apply {

                postRotate(
                    rotationDegrees
                        .toFloat()
                )
            }

        return Bitmap
            .createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
    }

    override fun onDestroy() {

        detector?.close()

        cameraExecutor
            .shutdown()

        super.onDestroy()
    }
}
