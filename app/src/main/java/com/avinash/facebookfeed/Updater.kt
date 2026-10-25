package com.avinash.facebookfeed

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manual update from GitHub Releases, started with key 9. Never runs by itself.
 *
 * Asks GitHub for the latest release (drafts and prereleases never count), and if its tag is newer than this build,
 * downloads family-feed.apk and family-feed.apk.sha256 into private storage, checks the checksum, the package name,
 * the version and that the APK is signed with the same certificate as the running app, and hands it to Android's
 * PackageInstaller. Android shows its own confirmation screen when it requires one, and replaces the app.
 */
object Updater {

    interface Listener {
        fun onUpdateStatus(message: String, done: Boolean)
    }

    var listener: Listener? = null

    /** Debug builds only: a release JSON to use instead of GitHub's, for testing (see MainActivity's update_api extra). */
    var apiOverride: String? = null

    /** A confirmation screen from the system installer is (about to be) on top: don't close the app on onStop. */
    @Volatile var awaitingUserAction = false

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread { run(app) }.start()
    }

    private fun run(context: Context) {
        try {
            status(context.getString(R.string.update_checking))
            val release = latestRelease()
            val current = currentVersionCode(context)
            if (release.versionCode <= current) {
                finish(context.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                finish(context.getString(R.string.update_permission))
                openInstallPermissionSettings(context)
                return
            }
            val dir = File(context.filesDir, "update").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, APK_NAME)
            val expected = String(download(release.checksumUrl, null)).trim().split(Regex("\\s+")).first().lowercase()
            download(release.apkUrl, apk) { percent ->
                status(context.getString(R.string.update_downloading, release.versionName, percent))
            }
            verify(context, apk, expected, current)
            status(context.getString(R.string.update_installing))
            Thread.sleep(INSTALL_NOTICE_MS)
            install(context, apk)
            running = false
        } catch (e: UpdateException) {
            Log.w(TAG, "Update failed: ${e.message}")
            finish(context.getString(R.string.update_failed, e.message))
        } catch (e: IOException) {
            Log.w(TAG, "Update failed", e)
            finish(context.getString(R.string.update_failed, "no connection to GitHub"))
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            finish(context.getString(R.string.update_failed, e.javaClass.simpleName))
        }
    }

    private class Release(val versionName: String, val versionCode: Long, val apkUrl: String, val checksumUrl: String)

    private class UpdateException(message: String) : Exception(message)

    private fun latestRelease(): Release {
        val api = apiOverride ?: "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        val json = JSONObject(String(download(api, null)))
        val tag = json.getString("tag_name")
        val match = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").find(tag) ?: throw UpdateException("unexpected tag $tag")
        val (major, minor, patch) = match.destructured
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        var checksum: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.getString("name")) {
                APK_NAME -> apk = asset.getString("browser_download_url")
                "$APK_NAME.sha256" -> checksum = asset.getString("browser_download_url")
            }
        }
        if (apk == null || checksum == null) throw UpdateException("release $tag has no $APK_NAME")
        return Release(tag.removePrefix("v"), major.toLong() * 10000 + minor.toLong() * 100 + patch.toLong(), apk, checksum)
    }

    /** Downloads [url] into [file] (returns empty), or into memory when [file] is null. */
    private fun download(url: String, file: File?, progress: ((Int) -> Unit)? = null): ByteArray {
        checkUrl(URL(url))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "FamilyFeed/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            val code = connection.responseCode
            checkUrl(connection.url)  // after redirects
            if (code == 403 || code == 429) throw UpdateException("GitHub limit reached, try again in an hour")
            if (code != 200) throw UpdateException("GitHub answered $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                if (file == null) return input.readBytes()
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress?.invoke(percent)
                        }
                    }
                }
            }
            return ByteArray(0)
        } finally {
            connection.disconnect()
        }
    }

    /** https to GitHub only (debug builds testing with [apiOverride] may also use http://127.0.0.1). */
    private fun checkUrl(url: URL) {
        val host = url.host.lowercase()
        val github = url.protocol == "https" &&
            (host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com"))
        val local = BuildConfig.DEBUG && apiOverride != null && (host == "127.0.0.1" || host == "localhost")
        if (!github && !local) throw UpdateException("refusing to download from $host")
    }

    private fun verify(context: Context, apk: File, expectedSha256: String, current: Long) {
        val actual = sha256(apk)
        if (actual != expectedSha256) throw UpdateException("checksum does not match")
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: throw UpdateException("not a valid APK")
        if (archive.packageName != context.packageName) throw UpdateException("wrong app (${archive.packageName})")
        if (versionCode(archive) <= current) throw UpdateException("not newer than this version")
        val own = pm.getPackageInfo(context.packageName, signingFlags())
        if (signers(archive).isEmpty() || signers(archive) != signers(own)) {
            throw UpdateException("signed with a different key")
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Allowed without a confirmation screen once this app is the one that installed itself.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            awaitingUserAction = true
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "Update handed to PackageInstaller (session $sessionId)")
    }

    /** From [UpdateReceiver]: the installer's answer. */
    fun onInstallStatus(context: Context, status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) awaitingUserAction = false
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> File(context.filesDir, "update").deleteRecursively()
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(context.getString(R.string.update_cancelled))
            else -> finish(context.getString(R.string.update_failed, message ?: "installer error $status"))
        }
    }

    private fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try {
                awaitingUserAction = true  // Back from Settings returns to the app
                context.startActivity(intent)
            } catch (e: Exception) {
                awaitingUserAction = false
                Log.w(TAG, "No settings screen for install permission", e)
            }
        }
    }

    private fun currentVersionCode(context: Context): Long =
        versionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        pm.getPackageArchiveInfo(apk.path, signingFlags())

    /** SHA-256 of each signing certificate. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun status(message: String) {
        Log.i(TAG, message)
        main.post { listener?.onUpdateStatus(message, false) }
    }

    private fun finish(message: String) {
        running = false
        Log.i(TAG, message)
        main.post { listener?.onUpdateStatus(message, true) }
    }

    private const val TAG = "FacebookFeed"
    private const val APK_NAME = "family-feed.apk"
    /** How long "The app will close and update" stays up before the installer takes over. */
    private const val INSTALL_NOTICE_MS = 3000L
}
