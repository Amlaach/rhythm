plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// The release keystore is supplied by CI through environment variables.
// When they are absent the release build simply falls back to the debug key,
// so a plain `gradlew assembleRelease` still produces an installable APK.
val ciKeystorePath: String? = System.getenv("RHYTHM_KEYSTORE_FILE")

android {
    namespace = "com.elchanan.rhythm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elchanan.rhythm"
        // Android 5.0. This is the real floor, not a preference: Compose and
        // Media3 both stop at 21, so nothing older can run this app whatever we
        // do. Everything above that line is reachable, and old phones are
        // exactly where a local player with no streaming still earns its place.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }

        // TensorFlow Lite ships a native library per architecture, and carrying
        // all four roughly doubles the download for nothing: arm64 covers every
        // phone sold in years, and x86_64 is what the emulator runs on. The two
        // that are left out are 32-bit builds this app's minimum already makes
        // rare.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (ciKeystorePath != null) {
            create("release") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("RHYTHM_STORE_PASSWORD")
                keyAlias = System.getenv("RHYTHM_KEY_ALIAS")
                keyPassword = System.getenv("RHYTHM_KEY_PASSWORD")
            }
        }
    }

    // Two builds of the same app. The tagging model is four megabytes of
    // weights plus a native runtime, and that is a real cost for someone on a
    // slow connection or a full phone - so it is a separate download rather
    // than something everybody carries. The lite build keeps every measured
    // feature; what it does not have is the model that names what it hears.
    flavorDimensions += "engine"
    productFlavors {
        create("lite") {
            dimension = "engine"
            versionNameSuffix = "-lite"
        }
        create("full") {
            dimension = "engine"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    androidResources {
        // A .tflite must reach the device byte for byte. Compressing it in the
        // apk means it cannot be memory mapped, and the interpreter refuses to
        // load it.
        noCompress += "tflite"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.guava:guava:32.1.3-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // On-device audio tagging, in the full build only.
    //
    // The bare interpreter rather than the audio task library: that one
    // declares minSdk 23 and would have taken Android 5 back off the table,
    // which is a live requirement here. Nothing is lost by dropping it - this
    // model takes a raw waveform, so the metadata handling the task library
    // exists to provide is not needed, and resampling is already in the
    // analyser.
    "fullImplementation"("org.tensorflow:tensorflow-lite:2.14.0")
}
