// The Windows build.
//
// Depends on :engine and nothing from :app. That is the whole point of the
// split: the recommender, the DSP and the style learner that run here are the
// same compiled classes that run on the phone, not a copy that will drift.
//
// Compose Multiplatform 1.6.11 rather than something newer, because 1.6.x is
// the last line that supports Kotlin 1.9. Moving to 1.7+ would mean taking the
// whole project to Kotlin 2.0, which is a change to the Android app as well,
// and there is no reason to couple those two things together.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

// The version tools/models/to_onnx.py checked the converted models with.
val ONNX_RUNTIME = "1.26.0"

// The theme is not copied here, it is compiled here. Color.kt, Type.kt and
// Theme.kt are pure Compose with no Android in them at all, so the desktop
// module builds the very same files the phone does rather than a second set
// that would start agreeing and end up drifting. The two builds cannot look
// different because there is only one description of how they look.
//
// The proper home for this is a Compose Multiplatform module both depend on,
// which needs the project on KMP; pointing a source directory at them says
// the same thing today.
kotlin.sourceSets["main"].kotlin.srcDir("../app/src/main/java/com/elchanan/rhythm/ui/theme")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

val onnxRuntime by configurations.creating

val onnxRuntimeTrimmed = tasks.register<Jar>("onnxRuntimeTrimmed") {
    archiveFileName.set("onnxruntime-$ONNX_RUNTIME-trimmed.jar")
    destinationDirectory.set(layout.buildDirectory.dir("onnxruntime"))
    from({ onnxRuntime.map { zipTree(it) } }) {
        // macOS (with its debug symbols, 90 MB unpacked) and ARM Linux: never
        // shipped, never run. Windows is what the installer is for, and
        // x86-64 Linux is what CI and the development sandbox run.
        exclude("ai/onnxruntime/native/osx-*/**")
        exclude("ai/onnxruntime/native/linux-aarch64/**")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    onnxRuntime("com.microsoft.onnxruntime:onnxruntime:$ONNX_RUNTIME")
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // Reads tags out of the file itself. MediaStore did this on the phone;
    // on a desktop there is no index to ask, only the files.
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Decoding. Every one of these registers itself with javax.sound through
    // its Service Provider Interface, so nothing in the player ever names a
    // format: a file goes through AudioSystem and whichever provider
    // recognises it picks it up. Adding a format is adding a line here.
    //
    // FFSampledSP is the one that covers m4a, and with it aac, wma and the
    // rest of what FFmpeg reads. It carries its own FFmpeg build as a native
    // library per platform - twelve megabytes for all six, of which Windows
    // x86_64 is the one that matters here. Bundled rather than called: VLCJ
    // would decode as much and decode it by calling a copy of VLC the user
    // has to go and fetch first, which turns a single installer into an
    // errand.
    //
    // The pure Java three stay underneath it. They are a few hundred
    // kilobytes and they mean mp3, the format most of a library is in, still
    // plays if the native fails to load on some machine - a provider that
    // cannot load declines the file and the next one is asked.
    implementation("com.tagtraum:ffsampledsp-complete:0.9.56")
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    implementation("org.jflac:jflac-codec:1.5.2")

    // The play, next and previous keys on a keyboard. Android handed those
    // over with a MediaSession; Windows has no equivalent a JVM can reach, so
    // they are claimed through the Win32 API directly. Unused on any other
    // system, where the listener simply never starts.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // The phone's models - YAMNet, Discogs-EffNet and its mood heads - in ONNX
    // form (tools/models/to_onnx.py), run here by ONNX Runtime because
    // TensorFlow Lite has no desktop Java build. The published jar carries the
    // native library for five platforms, 42 MB of which Windows is 15; the
    // installer gets a copy with only Windows and Linux in it (Linux for the
    // build machines), see onnxRuntimeTrimmed below.
    implementation(files(onnxRuntimeTrimmed))

    // The library, ratings and analysis rows. Room is Android only, so the
    // desktop keeps the same data in plain SQLite through JDBC - the entity
    // classes it reads and writes are the ones in :engine, unchanged.
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.elchanan.rhythm.desktop.MainKt"

        nativeDistributions {
            // Msi is the graphical wizard: welcome page, directory chooser,
            // Start menu entry, an uninstaller and an Add/Remove Programs
            // record. jpackage builds it through WiX; Exe would be a bare
            // self-extractor with no wizard at all.
            targetFormats(TargetFormat.Msi)
            packageName = "Rhythm"

            // The bundled runtime is built by jlink, which ships only the
            // modules it is told about. Nothing here is detected: SQLite
            // reaches for java.sql, jaudiotagger for java.logging, the
            // decoders and the file chooser for java.desktop, and a missing
            // one is not a build error - it is an install that works and
            // then throws ClassNotFoundException the first time someone
            // presses scan.
            //
            // Everything, rather than a list. A list is a thing to get wrong
            // once and find out about from a user, and the difference is
            // some tens of megabytes in a download people do once. It can be
            // trimmed later, against a build that is known to run.
            includeAllModules = true
            packageVersion = project.findProperty("rhythmVersion")?.toString() ?: "1.1.0"
            vendor = "Rhythm"
            // Latin on purpose, and it has to stay that way. Compose writes
            // jpackage's arguments to a file as UTF-8; jpackage on JDK 17
            // reads that file in the platform's default charset, which on
            // Windows is still windows-1252 because UTF-8 only became the
            // default in JDK 18. The first Hebrew letter is D7 90 in UTF-8
            // and 0x90 is one of the bytes windows-1252 does not map, so the
            // decoder threw on it and packaging failed with nothing but
            // "Input length = 1" in a log file nobody was reading.
            //
            // This string is the Comments field in Add/Remove Programs and
            // nothing else - no part of the wizard or the app shows it, and
            // the app itself is Hebrew throughout.
            description = "Offline music player with a local recommendation engine"

            windows {
                // A stable upgrade UUID is what makes the next MSI replace
                // this install rather than sit beside it in Add/Remove
                // Programs. It must never change once anything has shipped.
                upgradeUuid = "8f3b1c42-5d6e-4a7f-9b2c-1e0d7a4f6c33"
                menu = true
                menuGroup = "Rhythm"
                shortcut = true
                // Lets the user choose where it goes, which is the page
                // people expect to see in an installer.
                dirChooser = true
                perUserInstall = true
                // The launcher icon the phone already uses, wrapped as an
                // .ico rather than redrawn, because the two builds should
                // not look like two different apps.
                iconFile.set(project.file("icons/rhythm.ico"))
            }
        }
    }
}
