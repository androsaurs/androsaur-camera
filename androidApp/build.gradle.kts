plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.hram.androsaur.camera"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.hram.androsaur.camera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
        // Phones only: ONNX Runtime and MediaPipe carry a native library per architecture, and x86 doubles the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Face-detector experiments (FaceLab): ML Kit (bundled model) and MediaPipe BlazeFace.
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    // Face recognition (FaceId): the SFace model runs in ONNX Runtime.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
