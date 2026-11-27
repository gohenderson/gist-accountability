package dev.gist.checkin

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Profiles: entirely separate setups on one phone (say Personal and Work), each with its own
 * check-ins and tasks, answers, settings, Gist folder, connections and devices. You see one
 * at a time; every profile's alarms stay set, and ring whichever profile you're on.
 *
 * Everything a profile keeps in the database ([Db]) is under its id (the first profile's is
 * [DEFAULT]); its files (photos, clips) are under files/ for the first profile and
 * files/profiles/<id>/ for another.
 *
 * Which profile code works in comes from its Context: an activity's or service's own
 * ([ProfileScoped]), or [scoped] around any other. Intents that cross into receivers, services
 * and activities carry it as [EXTRA]; one without it (set before profiles) is the first profile.
 */
object Profiles {
    const val EXTRA = "gistProfile"
    /** The first profile: Gist's original storage. */
    const val DEFAULT = ""

    const val DEFAULT_NAME = "Personal"
    private const val K_CURRENT = "currentProfile"

    /**
     * The names each profile keeps settings and sync bookkeeping under ([prefs]); before the
     * database, preferences files of these names.
     */
    val PREF_NAMES = listOf(Store.PREFS, Connections.PREFS, Devices.PREFS, GistFolder.PREFS, LocationTriggers.PREFS)

    data class Profile(val id: String, val name: String)

    /** Every profile, the first one first. */
    fun all(context: Context): List<Profile> {
        val list = Db.get(context).read.query("profile", arrayOf("id", "name"), null, null, null, null, "position, rowid")
            .rows { Profile(it.str("id"), it.str("name")) }
        return if (list.none { it.id == DEFAULT }) listOf(Profile(DEFAULT, DEFAULT_NAME)) + list else list
    }

    fun name(context: Context, id: String): String = all(context).firstOrNull { it.id == id }?.name ?: DEFAULT_NAME

    /** The profile you're looking at. */
    fun current(context: Context): String {
        val id = Db.get(context).read.query("app_state", arrayOf("value"), "key = ?", arrayOf(K_CURRENT), null, null, null)
            .rows { it.strOrNull("value") }.firstOrNull() ?: return DEFAULT
        return if (all(context).any { it.id == id }) id else DEFAULT
    }

    fun setCurrent(context: Context, id: String) {
        if (all(context).any { it.id == id }) Db.get(context).tx { setCurrentIn(it, id) }
    }

