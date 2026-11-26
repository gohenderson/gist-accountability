package dev.gist.checkin

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Profiles: entirely separate setups on one phone (say Personal and Work), each with its own
 * check-ins and tasks, answers, settings, Gist folder, connections and devices. You see one
 * at a time; every profile's alarms stay set, and ring whichever profile you're on.
 *
 * The first profile ([DEFAULT]) uses Gist's original storage, so nothing moves for it. Another
 * profile keeps its preferences as "<name>@<id>" and its files under files/profiles/<id>/.
 *
 * Which profile code works in comes from its Context: an activity's or service's own
 * ([ProfileScoped]), or [scoped] around any other. Intents that cross into receivers, services
 * and activities carry it as [EXTRA]; one without it (set before profiles) is the first profile.
 */
object Profiles {
    const val EXTRA = "gistProfile"
    /** The first profile: Gist's original storage. */
    const val DEFAULT = ""

    private const val PREFS = "gist-profiles"
    private const val K_LIST = "profiles"
    private const val K_CURRENT = "current"

    /** Preferences each profile has its own of (deleted with it). */
    val PREF_NAMES = listOf("gist", Connections.PREFS, Devices.PREFS, GistFolder.PREFS, LocationTriggers.PREFS)

    data class Profile(val id: String, val name: String)

    private fun global(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every profile, the first one first. */
    fun all(context: Context): List<Profile> {
        val raw = global(context).getString(K_LIST, null)
        val list = raw?.let { s ->
            try {
                JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Profile(it.getString("id"), it.getString("name")) } }
            } catch (e: Exception) { null }
        } ?: emptyList()
        return if (list.none { it.id == DEFAULT }) listOf(Profile(DEFAULT, "Personal")) + list else list
    }

    private fun save(context: Context, list: List<Profile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        global(context).edit().putString(K_LIST, a.toString()).commit()
    }

    fun name(context: Context, id: String): String = all(context).firstOrNull { it.id == id }?.name ?: "Personal"

    /** The profile you're looking at. */
    fun current(context: Context): String =
        global(context).getString(K_CURRENT, DEFAULT)?.takeIf { id -> all(context).any { it.id == id } } ?: DEFAULT

    fun setCurrent(context: Context, id: String) {
        if (all(context).any { it.id == id }) global(context).edit().putString(K_CURRENT, id).commit()
    }

    /** More than one profile (so it's worth saying which one something is from). */
    fun several(context: Context): Boolean = all(context).size > 1

    /** A new, empty profile called [name]. */
    fun add(context: Context, name: String): Profile {
        val p = Profile(Store.newId() + Store.newId(), name.trim().ifEmpty { "Profile" })
        Store(scoped(context, p.id)).checkins = emptyList() // empty: not the first-run examples
        save(context, all(context) + p)
        return p
    }

    fun rename(context: Context, id: String, name: String) {
        val n = name.trim().ifEmpty { return }
        save(context, all(context).map { if (it.id == id) it.copy(name = n) else it })
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
        val app = context.applicationContext
        PREF_NAMES.forEach { app.deleteSharedPreferences(prefsName(it, id)) }
        File(app.filesDir, "profiles/$id").deleteRecursively()
        save(context, all(context).filter { it.id != id })
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

    fun prefsName(name: String, id: String) = if (id == DEFAULT) name else "$name@$id"

    /** Preferences [name] of [context]'s profile. */
    fun prefs(context: Context, name: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName(name, of(context)), Context.MODE_PRIVATE)

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
