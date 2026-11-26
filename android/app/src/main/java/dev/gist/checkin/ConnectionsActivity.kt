package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Connections: share check-ins and tasks with another copy of Gist (your partner's phone,
 * or Gist B on this one) through a folder you both can reach. One copy invites (picks an
 * empty folder, and shows an invite: a QR code, or a code to type); the other joins (scans or
 * types the invite, then picks the same folder). The invite never goes in the folder, so only
 * whoever saw it can join. Each sees what the other shares, read-only. Later, Syncthing keeps
 * the folder in step between phones. Your own devices join each other the same way.
 */
class ConnectionsActivity : BaseActivity() {
    private lateinit var links: Connections
    /** The invite scanned or typed, waiting for its folder to be picked. */
    private var pendingInvite: String? = null
    /** The connection being taken over, waiting for its folder to be picked. */
    private var pendingAdopt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links = Connections(this)
        pendingInvite = savedInstanceState?.getString(STATE_INVITE)
        pendingAdopt = savedInstanceState?.getString(STATE_ADOPT)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_INVITE, pendingInvite)
        outState.putString(STATE_ADOPT, pendingAdopt)
    }

    override fun onResume() {
        super.onResume()
        render()
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
    }

    private fun render() {
        val col = column()
        col.add(header("Connections", "Share check-ins and tasks with another copy of Gist"))

        // Your name
        val me = card()
        me.add(sectionTitle("Your name"))
        me.add(muted("What the people you connect with see.", 14f), dp(4))
        me.add(EditText(this).apply {
            setText(links.myName)
            hint = "e.g. Alex"
            tag = "my-name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            setOnFocusChangeListener { _, has -> if (!has) links.myName = text.toString() }
        }, dp(4))
        col.add(me, dp(20))
        col.add(devicesCard(), dp(12))

        // New
        val add = card()
        add.add(sectionTitle("New connection"))
        add.add(label("Each connection uses its own folder that both copies of Gist can reach: on one phone, any " +
            "folder (say Documents/GistLink); between phones, a folder Syncthing keeps in step. One side invites " +
            "(picks an empty folder and shows an invite code), the other joins (scans or types the code, then " +
            "picks the same folder). What you share is encrypted, and only you can change it.", 15f), dp(6))
        val r = row()
        r.addWeighted(primaryButton("Invite…") { pick(REQ_INVITE) }.apply { tag = "invite" })
        r.addWeighted(outlineButton("Join…") { askForInvite('c') }.apply { tag = "join" }, startMargin = dp(8))
        add.add(r, dp(12))
        col.add(add, dp(12))

        // Each connection
        val all = links.all
        if (all.isEmpty()) {
            col.add(muted("No connections yet.", 15f), dp(16))
        }
        for (l in all) col.add(linkCard(l), dp(12))

        if (all.isNotEmpty()) {
            col.add(textButton("Check for changes now") {
                Connections.syncInBackground(this) { render() }
                toast("Checking…")
            }.apply { tag = "sync-now" }, dp(8))
        }
        setContentView(screen(col))
    }

    /** Your own devices: everything kept in step between them; whether this one rings. */
    private fun devicesCard() = card().apply {
        val devices = Devices(this@ConnectionsActivity)
        val store = Store(this@ConnectionsActivity)
        add(sectionTitle("Your devices"))
        if (devices.folder == null) {
            add(label("Keep all your check-ins, tasks and answers in step on your phone and tablet (say). Set up on " +
                "one (pick an empty folder); then, for each other device, tap Add a device on one that's in and " +
                "Join on the new one (scan or type its code, then pick the same folder, kept in step by Syncthing).", 15f), dp(6))
            val r = row()
            r.addWeighted(primaryButton("Set up…") { pick(REQ_DEVICES_SETUP) }.apply { tag = "devices-setup" })
            r.addWeighted(outlineButton("Join…") { askForInvite('d') }.apply { tag = "devices-join" }, startMargin = dp(8))
            add(r, dp(12))
        } else {
            add(EditText(context).apply {
                setText(devices.name)
                hint = "This device's name"
                tag = "device-name"
                isSingleLine = true
                setOnFocusChangeListener { _, has -> if (!has) devices.name = text.toString() }
            }, dp(4))
            if (!devices.ready) {
                add(label("Waiting to be let in: open Gist on the device whose code you used.", 15f, color = colorWarn)
                    .apply { tag = "devices-waiting" }, dp(6))
            }
            val others = devices.others()
            if (others.isEmpty()) add(muted("No other devices yet: tap Add a device, then Join on the other device.", 14f), dp(6))
            others.forEach { (side, name, code) ->
                val r = row()
                r.addWeighted(label("$name · code $code", 15f).apply { tag = "device:$side" })
                if (devices.ready) r.addView(textButton("Remove") { confirmRemoveDevice(devices, side, name) }
                    .apply { tag = "device-remove:$side" })
                add(r, dp(6))
            }
            add(muted("Each pair shows the same code on both devices when nobody swapped a key.", 13f), dp(2))
            if (devices.ready) {
                add(outlineButton("Add a device…") {
                    devices.invitation()?.let { (inv, until) ->
                        showInvite("Add a device", "On the other device, open Connections, tap Join under Your devices, " +
                            "scan this (or type the code), then pick the same folder.", inv, until)
                    }
                }.apply { tag = "devices-add" }, dp(8))
            }
            add(textButton("Stop syncing on this device", colorOf(R.color.bad)) {
                AlertDialog.Builder(this@ConnectionsActivity)
                    .setTitle("Stop syncing here?")
                    .setMessage("Everything stays on this device; it stops keeping in step with your others, and they " +
                        "stop listing it.")
                    .setPositiveButton("Stop") { _, _ -> devices.leave(); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }.apply { tag = "devices-leave" }, dp(4))
        }
        add(styledSwitch("Ring on this device").apply {
            isChecked = store.ringHere
            tag = "ring-here"
            setOnCheckedChangeListener { _, on ->
                store.ringHere = on
                render()
            }
        }, dp(10))
        if (!store.ringHere) {
            add(muted("Check-ins don't ring here: they remind quietly and wait on the start page, where you can still check in or dismiss them.", 13f), dp(2))
        }
    }

    private fun confirmRemoveDevice(devices: Devices, side: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("It stops getting your changes, and your devices switch to a new key it doesn't have, so it " +
                "can't read anything synced from now on. Use this for a device that's lost or that you can't open; " +
                "on one you have, tap Stop syncing there instead. What's already on it stays on it.")
            .setPositiveButton("Remove") { _, _ ->
                devices.remove(side)
                render()
                Devices.syncInBackground(this) { render() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun linkCard(l: Link) = card().apply {
        tag = "link:${l.id}"
        add(label(if (l.connected) l.peerName.ifEmpty { "Someone" } else "Waiting for someone to join…", 18f, bold = true)
            .apply { tag = "link-name:${l.id}" })
        if (l.connected) {
            add(muted("Code ${l.code}: it's the same on their phone when nobody swapped a key. Compare it once.", 14f)
                .apply { tag = "link-code:${l.id}" }, dp(4))
            val mine = Store(context).checkins.count { l.id in it.sharedWith }
            val theirs = links.shared(l).size
            add(muted("You share ${plural(mine, "item")} · they share ${plural(theirs, "item")}", 14f)
                .apply { tag = "link-counts:${l.id}" }, dp(4))
            add(styledSwitch("Show on the start page").apply {
                isChecked = l.shown
                tag = "link-shown:${l.id}"
                setOnCheckedChangeListener { _, on -> links.setShown(l.id, on) }
            }, dp(8))
        } else if (l.isHere) {
            add(muted("Show them the invite: they tap Join, scan it (or type the code) and pick the same folder.", 14f), dp(4))
            add(outlineButton("Show the invite") { showLinkInvite(l) }.apply { tag = "link-invite:${l.id}" }, dp(8))
        } else {
            add(muted("The invite is on ${l.homeName.ifEmpty { "your other device" }}.", 14f), dp(4))
        }
        if (!l.isHere) {
            val home = l.homeName.ifEmpty { "your other device" }
            add(muted("Kept on $home: it reads and shares for this connection, and what you change here reaches it " +
                "through your devices.", 13f).apply { tag = "link-home:${l.id}" }, dp(8))
            add(textButton("Keep it on this device instead…") { confirmAdopt(l) }.apply { tag = "link-adopt:${l.id}" }, dp(4))
        } else {
            if (Devices(context).folder != null) {
                add(muted("Kept on this device: it reads and shares for this connection for all your devices.", 13f)
                    .apply { tag = "link-home:${l.id}" }, dp(8))
            }
            add(textButton("Remove", colorOf(R.color.bad)) { confirmRemove(l) }.apply { tag = "link-remove:${l.id}" }, dp(4))
        }
    }

    private fun confirmAdopt(l: Link) {
        val home = l.homeName.ifEmpty { "your other device" }
        AlertDialog.Builder(this)
            .setTitle("Keep it on this device?")
            .setMessage("This device takes over reading and sharing for ${l.peerName.ifEmpty { "this connection" }}, " +
                "and $home stops once it hears. Do this when $home is gone or won't be opening Gist: if both share " +
                "at once before hearing, changes can clash.\n\nNext, pick the connection's folder as this device " +
                "reaches it (the one Syncthing keeps in step).")
            .setPositiveButton("Pick the folder") { _, _ ->
                pendingAdopt = l.id
                pick(REQ_ADOPT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun confirmRemove(l: Link) {
        AlertDialog.Builder(this)
            .setTitle("Remove this connection?")
            .setMessage("You stop sharing with ${l.peerName.ifEmpty { "them" }} and no longer see what they share. " +
                "The folder is left as it is.")
            .setPositiveButton("Remove") { _, _ ->
                links.remove(l.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLinkInvite(l: Link) {
        links.invitation(l)?.let { (inv, until) ->
            showInvite("Invite", "On the other phone (or Gist B), open Connections, tap Join, scan this (or type the " +
                "code), then pick the same folder.", inv, until)
        }
    }

    /** Shows [inv] as a QR code and a code to type, until [until]. */
    private fun showInvite(title: String, how: String, inv: LinkInvite, until: Long) {
        val col = column()
        col.add(label(how, 15f))
        col.add(ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(qr(inv.qr, dp(240)))
            contentDescription = "Invite QR code"
            tag = "invite-qr"
        }, dp(12))
        col.add(label(inv.code, 18f, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            tag = "invite-code"
        }, dp(8))
        col.add(muted("Works once, until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))}. Don't send it through the shared folder.", 13f), dp(6))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(col.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }) })
            .setPositiveButton("Done") { _, _ -> render() }
            .setNeutralButton("Copy code") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Gist invite", inv.code))
                toast("Copied")
            }
            .show()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Joining: get the invite ([kind] 'c' a connection, 'd' your devices) by scanning or typing, then the folder. */
    private fun askForInvite(kind: Char) {
        if (kind == 'c' && links.myName.isBlank()) {
            toast("Add your name first, so they know it's you.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (kind == 'c') "Join a connection" else "Join your devices")
            .setMessage("Scan the invite's QR code on the other " + (if (kind == 'c') "phone" else "device") +
                ", or type the code shown under it.")
            .setPositiveButton("Scan") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_TITLE, "Scan the invite")
                    .putExtra(ScanActivity.EXTRA_TEXT, "Point the camera at the QR code the other device shows."),
                    if (kind == 'c') REQ_SCAN_JOIN else REQ_SCAN_DEVICES)
            }
            .setNeutralButton("Type the code") { _, _ -> typeInvite(kind) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun typeInvite(kind: Char) {
        val field = EditText(this).apply {
            hint = "ABCD-EFGH-…"
            tag = "invite-field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Type the code")
            .setView(column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)); add(field) })
            .setPositiveButton("Next") { _, _ -> gotInvite(kind, field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Checks the scanned or typed [text] is a [kind] invite, then asks for the folder. */
    private fun gotInvite(kind: Char, text: String) {
        val inv = LinkInvite.parse(text)
        when {
            inv == null -> toast("That isn't a Gist invite code: check it and try again.")
            inv.kind != kind -> toast(if (kind == 'c') "That's an invite for your devices: use Join under Your devices."
                else "That's an invite for a connection: use Join under New connection.")
            else -> {
                pendingInvite = inv.code
                toast("Now pick the same folder they picked.")
                pick(if (kind == 'c') REQ_JOIN else REQ_DEVICES_JOIN)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pick(request: Int) {
        if (links.myName.isBlank() && request in setOf(REQ_INVITE, REQ_JOIN)) {
            toast("Add your name first, so they know it's you.")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), request)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN_JOIN || requestCode == REQ_SCAN_DEVICES) {
            val code = data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (resultCode == RESULT_OK && code != null) gotInvite(if (requestCode == REQ_SCAN_JOIN) 'c' else 'd', code)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (uri.scheme != "content" || uri.authority.orEmpty().startsWith(packageName)) {
            toast("That folder isn't supported.")
            return
        }
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            when (requestCode) {
                REQ_DEVICES_SETUP -> {
                    val f = LinkFolder.open(this, uri.toString())
                    if (Devices.groupOf(f) != null) {
                        toast("Your devices are already set up in that folder: tap Join instead.")
                        return
                    }
                    Devices(this).setUp(uri.toString())
                    toast("Set up. Now tap Join on your other devices and pick the same folder.")
                }
                REQ_DEVICES_JOIN -> {
                    val inv = pendingInvite?.let { LinkInvite.parse(it) } ?: return toast("Scan or type the invite first.")
                    when (Devices(this).join(uri.toString(), inv)) {
                        Devices.JoinProblem.NOT_DEVICES -> return toast("That folder isn't set up for your devices: tap Set up on one of them first.")
                        Devices.JoinProblem.NOT_THEIR_INVITE -> return toast("That invite isn't from a device in that folder: check you picked the same folder.")
                        null -> toast("Joined. Open Gist on the device that showed the invite to let this one in.")
                    }
                    pendingInvite = null
                }
                REQ_INVITE -> {
                    if (LinkProtocol.linkId(LinkFolder.open(this, uri.toString())) != null) {
                        toast("That folder already has a connection: tap Join to join it, or pick an empty folder.")
                        return
                    }
                    val l = links.invite(uri.toString())
                    render()
                    showLinkInvite(l)
                }
                REQ_ADOPT -> {
                    val id = pendingAdopt ?: return
                    when (links.adopt(id, uri.toString())) {
                        Connections.AdoptProblem.NOT_ITS_FOLDER -> return toast("That isn't this connection's folder: pick the one Syncthing keeps in step with the other person.")
                        Connections.AdoptProblem.NOT_KEPT_ELSEWHERE -> toast("It's already kept here.")
                        null -> toast("Kept on this device now.")
                    }
                    pendingAdopt = null
                    Devices.syncInBackground(this) { render() } // tell your other devices
                    Connections.syncInBackground(this) { render() }
                }
                REQ_JOIN -> {
                    val inv = pendingInvite?.let { LinkInvite.parse(it) } ?: return toast("Scan or type the invite first.")
                    val (l, problem) = links.join(uri.toString(), inv)
                    when (problem) {
                        Connections.JoinProblem.NOT_A_CONNECTION -> return toast("There's no invite to join in that folder.")
                        Connections.JoinProblem.ALREADY_YOURS -> return toast("You're already in that connection.")
                        Connections.JoinProblem.NOT_THEIR_INVITE -> return toast("That invite isn't for the connection in that folder: check you picked the same folder.")
                        null -> toast("Connected with ${l?.peerName?.ifEmpty { "them" } ?: "them"}")
                    }
                    pendingInvite = null
                }
            }
        } catch (e: Exception) {
            toast("That folder can't be used: ${e.message}")
        }
        render()
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_INVITE = 21
        private const val REQ_JOIN = 22
        private const val REQ_DEVICES_SETUP = 23
        private const val REQ_DEVICES_JOIN = 24
        private const val REQ_SCAN_JOIN = 25
        private const val REQ_SCAN_DEVICES = 26
        private const val STATE_INVITE = "pendingInvite"
        private const val STATE_ADOPT = "pendingAdopt"
        private const val REQ_ADOPT = 27

        fun intent(context: Context) = Intent(context, ConnectionsActivity::class.java)
    }
}
