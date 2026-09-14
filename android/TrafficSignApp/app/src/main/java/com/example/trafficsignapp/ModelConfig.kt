package com.example.trafficsignapp

// Cấu hình một model LiteRT/TFLite dùng trong app và benchmark.
data class ModelConfig(
    val id: String,
    val displayName: String,
    val assetFile: String,
    val expectedInputSize: Int
) {

    companion object {

        val N320 = ModelConfig(
            id = "yolo11n_320",
            displayName = "YOLO11n 320",
            assetFile = "models/yolo11n_320.tflite",
            expectedInputSize = 320
        )

        val N640 = ModelConfig(
            id = "yolo11n_640",
            displayName = "YOLO11n 640",
            assetFile = "models/yolo11n_640.tflite",
            expectedInputSize = 640
        )

        val S320 = ModelConfig(
            id = "yolo11s_320",
            displayName = "YOLO11s 320",
            assetFile = "models/yolo11s_320.tflite",
            expectedInputSize = 320
        )

        val S640 = ModelConfig(
            id = "yolo11s_640",
            displayName = "YOLO11s 640",
            assetFile = "models/yolo11s_640.tflite",
            expectedInputSize = 640
        )

        // Bản Quantization FP16 của 2 model đại diện (xem AI/notebooks/08_quantization_export.ipynb).
        val N640_FP16 = ModelConfig(
            id = "yolo11n_640_fp16",
            displayName = "YOLO11n 640 FP16",
            assetFile = "models/yolo11n_640_fp16.tflite",
            expectedInputSize = 640
        )

        val S640_FP16 = ModelConfig(
            id = "yolo11s_640_fp16",
            displayName = "YOLO11s 640 FP16",
            assetFile = "models/yolo11s_640_fp16.tflite",
            expectedInputSize = 640
        )

        // Bản Quantization INT8 (dynamic range: trọng số INT8, input/output vẫn FLOAT32) của 2 model đại diện.
        val N640_INT8 = ModelConfig(
            id = "yolo11n_640_int8",
            displayName = "YOLO11n 640 INT8",
            assetFile = "models/yolo11n_640_int8.tflite",
            expectedInputSize = 640
        )

        val S640_INT8 = ModelConfig(
            id = "yolo11s_640_int8",
            displayName = "YOLO11s 640 INT8",
            assetFile = "models/yolo11s_640_int8.tflite",
            expectedInputSize = 640
        )

        // Thứ tự chạy benchmark 4 model FP32 (baseline gốc, giữ nguyên để so sánh được với dữ liệu cũ).
        val ALL = listOf(
            N320,
            N640,
            S320,
            S640
        )

        // Các model dùng cho vòng benchmark Quantization x Delegate (Phase 2).
        // Chỉ chọn 640 vì đây là input size cho accuracy tốt nhất theo kết luận notebook 05.
        val QUANTIZATION_SWEEP = listOf(
            N640,
            N640_FP16,
            N640_INT8,
            S640,
            S640_FP16,
            S640_INT8
        )

        // Model dùng cho camera realtime sau khi benchmark xong.
        val REALTIME_DEFAULT = N640
    }
}
