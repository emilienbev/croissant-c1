plugins {
    // AGP 9+ compiles the Kotlin sources itself, as in :app.
    alias(libs.plugins.android.library)
}

// The TFLite Micro audio frontend (C, from TensorFlow), vendored from pymicro-features 2.0.2
// with a JNI wrapper, for the wake word. Origin and what was changed: README.md beside this file.
android {
    namespace = "com.bleural.croissant.microfrontend"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            // Pinned, or AGP installs its own default CMake on the first build.
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
