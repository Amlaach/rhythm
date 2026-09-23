plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// The release keystore is supplied by CI through environment variables.
// When they are absent the release build simply falls back to the debug key,
// so a plain `gradlew assembleRelease` still produces an installable APK.
val ciKeystorePath: String? = System.getenv("RHYTHM_KEYSTORE_FILE")

// The version has to change between builds or it cannot answer the only
// question ever asked of it: is this the one with the fix in it. It was
// pinned at 1 / "1.0.0", so every APK ever produced claimed to be the same
// release and there was no way to tell an install from last month from one
// built five minutes ago.
//
// CI supplies a run number that only ever goes up, which is exactly what a
// version code is meant to be, and the commit it was built from. A build made
// on someone's own machine has neither and says so rather than borrowing a
// number it has no right to.
//
// Deliberately no build timestamp: it would differ on every configuration and
// so invalidate the build cache this project turns on, and the commit answers
// the same question more precisely anyway.
val ciBuildNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciCommit: String = System.getenv("GITHUB_SHA").orEmpty().take(7)

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
        versionCode = ciBuildNumber ?: 1
        versionName = if (ciBuildNumber != null) "1.1.$ciBuildNumber" else "1.1.0-dev"
        // Carried into the app so the about screen can name the exact commit
        // rather than a version number that only says which day it was.
        buildConfigField("String", "GIT_SHA", "\"$ciCommit\"")
        vectorDrawables { useSupportLibrary = true }

        // TensorFlow Lite ships a native library per architecture. 32-bit ARM
        // is in, because leaving it out made the app refuse to install at all
        // on exactly the phones minSdk 21 is there for: older and budget
        // devices, and many newer Android Go ones, run a 32-bit system even
        // on 64-bit chips, and Android will not install an apk whose native
        // code has no build for the running system. It costs about a
        // megabyte and a half. 32-bit x86 stays out: no phone runs it.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
        // Java 8 library methods - Map.getOrDefault, putIfAbsent, merge and
        // the rest - only exist on the device from Android 7. Kotlin calls
        // them happily and lint is switched off for release builds, so on
        // Android 5 and 6 they compiled and then crashed. Desugaring supplies
        // them to older systems at build time.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        // No -opt-in for media3's UnstableApi here.
        //
        // That flag takes a Kotlin @RequiresOptIn marker, and media3's is
        // androidx's, which the Kotlin compiler does not recognise - it
        // answered "not an opt-in requirement marker" on every build and did
        // nothing else. The opt-in is declared where it is actually used,
        // with the annotation that means something for an androidx marker.
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation("junit:junit:4.13.2")
    // The music engine. Pure Kotlin, no Android - see engine/build.gradle.kts.
    implementation(project(":engine"))

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

    // Carries @OptIn for androidx's own opt-in markers, which is how the
    // media3 unstable APIs below are accepted. It arrives with media3 anyway;
    // naming it says why it is on the classpath.
    implementation("androidx.annotation:annotation-experimental:1.4.1")
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

    // On-device audio tagging.
    //
    // The bare interpreter rather than the audio task library: that one
    // declares minSdk 23 and would have taken Android 5 back off the table,
    // which is a live requirement here. Nothing is lost by dropping it - this
    // model takes a raw waveform, so the metadata handling the task library
    // exists to provide is not needed, and resampling is already in the
    // analyser.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
