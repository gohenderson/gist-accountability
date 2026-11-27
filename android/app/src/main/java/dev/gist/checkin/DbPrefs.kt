package dev.gist.checkin

import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Settings and sync bookkeeping of one profile, under one name (say "gist-links"), kept in the
 * database's setting table and read through the SharedPreferences interface, so the code that
 * keeps them didn't have to change. Values are cached in memory (shared by every instance in
 * the app's one process) and written straight through: commit() and apply() both write now.
 */
class DbPrefs(private val db: Db, private val profile: String, private val ns: String) : SharedPreferences {

    private fun values(): MutableMap<String, Any> = synchronized(db) {
        db.prefsCache.getOrPut(profile to ns) { load(db.read, profile, ns) }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(db) { HashMap(values()) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(db) { values()[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(db) { (values()[key] as? Set<String>)?.toMutableSet() ?: defValues }

    override fun getInt(key: String, defValue: Int): Int = synchronized(db) { (values()[key] as? Int) ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(db) { (values()[key] as? Long) ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(db) { (values()[key] as? Float) ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(db) { (values()[key] as? Boolean) ?: defValue }
    override fun contains(key: String): Boolean = synchronized(db) { values().containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    // Nothing in Gist listens for changes.
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>() // null: remove
        private var clear = false

        private fun set(key: String, value: Any?): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = set(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = set(key, values?.toSet())
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun putLong(key: String, value: Long) = set(key, value)
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) = set(key, null)
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            synchronized(db) {
                val cache = values()
                db.tx { w ->
                    if (clear) {
                        w.delete("setting", "profile_id = ? AND ns = ?", arrayOf(profile, ns))
                        cache.clear()
                    }
                    puts.forEach { (k, v) ->
                        if (v == null) {
                            w.delete("setting", "profile_id = ? AND ns = ? AND key = ?", arrayOf(profile, ns, k))
                            cache.remove(k)
                        } else {
                            write(w, profile, ns, k, v)
                            cache[k] = v
                        }
                    }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    companion object {
        /** Writes one value (replacing any). Used by the import too. */
        fun write(w: SQLiteDatabase, profile: String, ns: String, key: String, value: Any) {
            val (type, text) = when (value) {
                is Int -> "i" to value.toString()
                is Long -> "l" to value.toString()
                is Boolean -> "b" to (if (value) "1" else "0")
                is Float -> "f" to value.toString()
                is String -> "s" to value
                is Set<*> -> "set" to JSONArray(value.map { it.toString() }.sorted()).toString()
                else -> throw IllegalArgumentException("Can't keep a ${value::class.java.simpleName} in settings")
            }
            w.insertWithOnConflict("setting", null, ContentValues().apply {
                put("profile_id", profile)
                put("ns", ns)
                put("key", key)
                put("type", type)
                put("value", text)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        private fun load(r: SQLiteDatabase, profile: String, ns: String): MutableMap<String, Any> {
            val out = HashMap<String, Any>()
            r.query("setting", arrayOf("key", "type", "value"), "profile_id = ? AND ns = ?", arrayOf(profile, ns), null, null, null).rows { c ->
                val key = c.str("key")
                val text = c.strOrNull("value") ?: return@rows
                val v: Any? = when (c.str("type")) {
                    "i" -> text.toIntOrNull()
                    "l" -> text.toLongOrNull()
                    "b" -> text == "1"
                    "f" -> text.toFloatOrNull()
                    "s" -> text
                    "set" -> runCatching { JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
                    else -> null
                }
                if (v != null) out[key] = v
            }
            return out
        }
    }
}
