package net.montierth.avogadro.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import net.montierth.avogadro.AvogadroApplication
import net.montierth.avogadro.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A build the server offers (`/apk/version.json`, written by `android/build.sh publish`). */
data class AppUpdate(val versionCode: Int, val versionName: String, val url: String, val sha256: String?, val size: Long?)

/** What an APK (or the installed app) is: package, version and signing certificates (SHA-256, hex). */
data class PackageFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** The updater's checks, as pure functions. Each returns null when all is well, else what is wrong. */
object UpdateCheck {
    /** The downloaded bytes are exactly what the server advertised (size and SHA-256, when it sent them). */
    fun digestProblem(size: Long, sha256: String, expectedSize: Long?, expectedSha256: String?): String? = when {
        expectedSize != null && size != expectedSize -> "size $size, expected $expectedSize"
        expectedSha256 != null && !sha256.equals(expectedSha256.trim(), ignoreCase = true) -> "sha256 $sha256, expected $expectedSha256"
        size <= 0 -> "empty file"
        else -> null
    }

    /** The APK may replace the installed app: same package, the advertised version, newer, same signers. */
    fun archiveProblem(apk: PackageFacts?, installed: PackageFacts, advertisedCode: Int): String? = when {
        apk == null -> "not a readable APK"
        apk.packageName != installed.packageName -> "package ${apk.packageName}, expected ${installed.packageName}"
        apk.versionCode != advertisedCode.toLong() -> "version ${apk.versionCode}, advertised $advertisedCode"
        apk.versionCode <= installed.versionCode -> "version ${apk.versionCode} is not newer than ${installed.versionCode}"
        apk.signers.isEmpty() -> "unsigned"
        apk.signers != installed.signers -> "signed by another key"
        else -> null
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** version.json, tolerantly: null unless it names a version and where the APK is. */
    fun parse(text: String): AppUpdate? {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return AppUpdate(
            versionCode = str("version_code")?.toIntOrNull() ?: return null,
            versionName = str("version_name").orEmpty(),
            url = str("url") ?: return null,
            sha256 = str("sha256"),
            size = (o["size"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

/**
 * Self-updater, after Pulitzer's: when the server offers a newer build, download it to the cache,
 * verify it (size, SHA-256, package, version, signing certificates) and install it with a
 * PackageInstaller session. Silently only once the app has been left (not while one of its pages is
 * open in a tab); any time from the home screen's "Update". Android installs without asking when an
 * app updates itself, holds UPDATE_PACKAGES_WITHOUT_USER_ACTION and may install apps (the user's
 * "install unknown apps" switch for Avogadro).
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.cacheDir, "update")
    private val mutex = Mutex()
    private val installing = Mutex()
    private val state = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val _ready = MutableStateFlow<AppUpdate?>(null)
    /** A newer build, downloaded and verified. */
    val ready: StateFlow<AppUpdate?> = _ready.asStateFlow()
    @Volatile var foreground = false
        private set
    @Volatile private var checkedAt = 0L
    @Volatile private var backgroundJob: Job? = null
    /** The last session handed to Android: version and when (ms). */
    @Volatile private var lastCommit: Pair<Int, Long>? = null

    fun start() {
        scope.launch(Dispatchers.IO) { cleanup() }
    }

    /** The app is in front: look for a new build (at most every few minutes). */
    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        if (System.currentTimeMillis() - checkedAt > CHECK_EVERY_MS) scope.launch { check() }
    }

    /** The app is out of sight. When it was left (not covered by a tab), install a ready update soon after. */
    fun onBackground(left: Boolean) {
        foreground = false
        backgroundJob?.cancel()
        if (left) {
            backgroundJob = scope.launch {
                delay(BACKGROUND_DELAY_MS)
                installSilently()
            }
        }
    }

    suspend fun check() {
        checkedAt = System.currentTimeMillis()
        val update = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("$BASE_URL/apk/version.json").build()).execute().use { r ->
                    if (r.isSuccessful) UpdateCheck.parse(r.body.string()) else null
                }
            }.getOrNull()
        } ?: return
        if (update.versionCode > BuildConfig.VERSION_CODE && prepare(update) != null) _ready.value = update
    }

    private suspend fun installSilently() {
        if (!installing.tryLock()) return
        try {
            val update = _ready.value ?: return
            if (foreground || !canInstall()) return
            if (state.getInt(KEY_NEEDS_USER, 0) == update.versionCode) return // known to need a tap
            // One session per version at a time: Android is still working on the last one.
            lastCommit?.let { (v, at) -> if (v == update.versionCode && System.currentTimeMillis() - at < RECOMMIT_AFTER_MS) return }
            val file = prepare(update) ?: return
            if (foreground) return
            commit(file, update, userInitiated = false)
        } finally {
            installing.unlock()
        }
    }

    /** "Update": install now, in the front. Silent when Android allows it (the app then closes); else it asks. */
    suspend fun installNow(): Boolean {
        if (!installing.tryLock()) return false
        try {
            val update = _ready.value ?: return false
            val file = prepare(update) ?: return false
            return commit(file, update, userInitiated = true)
        } finally {
            installing.unlock()
        }
    }

    /** May this app ask to install packages (the "install unknown apps" switch for it)? */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The verified APK for [update], downloading it if needed; null (and nothing kept) when anything is off. */
    private suspend fun prepare(update: AppUpdate): File? = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, "avogadro-${update.versionCode}.apk")
            dir.listFiles()?.forEach { if (it != target) it.delete() }
            if (target.exists()) {
                if (verify(target, update) == null) return@withContext target
                target.delete()
            }
            val part = File(dir, target.name + ".part")
            try {
                download(BASE_URL.toHttpUrl().resolve(update.url)?.toString() ?: return@withContext null, part)
            } catch (e: IOException) {
                Log.i(TAG, "download failed, will retry: ${e.message}")
                part.delete()
                return@withContext null
            }
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext null
            }
            val problem = verify(target, update)
            if (problem != null) {
                Log.w(TAG, "rejected update ${update.versionCode}: $problem")
                target.delete()
                return@withContext null
            }
            target
        }
    }

    private fun download(url: String, to: File) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            to.outputStream().use { out -> response.body.byteStream().use { it.copyTo(out) } }
        }
    }

    /** Size and digest, then what the APK says it is against the installed app. */
    private fun verify(file: File, update: AppUpdate): String? {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
            }
        }
        UpdateCheck.digestProblem(file.length(), UpdateCheck.hex(sha.digest()), update.size, update.sha256)?.let { return it }
        val installed = facts(installedInfo()) ?: return "cannot read the installed app"
        return UpdateCheck.archiveProblem(facts(archiveInfo(file)), installed, update.versionCode)
    }

    private fun commit(file: File, update: AppUpdate, userInitiated: Boolean): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Ask Android not to prompt (it agrees for an app updating itself that may install apps);
            // when it wants a person anyway, a tap gets its confirmation (see UpdateReceiver).
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("avogadro.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
                .setAction(ACTION_STATUS)
                .putExtra(EXTRA_USER, userInitiated)
                .putExtra(EXTRA_VERSION, update.versionCode)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        lastCommit = update.versionCode to System.currentTimeMillis()
        Log.i(TAG, "committed update ${update.versionCode} (${if (userInitiated) "tap" else "background"})")
        true
    } catch (e: Exception) {
        Log.w(TAG, "install failed, will retry", e)
        false
    }

    /** Drops downloads for builds that are no longer newer than this one. */
    fun cleanup() {
        dir.listFiles()?.forEach { f ->
            val code = f.name.removePrefix("avogadro-").substringBefore('.').toIntOrNull()
            if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
        }
        if (state.getInt(KEY_NEEDS_USER, 0) <= BuildConfig.VERSION_CODE) state.edit().remove(KEY_NEEDS_USER).apply()
    }

    internal fun needsUser(version: Int) {
        state.edit().putInt(KEY_NEEDS_USER, version).apply()
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(SIGNATURE_FLAGS.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, SIGNATURE_FLAGS)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(SIGNATURE_FLAGS.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo?): PackageFacts? {
        info ?: return null
        // The certificates that signed the APK's contents (not any rotation history).
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        val signers = signatures.orEmpty().map { UpdateCheck.hex(sha.digest(it.toByteArray())) }.toSet()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return PackageFacts(info.packageName, code, signers)
    }

    companion object {
        const val TAG = "AvogadroUpdate"
        const val ACTION_STATUS = "net.montierth.avogadro.UPDATE_STATUS"
        const val EXTRA_USER = "user"
        const val EXTRA_VERSION = "version"
        private const val KEY_NEEDS_USER = "needs_user_version"
        private const val CHECK_EVERY_MS = 5 * 60_000L
        private const val BACKGROUND_DELAY_MS = 8_000L
        private const val RECOMMIT_AFTER_MS = 10 * 60_000L

        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

/** The install session's outcome (non-exported; only the system's PackageInstaller sends it). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_STATUS) return
        val updater = (context.applicationContext as? AvogadroApplication)?.updater ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val version = intent.getIntExtra(Updater.EXTRA_VERSION, 0)
        val userInitiated = intent.getBooleanExtra(Updater.EXTRA_USER, false)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (userInitiated && updater.foreground && confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // Android wants a person for this one: leave it to "Update".
                    Log.i(Updater.TAG, "update $version needs the user")
                    if (updater.canInstall()) updater.needsUser(version)
                    val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(Updater.TAG, "update $version installed")
                updater.cleanup()
            }
            else -> Log.w(
                Updater.TAG,
                "update $version failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
            )
        }
    }
}
