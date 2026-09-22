// The engine: everything the app knows about music, and nothing about the
// device it is running on.
//
// This is a plain Kotlin library on purpose. It compiles without the Android
// SDK, which is not a tidiness exercise - it is what lets the same recommender,
// the same DSP and the same style learner run on a desktop build later without
// being copied and then quietly diverging. The compiler enforces it: an
// `import android.` anywhere in here stops being a design smell and starts
// being a build failure.
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Room's annotations and nothing else. room-common is an ordinary JVM jar
    // - the database, the compiler and the Android runtime all live in
    // room-runtime, which this module does not have and does not want. The
    // entity classes are here because the engine reads them on every call;
    // KSP still generates the database over in :app.
    //
    // api rather than implementation so the annotations stay visible to that
    // KSP run, which has to resolve them on classes it did not compile.
    api("androidx.room:room-common:2.6.1")
}
