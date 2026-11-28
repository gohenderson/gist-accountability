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
 * Connections: your other devices (everything kept in step) and the people you share with
 * (read-only both ways), as one list. + New connection walks through it: who it's with, creating
 * it (show a code) or connecting to one (scan it), and, creating, automatic (Easy Connect: Gist's
 * own Syncthing) or manual (a folder you share yourself). The scanning side sees who and what it's
 * connecting to ([ConnectPreview]) and confirms before anything is saved.
 */
class ConnectionsActivity : BaseActivity() {
    private lateinit var links: Connections
    /** What to do once access to the Gist folder is allowed: [AFTER_DEVICES] or [AFTER_PERSON]<who>. */
    private var afterAccess: String? = null
    /** Sent to Android's All files access page: carry on when back, if it was allowed. */
    private var askedAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links = Connections(this)
        afterAccess = savedInstanceState?.getString(STATE_AFTER)
        askedAccess = savedInstanceState?.getBoolean(STATE_ASKED) == true
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AFTER, afterAccess)
        outState.putBoolean(STATE_ASKED, askedAccess)
    }

    override fun onResume() {
        super.onResume()
        if (askedAccess) {
            askedAccess = false
            if (GistRoot.canReach(this)) accessAllowed() else afterAccess = null
        }
        tryWaiting(quiet = true)
        render()
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
        if (EasyConnect.isOn(this)) SyncthingService.start(this)
        refresher.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Every few seconds: the automatic connections' status line, a join waiting for its folder,
     * and how a code showing here ended (connected, or turned down: then it's withdrawn).
     */
    private val refresher = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            Thread {
                val status = if (EasyConnect.isOn(this@ConnectionsActivity)) EasyConnect.status(this@ConnectionsActivity) else null
                val declined = runCatching { Handshake.withdrawDeclined(this@ConnectionsActivity) }.getOrDefault(emptyList())
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val changed = status != easyStatus || declined.isNotEmpty()
                    easyStatus = status
                    codeEnded(declined)
                    if (GistFolder(this@ConnectionsActivity).waiting != null) tryWaiting(quiet = true)
                    else if (changed) render()
                }
            }.start()
            handler.postDelayed(this, 3_000)
        }
    }

    /** The code showing here, if one is: its dialog, and its automatic folder (to hear how it ended). */
    private var codeDialog: AlertDialog? = null
    private var codeFolder: String? = null

    /** A code showing here was used or turned down ([declined]: manual ones withdrawn just now). */
    private fun codeEnded(declined: List<String>) {
        val folder = codeFolder
        val auto = folder?.let { EasyConnect.takeResult(this, it) }
        val answer = auto ?: if (declined.isNotEmpty()) "no" else return
        if (codeDialog?.isShowing == true && (auto != null || declined.isNotEmpty())) codeDialog?.dismiss()
        codeDialog = null
        codeFolder = null
        toast(if (answer == "yes") "They said yes: connecting." else "They said it wasn't the one they meant, so the code is cancelled.")
        render()
    }

    /** Joins with the invite waiting for its folder, if the folder's there now. */
    private fun tryWaiting(quiet: Boolean) {
        val gf = GistFolder(this)
        val w = gf.waiting ?: return
        val inv = LinkInvite.parse(w.substring(2)) ?: run { gf.waiting = null; return }
        if (!gf.waitingConfirmed) {
            // Manual: once its folder's here, ask first, with what the other side put in it.
            if (confirming) return
            val loc = arrivedFolder(inv) ?: run {
                if (!quiet) toast("Its folder isn't here yet: Gist asks you as soon as it arrives.")
                render()
                return
            }
            val f = runCatching { LinkFolder.open(this, loc) }.getOrNull() ?: return
            confirming = true
            confirmConnect(inv, auto = false, preview = Handshake.preview(f, inv.token),
                onYes = {
                    confirming = false
                    gf.waitingConfirmed = true
                    tryWaiting(quiet = false)
                },
                onNo = {
                    confirming = false
                    runCatching { Handshake.decline(f, inv.token) }
                    gf.waiting = null
                    render()
                    toast("Nothing was saved. Their code stops working as soon as their Gist sees your answer in the folder.")
                })
            return
        }
        if (joinInGistFolder(inv, quiet)) gf.waiting = null
        render()
    }

    /** A manual join's confirmation is showing. */
    private var confirming = false

    /** The folder a manual code joins, once it's here: My devices (set up), or theirs in People. */
    private fun arrivedFolder(inv: LinkInvite): String? = runCatching {
        val gf = GistFolder(this)
        if (inv.kind == 'd') gf.devices()?.takeIf { Devices.groupOf(LinkFolder.open(this, it)) != null }
        else links.findInvited(gf.people().values, inv)
    }.getOrNull()

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

    /** You've a name to show them (it's in your code and on your side of the folder); asks for it if not, then [then]. */
    private fun withName(then: () -> Unit) {
        if (links.myName.isNotBlank()) return then()
        askText("Your name", "What the people you connect with see.", "e.g. Alex", "ask-my-name") { name ->
            if (name.isBlank()) return@askText
            links.myName = name
            render()
            then()
        }
    }

    /** Asks for a line of text: [title], [message], [hint]; then [onOk] with it. */
    private fun askText(title: String, message: String, hint: String, key: String, onOk: (String) -> Unit) {
        val field = EditText(this).apply {
            this.hint = hint
            tag = key
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)); add(field) })
            .setPositiveButton("Next") { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- a new connection, step by step

    /** New connection: who it's with. */
    private fun newConnection() {
        pickDialog("New connection", listOf(
            DialogOption("It's my device", "Another of your phones or tablets: everything kept in step", "new-device"),
            DialogOption("Another person", "Share check-ins and tasks with someone", "new-person"),
        ), message = "Who's the connection with?") { i -> createOrConnect(if (i == 0) 'd' else 'c') }
    }

    /** Then: creating it here (show a code), or connecting to one (scan its code). */
    private fun createOrConnect(kind: Char) {
        val other = if (kind == 'd') "your other device" else "their phone"
        pickDialog(if (kind == 'd') "It's my device" else "Another person", listOf(
            DialogOption("Create the connection", "Show a code for $other to scan", "new-create"),
            DialogOption(if (kind == 'd') "Connect to my other device's" else "Connect to someone else's",
                "Scan the code $other shows", "new-connect"),
        ), message = "Are you creating the connection, or connecting to one?") { i -> if (i == 0) create(kind) else connect(kind) }
    }

    /** Creating: (a person: who for) and how, then the code. */
    private fun create(kind: Char) {
        val devices = Devices(this)
        if (kind == 'd' && devices.folder != null && !devices.ready) {
            return toast("This device is still waiting to be let in by your other device.")
        }
        fun askHow(then: (Boolean) -> Unit) {
            if (!SyncthingEngine.shared(this).available) return then(false)
            pickDialog("How should it connect?", listOf(
                DialogOption("Automatic connection", "Gist keeps the two in step itself, over the internet, encrypted", "new-auto"),
                DialogOption("Manual connection", "Through a folder you share with an app of your choice", "new-manual"),
            )) { i -> then(i == 0) }
        }
        if (kind == 'd') return askHow { auto -> if (auto) easyReady { easyOffer() } else startDevicesManual() }
        withName {
            askText("Who's it with?", "Their name: Gist makes a folder for the connection, named for them.", "e.g. Jess", "invite-who") { who ->
                if (who.isBlank()) return@askText
                askHow { auto -> if (auto) easyReady { easyOfferPerson(who) } else startPersonManual(who) }
            }
        }
    }

    /** Connecting: scan the code the other side shows (or, for a manual one, type it). */
    private fun connect(kind: Char) {
        if (kind == 'd' && Devices(this).folder != null) return toast("This device already syncs with your devices.")
        val go = {
            AlertDialog.Builder(this)
                .setTitle("Scan their code")
                .setMessage("On the other " + (if (kind == 'd') "device" else "phone") + ", create the connection; " +
                    "it shows a code. Scan it here.")
                .setPositiveButton("Scan") { _, _ -> scan() }
                .setNeutralButton("Type the code") { _, _ -> typeCode() }
                .setNegativeButton("Cancel", null)
                .show()
            Unit
        }
        if (kind == 'c') withName(go) else go()
    }

    @Suppress("DEPRECATION")
    private fun scan() {
        startActivityForResult(Intent(this, ScanActivity::class.java)
            .putExtra(ScanActivity.EXTRA_TITLE, "Scan their code")
            .putExtra(ScanActivity.EXTRA_TEXT, "Point the camera at the code the other device shows."), REQ_SCAN)
    }

    private fun typeCode() {
        val field = EditText(this).apply {
            hint = "ABCD-EFGH-…"
            tag = "invite-field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Type the code")
            .setMessage("The code under the QR code (manual connections).")
            .setView(column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)); add(field) })
            .setPositiveButton("Next") { _, _ -> scanned(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A code scanned or typed. Before anything is saved, Gist finds out what it connects to (an
     * automatic code: from the other device, through Syncthing; a manual one: from its folder,
     * once that's arrived) and asks.
     */
    private fun scanned(text: String) {
        val raw = text.trim()
        val easy = EasyConnect.Code.parse(raw)
        val inv = easy?.invite ?: LinkInvite.parse(raw) ?: return toast("That isn't a Gist connection code.")
        if (easy != null && !SyncthingEngine.shared(this).available) return toast("This copy of Gist can't make automatic connections.")
        if (inv.kind == 'd' && Devices(this).folder != null) return toast("This device already syncs with your devices.")
        val go = { if (easy != null) easyReady { reachThenConfirm(easy) } else joinManual(inv) }
        if (inv.kind == 'c') withName(go) else go()
    }

    /**
     * Automatic: reaches the device that showed [code] (through a setup folder only), waits for
     * what it says it is, and asks. Cancelled or never reached, everything's dropped.
     */
    private fun reachThenConfirm(code: EasyConnect.Code) {
        var waiting = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Finding the other device…")
            .setMessage("Keep Gist open on both. This takes a few seconds, or longer when they're not on the same Wi-Fi.")
            .setNegativeButton("Cancel") { _, _ -> waiting = false }
            .setCancelable(false)
            .show()
        Thread {
            val reached = runCatching { EasyConnect.reach(this, code) }
            var preview: ConnectPreview? = null
            val until = System.currentTimeMillis() + REACH_MS
            if (reached.isSuccess) {
                while (waiting && System.currentTimeMillis() < until) {
                    preview = EasyConnect.preview(this, code)
                    if (preview != null) break
                    Thread.sleep(1_000)
                }
            }
            if (preview == null) runCatching { EasyConnect.abandon(this, code) }
            val found = preview
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                runCatching { dialog.dismiss() }
                when {
                    reached.isFailure -> easyFailed(reached.exceptionOrNull()!!)
                    !waiting -> toast("Cancelled: nothing was saved.")
                    found == null -> toast("Couldn't reach the other device. Check both are online with Gist open on the " +
                        "Connections page, then scan again.")
                    else -> confirmConnect(code.invite, auto = true, preview = found,
                        onYes = { answerEasy(code, true) }, onNo = { answerEasy(code, false) })
                }
            }
        }.start()
    }

    /** Automatic: tells the other device yes (then joins) or no (it withdraws its code). */
    private fun answerEasy(code: EasyConnect.Code, yes: Boolean) {
        toast(if (yes) "Connecting…" else "Telling the other device…")
        Thread {
            val heard = runCatching { EasyConnect.answer(this, code, yes) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val failed = heard.exceptionOrNull()
                when {
                    failed != null -> easyFailed(failed)
                    yes -> toast(if (code.invite.kind == 'd') "Connecting: this device joins your devices as soon as My devices arrives."
                        else "Connecting with ${code.name ?: "them"}: you're connected as soon as their folder arrives.")
                    heard.getOrDefault(false) -> toast("Done: the other device withdrew its code. Nothing was saved.")
                    else -> toast("Nothing was saved. The other device didn't hear in time: its code stops working in 15 minutes.")
                }
                render()
            }
        }.start()
    }

    /**
     * Is this the right device, connected the right way? [preview] is what the other side says
     * it is (null: it didn't say). [onYes] connects; [onNo] says no.
     */
    private fun confirmConnect(inv: LinkInvite, auto: Boolean, preview: ConnectPreview?, onYes: () -> Unit, onNo: () -> Unit) {
        val device = inv.kind == 'd'
        val who = preview?.name
        val them = who ?: "them"
        val title = if (device) "Connect to your device?" else "Connect with ${who ?: "them"}?"
        val body = dialogBody {
            add(label(if (device) "Your device" else "Another person", 13f, color = colorMuted))
            add(label(if (device) preview?.device ?: "Your other device" else who ?: "Someone", 20f, bold = true)
                .apply { tag = "confirm-who" }, dp(2))
            if (!device && preview?.device != null) add(muted("On ${preview.device}", 14f).apply { tag = "confirm-device" }, dp(2))
            if (device && who != null) add(muted("$who's", 14f), dp(2))
            add(label(if (auto) "Automatic connection" else "Manual connection", 15f, bold = true)
                .apply { tag = "confirm-how" }, dp(12))
            add(muted(if (auto) "Gist keeps you in step itself, over the internet, encrypted."
                else "Through a folder in your ${GistRoot.NAME} folder, shared with an app of your choice.", 14f), dp(2))
            add(label("What you can change", 15f, bold = true), dp(12))
            add(muted(if (device) "Everything, from either device: check-ins, tasks, answers and settings are kept in " +
                "step both ways."
                else "Read-only both ways: you see what $them shares, and only $them can change it; $them sees only " +
                    "what you share, and only you can change that.", 14f).apply { tag = "confirm-control" }, dp(2))
            if (device) {
                if (preview == null) {
                    add(muted("The other device didn't say what's on it.", 14f).apply { tag = "confirm-sample" }, dp(12))
                } else if (preview.sample.isEmpty()) {
                    add(muted("There are no check-ins or tasks there yet.", 14f).apply { tag = "confirm-sample" }, dp(12))
                } else {
                    add(label("Some of its check-ins and tasks (${preview.count} in all)", 15f, bold = true), dp(12))
                    add(label(preview.sample.joinToString("\n") { "• $it" }, 15f).apply { tag = "confirm-sample" }, dp(4))
                }
            } else {
                add(muted("Nothing is shared yet: each of you chooses what to share, check-in by check-in.", 14f)
                    .apply { tag = "confirm-sample" }, dp(12))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(body)
            .setPositiveButton("Connect") { _, _ -> onYes() }
            .setNegativeButton("Not this one") { _, _ -> onNo() }
            .setCancelable(false)
            .show()
    }

    /**
     * Manual: waits for the code's folder (My devices, or theirs in People), then asks, with what
     * the other side put there; joins only on a yes.
     */
    private fun joinManual(inv: LinkInvite) {
        val gf = GistFolder(this)
        gf.waiting = "${inv.kind}:${inv.code}"
        if (gf.root == null) return requestAccess()
        tryWaiting(quiet = false)
    }

    /** Your devices, manually: sets up My devices if it isn't, then shows its code. */
    private fun startDevicesManual() {
        if (GistFolder(this).root == null) return requestAccess(AFTER_DEVICES)
        val devices = Devices(this)
        if (devices.folder == null && !setUpDevices()) return
        val (inv, until) = devices.invitation() ?: return
        // What this side is, for the other to check: in My devices, readable only with the code.
        devices.folder?.let { loc -> runCatching { Handshake.putPreview(LinkFolder.open(this, loc), inv.token, ConnectPreview.of(this, 'd')) } }
        showCode("Your device", "Share My devices (in your ${GistRoot.NAME} folder) with your other device. On it, in " +
            "Gist: Connections → New connection → It's my device → Connect, and scan this.", inv, until) {
            devices.cancelInvitation()
            devices.folder?.let { loc -> runCatching { Handshake.clear(LinkFolder.open(this, loc)) } }
        }
    }

    /** A person, manually: makes People/<who> and its connection, then shows its code. */
    private fun startPersonManual(who: String) {
        if (GistFolder(this).root == null) return requestAccess("$AFTER_PERSON$who")
        val loc = GistFolder(this).newPerson(who) ?: return
        try {
            val l = links.invite(loc)
            render()
            showLinkInvite(l)
        } catch (e: Exception) {
            toast("Couldn't make the folder: check Gist still has access to the ${GistRoot.NAME} folder.")
        }
    }

    /** A person's connection waiting for them: its code again, or cancel it. */
    private fun pendingDetails(l: Link) {
        AlertDialog.Builder(this)
            .setTitle("Waiting for ${personOf(l) ?: "them"}")
            .setMessage("Person · ${how(EasyConnect.linkFolderId(l.id))}. They scan the code on their phone to connect.")
            .setPositiveButton("Show the code") { _, _ -> showLinkInvite(l) }
            .setNegativeButton("Cancel the code") { _, _ -> cancelPerson(l) }
            .setNeutralButton("Close", null)
            .show()
    }

    /** Withdraws a person's code: the connection waiting for them goes, and nobody can join with it. */
    private fun cancelPerson(l: Link) {
        val folder = EasyConnect.linkFolderId(l.id)
        EasyConnect.cancelOffer(this, folder)
        runCatching { Handshake.clear(LinkFolder.open(this, l.folder)) }
        links.remove(l.id)
        if (EasyConnect.isAuto(this, folder)) Thread { EasyConnect.unshare(this, folder) }.start()
        render()
        toast("Cancelled: the code no longer works.")
    }

    /** The last status line from Syncthing (read off the main thread). */
    private var easyStatus: String? = null

    /** Access to the Gist folder (which Syncthing reaches by path too) and the network; then [then]. */
    private fun easyReady(then: () -> Unit) {
        if (GistFolder(this).root == null) {
            requestAccess()
            return
        }
        if (!SyncthingEngine.shared(this).networkAllowed()) return networkBlocked()
        then()
    }

    /** Easy Connect couldn't start: why, and what to do about it if we know. */
    private fun easyFailed(e: Throwable) {
        if ((e.message ?: "").contains("EPERM") || e is SecurityException) networkBlocked()
        else toast("Couldn't start the automatic connection: ${e.message}")
    }

    /** Android won't let Gist use the network (an app setting on some phones): where to allow it. */
    private fun networkBlocked() {
        AlertDialog.Builder(this)
            .setTitle("Allow network access")
            .setMessage("Automatic connections need the network to reach the other device, but this phone doesn't let Gist " +
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

    /** Automatic, your devices: the code for another device to scan (setting up My devices first, if it isn't). */
    private fun easyOffer() {
        val devices = Devices(this)
        if (devices.folder == null && !setUpDevices()) return
        toast("Starting…")
        Thread {
            val result = runCatching { EasyConnect.offer(this) }
            runOnUiThread {
                result.onSuccess { (code, until) ->
                    showCode("Your device", "On your other device, in Gist: Connections → New connection → It's my " +
                        "device → Connect, and scan this. Keep Gist open on both until it's in.", code.invite, until,
                        qrText = code.qr, typable = false, autoFolder = code.folderId) {
                        devices.cancelInvitation()
                        devices.group?.let { EasyConnect.cancelOffer(this, EasyConnect.folderId(it)) }
                    }
                }.onFailure { easyFailed(it) }
                render()
            }
        }.start()
    }

    /** Automatic, a person: the code for [person] to scan (their folder, invite and this device's Syncthing). */
    private fun easyOfferPerson(person: String) {
        toast("Starting…")
        Thread {
            val result = runCatching { EasyConnect.offerPerson(this, person) }
            runOnUiThread {
                result.onSuccess { (code, until) ->
                    showCode("Connect with $person", "On their phone, in Gist: Connections → New connection → " +
                        "Another person → Connect, and scan this. Keep Gist open on both until you see each other.",
                        code.invite, until, qrText = code.qr, typable = false, autoFolder = code.folderId) {
                        links.all.firstOrNull { EasyConnect.linkFolderId(it.id) == code.folderId && !it.connected }
                            ?.let { cancelPerson(it) }
                    }
                }.onFailure { easyFailed(it) }
                render()
            }
        }.start()
    }

    /** A person's connection: its code to compare, what's in its folder, and what you can do with it. */
    private fun linkDetails(l: Link) {
        var dialog: AlertDialog? = null
        val body = dialogBody {
            add(label("Person · ${how(EasyConnect.linkFolderId(l.id))}", 15f).apply { tag = "link-type:${l.id}" })
            if (l.connected) {
                add(muted("Code ${l.code}: it's the same on their phone when nobody swapped a key. Compare it once.", 14f)
                    .apply { tag = "link-code:${l.id}" }, dp(6))
                add(muted("In its folder: yours is from-${l.side}, theirs from-${l.peer}.", 13f)
                    .apply { tag = "link-folders:${l.id}" }, dp(4))
                add(styledSwitch("Show on the start page").apply {
                    isChecked = l.shown
                    tag = "link-shown:${l.id}"
                    setOnCheckedChangeListener { _, on -> links.setShown(l.id, on) }
                }, dp(8))
            } else {
                add(muted("The code is on ${l.homeName.ifEmpty { "your other device" }}.", 14f), dp(6))
            }
            if (!l.isHere) {
                val home = l.homeName.ifEmpty { "your other device" }
                add(muted("Kept on $home: it reads and shares for this connection, and what you change here reaches it " +
                    "through your devices.", 13f).apply { tag = "link-home:${l.id}" }, dp(8))
                add(textButton("Keep it on this device instead…") { dialog?.dismiss(); confirmAdopt(l) }
                    .apply { tag = "link-adopt:${l.id}" }, dp(4))
            } else if (Devices(context).folder != null) {
                add(muted("Kept on this device: it reads and shares for this connection for all your devices.", 13f)
                    .apply { tag = "link-home:${l.id}" }, dp(8))
            }
        }
        val b = AlertDialog.Builder(this)
            .setTitle(l.peerName.ifEmpty { "Waiting for someone to join" })
            .setView(body)
            .setPositiveButton("Close") { _, _ -> render() }
        if (l.isHere) b.setNegativeButton("Remove") { _, _ -> confirmRemove(l) }
        dialog = b.show()
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

    /** A person's manual code again (or for the first time). */
    private fun showLinkInvite(l: Link) {
        val folder = personOf(l)
        links.invitation(l)?.let { (inv, until) ->
            // What this side is, for them to check: in their folder, readable only with the code.
            runCatching { Handshake.putPreview(LinkFolder.open(this, l.folder), inv.token, ConnectPreview.of(this, 'c')) }
            showCode("Connect with ${folder ?: "them"}", (if (folder != null) "Share People/$folder (in your " +
                "${GistRoot.NAME} folder) with them. " else "") + "On their phone, in Gist: Connections → New connection → " +
                "Another person → Connect, and scan this (or type the code).", inv, until) { cancelPerson(l) }
        }
    }

    /** Makes My devices in the Gist folder (this device the first in it). False if it couldn't. */
    private fun setUpDevices(): Boolean {
        val gf = GistFolder(this)
        val loc = gf.devices() ?: run { requestAccess(AFTER_DEVICES); return false }
        return try {
            if (Devices.groupOf(LinkFolder.open(this, loc)) != null) {
                toast("My devices is already set up in your ${GistRoot.NAME} folder: on this device, connect to " +
                    "your other device's code instead.")
                false
            } else {
                Devices(this).setUp(loc)
                render()
                true
            }
        } catch (e: Exception) {
            toast("Couldn't make My devices in your ${GistRoot.NAME} folder: check Gist still has access to it.")
            false
        }
    }

    /**
     * Shows a code for the other side to scan, until [until] (a manual one: a code to type too).
     * Cancel the code withdraws it at once ([onCancel]); an automatic one ([autoFolder]) closes
     * by itself when the other side answers.
     */
    private fun showCode(
        title: String, how: String, inv: LinkInvite, until: Long,
        qrText: String = inv.qr, typable: Boolean = true, autoFolder: String? = null, onCancel: () -> Unit,
    ) {
        val col = column()
        col.add(label(how, 15f))
        col.add(ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(qr(qrText, dp(260)))
            contentDescription = "Connection QR code"
            tag = "invite-qr"
        }, dp(12))
        if (typable) col.add(label(inv.code, 18f, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            tag = "invite-code"
        }, dp(8))
        col.add(muted("Works once, until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))}. " +
            "Don't send it through the shared folder.", 13f), dp(6))
        codeFolder = autoFolder
        codeDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(col.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }) })
            .setPositiveButton("Done") { _, _ -> render() }
            .setNegativeButton("Cancel the code") { _, _ ->
                onCancel()
                render()
            }
            .apply { if (typable) setNeutralButton("Copy code") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Gist code", inv.code))
                toast("Copied")
            } }
            .setCancelable(false)
            .show()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Asks Android for access to the Gist folder (All files access; Android 10's storage
     * permission); then does [then].
     */
    private fun requestAccess(then: String? = null) {
        afterAccess = then
        if (GistRoot.canReach(this)) {
            // Allowed, but there's no Gist folder: say why.
            render()
            GistRoot.problem(this)?.let { toast(it) }
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 30) {
            requestPermissions(GistRoot.LEGACY_STORAGE, REQ_STORAGE)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow All files access")
            .setMessage("Gist keeps its folders in ${GistRoot.NAME} at the top of your storage, and reaches them by " +
                "path, as automatic connections (Syncthing) and other apps do. Android calls that All files access. Gist only " +
                "uses its ${GistRoot.NAME} folder (on GrapheneOS, Storage Scopes can hold it to just that).\n\n" +
                "Turn on Allow access to manage all files, then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                askedAccess = true
                runCatching { startActivity(GistRoot.accessIntent(this)) }.onFailure { askedAccess = false }
            }
            .setNegativeButton("Cancel") { _, _ -> afterAccess = null }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        if (GistRoot.canReach(this)) accessAllowed()
        else {
            afterAccess = null
            toast("Gist needs storage access to reach your ${GistRoot.NAME} folder.")
        }
    }

    /** Access to the Gist folder was just allowed: carry on with what asked for it. */
    private fun accessAllowed() {
        val then = afterAccess
        afterAccess = null
        tryWaiting(quiet = true)
        render()
        GistRoot.problem(this)?.let { return toast(it) }
        when {
            then == AFTER_DEVICES -> startDevicesManual()
            then?.startsWith(AFTER_PERSON) == true -> startPersonManual(then.removePrefix(AFTER_PERSON))
        }
        Connections.syncInBackground(this) { render() }
        Devices.syncInBackground(this) { render() }
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN) {
            val text = data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (resultCode == RESULT_OK && text != null) scanned(text)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_SCAN = 25
        /** How long to look for the device that showed an automatic code. */
        private const val REACH_MS = 120_000L
        private const val STATE_AFTER = "afterAccess"
        private const val STATE_ASKED = "askedAccess"
        private const val REQ_STORAGE = 31
        private const val AFTER_DEVICES = "devices"
        /** Then "person:<who>": start a manual connection with them. */
        private const val AFTER_PERSON = "person:"

        fun intent(context: Context) = Intent(context, ConnectionsActivity::class.java)
    }
}
