package dev.gist.checkin

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Gist's database: one SQLite file (gist.db) for every profile. Each table's rows carry their
 * profile's id; deleting a profile deletes its rows ([deleteProfile]).
 *
 * - Check-ins and tasks are rows, with their triggers, steps, questions and who they're
 *   shared with in their own tables (deleted with the check-in).
 * - Results are a row per check-in, day and time, with a row per answer. They aren't tied to
 *   the check-in (no foreign key): history can arrive from your other devices before the
 *   check-in does, and outlives deleting it until you delete it too.
 * - Settings, and the bookkeeping for syncing (connections, devices, the Gist folder, place
 *   watches), are typed key-value rows ([setting]), read through [DbPrefs].
 *
 * The first time it opens on a phone that had Gist before, it takes everything from the old
 * preferences files ([LegacyImport]) in the same transaction, then deletes those files.
 */
class Db private constructor(private val context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    /** Imported from the old preferences files in onCreate: those files can go once it's committed. */
    @Volatile
    private var imported = false

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach { db.execSQL(it) }
        db.insert("profile", null, ContentValues().apply {
            put("id", Profiles.DEFAULT)
            put("name", Profiles.DEFAULT_NAME)
            put("position", 0)
        })
        imported = LegacyImport.run(context, db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first; later versions add their steps here.
    }

    /**
     * Runs [block] in a transaction; nested calls join the outer one. Writes hold this object's
     * lock (the one the caches use) for the whole transaction, always taken before SQLite's,
     * so two threads can't each wait for the other.
     */
    fun <T> tx(block: (SQLiteDatabase) -> T): T = synchronized(this) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block(db)
            db.setTransactionSuccessful()
            r
        } finally {
            db.endTransaction()
        }
    }

    val read: SQLiteDatabase get() = readableDatabase

    // ------------------------------------------------------------ caches

    /** Each profile's check-ins as last read or written (every write goes through Store). */
    internal val checkinCache = HashMap<String, List<Checkin>>()

    /** [DbPrefs]' values, by profile and name. */
    internal val prefsCache = HashMap<Pair<String, String>, MutableMap<String, Any>>()

    fun clearCaches() = synchronized(this) {
        checkinCache.clear()
        prefsCache.clear()
    }

    /**
     * Imports old preferences files now (they're normally only there on the first open after
     * updating from a version before the database; tests write some to check the import).
     */
    fun importLegacyNow() {
        val ok = tx { LegacyImport.run(context, it) }
        clearCaches()
        if (ok) LegacyImport.deleteOldFiles(context)
    }

    /** Deletes everything profile [id] keeps here. */
    fun deleteProfile(id: String) = synchronized(this) {
        tx { db ->
            PROFILE_TABLES.forEach { db.delete(it, "profile_id = ?", arrayOf(id)) }
            db.delete("profile", "id = ?", arrayOf(id))
        }
        clearCaches()
    }

    companion object {
        const val NAME = "gist.db"
        const val VERSION = 1

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { db ->
                db.writableDatabase // creates (and imports) now, not halfway through something else
                if (db.imported) LegacyImport.deleteOldFiles(context.applicationContext)
                instance = db
            }
        }

        /** The tables with a profile_id column (child rows go with their parents). */
        val PROFILE_TABLES = listOf("checkin", "slot_result", "pending", "deleted_item", "setting")

        val SCHEMA = listOf(
            """CREATE TABLE profile (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                position INTEGER NOT NULL)""",
            """CREATE TABLE app_state (
                key TEXT PRIMARY KEY,
                value TEXT)""",
            // Settings and sync bookkeeping. type: i(nt) l(ong) b(oolean) f(loat) s(tring) set
            """CREATE TABLE setting (
                profile_id TEXT NOT NULL,
                ns TEXT NOT NULL,
                key TEXT NOT NULL,
                type TEXT NOT NULL,
                value TEXT,
                PRIMARY KEY (profile_id, ns, key))""",
            """CREATE TABLE checkin (
                profile_id TEXT NOT NULL,
                id TEXT NOT NULL,
                position INTEGER NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('checkin', 'task')),
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL,
                about_yesterday INTEGER NOT NULL,
                commitment TEXT NOT NULL,
                alert TEXT NOT NULL CHECK (alert IN ('sound', 'vibrate', 'quiet')),
                sound_uri TEXT,
                sound_title TEXT NOT NULL DEFAULT '',
                snooze_minutes INTEGER NOT NULL,
                voice_source TEXT NOT NULL,
                voice_text TEXT NOT NULL DEFAULT '',
                voice_headphones_only INTEGER NOT NULL,
                repeat_kind TEXT NOT NULL,
                repeat_days INTEGER NOT NULL DEFAULT 0,
                repeat_date TEXT,
                background_kind TEXT NOT NULL,
                background_interval_sec INTEGER NOT NULL,
                background_caption TEXT NOT NULL DEFAULT '',
                time_commitment TEXT NOT NULL DEFAULT '',
                follows_up_id TEXT,
                assignee TEXT NOT NULL DEFAULT '',
                done_at INTEGER,
                done_by TEXT,
                backlog_at INTEGER,
                linked_from TEXT,
                linked_assignee TEXT NOT NULL DEFAULT '',
                linked_gone INTEGER NOT NULL DEFAULT 0,
                linked_done_at INTEGER,
                updated_at INTEGER,
                PRIMARY KEY (profile_id, id))""",
            """CREATE TABLE checkin_trigger (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                kind TEXT NOT NULL,
                hour INTEGER,
                minute INTEGER,
                repeat_kind TEXT,
                repeat_days INTEGER,
                repeat_date TEXT,
                where_mode TEXT NOT NULL,
                place_name TEXT,
                place_lat REAL,
                place_lon REAL,
                place_radius_m INTEGER,
                window_start_min INTEGER,
                window_end_min INTEGER,
                once_per_day INTEGER NOT NULL,
                min_run_minutes INTEGER NOT NULL,
                PRIMARY KEY (profile_id, checkin_id, position),
                FOREIGN KEY (profile_id, checkin_id) REFERENCES checkin (profile_id, id) ON DELETE CASCADE)""",
            """CREATE TABLE step (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                id TEXT NOT NULL,
                kind TEXT NOT NULL,
                math_count INTEGER NOT NULL,
                math_level INTEGER NOT NULL,
                scan_code TEXT,
                scan_label TEXT NOT NULL DEFAULT '',
                pick_count INTEGER NOT NULL,
                PRIMARY KEY (profile_id, checkin_id, position),
                FOREIGN KEY (profile_id, checkin_id) REFERENCES checkin (profile_id, id) ON DELETE CASCADE)""",
            """CREATE TABLE question (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                step_position INTEGER NOT NULL,
                position INTEGER NOT NULL,
                id TEXT NOT NULL,
                text TEXT NOT NULL,
                type TEXT NOT NULL,
                yes_is_good INTEGER NOT NULL,
                PRIMARY KEY (profile_id, checkin_id, step_position, position),
                FOREIGN KEY (profile_id, checkin_id, step_position) REFERENCES step (profile_id, checkin_id, position) ON DELETE CASCADE)""",
            """CREATE TABLE checkin_share (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                connection_id TEXT NOT NULL,
                PRIMARY KEY (profile_id, checkin_id, connection_id),
                FOREIGN KEY (profile_id, checkin_id) REFERENCES checkin (profile_id, id) ON DELETE CASCADE)""",
            // extra: any fields of a result this version doesn't know (from a newer app on
            // another device), kept so they go back out unchanged.
            """CREATE TABLE slot_result (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                day TEXT NOT NULL,
                time TEXT NOT NULL,
                status TEXT NOT NULL,
                early INTEGER NOT NULL DEFAULT 0,
                soft INTEGER NOT NULL DEFAULT 0,
                rang_at INTEGER,
                took_start_s INTEGER,
                took_steps_s INTEGER,
                changed_at INTEGER,
                extra TEXT,
                PRIMARY KEY (profile_id, checkin_id, day, time))""",
            "CREATE INDEX slot_result_day ON slot_result (profile_id, day)",
            """CREATE TABLE answer (
                profile_id TEXT NOT NULL,
                checkin_id TEXT NOT NULL,
                day TEXT NOT NULL,
                time TEXT NOT NULL,
                question_id TEXT NOT NULL,
                yes_no INTEGER,
                value TEXT,
                PRIMARY KEY (profile_id, checkin_id, day, time, question_id),
                FOREIGN KEY (profile_id, checkin_id, day, time) REFERENCES slot_result (profile_id, checkin_id, day, time) ON DELETE CASCADE)""",
            """CREATE TABLE pending (
                profile_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                checkin_id TEXT NOT NULL,
                day TEXT NOT NULL,
                time TEXT NOT NULL,
                practice INTEGER NOT NULL,
                PRIMARY KEY (profile_id, checkin_id, day, time, practice))""",
            """CREATE TABLE deleted_item (
                profile_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                deleted_at INTEGER NOT NULL,
                PRIMARY KEY (profile_id, item_id))""",
        )
    }
}

// ---------------------------------------------------------------- cursor helpers

internal fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))
internal fun Cursor.strOrNull(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
internal fun Cursor.intOrNull(col: String): Int? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
internal fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
internal fun Cursor.longOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
internal fun Cursor.dbl(col: String): Double = getDouble(getColumnIndexOrThrow(col))
internal fun Cursor.bool(col: String): Boolean = getInt(getColumnIndexOrThrow(col)) != 0

internal inline fun <T> Cursor.rows(block: (Cursor) -> T): List<T> = use { c ->
    val out = ArrayList<T>(c.count)
    while (c.moveToNext()) out.add(block(c))
    out
}

internal fun ContentValues.putBool(key: String, v: Boolean) = put(key, if (v) 1 else 0)
