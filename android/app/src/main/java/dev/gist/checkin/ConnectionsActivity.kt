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
    /** What to do once access to the Gist folder is allowed: [AFTER_INVITE] or [AFTER_DEVICES]. */
    private var afterAccess: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links = Connections(this)
        afterAccess = savedInstanceState?.getString(STATE_AFTER)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AFTER, afterAccess)
    }

    override fun onResume() {
        super.onResume()
        tryWaiting(quiet = true)
        render()
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
        if (EasyConnect.isOn(this)) {
            SyncthingService.start(this)
            refresher.run()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** While Easy Connect is on: the status line, and a join waiting for My devices, every few seconds. */
    private val refresher = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            Thread {
                val status = EasyConnect.status(this@ConnectionsActivity)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val changed = status != easyStatus || GistFolder(this@ConnectionsActivity).waiting != null
                    easyStatus = status
                    if (GistFolder(this@ConnectionsActivity).waiting != null) tryWaiting(quiet = true)
                    else if (changed) render()
                }
            }.start()
            handler.postDelayed(this, 3_000)
        }
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
        col.add(gistFolderCard(), dp(12))
        col.add(devicesCard(), dp(12))

        // New
        val add = card()
        add.add(sectionTitle("New connection"))
        add.add(label("One side invites: Gist makes a folder for the connection in your Gist folder (People/Jess, " +
            "say) and shows an invite code; share that folder with them. The other joins: scans or types the " +
            "code, and Gist finds the folder once it's arrived. What you share is encrypted, and " +
            "only you can change it.", 15f), dp(6))
        val r = row()
        r.addWeighted(primaryButton("Invite…") { invite() }.apply { tag = "invite" })
        r.addWeighted(outlineButton("Join…") { askForInvite('c') }.apply { tag = "join" }, startMargin = dp(8))
        add.add(r, dp(12))
        if (SyncthingEngine.shared(this).available) {
            add.add(sectionTitle("Easy Connect"), dp(14))
            add.add(muted("No folder to share by hand: Gist connects your device and theirs itself. One of you shows a " +
                "code, the other scans it.", 13f), dp(4))
            val e = row()
            e.addWeighted(primaryButton("Show a code") { easyReady { if (needName()) askWhoFor(easy = true) } }.apply { tag = "easy-person-offer" })
            e.addWeighted(outlineButton("Scan a code") { easyReady { if (needName()) easyScan() } }.apply { tag = "easy-person-join" },
                startMargin = dp(8))
            add.add(e, dp(8))
        }
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

    /** Where this profile syncs (Gist/<profile>), and a join waiting for its folder. */
    private fun gistFolderCard() = card().apply {
        val gf = GistFolder(this@ConnectionsActivity)
        add(sectionTitle("Your Gist folder"))
        val root = gf.root
        if (root == null) {
            add(label("Gist syncs through folders in a folder named ${GistRoot.NAME} at the top of your phone's storage, " +
                "where the app you share folders with can reach them too. Android needs you to allow access to it once; " +
                "Gist makes everything inside it, for every profile.", 15f), dp(6))
            add(muted("If it isn't there yet: in the picker, make a new folder named ${GistRoot.NAME} at the top of your " +
                "storage and open it. Then tap Use this folder, and Allow.", 13f), dp(6))
            add(primaryButton("Allow access to the ${GistRoot.NAME} folder…") { requestAccess() }.apply { tag = "gist-folder-allow" }, dp(10))
        } else {
            val shown = if (root.startsWith("content://")) GistRoot.pathOf(root).removePrefix("/storage/emulated/0/") else root
            add(label(shown, 15f).apply { tag = "gist-folder" }, dp(6))
            add(muted("My devices: share it with your other devices. People: one folder per connection, " +
                "each shared with that person only.", 13f), dp(4))
        }
        gf.waiting?.let { w ->
            val what = if (w.startsWith("d:")) "My devices" else "their folder"
            add(label("Waiting for $what to arrive. Share it into " +
                (if (w.startsWith("d:")) "your Gist folder as My devices" else "People in your Gist folder") +
                "; Gist joins as soon as it's there.", 15f, color = colorWarn).apply { tag = "join-waiting" }, dp(10))
            val r = row()
            r.addWeighted(outlineButton("Look again") { tryWaiting(quiet = false) }.apply { tag = "join-look" })
            r.addWeighted(textButton("Cancel") { gf.waiting = null; render() }.apply { tag = "join-cancel" }, startMargin = dp(8))
            add(r, dp(6))
        }
    }

    /** Joins with the invite waiting for its folder, if the folder's there now. */
    private fun tryWaiting(quiet: Boolean) {
        val gf = GistFolder(this)
        val w = gf.waiting ?: return
        val inv = LinkInvite.parse(w.substring(2)) ?: run { gf.waiting = null; return }
        if (joinInGistFolder(inv, quiet)) gf.waiting = null
        render()
    }

    /**
     * Joins with [inv] in the Gist folder: your devices in My devices, a connection in the
     * folder under People that it's for. False when the folder isn't there yet.
     */
    private fun joinInGistFolder(inv: LinkInvite, quiet: Boolean): Boolean {
        val gf = GistFolder(this)
        try {
            if (inv.kind == 'd') {
                val loc = gf.devices() ?: return false
                if (Devices(this).folder != null) { toast("This device already syncs with your devices."); return true }
                return when (Devices(this).join(loc, inv)) {
                    null -> { toast("Joined. Open Gist on the device that showed the invite to let this one in."); Devices.syncInBackground(this) { render() }; true }
                    else -> { if (!quiet) toast("My devices isn't here yet, or the invite's device isn't in it yet."); false }
                }
            }
            val loc = links.findInvited(gf.people().values, inv)
                ?: run { if (!quiet) toast("Their folder isn't in People yet."); return false }
            val (l, problem) = links.join(loc, inv)
            if (problem != null || l == null) return false
            toast("Connected with ${l.peerName.ifEmpty { "them" }}")
            Connections.syncInBackground(this) { render() }
            return true
        } catch (e: Exception) {
            if (!quiet) toast("Couldn't read the Gist folder: pick it again.")
            return false
        }
    }

    /** You've a name to show them (it's in your invite and on your side of the folder). */
    private fun needName(): Boolean {
        if (links.myName.isNotBlank()) return true
        toast("Add your name first, so they know it's you.")
        return false
    }

    /** Who's the new connection for: the folder's named for them. [easy]: show an Easy Connect code. */
    private fun askWhoFor(easy: Boolean = false) {
        val field = EditText(this).apply {
            hint = "e.g. Jess"
            tag = "invite-who"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Who's it for?")
            .setMessage("Gist makes a folder for it in People in your Gist folder, named for them.")
            .setView(column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)); add(field) })
            .setPositiveButton(if (easy) "Show the code" else "Invite") { _, _ ->
                if (easy) return@setPositiveButton easyOfferPerson(field.text.toString())
                val loc = GistFolder(this).newPerson(field.text.toString()) ?: return@setPositiveButton
                try {
                    val l = links.invite(loc)
                    render()
                    showLinkInvite(l, loc.substringAfterLast('#').substringAfter("${GistFolder.PEOPLE}/"))
                } catch (e: Exception) {
                    toast("Couldn't make the folder: pick your Gist folder again.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Easy Connect: Gist shares My devices through its own
     * Syncthing, so another device only scans a code. [setUp]: this device has My devices.
     */
    private fun easyConnect(card: android.widget.LinearLayout, setUp: Boolean) {
        if (!SyncthingEngine.shared(this).available) return
        card.add(sectionTitle("Easy Connect"), dp(14))
        card.add(muted("Gist keeps your devices in step itself, with Syncthing built in: show a code on one device, " +
            "scan it on the other. My devices stays in your Gist folder, so anything else can still share it too.", 13f), dp(4))
        val ready = Devices(this).ready
        val r = row()
        if (!setUp || ready) {
            r.addWeighted(primaryButton("Show a code") { easyReady { easyOffer() } }.apply { tag = "easy-offer" })
        }
        if (!setUp) {
            r.addWeighted(outlineButton("Scan a code") { easyReady { easyScan() } }.apply { tag = "easy-join" }, startMargin = dp(8))
        }
        card.add(r, dp(8))
        if (EasyConnect.isOn(this)) {
            card.add(muted(easyStatus ?: "Easy Connect is on.", 13f).apply { tag = "easy-status" }, dp(6))
            card.add(textButton("Turn off Easy Connect") {
                EasyConnect.setOn(this, false)
                easyStatus = null
                render()
            }.apply { tag = "easy-off" }, dp(2))
        }
    }

    /** The last status line from Syncthing (read off the main thread). */
    private var easyStatus: String? = null

    /** Access to the Gist folder, and All files access for Syncthing; then [then]. */
    private fun easyReady(then: () -> Unit) {
        if (GistFolder(this).root == null) {
            toast("First allow access to the ${GistRoot.NAME} folder.")
            requestAccess()
            return
        }
        if (!SyncthingEngine.shared(this).networkAllowed()) return networkBlocked()
        if (!EasyConnect.canReachFiles()) {
            AlertDialog.Builder(this)
                .setTitle("Allow All files access")
                .setMessage("Syncthing, built into Gist, reaches the folders in your ${GistRoot.NAME} folder by their path, " +
                    "which Android calls All files access. Gist only uses it for its folders in ${GistRoot.NAME}. " +
                    "Turn it on for Gist, then come back and tap again.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { startActivity(EasyConnect.filesAccessIntent(this)) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        then()
    }

    /** Easy Connect couldn't start: why, and what to do about it if we know. */
    private fun easyFailed(e: Throwable) {
        if ((e.message ?: "").contains("EPERM") || e is SecurityException) networkBlocked()
        else toast("Couldn't start Easy Connect: ${e.message}")
    }

    /** Android won't let Gist use the network (an app setting on some phones): where to allow it. */
    private fun networkBlocked() {
        AlertDialog.Builder(this)
            .setTitle("Allow network access")
            .setMessage("Easy Connect needs the network to reach your other device, but this phone doesn't let Gist " +
                "use it. In Gist's App info, allow it: Permissions → Network on GrapheneOS, or Mobile data & Wi-Fi " +
                "(or a firewall app) on other phones. Then come back and tap again.")
            .setPositiveButton("Open App info") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shows the code for another device to scan (setting up My devices first, if it isn't). */
    private fun easyOffer() {
        if (Devices(this).folder == null) {
            val loc = GistFolder(this).devices() ?: return
            try {
                Devices(this).setUp(loc)
            } catch (e: Exception) {
                toast("Couldn't make My devices in your Gist folder.")
                return
            }
        }
        toast("Starting Easy Connect…")
        Thread {
            val result = runCatching { EasyConnect.offer(this) }
            runOnUiThread {
                result.onSuccess { (code, until) ->
                    showInvite("Easy Connect", "On the other device, open Connections and tap Scan a code under Easy " +
                        "Connect. It connects by itself; keep Gist open on both until it says it's in.", code.invite, until,
                        qrText = code.qr, typable = false)
                }.onFailure { easyFailed(it) }
                render()
            }
        }.start()
    }

    /** Shows the Easy Connect code for [person]: their folder, invite and this device's Syncthing. */
    private fun easyOfferPerson(person: String) {
        toast("Starting Easy Connect…")
        Thread {
            val result = runCatching { EasyConnect.offerPerson(this, person) }
            runOnUiThread {
                result.onSuccess { (code, until) ->
                    showInvite("Easy Connect", "On their phone, in Gist: Connections → New connection → Scan a code. " +
                        "It connects by itself; keep Gist open on both until you see each other.", code.invite, until,
                        qrText = code.qr, typable = false)
                }.onFailure { easyFailed(it) }
                render()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun easyScan() {
        startActivityForResult(Intent(this, ScanActivity::class.java)
            .putExtra(ScanActivity.EXTRA_TITLE, "Scan the Easy Connect code")
            .putExtra(ScanActivity.EXTRA_TEXT, "Point the camera at the code the other device shows."), REQ_SCAN_EASY)
    }

    private fun easyScanned(text: String) {
        val code = EasyConnect.Code.parse(text) ?: return toast(
            if (LinkInvite.parse(text) != null) "That's an ordinary invite: use Join instead." else "That isn't an Easy Connect code.")
        if (code.invite.kind == 'd' && Devices(this).folder != null) return toast("This device already syncs with your devices.")
        if (code.invite.kind == 'c' && !needName()) return
        toast("Connecting…")
        Thread {
            val result = runCatching { EasyConnect.join(this, code) }
            runOnUiThread {
                result.onSuccess {
                    toast(if (code.invite.kind == 'd') "Connecting: this device joins your devices as soon as My devices arrives."
                    else "Connecting with ${code.name ?: "them"}: you're connected as soon as their folder arrives.")
                }
                    .onFailure { easyFailed(it) }
                render()
            }
        }.start()
    }

    /** Your own devices: everything kept in step between them; whether this one rings. */
    private fun devicesCard() = card().apply {
        val devices = Devices(this@ConnectionsActivity)
        val store = Store(this@ConnectionsActivity)
        add(sectionTitle("Your devices"))
        if (devices.folder == null) {
            add(label("Keep all your check-ins, tasks and answers in step on your phone and tablet (say). Set up on " +
                "one: Gist makes My devices in your Gist folder; share it with your other devices. " +
                "Then, for each, tap Add a device on one that's in and Join on the new one (scan or type its code).", 15f), dp(6))
            val r = row()
            r.addWeighted(primaryButton("Set up…") { setUpDevices() }.apply { tag = "devices-setup" })
            r.addWeighted(outlineButton("Join…") { askForInvite('d') }.apply { tag = "devices-join" }, startMargin = dp(8))
            add(r, dp(12))
            easyConnect(this, setUp = false)
        } else {
            add(EditText(context).apply {
                setText(devices.name)
                hint = "This device's name"
                tag = "device-name"
                isSingleLine = true
                setOnFocusChangeListener { _, has -> if (!has) devices.name = text.toString() }
            }, dp(4))
            add(muted("This device's files are in from-${devices.side} in the folder.", 13f)
                .apply { tag = "device-folder" }, dp(2))
            if (!devices.ready) {
                add(label("Waiting to be let in: open Gist on the device whose code you used.", 15f, color = colorWarn)
                    .apply { tag = "devices-waiting" }, dp(6))
            }
            val others = devices.others()
            if (others.isEmpty()) add(muted("No other devices yet: tap Add a device, then Join on the other device.", 14f), dp(6))
            others.forEach { (side, name, code) ->
                val r = row()
                r.addWeighted(column().apply {
                    add(label("$name · code $code", 15f).apply { tag = "device:$side" })
                    add(muted("from-$side", 13f).apply { tag = "device-folder:$side" })
                })
                if (devices.ready) r.addView(textButton("Remove") { confirmRemoveDevice(devices, side, name) }
                    .apply { tag = "device-remove:$side" })
                add(r, dp(6))
            }
            add(muted("Each pair shows the same code on both devices when nobody swapped a key.", 13f), dp(2))
            if (devices.ready) {
                add(outlineButton("Add a device…") {
                    devices.invitation()?.let { (inv, until) ->
                        showInvite("Add a device", "On the other device, open Connections, tap Join under Your devices, " +
                            "and scan this (or type the code).", inv, until)
                    }
                }.apply { tag = "devices-add" }, dp(8))
            }
            easyConnect(this, setUp = true)
            add(textButton("Stop syncing on this device", colorOf(R.color.bad)) {
                AlertDialog.Builder(this@ConnectionsActivity)
                    .setTitle("Stop syncing here?")
                    .setMessage("Everything stays on this device; it stops keeping in step with your others, and they " +
                        "stop listing it.")
                    .setPositiveButton("Stop") { _, _ ->
                        devices.group?.let { g -> Thread { EasyConnect.unshare(this@ConnectionsActivity, EasyConnect.folderId(g)) }.start() }
                        devices.leave()
                        render()
                    }
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
            add(muted("In its folder: yours is from-${l.side}, theirs from-${l.peer}.", 13f)
                .apply { tag = "link-folders:${l.id}" }, dp(2))
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
                "at once before hearing, changes can clash. Its folder needs to be on this device too (in People in " +
                "your Gist folder).")
            .setPositiveButton("Go ahead") { _, _ ->
                val found = GistFolder(this).people().values.firstOrNull { loc ->
                    try { LinkProtocol.linkId(LinkFolder.open(this, loc)) == l.id } catch (e: Exception) { false }
                }
                if (found != null && links.adopt(l.id, found) == null) {
                    toast("Kept on this device now.")
                    render()
                    Devices.syncInBackground(this) { render() }
                    Connections.syncInBackground(this) { render() }
                } else {
                    toast("Its folder isn't in People in your Gist folder yet: share it there first, then try again.")
                }
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
                Thread { EasyConnect.unshare(this, EasyConnect.linkFolderId(l.id)) }.start()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLinkInvite(l: Link, person: String? = null) {
        val folder = person ?: l.folder.substringAfter('#', "").substringAfter("${GistFolder.PEOPLE}/").ifEmpty { null }
        links.invitation(l)?.let { (inv, until) ->
            showInvite("Invite", (if (folder != null) "Share People/$folder (in your Gist folder) with " +
                "them. " else "") + "On their phone (or Gist B), they open Connections, tap Join and scan this (or " +
                "type the code).", inv, until)
        }
    }

    private fun setUpDevices() {
        val gf = GistFolder(this)
        val loc = gf.devices() ?: return requestAccess(AFTER_DEVICES)
        try {
            if (Devices.groupOf(LinkFolder.open(this, loc)) != null) {
                toast("My devices is already set up in your Gist folder: tap Join instead.")
                return
            }
            Devices(this).setUp(loc)
            toast("Set up. Now share My devices (in your Gist folder) with your other devices.")
        } catch (e: Exception) {
            toast("Couldn't make My devices in your Gist folder: check Gist still has access to the ${GistRoot.NAME} folder.")
        }
        render()
    }

    /** Shows [inv] as a QR code and a code to type, until [until]. */
    private fun showInvite(title: String, how: String, inv: LinkInvite, until: Long, qrText: String = inv.qr, typable: Boolean = true) {
        val col = column()
        col.add(label(how, 15f))
        col.add(ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(qr(qrText, dp(240)))
            contentDescription = "Invite QR code"
            tag = "invite-qr"
        }, dp(12))
        if (typable) col.add(label(inv.code, 18f, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            tag = "invite-code"
        }, dp(8))
        col.add(muted("Works once, until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))}. Don't send it through the shared folder.", 13f), dp(6))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(col.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }) })
            .setPositiveButton("Done") { _, _ -> render() }
            .apply { if (typable) setNeutralButton("Copy code") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Gist invite", inv.code))
                toast("Copied")
            } }
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
            GistFolder(this).root != null -> {
                if (!joinInGistFolder(inv, quiet = true)) GistFolder(this).waiting = "$kind:${inv.code}"
                render()
            }
            else -> {
                // Joined as soon as there's access and the folder has arrived.
                GistFolder(this).waiting = "$kind:${inv.code}"
                requestAccess()
            }
        }
    }

    private fun invite() {
        if (links.myName.isBlank()) {
            toast("Add your name first, so they know it's you.")
            return
        }
        if (GistFolder(this).root == null) return requestAccess(AFTER_INVITE)
        askWhoFor()
    }

    /** Asks Android for access to the Gist folder (the only folder Gist uses); then does [then]. */
    @Suppress("DEPRECATION")
    private fun requestAccess(then: String? = null) {
        afterAccess = then
        try {
            startActivityForResult(GistRoot.pickIntent(), REQ_ACCESS)
        } catch (e: android.content.ActivityNotFoundException) {
            toast("This phone has no folder picker, so Gist can't get access to the ${GistRoot.NAME} folder.")
        }
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN_EASY) {
            val text = data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (resultCode == RESULT_OK && text != null) easyScanned(text)
            return
        }
        if (requestCode == REQ_SCAN_JOIN || requestCode == REQ_SCAN_DEVICES) {
            val code = data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (resultCode == RESULT_OK && code != null) gotInvite(if (requestCode == REQ_SCAN_JOIN) 'c' else 'd', code)
            return
        }
        if (requestCode != REQ_ACCESS) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            afterAccess = null
            return
        }
        if (!GistRoot.allow(this, uri)) {
            AlertDialog.Builder(this)
                .setTitle("That's not the ${GistRoot.NAME} folder")
                .setMessage("Gist only uses ${GistRoot.PATH} (any case: gist will do). Pick that folder (make it at the " +
                    "top of your storage if it isn't there), then Use this folder.")
                .setPositiveButton("Try again") { _, _ -> requestAccess(afterAccess) }
                .setNegativeButton("Cancel") { _, _ -> afterAccess = null }
                .show()
            return
        }
        val then = afterAccess
        afterAccess = null
        tryWaiting(quiet = true)
        render()
        when (then) {
            AFTER_INVITE -> invite()
            AFTER_DEVICES -> setUpDevices()
        }
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_SCAN_JOIN = 25
        private const val REQ_SCAN_DEVICES = 26
        private const val STATE_AFTER = "afterAccess"
        private const val REQ_ACCESS = 28
        private const val REQ_SCAN_EASY = 29
        private const val AFTER_INVITE = "invite"
        private const val AFTER_DEVICES = "devices"

        fun intent(context: Context) = Intent(context, ConnectionsActivity::class.java)
    }
}
