package com.elchanan.rhythm.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.elchanan.rhythm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from inside the app, for the phones that have the internet.
 *
 * The app is made for people many of whom have no internet at all, or a
 * filtered one, and for them nothing here may show: no setting, no message,
 * no error. So everything is silent until a check has actually reached the
 * server once. No connection, a filter that blocks the address, a filter
 * that answers with a page of its own instead of the file - each ends the
 * same way, quietly, and the app looks exactly as it did before updates
 * existed.
 *
 * Nothing about the phone or the library is sent. A check reads one small
 * file, update.json, that CI publishes with every release on GitHub; the
 * APK it names is downloaded, checked against the SHA-256 in that file, and
 * handed to Android's own installer - which also refuses anything not
 * signed with the key the installed app was signed with.
 */
object Updater {

    /** Where CI publishes: the newest release's update.json. */
    private const val MANIFEST =
        "https://github.com/a0527198150-del/rhythm/releases/latest/download/update.json"

    /**
     * @param availableAt when the app may offer it, in epoch milliseconds.
     *   CI sets it two days after the release is published, so the owner's
     *   own download page gets the first two days; 0 from a release made
     *   before there was such a thing, which means at once.
     */
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val availableAt: Long = 0L
    )

    /** Whatever the installer says after the confirmation, for the screen that started it. */
    val installResult = MutableStateFlow<String?>(null)

    /**
     * A working connection to the internet, as the system sees it. Without
     * one nothing is tried at all - not even a request that would fail.
     */
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }.getOrDefault(false)

    /**
     * The releases update.json offers - the newest and the few before it,
     * each with the time it may be offered - or null when it could not be
     * read: offline, blocked, or anything else. Null says nothing about
     * whether one exists.
     */
    suspend fun latest(context: Context): List<Release>? = withContext(Dispatchers.IO) {
        if (!online(context)) return@withContext null
        runCatching {
            val json = JSONObject(get(MANIFEST, limit = 64 * 1024).toString(Charsets.UTF_8))
            val all = ArrayList<Release>()
            releaseOf(json)?.let { all.add(it) }
            json.optJSONArray("earlier")?.let { list ->
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { releaseOf(it) }?.let { all.add(it) }
            }
            all.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun releaseOf(json: JSONObject): Release? = runCatching {
        Release(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            url = json.getString("url"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.optLong("size", -1L),
            availableAt = json.optLong("availableAt", 0L)
        ).takeIf { it.url.startsWith("https://") && it.sha256.length == 64 }
    }.getOrNull()

    /**
     * What to offer now: the newest release that is newer than this app and
     * whose time has come. Merged again within the two days, the one before
     * is offered meanwhile rather than nothing.
     */
    fun toOffer(releases: List<Release>, now: Long = System.currentTimeMillis()): Release? =
        releases.filter { isNewer(it) && isDue(it, now) }.maxByOrNull { it.versionCode }

    fun isNewer(release: Release): Boolean = release.versionCode > BuildConfig.VERSION_CODE

    /** Whether the time CI set for offering it has come. */
    fun isDue(release: Release, now: Long = System.currentTimeMillis()): Boolean = now >= release.availableAt

    fun encodeAll(releases: List<Release>): String =
        org.json.JSONArray().apply { releases.forEach { put(JSONObject(encode(it))) } }.toString()

    fun decodeAll(text: String): List<Release> = runCatching {
        if (text.isBlank()) return emptyList()
        // One object, as the first version of this stored it, or a list.
        if (text.trimStart().startsWith("{")) return listOfNotNull(decode(text))
        val list = org.json.JSONArray(text)
        (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.toString()?.let { decode(it) }
        }
    }.getOrDefault(emptyList())

    fun encode(release: Release): String = JSONObject()
        .put("versionCode", release.versionCode)
        .put("versionName", release.versionName)
        .put("url", release.url)
        .put("sha256", release.sha256)
        .put("size", release.size)
        .put("availableAt", release.availableAt)
        .toString()

    fun decode(text: String): Release? = runCatching {
        val json = JSONObject(text)
        Release(json.getInt("versionCode"), json.getString("versionName"), json.getString("url"),
            json.getString("sha256"), json.optLong("size", -1L), json.optLong("availableAt", 0L))
    }.getOrNull()

    /**
     * Downloads the APK into the app's own cache, reporting progress from 0
     * to 1, and returns it only when its SHA-256 is the one the release
     * named: a filter's block page, a cut connection or a changed file never
     * reach the installer.
     */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "rhythm-${release.versionCode}.apk")
            runCatching {
                val connection = open(release.url)
                try {
                    // Read from the header: contentLengthLong is Android 7 and later.
                    val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: release.size
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hex != release.sha256) {
                        file.delete()
                        null
                    } else {
                        file
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse {
                file.delete()
                null
            }
        }

    /**
     * Hands the APK to the system installer, which asks the listener to
     * confirm. Android refuses it if it is not signed as this app is.
     */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("rhythm.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            // Mutable: the installer writes its status into the extras.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, id, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        }
        true
    }.getOrDefault(false)

    /** Whether Android will let this app install one - asked for from Android 8 on. */
    fun mayInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the listener allows it. */
    fun allowInstallIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Rhythm/${BuildConfig.VERSION_NAME}")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP ${connection.responseCode}")
        }
        return connection
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) error("too large")
                }
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** What the system installer says about an update session. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            // Success replaces this process; there is nobody left to tell.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installResult.value = "העדכון בוטל"
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Updater.installResult.value = "העדכון לא מתאים לגרסה המותקנת"
            else -> Updater.installResult.value = "ההתקנה נכשלה"
        }
    }
}
