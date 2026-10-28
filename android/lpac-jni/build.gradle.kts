plugins {
    // AGP 9+ compiles the Kotlin sources itself, as in :app.
    alias(libs.plugins.android.library)
}

// lpac's libeuicc (C) and OpenEUICC's JNI wrapper around it, vendored for the eSIM page.
// Origins, commits and what was changed: README.md beside this file.
android {
    namespace = "net.typeblog.lpac_jni"
    compileSdk = 37
    // AGP 9.4's default NDK, installed with `sdkmanager --install "ndk;28.2.13676358"`.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        // The R1 is arm64 and there is no other device.
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/lpac-jni.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
