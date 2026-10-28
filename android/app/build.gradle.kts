plugins {
    // AGP 9+ has built-in Kotlin support; the org.jetbrains.kotlin.android
    // plugin is not just unnecessary now, it is a hard error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The one version of the app, the server and the installer: VERSION at the repo root, set
// by scripts/bump-version.sh. versionName is X.Y.Z and versionCode X*10000 + Y*100 + Z
// (0.1.0 is 100, 1.2.3 is 10203), so minor and patch stay below 100.
val croissantVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../VERSION"))
    .asText.get().trim()
val croissantVersionCode = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d?)\.(0|[1-9]\d?)""")
    .matchEntire(croissantVersion)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
    ?: throw GradleException("VERSION is '$croissantVersion', not X.Y.Z with minor and patch below 100")

// Release signing. With CROISSANT_KEYSTORE (an absolute path), CROISSANT_KEYSTORE_PASSWORD,
// CROISSANT_KEY_ALIAS and CROISSANT_KEY_PASSWORD all set, as Gradle properties (-P or
// ~/.gradle/gradle.properties) or environment variables, release is signed with that key:
// the published releases are (.github/workflows/release.yml). With none of them it is
// signed with this machine's debug key, as every build from source always was. Some but
// not all is an error rather than a quiet debug-key build.
val releaseKeyNames = listOf(
    "CROISSANT_KEYSTORE", "CROISSANT_KEYSTORE_PASSWORD", "CROISSANT_KEY_ALIAS", "CROISSANT_KEY_PASSWORD",
)
val releaseKey: Map<String, String>? = releaseKeyNames
    .associateWith { providers.gradleProperty(it).orElse(providers.environmentVariable(it)).orNull.orEmpty() }
    .let { key ->
        val missing = key.filterValues { it.isBlank() }.keys
        when {
            missing.size == key.size -> null
            missing.isEmpty() -> key
            else -> throw GradleException("release signing: ${missing.joinToString()} not set (set all four or none)")
        }
    }

android {
    namespace = "com.bleural.croissant"
    compileSdk = 37

    defaultConfig {
        // Set in gradle.properties (`croissant.applicationId`); the namespace above does not follow it.
        applicationId = providers.gradleProperty("croissant.applicationId").get()
        // The R1 runs LineageOS 20 / SDK 33. minSdk matches it exactly; there is
        // no other device to support, so there is nothing to gain from going lower.
        minSdk = 33
        targetSdk = 33
        versionCode = croissantVersionCode
        versionName = croissantVersion
        // Where releases come from, `owner/repo` on GitHub: `croissant.updateRepo` in gradle.properties.
        buildConfigField(
            "String", "UPDATE_REPO",
            "\"${providers.gradleProperty("croissant.updateRepo").get()}\"",
        )
        // The R1 is arm64. The native dependencies (lpac-jni, zxing-cpp, QuickJS) ship
        // four ABIs; without this the APK would carry three it can never run.
        ndk { abiFilters += "arm64-v8a" }
    }

    if (releaseKey != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKey.getValue("CROISSANT_KEYSTORE"))
            storePassword = releaseKey.getValue("CROISSANT_KEYSTORE_PASSWORD")
            keyAlias = releaseKey.getValue("CROISSANT_KEY_ALIAS")
            keyPassword = releaseKey.getValue("CROISSANT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The release key when it is given (above), else the debug key. Either way an
            // install only takes updates signed with the key it was installed with: the app is
            // Device Owner, and replacing it with a package signed by another key needs an
            // uninstall, which unbinds the owner. There is no store build.
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // atelier/AtelierService.kt's binder, IAtelier and IAtelierHost.
        aidl = true
    }
    testOptions {
        // The room and the elastic are plain classes and run on the Mac, but a couple of
        // them reach one Android call: SystemClock.uptimeMillis, which the stub android.jar
        // throws on. Returning the default (0) is what the tests that touch it want anyway,
        // a clock that stands still being the harshest case for anything timed.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":lpac-jni"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Reads the eSIM QR code off a CameraX frame; the fixed-focus lens needs a decoder
    // that copes with blur, which zxing-cpp does far better than zxing's Java reader.
    implementation(libs.zxingcpp)
    // Writes the Send code. zxing-cpp above is a reader only, and the encoder is pure
    // Java with no native side, so this costs an ABI-independent 500 kB and no more.
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    // Atelier's apps: QuickJS runs each one's app.js (atelier/AppRuntime.kt). About 0.9 MB
    // of arm64 native library.
    implementation(libs.quickjs)
    // The Translator's local mode: sherpa-onnx (Parakeet,
    // onnxruntime 1.28.2 inside) and Foxlet (Bergamot). Neither is on Maven Central;
    // android/libs/fetch.sh fetches both, hash-checked.
    implementation(files("../libs/sherpa-onnx-1.13.8.aar"))
    implementation(files("../libs/foxlet-v0.5.0.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    // JVM tests of the data layer: the real org.json, since the SDK stub throws.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// The JVM tests cannot load QuickJS's Android library: they run its desktop build instead,
// which carries the Mac's and Linux's natives.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