    internal fun setCurrentIn(w: SQLiteDatabase, id: String) {
        w.insertWithOnConflict("app_state", null, ContentValues().apply {
            put("key", K_CURRENT)
            put("value", id)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** More than one profile (so it's worth saying which one something is from). */
    fun several(context: Context): Boolean = all(context).size > 1

    /** A new, empty profile called [name]. */
    fun add(context: Context, name: String): Profile {
        val p = Profile(Store.newId() + Store.newId(), name.trim().ifEmpty { "Profile" })
        Db.get(context).tx { w ->
            val position = w.compileStatement("SELECT COALESCE(MAX(position), 0) + 1 FROM profile").simpleQueryForLong()
            w.insertOrThrow("profile", null, ContentValues().apply {
                put("id", p.id)
                put("name", p.name)
                put("position", position)
            })
        }
        Store(scoped(context, p.id)).checkins = emptyList() // empty: not the first-run examples
        return p
    }

    fun rename(context: Context, id: String, name: String) {
        val n = name.trim().ifEmpty { return }
        Db.get(context).tx { w ->
            val v = ContentValues().apply { put("name", n) }
            if (w.update("profile", v, "id = ?", arrayOf(id)) == 0 && id == DEFAULT) {
                w.insert("profile", null, v.apply { put("id", DEFAULT); put("position", 0) })
            }
        }
    }

    /**
     * Deletes profile [id] (not the first, not the one you're on): its alarms and place
     * watches go, then everything it kept.
     */
    fun remove(context: Context, id: String): Boolean {
        if (id == DEFAULT || id == current(context) || all(context).none { it.id == id }) return false
        val ctx = scoped(context, id)
        val store = Store(ctx)
        store.checkins.forEach {
            AlarmScheduler.cancel(ctx, it.id)
            LocationTriggers.remove(ctx, it.id)
            Notif.clearQuiet(ctx, it.id)
        }
        Db.get(context).deleteProfile(id)
        File(context.applicationContext.filesDir, "profiles/$id").deleteRecursively()
        return true
    }

    // ------------------------------------------------------------ where a profile keeps things

    /** The profile [context] works in. */
    fun of(context: Context): String = explicit(context) ?: DEFAULT

    /** The profile [context] says it works in; null for a plain context (the first profile's). */
    fun explicit(context: Context): String? {
        var c: Context? = context
        while (c != null) {
            if (c is ProfileScoped) return c.profileId
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** The old preferences file a profile kept [name] in (see LegacyImport). */
    fun prefsName(name: String, id: String) = if (id == DEFAULT) name else "$name@$id"

    /** Settings [name] of [context]'s profile, kept in the database. */
    fun prefs(context: Context, name: String): SharedPreferences = DbPrefs(Db.get(context), of(context), name)

    /** Where [context]'s profile keeps its files (photos, clips). */
    fun filesDir(context: Context): File {
        val id = of(context)
        val base = context.applicationContext.filesDir
        return if (id == DEFAULT) base else File(base, "profiles/$id").also { it.mkdirs() }
    }

    /** [context], working in profile [id]. */
    fun scoped(context: Context, id: String): Context = ProfileContext(context.applicationContext, id)

    /** [context], working in the profile [intent] is for (the first one if it doesn't say). */
    fun fromIntent(context: Context, intent: Intent?): Context =
        scoped(context, intent?.getStringExtra(EXTRA)?.takeIf { id -> all(context).any { it.id == id } } ?: DEFAULT)

    /** [intent], for [context]'s profile. */
    fun tag(intent: Intent, context: Context): Intent = intent.putExtra(EXTRA, of(context))

    /** Every profile's context. */
    fun contexts(context: Context): List<Context> = all(context).map { scoped(context, it.id) }

    /**
     * The profile with an alarm waiting to be answered on the alarm screen: the one [first]
     * if it has one, else the one you're on, else any. Null when none has.
     */
    fun withAlarm(context: Context, first: String? = null): String? {
        val order = listOfNotNull(first, current(context)) + all(context).map { it.id }
        return order.distinct().firstOrNull { Store(scoped(context, it)).alarmPending.isNotEmpty() }
    }

    /** Sets every profile's alarms (after a reboot, say). */
    fun scheduleAll(context: Context) = contexts(context).forEach { AlarmScheduler.scheduleAll(it) }

    /**
     * A number for [key] in [context]'s profile, apart from other profiles' (request codes,
     * notification ids). The first profile's are what they were before profiles.
     */
    fun hash(context: Context, key: String): Int = of(context).let { id -> if (id == DEFAULT) key.hashCode() else "$id/$key".hashCode() }

    /**
     * [intent] for [context]'s profile, kept apart from the same one in other profiles (a
     * PendingIntent is told apart by its data, not its extras). The first profile's stay as
     * they were, so alarms set before profiles are replaced, not doubled.
     */
    fun distinct(intent: Intent, context: Context, key: String): Intent {
        val id = of(context)
        intent.putExtra(EXTRA, id)
        if (id != DEFAULT) intent.data = android.net.Uri.Builder().scheme("gist-profile").authority(id).appendPath(key).build()
        return intent
    }
}

/** A context that works in a profile (activities and services know theirs). */
interface ProfileScoped {
    val profileId: String
}

/** Any context, working in profile [profileId]. */
class ProfileContext(base: Context, override val profileId: String) : ContextWrapper(base), ProfileScoped
