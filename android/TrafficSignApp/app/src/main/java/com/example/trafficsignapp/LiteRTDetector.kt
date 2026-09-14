package com.example.trafficsignapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.min

// Một vật thể được YOLO phát hiện.
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

// Kết quả xử lý của một frame.
data class InferenceResult(
    val preprocessMs: Double,
    val inferenceMs: Double,
    val totalMs: Double,
    val maxScore: Float,
    val detections: List<Detection>,
    val sourceWidth: Int,
    val sourceHeight: Int
)

// Thông tin dùng để đảo quá trình letterbox.
private data class LetterboxResult(
    val bitmap: Bitmap,
    val scale: Float,
    val padX: Float,
    val padY: Float
)

class LiteRTDetector(
    private val context: Context,
    val config: ModelConfig = ModelConfig.REALTIME_DEFAULT,
    val runtimeConfig: RuntimeConfig = RuntimeConfig.CPU_1_THREAD
) {

    companion object {

        private const val TAG = "LiteRTDetector"

        private const val INPUT_CHANNELS = 3

        // Giữ cùng threshold cho toàn bộ 4 model để benchmark công bằng.
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
    }

    private var interpreter: Interpreter? = null

    // Chỉ khác null khi runtimeConfig dùng GPU — cần giữ lại để close() giải phóng đúng cách.
    private var gpuDelegate: GpuDelegate? = null

    // Delegate thực tế đã áp dụng (có thể fallback về CPU nếu thiết bị không hỗ trợ GPU/NNAPI).
    var appliedDelegateLabel: String = runtimeConfig.label
        private set

    // NCHW = ai-edge-torch (model FP32 gốc), NHWC = onnx2tf (model FP16/INT8 mới).
    private enum class InputLayout { NCHW, NHWC }

    private var inputLayout = InputLayout.NCHW

    // Các thông số này được đọc trực tiếp từ tensor của từng model.
    private var inputSize = 0
    private var outputChannels = 0
    private var numClasses = 0
    private var numCandidates = 0

    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: ByteBuffer
    private lateinit var pixels: IntArray
    private lateinit var outputValues: FloatArray

    // Cả 4 model dùng chung 58 labels.
    private val labels: List<String> by lazy {

        context.assets
            .open("labels.txt")
            .bufferedReader()
            .readLines()
            .filter {
                it.isNotBlank()
            }
    }

    init {
        loadModel()
    }

    private fun loadModel() {

        try {

            val modelBuffer =
                loadModelFile(
                    config.assetFile
                )

            val options =
                buildInterpreterOptions()

            interpreter =
                Interpreter(
                    modelBuffer,
                    options
                )

            val inputTensor =
                interpreter!!
                    .getInputTensor(0)

            val outputTensor =
                interpreter!!
                    .getOutputTensor(0)

            val inputShape =
                inputTensor.shape()

            val outputShape =
                outputTensor.shape()

            /*
             * Model FP32 gốc (ai-edge-torch) xuất input dạng NCHW [1,3,H,W].
             * Model FP16/INT8 export qua onnx2tf (ultralytics .export) lại ra NHWC [1,H,W,3].
             * Tự nhận diện layout theo vị trí trục có độ dài = INPUT_CHANNELS để tương thích cả hai.
             */
            val isChannelFirst =
                inputShape[1] == INPUT_CHANNELS &&
                        inputShape[2] == inputShape[3]

            val isChannelLast =
                inputShape[3] == INPUT_CHANNELS &&
                        inputShape[1] == inputShape[2]

            require(
                inputShape.size == 4 &&
                        inputShape[0] == 1 &&
                        (isChannelFirst || isChannelLast)
            ) {
                "Input tensor không đúng dạng [1,3,H,W] hoặc [1,H,W,3]: " +
                        inputShape.contentToString()
            }

            require(
                outputShape.size == 3 &&
                        outputShape[0] == 1
            ) {
                "Output tensor không đúng dạng [1,C,N]: " +
                        outputShape.contentToString()
            }

            inputLayout =
                if (isChannelFirst) {
                    InputLayout.NCHW
                } else {
                    InputLayout.NHWC
                }

            inputSize =
                if (isChannelFirst) {
                    inputShape[2]
                } else {
                    inputShape[1]
                }

            outputChannels =
                outputShape[1]

            numCandidates =
                outputShape[2]

            numClasses =
                outputChannels - 4

            require(
                inputSize ==
                        config.expectedInputSize
            ) {
                "${config.id}: expected input " +
                        "${config.expectedInputSize} nhưng model là $inputSize"
            }

            require(
                numClasses ==
                        labels.size
            ) {
                "${config.id}: output có $numClasses classes " +
                        "nhưng labels.txt có ${labels.size}"
            }

            allocateBuffers()

            Log.d(
                TAG,
                "${config.id} loaded successfully"
            )

            Log.d(
                TAG,
                "Input shape: ${inputShape.contentToString()}"
            )

            Log.d(
                TAG,
                "Output shape: ${outputShape.contentToString()}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to load ${config.id}",
                e
            )

            throw e
        }
    }

    // Dựng Interpreter.Options theo runtimeConfig, tự fallback về CPU nếu thiết bị không hỗ trợ.
    private fun buildInterpreterOptions(): Interpreter.Options {

        val options = Interpreter.Options()

        when (runtimeConfig.delegate) {

            DelegateType.CPU -> {

                options.setNumThreads(
                    runtimeConfig.numThreads
                )
            }

            DelegateType.GPU -> {

                try {

                    val compatList = CompatibilityList()

                    val delegateOptions =
                        if (compatList.isDelegateSupportedOnThisDevice) {
                            compatList.bestOptionsForThisDevice
                        } else {
                            GpuDelegate.Options()
                        }

                    val delegate = GpuDelegate(delegateOptions)

                    gpuDelegate = delegate

                    options.addDelegate(delegate)

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "${config.id}: GPU delegate không khả dụng, fallback CPU",
                        e
                    )

                    appliedDelegateLabel = "CPU-${runtimeConfig.numThreads}T (GPU fallback)"

                    options.setNumThreads(
                        runtimeConfig.numThreads
                    )
                }
            }

            DelegateType.NNAPI -> {

                try {

                    options.setUseNNAPI(true)

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "${config.id}: NNAPI không khả dụng, fallback CPU",
                        e
                    )

                    appliedDelegateLabel = "CPU-${runtimeConfig.numThreads}T (NNAPI fallback)"

                    options.setNumThreads(
                        runtimeConfig.numThreads
                    )
                }
            }
        }

        return options
    }

    // Tạo buffer đúng kích thước của model 320 hoặc 640.
    private fun allocateBuffers() {

        inputBuffer =
            ByteBuffer.allocateDirect(
                1 *
                        INPUT_CHANNELS *
                        inputSize *
                        inputSize *
                        4
            ).order(
                ByteOrder.nativeOrder()
            )

        outputBuffer =
            ByteBuffer.allocateDirect(
                1 *
                        outputChannels *
                        numCandidates *
                        4
            ).order(
                ByteOrder.nativeOrder()
            )

        pixels =
            IntArray(
                inputSize *
                        inputSize
            )

        outputValues =
            FloatArray(
                outputChannels *
                        numCandidates
            )
    }

    // Chạy YOLO trên Bitmap.
    fun runOnBitmap(
        bitmap: Bitmap
    ): InferenceResult {

        val currentInterpreter =
            interpreter
                ?: throw IllegalStateException(
                    "LiteRT Interpreter chưa được khởi tạo"
                )

        val totalStart =
            System.nanoTime()

        // =========================
        // 1. PREPROCESS
        // =========================

        val preprocessStart =
            System.nanoTime()

        val letterboxResult =
            letterbox(bitmap)

        val modelBitmap =
            letterboxResult.bitmap

        modelBitmap.getPixels(
            pixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        inputBuffer.clear()

        // Normalize 0..1, thứ tự ghi phụ thuộc layout thật của model (xem loadModel()).
        when (inputLayout) {

            InputLayout.NCHW -> {

                // R plane
                for (pixel in pixels) {
                    inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                }

                // G plane
                for (pixel in pixels) {
                    inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                }

                // B plane
                for (pixel in pixels) {
                    inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
                }
            }

            InputLayout.NHWC -> {

                // Mỗi pixel ghi liền R, G, B.
                for (pixel in pixels) {
                    inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                    inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                    inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
                }
            }
        }

        inputBuffer.rewind()

        val preprocessMs =
            (
                    System.nanoTime() -
                            preprocessStart
                    ) /
                    1_000_000.0

        // =========================
        // 2. INFERENCE
        // =========================

        outputBuffer.clear()

        val inferenceStart =
            System.nanoTime()

        currentInterpreter.run(
            inputBuffer,
            outputBuffer
        )

        val inferenceMs =
            (
                    System.nanoTime() -
                            inferenceStart
                    ) /
                    1_000_000.0

        // =========================
        // 3. POSTPROCESS
        // =========================

        outputBuffer.rewind()

        outputBuffer
            .asFloatBuffer()
            .get(outputValues)

        var maxScore =
            0f

        for (
        channel in 4
                until outputChannels
        ) {

            val baseIndex =
                channel *
                        numCandidates

            for (
            candidate in 0
                    until numCandidates
            ) {

                val score =
                    outputValues[
                        baseIndex +
                                candidate
                    ]

                if (
                    score > maxScore
                ) {
                    maxScore =
                        score
                }
            }
        }

        val modelDetections =
            decodeOutput()

        val sourceDetections =
            modelDetections.mapNotNull {

                mapDetectionToSource(
                    detection = it,
                    letterboxResult =
                        letterboxResult,
                    sourceWidth =
                        bitmap.width,
                    sourceHeight =
                        bitmap.height
                )
            }

        modelBitmap.recycle()

        val totalMs =
            (
                    System.nanoTime() -
                            totalStart
                    ) /
                    1_000_000.0

        return InferenceResult(
            preprocessMs =
                preprocessMs,

            inferenceMs =
                inferenceMs,

            totalMs =
                totalMs,

            maxScore =
                maxScore,

            detections =
                sourceDetections,

            sourceWidth =
                bitmap.width,

            sourceHeight =
                bitmap.height
        )
    }

    // Decode output [1,C,N]. N tự thay đổi 2100 hoặc 8400.
    private fun decodeOutput():
            List<Detection> {

        val detections =
            mutableListOf<Detection>()

        for (
        candidate in 0
                until numCandidates
        ) {

            /*
             * LiteRT output bbox normalized 0..1.
             * Nhân inputSize để chuyển sang pixel.
             */
            val centerX =
                outputValues[
                    candidate
                ] * inputSize

            val centerY =
                outputValues[
                    numCandidates +
                            candidate
                ] * inputSize

            val boxWidth =
                outputValues[
                    2 *
                            numCandidates +
                            candidate
                ] * inputSize

            val boxHeight =
                outputValues[
                    3 *
                            numCandidates +
                            candidate
                ] * inputSize

            var bestClassId =
                -1

            var bestScore =
                0f

            for (
            classId in 0
                    until numClasses
            ) {

                val score =
                    outputValues[
                        (4 + classId) *
                                numCandidates +
                                candidate
                    ]

                if (
                    score > bestScore
                ) {

                    bestScore =
                        score

                    bestClassId =
                        classId
                }
            }

            if (
                bestScore <
                CONFIDENCE_THRESHOLD ||
                bestClassId < 0
            ) {

                continue
            }

            val left =
                centerX -
                        boxWidth / 2f

            val top =
                centerY -
                        boxHeight / 2f

            val right =
                centerX +
                        boxWidth / 2f

            val bottom =
                centerY +
                        boxHeight / 2f

            val label =
                labels.getOrElse(
                    bestClassId
                ) {

                    "Class $bestClassId"
                }

            detections.add(
                Detection(
                    classId =
                        bestClassId,

                    label =
                        label,

                    confidence =
                        bestScore,

                    left =
                        left,

                    top =
                        top,

                    right =
                        right,

                    bottom =
                        bottom
                )
            )
        }

        return nonMaximumSuppression(
            detections
        )
    }

    private fun mapDetectionToSource(
        detection: Detection,
        letterboxResult: LetterboxResult,
        sourceWidth: Int,
        sourceHeight: Int
    ): Detection? {

        val scale =
            letterboxResult.scale

        val padX =
            letterboxResult.padX

        val padY =
            letterboxResult.padY

        val left =
            (
                    (
                            detection.left -
                                    padX
                            ) /
                            scale
                    )
                .coerceIn(
                    0f,
                    sourceWidth.toFloat()
                )

        val top =
            (
                    (
                            detection.top -
                                    padY
                            ) /
                            scale
                    )
                .coerceIn(
                    0f,
                    sourceHeight.toFloat()
                )

        val right =
            (
                    (
                            detection.right -
                                    padX
                            ) /
                            scale
                    )
                .coerceIn(
                    0f,
                    sourceWidth.toFloat()
                )

        val bottom =
            (
                    (
                            detection.bottom -
                                    padY
                            ) /
                            scale
                    )
                .coerceIn(
                    0f,
                    sourceHeight.toFloat()
                )

        if (
            right <= left ||
            bottom <= top
        ) {

            return null
        }

        return detection.copy(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun nonMaximumSuppression(
        detections:
        List<Detection>
    ): List<Detection> {

        val remaining =
            detections
                .sortedByDescending {
                    it.confidence
                }
                .toMutableList()

        val selected =
            mutableListOf<Detection>()

        while (
            remaining.isNotEmpty()
        ) {

            val best =
                remaining.removeAt(0)

            selected.add(
                best
            )

            val iterator =
                remaining.iterator()

            while (
                iterator.hasNext()
            ) {

                val other =
                    iterator.next()

                if (
                    best.classId ==
                    other.classId &&
                    calculateIoU(
                        best,
                        other
                    ) >
                    IOU_THRESHOLD
                ) {

                    iterator.remove()
                }
            }
        }

        return selected
    }

    private fun calculateIoU(
        a: Detection,
        b: Detection
    ): Float {

        val left =
            maxOf(
                a.left,
                b.left
            )

        val top =
            maxOf(
                a.top,
                b.top
            )

        val right =
            minOf(
                a.right,
                b.right
            )

        val bottom =
            minOf(
                a.bottom,
                b.bottom
            )

        val intersectionWidth =
            maxOf(
                0f,
                right - left
            )

        val intersectionHeight =
            maxOf(
                0f,
                bottom - top
            )

        val intersectionArea =
            intersectionWidth *
                    intersectionHeight

        val areaA =
            maxOf(
                0f,
                a.right - a.left
            ) *
                    maxOf(
                        0f,
                        a.bottom - a.top
                    )

        val areaB =
            maxOf(
                0f,
                b.right - b.left
            ) *
                    maxOf(
                        0f,
                        b.bottom - b.top
                    )

        val unionArea =
            areaA +
                    areaB -
                    intersectionArea

        if (
            unionArea <= 0f
        ) {

            return 0f
        }

        return intersectionArea /
                unionArea
    }

    // Letterbox theo đúng input 320 hoặc 640 của model.
    private fun letterbox(
        bitmap: Bitmap
    ): LetterboxResult {

        val outputBitmap =
            Bitmap.createBitmap(
                inputSize,
                inputSize,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(
                outputBitmap
            )

        canvas.drawColor(
            Color.rgb(
                114,
                114,
                114
            )
        )

        val scale =
            min(
                inputSize.toFloat() /
                        bitmap.width,

                inputSize.toFloat() /
                        bitmap.height
            )

        val scaledWidth =
            bitmap.width *
                    scale

        val scaledHeight =
            bitmap.height *
                    scale

        val padX =
            (
                    inputSize -
                            scaledWidth
                    ) /
                    2f

        val padY =
            (
                    inputSize -
                            scaledHeight
                    ) /
                    2f

        val destination =
            RectF(
                padX,
                padY,
                padX +
                        scaledWidth,
                padY +
                        scaledHeight
            )

        val paint =
            Paint(
                Paint.FILTER_BITMAP_FLAG
            )

        canvas.drawBitmap(
            bitmap,
            null,
            destination,
            paint
        )

        return LetterboxResult(
            bitmap =
                outputBitmap,

            scale =
                scale,

            padX =
                padX,

            padY =
                padY
        )
    }

    fun getInputSize():
            Int {

        return inputSize
    }

    fun getModelSizeMb():
            Double {

        val descriptor =
            context.assets.openFd(
                config.assetFile
            )

        val bytes =
            descriptor.declaredLength

        descriptor.close()

        return bytes /
                (1024.0 * 1024.0)
    }

    // Smoke test động theo model hiện tại.
    fun testInference() {

        val currentInterpreter =
            interpreter ?: return

        inputBuffer.clear()

        repeat(
            1 *
                    INPUT_CHANNELS *
                    inputSize *
                    inputSize
        ) {

            inputBuffer.putFloat(
                0f
            )
        }

        inputBuffer.rewind()

        outputBuffer.clear()

        val startTime =
            System.nanoTime()

        currentInterpreter.run(
            inputBuffer,
            outputBuffer
        )

        val inferenceTimeMs =
            (
                    System.nanoTime() -
                            startTime
                    ) /
                    1_000_000.0

        Log.d(
            TAG,
            "${config.id} smoke inference: " +
                    "%.2f ms".format(
                        inferenceTimeMs
                    )
        )
    }

    private fun loadModelFile(
        modelName: String
    ): MappedByteBuffer {

        val fileDescriptor =
            context.assets.openFd(
                modelName
            )

        FileInputStream(
            fileDescriptor.fileDescriptor
        ).use { inputStream ->

            return inputStream
                .channel
                .map(
                    java.nio.channels.FileChannel
                        .MapMode
                        .READ_ONLY,

                    fileDescriptor
                        .startOffset,

                    fileDescriptor
                        .declaredLength
                )
        }
    }

    fun close() {

        interpreter?.close()

        interpreter =
            null

        gpuDelegate?.close()

        gpuDelegate =
            null
    }
}
