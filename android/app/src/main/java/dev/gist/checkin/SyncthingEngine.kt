package dev.gist.checkin

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Syncthing inside Gist: the Easy Connect experiment (debug builds only for now).
 *
 * Syncthing's own program (built for Android by CI, .github/scripts/build-syncthing.sh) is
 * installed with the app as libsyncthing.so in its native library folder, the one place
 * Android lets an app run a program from. Gist runs it as a separate process with its own
 * folder (files/syncthing: its keys, settings and database), and talks to it only through its
 * REST API on 127.0.0.1 with a random key made for that run. Nothing here touches your
 * folders yet: this proves it starts, answers and stops on a phone.
 *
 * Calls block (network and process waits): call them off the main thread.
 */
class SyncthingEngine(context: Context) {
    private val app = context.applicationContext
    val binary = File(app.applicationInfo.nativeLibraryDir, "libsyncthing.so")
    val home = File(app.filesDir, "syncthing")
    val log = File(home, "syncthing.log")

    private var process: Process? = null
    /** Where its API listens on 127.0.0.1 this run. */
    var port = 0
        private set
    private var apiKey = ""

    /** Whether this build has Syncthing (debug builds from CI do). */
    val available: Boolean get() = binary.isFile

    val running: Boolean get() = process?.isAlive == true

    /**
     * Starts Syncthing and waits (up to [timeoutMs]) until its API answers. Its GUI and API
     * listen only on 127.0.0.1, on a free port, with a key only this run knows.
     */
    @Synchronized
    fun start(timeoutMs: Long = 60_000) {
        if (running) return
        check(available) { "This build doesn't include Syncthing" }
        home.mkdirs()
        port = ServerSocket(0).use { it.localPort }
        apiKey = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val pb = ProcessBuilder(
            binary.path, "serve",
            "--home=${home.path}",
            "--gui-address=http://127.0.0.1:$port",
            "--gui-apikey=$apiKey",
            "--no-browser", "--no-restart", "--no-upgrade",
            "--log-file=${log.path}",
        ).redirectErrorStream(true).redirectOutput(File(home, "stdout.log"))
        pb.environment().apply {
            put("HOME", home.path)
            put("TMPDIR", app.cacheDir.path)
            put("STNODEFAULTFOLDER", "1") // no "Default Folder" in Sync: Gist adds what it shares
            put("STNOUPGRADE", "1")
        }
        process = pb.start()
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            if (!running) throw IllegalStateException("Syncthing exited (${process?.exitValue()}): ${tail()}")
            val answer = runCatching { ping() }
            if (answer.getOrDefault(false)) return
            last = answer.exceptionOrNull() ?: last
            Thread.sleep(250)
        }
        stop()
        throw IllegalStateException("Syncthing didn't answer within ${timeoutMs / 1000} s (last: $last): ${tail()}")
    }

    /** Asks Syncthing to shut down, and makes sure it has (up to [timeoutMs]). */
    @Synchronized
    fun stop(timeoutMs: Long = 15_000) {
        val p = process ?: return
        if (p.isAlive) {
            runCatching { call("POST", "/rest/system/shutdown") }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        process = null
    }

    fun ping(): Boolean = JSONObject(call("GET", "/rest/system/ping")).optString("ping") == "pong"

    /** This device's Syncthing ID (what another device adds to reach it). */
    fun deviceId(): String = JSONObject(call("GET", "/rest/system/status")).getString("myID")

    /** Syncthing's version, e.g. "v2.1.7". */
    fun version(): String = JSONObject(call("GET", "/rest/system/version")).getString("version")

    /** A REST API call; its response body. */
    fun call(method: String, path: String, body: String? = null): String {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 2_000
            c.readTimeout = 10_000
            c.setRequestProperty("X-API-Key", apiKey)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Syncthing $method $path: HTTP $code $text")
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        @Volatile
        private var shared: SyncthingEngine? = null

        /** The app's one engine (Syncthing runs once per device, whatever the screen). */
        fun shared(context: Context): SyncthingEngine =
            shared ?: synchronized(this) { shared ?: SyncthingEngine(context).also { shared = it } }
    }

    /** The end of Syncthing's output, for saying why it didn't start. */
    fun tail(lines: Int = 20): String = listOf(File(home, "stdout.log"), log)
        .filter { it.isFile }
        .joinToString(" | ") { f -> f.readLines().takeLast(lines).joinToString(" / ") }
}
