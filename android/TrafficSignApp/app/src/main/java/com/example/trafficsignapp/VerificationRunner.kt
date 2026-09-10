package com.example.trafficsignapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

data class VerificationExportResult(
    val imageCount: Int,
    val modelCount: Int,
    val modelSummaryPath: String,
    val detailPath: String,
    val detectionsPath: String,
    val durationMs: Double
)

private data class BenchmarkSample(
    val bestConfidence: Double,
    val hasDetection: Boolean,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val totalMs: Double,
    val cpuPercent: Double,
    val pssMb: Double,
    val rssMb: Double
)

private data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val hardware: String,
    val cpuCores: Int
) {
    fun fileTag(): String {
        return "${manufacturer}_${model}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }
}

class VerificationRunner(
    private val context: Context
) {

    companion object {
        private const val TAG = "VerificationRunner"
        private const val IMAGE_DIRECTORY = "benchmark_images"
        private const val WARMUP_RUNS = 5
        private const val DELEGATE_NAME = "CPU"
        private const val MODEL_COOLDOWN_MS = 2000L
    }

    fun run(): VerificationExportResult {

        val benchmarkStart = System.nanoTime()
        val deviceInfo = getDeviceInfo()

        val imageNames =
            context.assets
                .list(IMAGE_DIRECTORY)
                ?.filter { name ->
                    name.endsWith(".jpg", true) ||
                            name.endsWith(".jpeg", true) ||
                            name.endsWith(".png", true)
                }
                ?.sorted()
                ?: emptyList()

        if (imageNames.isEmpty()) {
            throw IllegalStateException(
                "Không tìm thấy ảnh trong assets/$IMAGE_DIRECTORY"
            )
        }

        Log.d(
            TAG,
            "Device=${deviceInfo.manufacturer} ${deviceInfo.model}, " +
                    "Android=${deviceInfo.androidVersion}, SDK=${deviceInfo.sdkInt}, " +
                    "hardware=${deviceInfo.hardware}, cpuCores=${deviceInfo.cpuCores}"
        )

        val summaryCsv = StringBuilder()
        val detailCsv = StringBuilder()
        val detectionsCsv = StringBuilder()

        val deviceHeader =
            "manufacturer,device_model,android_version,sdk_int,hardware,cpu_cores,"

        summaryCsv.append(
            deviceHeader +
                    "model,input_size,images,detection_rate_percent," +
                    "mean_conf_detected_only,mean_conf_all," +
                    "preprocess_mean_ms," +
                    "inference_mean_ms,inference_median_ms,inference_p95_ms," +
                    "total_mean_ms,total_median_ms,total_p95_ms,estimated_fps," +
                    "cpu_mean_percent,cpu_median_percent,cpu_p95_percent," +
                    "pss_mean_mb,pss_peak_mb,rss_mean_mb,rss_peak_mb," +
                    "model_size_mb,gpu_usage_percent,delegate\n"
        )

        detailCsv.append(
            deviceHeader +
                    "model,image,input_size,detections,best_class_id,best_label," +
                    "best_confidence,best_left,best_top,best_right,best_bottom," +
                    "preprocess_ms,inference_ms,total_ms,cpu_percent,pss_mb,rss_mb," +
                    "model_size_mb,gpu_usage_percent,delegate\n"
        )

        detectionsCsv.append(
            deviceHeader +
                    "model,image,input_size,detection_index,class_id,label,confidence," +
                    "left,top,right,bottom,preprocess_ms,inference_ms,total_ms," +
                    "cpu_percent,pss_mb,rss_mb,model_size_mb,gpu_usage_percent,delegate\n"
        )

        for ((modelIndex, config) in ModelConfig.ALL.withIndex()) {

            Log.d(
                TAG,
                "===== ${modelIndex + 1}/${ModelConfig.ALL.size}: ${config.id} ====="
            )

            System.gc()
            Thread.sleep(300)

            val detector =
                LiteRTDetector(
                    context = context,
                    config = config
                )

            val modelSizeMb = detector.getModelSizeMb()

            val warmupBitmap = loadBitmap(imageNames.first())

            repeat(WARMUP_RUNS) {
                detector.runOnBitmap(warmupBitmap)
                Log.d(
                    TAG,
                    "${config.id} warm-up ${it + 1}/$WARMUP_RUNS"
                )
            }

            warmupBitmap.recycle()

            val samples = mutableListOf<BenchmarkSample>()

            for ((imageIndex, imageName) in imageNames.withIndex()) {

                val bitmap = loadBitmap(imageName)

                val cpuBeforeMs = Process.getElapsedCpuTime()
                val wallBeforeNs = System.nanoTime()

                val result = detector.runOnBitmap(bitmap)

                val wallAfterNs = System.nanoTime()
                val cpuAfterMs = Process.getElapsedCpuTime()

                val wallElapsedMs =
                    (wallAfterNs - wallBeforeNs) / 1_000_000.0

                val cpuElapsedMs =
                    (cpuAfterMs - cpuBeforeMs).toDouble()

                val cpuPercent =
                    if (wallElapsedMs > 0.0) {
                        cpuElapsedMs / wallElapsedMs * 100.0
                    } else {
                        0.0
                    }

                val memoryInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memoryInfo)

                val pssMb = memoryInfo.totalPss / 1024.0
                val rssMb = readRssMb()

                val bestDetection =
                    result.detections.maxByOrNull {
                        it.confidence
                    }

                samples.add(
                    BenchmarkSample(
                        bestConfidence =
                            (bestDetection?.confidence ?: 0f).toDouble(),
                        hasDetection =
                            bestDetection != null,
                        preprocessMs =
                            result.preprocessMs,
                        inferenceMs =
                            result.inferenceMs,
                        totalMs =
                            result.totalMs,
                        cpuPercent =
                            cpuPercent,
                        pssMb =
                            pssMb,
                        rssMb =
                            rssMb
                    )
                )

                // Baseline hiện tại không dùng GPU Delegate.
                val gpuUsage = ""

                val deviceColumns =
                    listOf(
                        csvText(deviceInfo.manufacturer),
                        csvText(deviceInfo.model),
                        csvText(deviceInfo.androidVersion),
                        deviceInfo.sdkInt.toString(),
                        csvText(deviceInfo.hardware),
                        deviceInfo.cpuCores.toString()
                    )

                val detailRow =
                    deviceColumns +
                            listOf(
                                csvText(config.id),
                                csvText(imageName),
                                detector.getInputSize().toString(),
                                result.detections.size.toString(),
                                bestDetection?.classId?.toString() ?: "-1",
                                csvText(bestDetection?.label ?: ""),
                                format(bestDetection?.confidence ?: 0f),
                                format(bestDetection?.left ?: 0f),
                                format(bestDetection?.top ?: 0f),
                                format(bestDetection?.right ?: 0f),
                                format(bestDetection?.bottom ?: 0f),
                                format(result.preprocessMs),
                                format(result.inferenceMs),
                                format(result.totalMs),
                                format(cpuPercent),
                                format(pssMb),
                                format(rssMb),
                                format(modelSizeMb),
                                gpuUsage,
                                csvText(DELEGATE_NAME)
                            )

                detailCsv.append(detailRow.joinToString(","))
                detailCsv.append("\n")

                result.detections
                    .sortedByDescending { it.confidence }
                    .forEachIndexed { detectionIndex, detection ->

                        val row =
                            deviceColumns +
                                    listOf(
                                        csvText(config.id),
                                        csvText(imageName),
                                        detector.getInputSize().toString(),
                                        detectionIndex.toString(),
                                        detection.classId.toString(),
                                        csvText(detection.label),
                                        format(detection.confidence),
                                        format(detection.left),
                                        format(detection.top),
                                        format(detection.right),
                                        format(detection.bottom),
                                        format(result.preprocessMs),
                                        format(result.inferenceMs),
                                        format(result.totalMs),
                                        format(cpuPercent),
                                        format(pssMb),
                                        format(rssMb),
                                        format(modelSizeMb),
                                        gpuUsage,
                                        csvText(DELEGATE_NAME)
                                    )

                        detectionsCsv.append(row.joinToString(","))
                        detectionsCsv.append("\n")
                    }

                bitmap.recycle()

                Log.d(
                    TAG,
                    "${config.id} [${imageIndex + 1}/${imageNames.size}] " +
                            "infer=${"%.1f".format(result.inferenceMs)} ms, " +
                            "cpu=${"%.1f".format(cpuPercent)}%, " +
                            "pss=${"%.1f".format(pssMb)} MB"
                )
            }

            val detected = samples.filter { it.hasDetection }

            val detectionRate =
                if (samples.isNotEmpty()) {
                    detected.size * 100.0 / samples.size
                } else {
                    0.0
                }

            val preprocess = samples.map { it.preprocessMs }
            val inference = samples.map { it.inferenceMs }
            val total = samples.map { it.totalMs }
            val cpu = samples.map { it.cpuPercent }
            val pss = samples.map { it.pssMb }
            val rss = samples.map { it.rssMb }

            val totalMedian = median(total)
            val estimatedFps =
                if (totalMedian > 0.0) {
                    1000.0 / totalMedian
                } else {
                    0.0
                }

            val summaryRow =
                listOf(
                    csvText(deviceInfo.manufacturer),
                    csvText(deviceInfo.model),
                    csvText(deviceInfo.androidVersion),
                    deviceInfo.sdkInt.toString(),
                    csvText(deviceInfo.hardware),
                    deviceInfo.cpuCores.toString(),
                    csvText(config.id),
                    detector.getInputSize().toString(),
                    samples.size.toString(),
                    format(detectionRate),
                    format(mean(detected.map { it.bestConfidence })),
                    format(mean(samples.map { it.bestConfidence })),
                    format(mean(preprocess)),
                    format(mean(inference)),
                    format(median(inference)),
                    format(percentile(inference, 95.0)),
                    format(mean(total)),
                    format(totalMedian),
                    format(percentile(total, 95.0)),
                    format(estimatedFps),
                    format(mean(cpu)),
                    format(median(cpu)),
                    format(percentile(cpu, 95.0)),
                    format(mean(pss)),
                    format(pss.maxOrNull() ?: 0.0),
                    format(mean(rss)),
                    format(rss.maxOrNull() ?: 0.0),
                    format(modelSizeMb),
                    "",
                    csvText(DELEGATE_NAME)
                )

            summaryCsv.append(summaryRow.joinToString(","))
            summaryCsv.append("\n")

            detector.close()
            System.gc()

            if (modelIndex < ModelConfig.ALL.lastIndex) {
                Thread.sleep(MODEL_COOLDOWN_MS)
            }
        }

        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())

        val deviceTag = deviceInfo.fileTag()

        val summaryFile =
            "android_4model_100_summary_${deviceTag}_$timestamp.csv"

        val detailFile =
            "android_4model_100_detail_${deviceTag}_$timestamp.csv"

        val detectionsFile =
            "android_4model_100_detections_${deviceTag}_$timestamp.csv"

        val summaryPath =
            saveCsv(
                summaryFile,
                summaryCsv.toString()
            )

        val detailPath =
            saveCsv(
                detailFile,
                detailCsv.toString()
            )

        val detectionsPath =
            saveCsv(
                detectionsFile,
                detectionsCsv.toString()
            )

        val durationMs =
            (System.nanoTime() - benchmarkStart) / 1_000_000.0

        return VerificationExportResult(
            imageCount = imageNames.size,
            modelCount = ModelConfig.ALL.size,
            modelSummaryPath = summaryPath,
            detailPath = detailPath,
            detectionsPath = detectionsPath,
            durationMs = durationMs
        )
    }

    // Tự lấy metadata của điện thoại đang benchmark.
    private fun getDeviceInfo(): DeviceInfo {

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            hardware = Build.HARDWARE ?: "unknown",
            cpuCores =
                Runtime.getRuntime().availableProcessors()
        )
    }

    private fun loadBitmap(
        imageName: String
    ): Bitmap {

        val path =
            "$IMAGE_DIRECTORY/$imageName"

        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig =
                    Bitmap.Config.ARGB_8888
                inMutable =
                    true
            }

        return context.assets
            .open(path)
            .use { inputStream ->
                BitmapFactory.decodeStream(
                    inputStream,
                    null,
                    options
                )
            }
            ?: throw IllegalStateException(
                "Không đọc được ảnh: $path"
            )
    }

    private fun readRssMb(): Double {

        return try {

            val line =
                File("/proc/self/status")
                    .useLines { lines ->
                        lines.firstOrNull {
                            it.startsWith("VmRSS:")
                        }
                    }

            if (line == null) {
                0.0
            } else {
                val kb =
                    line.trim()
                        .split(Regex("\\s+"))[1]
                        .toDouble()

                kb / 1024.0
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Không đọc được RSS",
                e
            )

            0.0
        }
    }

    private fun mean(
        values: List<Double>
    ): Double {

        return if (values.isEmpty()) {
            0.0
        } else {
            values.average()
        }
    }

    private fun median(
        values: List<Double>
    ): Double {

        return percentile(
            values,
            50.0
        )
    }

    private fun percentile(
        values: List<Double>,
        percentile: Double
    ): Double {

        if (values.isEmpty()) {
            return 0.0
        }

        val sorted = values.sorted()

        if (sorted.size == 1) {
            return sorted[0]
        }

        val rank =
            percentile / 100.0 *
                    (sorted.size - 1)

        val lower =
            floor(rank).toInt()

        val upper =
            ceil(rank).toInt()

        if (lower == upper) {
            return sorted[lower]
        }

        val weight =
            rank - lower

        return sorted[lower] *
                (1.0 - weight) +
                sorted[upper] *
                weight
    }

    private fun format(
        value: Number
    ): String {

        return String.format(
            Locale.US,
            "%.4f",
            value.toDouble()
        )
    }

    private fun csvText(
        text: String
    ): String {

        return "\"" +
                text.replace(
                    "\"",
                    "\"\""
                ) +
                "\""
    }

    private fun saveCsv(
        fileName: String,
        content: String
    ): String {

        val finalContent =
            "\uFEFF$content"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            val resolver =
                context.contentResolver

            val values =
                ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        fileName
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        "text/csv"
                    )

                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/TrafficSignApp"
                    )

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1
                    )
                }

            val uri =
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )
                    ?: throw IllegalStateException(
                        "Không tạo được CSV"
                    )

            resolver
                .openOutputStream(uri)
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write(finalContent)
                }
                ?: throw IllegalStateException(
                    "Không ghi được CSV"
                )

            values.clear()

            values.put(
                MediaStore.MediaColumns.IS_PENDING,
                0
            )

            resolver.update(
                uri,
                values,
                null,
                null
            )

            return "Downloads/TrafficSignApp/$fileName"
        }

        val directory =
            File(
                context.getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS
                ),
                "TrafficSignApp"
            )

        directory.mkdirs()

        val file =
            File(
                directory,
                fileName
            )

        file.writeText(
            finalContent,
            Charsets.UTF_8
        )

        return file.absolutePath
    }
}
