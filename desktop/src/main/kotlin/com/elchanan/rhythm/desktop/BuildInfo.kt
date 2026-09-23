package com.elchanan.rhythm.desktop

import java.util.Properties

/**
 * Which build this is, for the about page: the phone shows its version, its
 * build number and its commit, and so does this. Written into the jar by the
 * build (desktop/build.gradle.kts, buildInfo); a build made on a developer's
 * machine has no build number, and says so.
 */
internal object BuildInfo {
    private val props: Properties by lazy {
        Properties().apply {
            runCatching {
                BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
            }
        }
    }

    val version: String get() = props.getProperty("version").orEmpty().ifBlank { "1.1-dev" }

    /** The CI run number, the last part of the version; null for a local build. */
    val build: String? get() = props.getProperty("build")?.takeIf { it.isNotBlank() }

    val commit: String? get() = props.getProperty("commit")?.takeIf { it.isNotBlank() }?.take(7)
}
