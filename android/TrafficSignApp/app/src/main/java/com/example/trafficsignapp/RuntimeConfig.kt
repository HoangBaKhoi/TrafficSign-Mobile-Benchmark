package com.example.trafficsignapp

// Backend dùng để chạy Interpreter: CPU thuần, GPU Delegate hoặc NNAPI (AI accelerator nếu thiết bị hỗ trợ).
enum class DelegateType {
    CPU,
    GPU,
    NNAPI
}

// Cấu hình runtime cho một lần benchmark: delegate + số luồng CPU (chỉ có ý nghĩa khi delegate = CPU).
data class RuntimeConfig(
    val delegate: DelegateType,
    val numThreads: Int = 1
) {

    // Nhãn ghi vào cột "delegate" của CSV benchmark.
    val label: String
        get() = when (delegate) {
            DelegateType.CPU -> "CPU-${numThreads}T"
            DelegateType.GPU -> "GPU"
            DelegateType.NNAPI -> "NNAPI"
        }

    companion object {

        val CPU_1_THREAD = RuntimeConfig(delegate = DelegateType.CPU, numThreads = 1)
        val CPU_4_THREAD = RuntimeConfig(delegate = DelegateType.CPU, numThreads = 4)
        val GPU = RuntimeConfig(delegate = DelegateType.GPU)
        val NNAPI = RuntimeConfig(delegate = DelegateType.NNAPI)

        /*
         * Model "_int8" ở đây là dynamic-range quantization (trọng số INT8, input/output vẫn
         * FLOAT32) — GPU Delegate không có kernel ổn định cho dạng trọng số này trên nhiều
         * thiết bị (dễ fallback CPU âm thầm hoặc lỗi), nên bỏ GPU khỏi tổ hợp benchmark cho
         * model INT8 để tránh số liệu gây hiểu nhầm.
         * NNAPI được giữ cho mọi loại model vì đây chính là hướng "AI accelerator" phù hợp
         * với model đã quantize theo tài liệu tham khảo trong đề cương (Benhamida et al., 2020).
         */
        fun compatibleWith(config: ModelConfig): List<RuntimeConfig> {

            val isInt8 = config.id.endsWith("_int8")

            return if (isInt8) {
                listOf(CPU_1_THREAD, CPU_4_THREAD, NNAPI)
            } else {
                listOf(CPU_1_THREAD, CPU_4_THREAD, GPU, NNAPI)
            }
        }
    }
}
