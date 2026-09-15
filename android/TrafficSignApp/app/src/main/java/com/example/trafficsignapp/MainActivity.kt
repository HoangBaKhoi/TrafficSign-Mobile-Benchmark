package com.example.trafficsignapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
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

    private lateinit var menuGroup:
            View

    private lateinit var cameraGroup:
            View

    private lateinit var btnStart:
            Button

    private lateinit var previewView:
            PreviewView

    private lateinit var overlayView:
            OverlayView

    private lateinit var tvFrameInfo:
            TextView

    private lateinit var btnSelectModel:
            Button

    private lateinit var cameraExecutor:
            ExecutorService

    private var detector:
            LiteRTDetector? = null

    private var frameCount =
        0

    private var lastFpsTime =
        System.currentTimeMillis()

    // Các chế độ realtime cho người dùng chọn (đơn giản hóa từ kết quả sweep
    // model x delegate — xem AI/notebooks/09_quantization_comparison.ipynb).
    private data class RealtimeMode(
        val label: String,
        val model: ModelConfig,
        val runtime: RuntimeConfig
    )

    private val realtimeModes =
        listOf(
            RealtimeMode(
                label = "Cân bằng (FP32, CPU)",
                model = ModelConfig.N640,
                runtime = RuntimeConfig.CPU_1_THREAD
            ),
            RealtimeMode(
                label = "Tốc độ cao (FP16, GPU)",
                model = ModelConfig.N640_FP16,
                runtime = RuntimeConfig.GPU
            ),
            RealtimeMode(
                label = "Tiết kiệm bộ nhớ (INT8, CPU)",
                model = ModelConfig.N640_INT8,
                runtime = RuntimeConfig.CPU_1_THREAD
            )
        )

    private var currentModeIndex =
        0

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

        menuGroup =
            findViewById(
                R.id.menuGroup
            )

        cameraGroup =
            findViewById(
                R.id.cameraGroup
            )

        btnStart =
            findViewById(
                R.id.btnStart
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

        btnSelectModel =
            findViewById(
                R.id.btnSelectModel
            )

        previewView.scaleType =
            PreviewView
                .ScaleType
                .FILL_CENTER

        cameraExecutor =
            Executors
                .newSingleThreadExecutor()

        btnStart
            .setOnClickListener {

                openCameraScreen()
            }

        btnSelectModel
            .setOnClickListener {

                showModeSelectionDialog()
            }
    }

    // =============================
    // MÀN MENU → MÀN CAMERA
    // =============================

    private fun openCameraScreen() {

        menuGroup.visibility =
            View.GONE

        cameraGroup.visibility =
            View.VISIBLE

        createRealtimeDetector(
            realtimeModes[currentModeIndex]
        )

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

    /*
     * Tạo/đóng detector luôn chạy trên cameraExecutor — cùng luồng với nơi
     * analyzer gọi interpreter.run(). TFLite Interpreter không thread-safe,
     * nên nếu đóng detector cũ từ UI thread trong lúc luồng camera đang suy
     * luận trên nó sẽ crash native (app bị kill ngay), không phải exception
     * Kotlin bắt được bằng try/catch.
     */
    private fun createRealtimeDetector(
        mode: RealtimeMode
    ) {

        cameraExecutor.execute {

            detector?.close()

            detector =
                LiteRTDetector(
                    context =
                        this,

                    config =
                        mode.model,

                    runtimeConfig =
                        mode.runtime
                )
        }
    }

    private fun showModeSelectionDialog() {

        val labels =
            realtimeModes
                .map { it.label }
                .toTypedArray()

        AlertDialog
            .Builder(
                this
            )
            .setTitle(
                "Chọn chế độ nhận diện"
            )
            .setSingleChoiceItems(
                labels,
                currentModeIndex
            ) { dialog, which ->

                currentModeIndex =
                    which

                createRealtimeDetector(
                    realtimeModes[currentModeIndex]
                )

                dialog.dismiss()
            }
            .setNegativeButton(
                "Hủy",
                null
            )
            .show()
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
                                    "Camera → ${activeDetector.config.id} " +
                                            "(${activeDetector.appliedDelegateLabel})\n" +

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

        // Đóng detector trên cùng luồng cameraExecutor, xem lý do ở createRealtimeDetector().
        cameraExecutor.execute {

            detector?.close()
        }

        cameraExecutor
            .shutdown()

        super.onDestroy()
    }
}
