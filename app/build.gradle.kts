import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.localvoice.assistant"
    compileSdk = 35 // Required by AndroidX 1.15/Compose tooling; runtime behavior stays pinned to targetSdk 30.

    defaultConfig {
        applicationId = "com.localvoice.assistant"
        minSdk = 28
        targetSdk = 30
        versionCode = 2
        versionName = "1.1"

        ndk {
            // Galaxy A50 (Exynos 9610) is arm64-v8a only; also strips x86/armeabi-v7a from sherpa-onnx AAR.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",      // Avoids libc++_shared.so ABI/duplicate conflicts with sherpa-onnx AAR.
                    "-DCMAKE_BUILD_TYPE=Release"    // Keep llama.cpp at -O3 even in debug builds so CPU inference is testable.
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Unified offline VAD, streaming Zipformer STT, and Piper VITS TTS via ONNX Runtime.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Pins compatible Jetpack Compose artifact versions.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    // Core Jetpack Compose layout, text, and state primitives.
    implementation("androidx.compose.ui:ui")
    // Material 3 components for the single-screen UI.
    implementation("androidx.compose.material3:material3")
    // Hosts Compose in ComponentActivity and provides SAF + runtime permission launchers.
    implementation("androidx.activity:activity-compose:1.10.1")
    // Collects foreground-service StateFlow streams safely with lifecycle awareness.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Runs STT, LLM, and TTS on separate coroutine dispatchers off the main thread.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}