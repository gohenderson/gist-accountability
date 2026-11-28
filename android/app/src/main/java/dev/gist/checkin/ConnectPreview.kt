package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a connection code says about the side that showed it, so the side scanning it can check
 * it's connecting to the right device, the right way, before anything is saved: who (their
 * name, their device's), how (automatic or manual), what for (your devices, or a person) and, for
 * your own devices, a few of the check-ins and tasks there. It rides in the QR code beside the
 * invite ([withPreview], [split]); a typed code has none.
 */
data class ConnectPreview(
    /** Their name (a person), or the owner's (your devices). */
    val name: String? = null,
    /** The device showing the code. */
    val device: String? = null,
    /** A few check-in and task names (your devices only: a person shares nothing yet). */
    val sample: List<String> = emptyList(),
    /** How many check-ins and tasks there are in all (your devices only). */
    val count: Int = 0,
) {
    fun encode(): String {
        val o = JSONObject()
        name?.let { o.put("n", it) }
        device?.let { o.put("d", it) }
        if (sample.isNotEmpty()) o.put("s", JSONArray(sample)).put("c", count)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    companion object {
        /** How many names go in the sample, and how long each can be. */
        const val SAMPLE = 3
        const val NAME_MAX = 40

        fun decode(text: String): ConnectPreview? = runCatching {
            val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(text)))
            val s = o.optJSONArray("s")
            ConnectPreview(
                name = o.optString("n").trim().take(64).ifEmpty { null },
                device = o.optString("d").trim().take(64).ifEmpty { null },
                sample = if (s == null) emptyList() else (0 until minOf(s.length(), SAMPLE)).map { s.getString(it).take(NAME_MAX) },
                count = o.optInt("c").coerceAtLeast(0),
            )
        }.getOrNull()

        /** What this side shows: [kind] 'd' your devices (with a sample), 'c' a person. */
        fun of(context: Context, kind: Char): ConnectPreview {
            val me = Connections(context).myName.trim().ifEmpty { null }
            val device = Devices(context).name.trim().ifEmpty { android.os.Build.MODEL }
            if (kind != 'd') return ConnectPreview(name = me, device = device)
            val all = Store(context).checkins
            val shown = (all.filter { it.enabled } + all.filterNot { it.enabled }).take(SAMPLE)
                .map { it.name.trim().let { n -> if (n.length > NAME_MAX) n.take(NAME_MAX - 1) + "…" else n } }
            return ConnectPreview(name = me, device = device, sample = shown, count = all.size)
        }

        /** [qr] with [preview] after it ("<qr>~<preview>"). */
        fun withPreview(qr: String, preview: ConnectPreview): String = qr + SEP + preview.encode()

        /** A scanned code and the preview riding with it, if any. */
        fun split(text: String): Pair<String, ConnectPreview?> {
            val t = text.trim()
            val i = t.indexOf(SEP)
            return if (i < 0) t to null else t.substring(0, i) to decode(t.substring(i + 1))
        }

        /** Not in either code (letters, digits, ':' and '-' only) or in URL-safe base64. */
        private const val SEP = '~'
    }
}
