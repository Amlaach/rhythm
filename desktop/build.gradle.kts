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

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // Reads tags out of the file itself. MediaStore did this on the phone;
    // on a desktop there is no index to ask, only the files.
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Decoding. These register themselves with javax.sound through its
    // Service Provider Interface, so nothing below ever names a format: a
    // file goes through AudioSystem and the right one picks it up. All four
    // are pure Java, which is what keeps the installer a single file with
    // nothing for the user to install first - VLCJ would have meant telling
    // people to go and fetch VLC.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    implementation("org.jflac:jflac-codec:1.5.2")
    implementation("net.sourceforge.jaadec:jaad:0.8.6")

    // The library, ratings and analysis rows. Room is Android only, so the
    // desktop keeps the same data in plain SQLite through JDBC - the entity
    // classes it reads and writes are the ones in :engine, unchanged.
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
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
            packageVersion = project.findProperty("rhythmVersion")?.toString() ?: "1.0.0"
            vendor = "Rhythm"
            description = "נגן מוזיקה אופליין עם מנוע המלצות מקומי"

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
            }
        }
    }
}
