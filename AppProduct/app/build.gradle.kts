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

    flavorDimensions += "dist"
    productFlavors {
        create("offline") {
            dimension = "dist"
            buildConfigField("boolean", "OFFLINE_BUILD", "true")
        }
        create("dev") {
            dimension = "dist"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "OFFLINE_BUILD", "false")
            proguardFile("proguard-dev.pro")
        }
    }

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

    testOptions {
        // Robolectric UI smoke tests on the JVM need merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Offline speech runtime (VAD, STT, TTS); ONNX Runtime is linked statically inside.
    // Fetched by tools/fetch_artifacts.py, pinned in artifacts.lock.json (not in git).
    implementation(files("../third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // Temporary translator for the dev flavor only (never in the offline APK).
    "devImplementation"(libs.mlkit.translate)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
