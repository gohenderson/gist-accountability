package dev.gist.checkin

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Place triggers, using Android's own location service (no Google Play services, no
 * internet). Arriving at / leaving a place uses LocationManager proximity alerts, which
 * Android keeps watching even while Gist isn't running; they're re-registered after a
 * reboot, an app update or a force stop.
 */
object LocationTriggers {
    private const val PREFS = "gist-geo"
    /** Android may report "inside" right after registering; ignore events this soon after. */
    private const val SETTLE_MS = 90_000L

    // ------------------------------------------------------------ permissions

    fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time": needed to notice arrivals and check location while Gist is closed. */
    fun hasBackground(context: Context): Boolean = hasLocation(context) &&
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun anyUseLocation(context: Context): Boolean = Store(context).checkins.any { it.enabled && it.trigger.usesLocation }

    // ------------------------------------------------------------ arrive / leave

    private fun pendingIntent(context: Context, checkinId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, ProximityReceiver::class.java).putExtra(AlarmScheduler.EXTRA_CHECKIN, checkinId)
        // Mutable: Android adds KEY_PROXIMITY_ENTERING to it. The intent is explicit (this
        // app's own non-exported receiver), so nothing else can be substituted.
        return PendingIntent.getBroadcast(context, requestCode(checkinId), intent, flags or PendingIntent.FLAG_MUTABLE)
    }

    private fun requestCode(id: String) = 0x40000000 + (id.hashCode() and 0x0FFFFFFF)

    private fun signature(c: Checkin): String =
        c.trigger.place?.let { "${c.trigger.kind}:${it.lat}:${it.lon}:${it.radiusM}" } ?: ""

    /** Is [checkinId]'s place being watched right now? */
    fun isRegistered(context: Context, checkinId: String): Boolean =
        pendingIntent(context, checkinId, PendingIntent.FLAG_NO_CREATE) != null

    /**
     * Starts (or stops) watching [c]'s place to match its settings. Does nothing if it's
     * already watched with the same place, so calling it often is fine.
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context, c: Checkin) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val want = c.enabled && c.trigger.isPlace && c.trigger.place != null && hasLocation(context)
        val lm = context.getSystemService(LocationManager::class.java)
        if (!want) {
            remove(context, c.id)
            return
        }
        if (isRegistered(context, c.id) && prefs.getString(c.id, null) == signature(c)) return
        remove(context, c.id)
        val p = c.trigger.place!!
        val pi = pendingIntent(context, c.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            lm.addProximityAlert(p.lat, p.lon, p.radiusM.toFloat(), -1, pi)
            prefs.edit().putString(c.id, signature(c)).putLong("${c.id}@", System.currentTimeMillis()).apply()
        } catch (e: SecurityException) {
            pi.cancel()
        }
    }

    fun remove(context: Context, checkinId: String) {
        val pi = pendingIntent(context, checkinId, PendingIntent.FLAG_NO_CREATE)
        if (pi != null) {
            try {
                context.getSystemService(LocationManager::class.java).removeProximityAlert(pi)
            } catch (e: SecurityException) {
            }
            pi.cancel()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(checkinId).remove("$checkinId@").apply()
    }

    /** Just registered (Android often reports "you're inside" straight away)? */
    fun settling(context: Context, checkinId: String, now: Long = System.currentTimeMillis()): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("$checkinId@", 0L)
        return at > 0 && now - at < SETTLE_MS && !ignoreSettling
    }

    /** Tests turn off the settling period. */
    @Volatile
    var ignoreSettling = false

    // ------------------------------------------------------------ where am I?

    /** Tests replace the real location lookup with a fixed answer. */
    @Volatile
    var locateOverride: ((Context) -> Location?)? = null

    /**
     * Finds the phone's location (a fresh fix if Android can get one within [timeoutMs],
     * else the most recent known one) and calls [done] on the main thread; null if unknown.
     */
    @SuppressLint("MissingPermission")
    fun locate(context: Context, timeoutMs: Long = 15_000L, done: (Location?) -> Unit) {
        locateOverride?.let { f -> done(f(context)); return }
        if (!hasLocation(context)) { done(null); return }
        val lm = context.getSystemService(LocationManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (Build.VERSION.SDK_INT < 30) { done(last); return }
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
        if (provider == null) { done(last); return }
        var finished = false
        val cancel = CancellationSignal()
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            cancel.cancel()
            done(l ?: last)
        }
        main.postDelayed({ finish(null) }, timeoutMs)
        try {
            lm.getCurrentLocation(provider, cancel, context.mainExecutor) { finish(it) }
        } catch (e: Exception) {
            finish(null)
        }
    }

    fun distanceTo(p: Place, l: Location): Double = Trigger.distanceM(p.lat, p.lon, l.latitude, l.longitude)

    // ------------------------------------------------------------ ringing

    /**
     * Rings [c] now for a place event, through an alarm-clock alarm a moment from now:
     * Android lets alarm clocks start the ringing service from the background, which a
     * location event alone may not.
     */
    fun ringNow(context: Context, c: Checkin) {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val day = Days.forAlarmNow(c.aboutYesterday, now)
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_CHECKIN, c.id)
            .putExtra(AlarmScheduler.EXTRA_TIME, time)
            .putExtra(AlarmScheduler.EXTRA_DAY, day)
            .putExtra(AlarmReceiver.EXTRA_PLACE, true)
        val pi = PendingIntent.getBroadcast(
            context, requestCode(c.id) + 0x08000000 and 0x7FFFFFFF, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + 500
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Android tells us the phone entered or left a check-in's place. */
class ProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_CHECKIN) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val store = Store(context)
        val c = store.checkin(id) ?: run { LocationTriggers.remove(context, id); return }
        if (LocationTriggers.settling(context, id)) return
        val now = LocalDateTime.now()
        val day = Days.forAlarmNow(c.aboutYesterday, now)
        val rangToday = store.results(day, id).values.any { it.status != SlotResult.SKIPPED } ||
            store.pending.any { it.checkinId == id && !it.practice }
        if (Trigger.placeEventRings(c, entering, now, rangToday)) LocationTriggers.ringNow(context, c)
    }
}
