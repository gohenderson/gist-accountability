package dev.gist.checkin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Before the database, Gist kept everything in preferences files: per profile "gist" (check-ins,
 * results, the waiting list, settings), "gist-links", "gist-devices", "gist-folder" and
 * "gist-geo" (another profile's as "<name>@<id>"), and the profile list in "gist-profiles".
 * The first time the database is made, this takes all of it in, in the same transaction, so
 * either all of it arrives or none does. The files are deleted afterwards
 * ([deleteOldFiles]), but only if every part came in: one that didn't is left where it was.
 */
internal object LegacyImport {
    private const val TAG = "GistImport"
    private const val GLOBAL = "gist-profiles"

    /** Imports what's there into [w]. True when there was something and all of it came in. */
    fun run(context: Context, w: SQLiteDatabase): Boolean {
        val app = context.applicationContext
        val names = Profiles.PREF_NAMES + GLOBAL
        if (oldFiles(app, names).isEmpty()) return false
        var ok = true
        fun part(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                ok = false
                Log.e(TAG, "Couldn't import $what; the old file is kept", e)
            }
        }

        // The profiles, and which one you're on
        val global = app.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)
        val profiles = mutableListOf(Profiles.DEFAULT)
        part("the profile list") {
            global.getString("profiles", null)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val id = o.getString("id")
                    val name = o.getString("name")
                    w.insertWithOnConflict("profile", null, ContentValues().apply {
                        put("id", id)
                        put("name", name)
                        put("position", i)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    if (id != Profiles.DEFAULT) profiles.add(id)
                }
            }
            global.getString("current", null)?.let { Profiles.setCurrentIn(w, it) }
        }

        for (id in profiles) {
            for (ns in Profiles.PREF_NAMES) {
                val name = Profiles.prefsName(ns, id)
                if (!prefsFile(app, name).exists()) continue
                val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                if (ns == Store.PREFS) importStore(w, id, all, ::part) else part(name) { copy(w, id, ns, all) }
            }
        }
        return ok
    }

    /** Check-ins, results, the waiting list and deletions into their tables; the rest are settings. */
    private fun importStore(w: SQLiteDatabase, id: String, all: Map<String, *>, part: (String, () -> Unit) -> Unit) {
        val rest = all.toMutableMap()
        (rest.remove(Store.K_CHECKINS) as? String)?.let { s ->
            part("check-ins") {
                val a = JSONArray(s)
                CheckinRows.write(w, id, (0 until a.length()).map { Store.parseCheckin(a.getJSONObject(it)) })
                DbPrefs.write(w, id, Store.PREFS, Store.K_CHECKINS_SAVED, true)
            }
        }
        (rest.remove(Store.K_PENDING) as? String)?.let { s ->
            part("the waiting list") {
                val a = JSONArray(s)
                PendingRows.write(w, id, (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Pending(o.getString("c"), o.getString("d"), o.optString("t", ""), o.optBoolean("p", false))
                })
            }
        }
        (rest.remove(Store.K_ENTRIES) as? String)?.let { s ->
            part("results") {
                val all = JSONObject(s)
                all.keys().forEach { day ->
                    val d = all.optJSONObject(day) ?: return@forEach
                    d.keys().forEach { checkin ->
                        val slots = d.optJSONObject(checkin) ?: return@forEach
                        slots.keys().forEach { time ->
                            slots.optJSONObject(time)?.let { ResultRows.put(w, id, checkin, day, time, ResultRows.fromJson(it)) }
                        }
                    }
                }
            }
        }
        (rest.remove(Store.K_DELETED) as? String)?.let { s ->
            part("deletions") {
                val o = JSONObject(s)
                DeletedRows.write(w, id, o.keys().asSequence().associateWith { o.getLong(it) })
            }
        }
        part("settings") { copy(w, id, Store.PREFS, rest) }
    }

    private fun copy(w: SQLiteDatabase, id: String, ns: String, all: Map<String, *>) {
        all.forEach { (k, v) -> if (v != null) DbPrefs.write(w, id, ns, k, v) }
    }

    private fun prefsFile(app: Context, name: String) = File(File(app.dataDir, "shared_prefs"), "$name.xml")

    /** Gist's old preferences files (names without .xml). */
    private fun oldFiles(app: Context, names: List<String>): List<String> =
        (File(app.dataDir, "shared_prefs").listFiles() ?: emptyArray())
            .map { it.name.removeSuffix(".xml") }
            .filter { n -> names.any { n == it || n.startsWith("$it@") } }

    /** Deletes the old preferences files, once everything in them is in the database. */
    fun deleteOldFiles(app: Context) {
        oldFiles(app, Profiles.PREF_NAMES + GLOBAL).forEach { app.deleteSharedPreferences(it) }
    }
}
