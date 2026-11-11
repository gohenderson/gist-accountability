package dev.gist.checkin

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.schema.CoreSchema
import java.math.BigInteger

/**
 * YAML for backup files, via SnakeYAML Engine (YAML 1.2, so JSON is valid YAML and old
 * .json backups still load). The app's data is built as org.json trees as before; this
 * converts them to and from YAML. Loading only ever produces maps, lists, strings,
 * numbers and booleans: a YAML file can't make the app create other objects.
 */
object Yaml {
    /** Big enough for a backup with photos and a video (base64). */
    private const val MAX_CODE_POINTS = 160 * 1024 * 1024

    fun dump(root: JSONObject, header: String = ""): String {
        val settings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setSchema(CoreSchema())
            .build()
        val body = Dump(settings).dumpToString(toPlain(root))
        return if (header.isEmpty()) body else header.lines().joinToString("\n") { "# $it".trimEnd() } + "\n" + body
    }

    /** Parses [text]; throws IllegalArgumentException if it isn't YAML. */
    fun load(text: String): Any? {
        val settings = LoadSettings.builder()
            .setCodePointLimit(MAX_CODE_POINTS)
            .setSchema(CoreSchema()) // also accepts True/TRUE, ~ etc. in hand-edited files
            .setAllowDuplicateKeys(false)
            .build()
        return try {
            Load(settings).loadFromString(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This isn't a Gist backup file (not valid YAML): ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    /** org.json tree → plain Kotlin maps and lists, keeping key order. */
    fun toPlain(v: Any?): Any? = when (v) {
        is JSONObject -> LinkedHashMap<String, Any?>().apply { v.keys().forEach { k -> put(k, toPlain(v.opt(k))) } }
        is JSONArray -> (0 until v.length()).map { toPlain(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    /** Loaded YAML → org.json tree (the rest of the app reads JSON). */
    fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it)) } }
        is Boolean, is String, is Int, is Long, is Double -> v
        is BigInteger -> v.toLong()
        is Number -> v.toDouble()
        is ByteArray -> Base64.encodeToString(v, Base64.NO_WRAP) // a !!binary value
        else -> v.toString()
    }
}
