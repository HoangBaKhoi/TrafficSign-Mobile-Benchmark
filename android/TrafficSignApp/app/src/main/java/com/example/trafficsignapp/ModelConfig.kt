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

        // Thứ tự chạy benchmark 4 model.
        val ALL = listOf(
            N320,
            N640,
            S320,
            S640
        )

        // Model dùng cho camera realtime sau khi benchmark xong.
        val REALTIME_DEFAULT = N640
    }
}
