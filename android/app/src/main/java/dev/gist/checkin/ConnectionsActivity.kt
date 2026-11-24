package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * Connections: share check-ins and tasks with another copy of Gist (your partner's phone,
 * or Gist B on this one) through a folder you both can reach. One copy invites (picks an
 * empty folder); the other joins (picks the same folder). Each sees what the other shares,
 * read-only. Later, Syncthing keeps the folder in step between phones.
 */
class ConnectionsActivity : BaseActivity() {
    private lateinit var links: Connections

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links = Connections(this)
        render()
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
            "(picks an empty folder), the other joins (picks the same folder). What you share is encrypted, and " +
            "only you can change it.", 15f), dp(6))
        val r = row()
        r.addWeighted(primaryButton("Invite…") { pick(REQ_INVITE) }.apply { tag = "invite" })
        r.addWeighted(outlineButton("Join…") { pick(REQ_JOIN) }.apply { tag = "join" }, startMargin = dp(8))
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
                "one (pick an empty folder), then Join on the others (the same folder, kept in step by Syncthing).", 15f), dp(6))
            val r = row()
            r.addWeighted(primaryButton("Set up…") { pick(REQ_DEVICES_SETUP) }.apply { tag = "devices-setup" })
            r.addWeighted(outlineButton("Join…") { pick(REQ_DEVICES_JOIN) }.apply { tag = "devices-join" }, startMargin = dp(8))
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
                add(label("Waiting to be let in: open Gist on one of your devices that's already in.", 15f, color = colorWarn)
                    .apply { tag = "devices-waiting" }, dp(6))
            }
            val others = devices.others()
            if (others.isEmpty()) add(muted("No other devices yet: tap Join on another device and pick the same folder.", 14f), dp(6))
            others.forEach { (side, name, code) ->
                add(label("$name · code $code", 15f).apply { tag = "device:$side" }, dp(6))
            }
            add(muted("Each pair shows the same code on both devices when nobody swapped a key.", 13f), dp(2))
            add(textButton("Stop syncing on this device", colorOf(R.color.bad)) {
                AlertDialog.Builder(this@ConnectionsActivity)
                    .setTitle("Stop syncing here?")
                    .setMessage("Everything stays on this device; it just stops keeping in step with your others.")
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
        } else {
            add(muted("Ask them to tap Join and pick the same folder.", 14f), dp(4))
        }
        add(textButton("Remove", colorOf(R.color.bad)) { confirmRemove(l) }.apply { tag = "link-remove:${l.id}" }, dp(4))
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
                    if (!Devices(this).join(uri.toString())) {
                        toast("That folder isn't set up for your devices: tap Set up on one of them first.")
                        return
                    }
                    toast("Joined. Open Gist on a device that's already in to let this one in.")
                }
                REQ_INVITE -> {
                    if (LinkProtocol.linkId(LinkFolder.open(this, uri.toString())) != null) {
                        toast("That folder already has a connection: tap Join to join it, or pick an empty folder.")
                        return
                    }
                    links.invite(uri.toString())
                    toast("Invite ready. Now tap Join on the other copy and pick the same folder.")
                }
                REQ_JOIN -> {
                    val l = links.join(uri.toString())
                    if (l == null) {
                        toast("There's no invite to join in that folder (or it's already yours).")
                        return
                    }
                    toast(if (l.connected) "Connected with ${l.peerName.ifEmpty { "them" }}" else "Joined")
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

        fun intent(context: Context) = Intent(context, ConnectionsActivity::class.java)
    }
}
