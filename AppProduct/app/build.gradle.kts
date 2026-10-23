plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ABIs to package: -Pvtt.abis=arm64-v8a (phone) or x86_64 (emulator). 32-bit is not supported.
val vttAbis: List<String> = providers.gradleProperty("vtt.abis").get()
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

fun git(vararg args: String): String = try {
    providers.exec {
        commandLine(listOf("git", "-C", rootDir.absolutePath) + args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    ""
}

val gitSha: String = git("rev-parse", "--short=12", "HEAD").ifEmpty { "unknown" }
val gitDirty: Boolean = git("status", "--porcelain", "--", ".").isNotEmpty()

android {
    namespace = "vn.viettechtrans.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "vn.viettechtrans.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
    }

    // One build only, fully offline (2026-10-07: the dev flavor with ML Kit was removed, DR-09).

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded thesis builds: signed with the debug key (documented in DR-01).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*vttAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Model assets stay compressed in the APK (−35 MB, 2026-10-01: user asked for a smaller
    // app); they are inflated once per load, in the background, after the UI is shown.
    // The 8-bit MT graphs only shrink 7% but follow the same rule.

    packaging {
        jniLibs {
            // 32-bit ABIs are not supported; the sherpa AAR still carries a 32-bit libonnxruntime.so
            // that would clash with onnxruntime-android's. The 64-bit sherpa JNI links ORT statically.
            excludes += setOf("lib/x86/**", "lib/armeabi-v7a/**")
        }
    }

    testOptions {
        // Robolectric UI smoke tests on the JVM need merged resources.
        unitTests.isIncludeAndroidResources = true
        // -Pvtt.rulesEvidence=<dir>: TextRulesCalibrationTest also writes its Validation rates there (DR-07 §16).
        unitTests.all { test ->
            (project.findProperty("vtt.rulesEvidence") as String?)?.let { test.systemProperty("vtt.rulesEvidence", file(it).absolutePath) }
        }
    }
}

dependencies {
    // Offline speech runtime (VAD, STT, TTS); ONNX Runtime is linked statically inside.
    // Fetched by tools/fetch_artifacts.py, pinned in artifacts.lock.json (not in git).
    implementation(files("../third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // Main-Core MT (Phase 03 Student, packaged by tools/mt/package_mt.py into assets/mt/).
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Desktop build of the same ONNX Runtime: runs the packaged MT graph in JVM tests (MarianGoldenTest).
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
