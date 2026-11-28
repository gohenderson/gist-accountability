package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Syncthing inside Gist, for Easy Connect.
 *
 * Syncthing's own program (built for Android by CI, .github/scripts/build-syncthing.sh) is
 * installed with the app as libsyncthing.so in its native library folder, the one place
 * Android lets an app run a program from. Gist runs it as a separate process with its own
 * folder (files/syncthing: its keys, settings and database), and talks to it only through its
 * REST API on 127.0.0.1 with a random key made for that run. Which folders it shares, with
 * which devices, is up to [EasyConnect].
 *
 * Calls block (network and process waits): call them off the main thread.
 */
class SyncthingEngine(
    context: Context,
    /** Its own folder: keys, settings, database (tests run a second one elsewhere). */
    val home: File = File(context.applicationContext.filesDir, "syncthing"),
) {
    private val app = context.applicationContext
    val binary = File(app.applicationInfo.nativeLibraryDir, "libsyncthing.so")
    val log = File(home, "syncthing.log")

    private var process: Process? = null
    /** Where its API listens on 127.0.0.1 this run. */
    var port = 0
        private set
    private var apiKey = ""

    /** Whether this build has Syncthing (CI's builds do; release builds for 64-bit ARM phones). */
    val available: Boolean get() = binary.isFile

    val running: Boolean get() = process?.isAlive == true

    /**
     * Whether Android lets Gist use the network at all. Some phones (GrapheneOS's Network
     * permission, firewall settings) can turn it off for an app: then every socket fails with
     * EPERM, Syncthing's included.
     */
    fun networkAllowed(): Boolean = try {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
        true
    } catch (e: java.net.SocketException) {
        !(e.message ?: "").contains("EPERM")
    } catch (e: SecurityException) {
        false
    }

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
            if (answer.getOrDefault(false)) {
                noReports()
                return
            }
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

    /**
     * Syncthing sends nothing about you to anyone: no usage reports (declined, so it doesn't
     * ask) and no crash reports. Its connections are only to devices you've added, through
     * Syncthing's discovery and relay servers when they aren't on your network.
     */
    private fun noReports() {
        runCatching { setOptions(JSONObject().put("urAccepted", -1).put("crashReportingEnabled", false)) }
    }

    fun ping(): Boolean = JSONObject(call("GET", "/rest/system/ping")).optString("ping") == "pong"

    /** This device's Syncthing ID (what another device adds to reach it). */
    fun deviceId(): String = JSONObject(call("GET", "/rest/system/status")).getString("myID")

    /** Syncthing's version, e.g. "v2.1.7". */
    fun version(): String = JSONObject(call("GET", "/rest/system/version")).getString("version")

    // ------------------------------------------------------------ configuration

    /** Changes its options (a JSON object of the ones to change), e.g. listen addresses. */
    fun setOptions(changes: JSONObject) {
        call("PATCH", "/rest/config/options", changes.toString())
    }

    /** Names this device (what other devices see while it waits to be accepted). */
    fun setName(name: String) {
        call("PATCH", "/rest/config/devices/${deviceId()}", JSONObject().put("name", name).toString())
    }

    /** The devices it knows: id → name. */
    fun devices(): Map<String, String> = JSONArray(call("GET", "/rest/config/devices")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associate { it.getString("deviceID") to it.optString("name") }
    }

    /** Adds (or replaces) device [id], reached at [addresses] ("dynamic": found by discovery). */
    fun addDevice(id: String, name: String, addresses: List<String> = listOf("dynamic")) {
        call("PUT", "/rest/config/devices/$id", JSONObject()
            .put("deviceID", id)
            .put("name", name)
            .put("addresses", JSONArray(addresses))
            .put("autoAcceptFolders", false)
            .toString())
    }

    /** Folder [id]'s settings, or null if it hasn't got it. */
    fun folder(id: String): JSONObject? = try {
        JSONObject(call("GET", "/rest/config/folders/$id"))
    } catch (e: IllegalStateException) {
        if (e.message?.contains("HTTP 404") == true) null else throw e
    }

    /**
     * Adds folder [id] at [path] (send and receive), shared with [devices], or adds [devices]
     * to it if it's there already (keeping the ones it has).
     */
    fun shareFolder(id: String, label: String, path: String, devices: List<String> = emptyList()) {
        val existing = folder(id)
        val have = existing?.optJSONArray("devices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("deviceID") } }
            ?: emptyList()
        val all = (have + devices).distinct()
        val list = JSONArray(all.map { JSONObject().put("deviceID", it) })
        if (existing != null) {
            call("PATCH", "/rest/config/folders/$id", JSONObject().put("devices", list).toString())
        } else {
            call("PUT", "/rest/config/folders/$id", JSONObject()
                .put("id", id)
                .put("label", label)
                .put("path", path)
                .put("type", "sendreceive")
                .put("devices", list)
                .put("fsWatcherEnabled", true)
                .put("rescanIntervalS", 60)
                .toString())
        }
    }

    /** Stops sharing folder [id] (its files stay where they are). */
    fun removeFolder(id: String) {
        call("DELETE", "/rest/config/folders/$id")
    }

    /** Forgets device [id]. */
    fun removeDevice(id: String) {
        call("DELETE", "/rest/config/devices/$id")
    }

    /** Each folder's devices: folder id → device ids. */
    fun folderDevices(): Map<String, List<String>> = JSONArray(call("GET", "/rest/config/folders")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associate { f ->
            val d = f.optJSONArray("devices") ?: JSONArray()
            f.getString("id") to (0 until d.length()).map { d.getJSONObject(it).getString("deviceID") }
        }
    }

    /** Devices that tried to connect and aren't known yet: id → the name they gave. */
    fun pendingDevices(): Map<String, String> = JSONObject(call("GET", "/rest/cluster/pending/devices")).let { o ->
        o.keys().asSequence().associateWith { o.optJSONObject(it)?.optString("name").orEmpty() }
    }

    /** The devices it's connected to right now. */
    fun connected(): Set<String> = JSONObject(call("GET", "/rest/system/connections")).optJSONObject("connections")?.let { o ->
        o.keys().asSequence().filter { o.optJSONObject(it)?.optBoolean("connected") == true }.toSet()
    } ?: emptySet()

    /** Asks it to look at folder [id] now (rather than when it next notices a change). */
    /** How much of folder [folder] device [device] has of ours, 0 to 100. */
    fun completion(folder: String, device: String): Double =
        JSONObject(call("GET", "/rest/db/completion?folder=$folder&device=$device")).optDouble("completion", 0.0)

    fun scan(id: String) {
        call("POST", "/rest/db/scan?folder=$id")
    }

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
