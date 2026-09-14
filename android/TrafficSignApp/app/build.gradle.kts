plugins {
    // Plugin để build ứng dụng Android
    alias(libs.plugins.android.application)
}

android {
    // Namespace của ứng dụng
    namespace = "com.example.trafficsignapp"

    // SDK dùng để biên dịch project
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // ID của ứng dụng khi cài lên Android
        applicationId = "com.example.trafficsignapp"
        // Phiên bản Android thấp nhất app hỗ trợ
        minSdk = 23
        // Phiên bản Android app hướng tới
        targetSdk = 37
        // Thông tin version của app
        versionCode = 1
        versionName = "1.0"
        // Cấu hình cho instrumentation test
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Chưa bật tối ưu hóa cho bản release
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        // Cấu hình tương thích Java 11
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Hỗ trợ Activity bằng Kotlin extension
    implementation(libs.androidx.activity.ktx)
    // Hỗ trợ AppCompatActivity
    implementation(libs.androidx.appcompat)
    // Layout giao diện dạng ConstraintLayout
    implementation(libs.androidx.constraintlayout)
    // Kotlin extension cho Android core
    implementation(libs.androidx.core.ktx)
    // Material Components cho giao diện
    implementation(libs.material)
    // LiteRT runtime để chạy model .tflite trên Android.
    // Ghim cùng version 1.4.2 cho cả litert + litert-gpu + litert-gpu-api: litert-gpu/gpu-api
    // chưa có bản 2.x trên Maven, nên nếu để litert lên 2.x sẽ lệch API với GpuDelegate
    // (Interpreter.Options.addDelegate không nhận được Delegate từ litert-gpu cũ).
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // GPU Delegate cho LiteRT (dùng cho benchmark backend CPU/GPU/NNAPI)
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")

    // Phiên bản CameraX dùng chung cho các module bên dưới
    val cameraXVersion = "1.6.1"
    // API nền tảng của CameraX
    implementation("androidx.camera:camera-core:$cameraXVersion")
    // Backend Camera2 để truy cập camera phần cứng
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    // Gắn camera với lifecycle của Activity
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    // Cung cấp PreviewView để hiển thị camera
    implementation("androidx.camera:camera-view:$cameraXVersion")
    // Unit test
    testImplementation(libs.junit)
    // UI test bằng Espresso
    androidTestImplementation(libs.androidx.espresso.core)
    // Android instrumentation test
    androidTestImplementation(libs.androidx.junit)
}