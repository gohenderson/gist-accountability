package dev.gist.checkin

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The menu that slides out from the left when you tap ☰ on any page (like Outlook's):
 * a rail of profile pictures down its left side (tap one to switch to it, + for a new profile),
 * and the places to go: Home, Connections, Backup, Settings, About · Donate. The page
 * behind is dimmed; tap it, ✕ or Back to close.
 *
 * [BaseActivity] calls [attach] after each setContentView (the menu stays open across rebuilds).
 */
class NavDrawer(private val activity: Activity) {
    var isOpen = false
        private set

    private var root: FrameLayout? = null
    private var scrim: View? = null
    private var panel: View? = null
    private var backCallback: Any? = null

    /**
     * Puts the menu's layer over the page that was just set with setContentView (open if it
     * was). The page stays the content's first child; the layer is added after it, and is empty
     * while the menu is closed (so the page's views are the only ones there).
     */
    fun attach() {
        val r = FrameLayout(activity)
        activity.addContentView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = r
        scrim = null
        panel = null
        if (isOpen) fill(animate = false)
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        fill(animate = true)
        listenForBack(true)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        listenForBack(false)
        val r = root
        val s = scrim
        val p = panel
        if (r == null || s == null || p == null) return
        s.animate().cancel()
        p.animate().cancel()
        s.animate().alpha(0f).setDuration(DURATION).start()
        p.animate().translationX(-p.width.toFloat()).setDuration(DURATION).withEndAction {
            if (!isOpen && root === r) {
                r.removeAllViews()
                scrim = null
                panel = null
            }
        }.start()
    }

    /** Builds the scrim and the panel in the layer, sliding in if [animate]. */
    private fun fill(animate: Boolean) {
        val r = root ?: return
        r.removeAllViews()
        val s = View(activity).apply {
            tag = "drawer-scrim"
            contentDescription = "Close menu"
            setBackgroundColor(Color.argb(115, 0, 0, 0))
            isClickable = true
            setOnClickListener { close() }
        }
        r.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = minOf(activity.dp(320), (activity.resources.displayMetrics.widthPixels * 0.85f).toInt())
        val p = build()
        r.addView(p, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        r.requestApplyInsets()
        scrim = s
        panel = p
        if (animate) {
            s.alpha = 0f
            p.translationX = -width.toFloat()
            s.animate().alpha(1f).setDuration(DURATION).start()
            p.animate().translationX(0f).setDuration(DURATION).start()
        }
    }

    /** Back closes the menu first (on Android 12 and older, the activity's onBackPressed asks). */
    private fun listenForBack(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = activity.onBackInvokedDispatcher
        if (on) {
            if (backCallback != null) return
            val cb = OnBackInvokedCallback { close() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else {
            (backCallback as? OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    // ---------------------------------------------------------------- the panel

    private fun build(): View {
        val a = activity
        val current = Profiles.of(a)
        val panel = LinearLayout(a).apply {
            tag = "drawer"
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(a.colorOf(R.color.surface))
            isClickable = true // taps on it don't reach the page
            elevation = a.dp(8).toFloat()
        }

        // The profiles rail.
        val rail = a.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(a.colorOf(R.color.surface_variant))
        }
        for (p in Profiles.all(a)) {
            val key = p.id.ifEmpty { "default" }
            val avatar = AvatarView(a, p.id, p.name, 48).apply {
                tag = "drawer-profile:$key"
                ring = p.id == current
                val waiting = if (p.id == current) null else ProfileUi.waitingText(a, p.id)
                contentDescription = p.name + (if (p.id == current) " (this one)" else ", switch to it") +
                    (waiting?.let { ", $it" } ?: "")
                isClickable = true
                setOnClickListener {
                    close()
                    if (p.id != current) ProfileUi.switchTo(a, p.id)
                }
            }
            rail.addView(avatar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = a.dp(12) })
        }
        val plus = a.label("+", 26f, color = a.colorPrimary).apply {
            tag = "drawer-new-profile"
            contentDescription = "New profile"
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(a.dp(1), a.colorOf(R.color.outline))
            }
            isClickable = true
            setOnClickListener {
                ProfileUi.add(a) { ProfileUi.switchTo(a, it.id) }
            }
        }
        rail.addView(plus, LinearLayout.LayoutParams(a.dp(48), a.dp(48)).apply { topMargin = a.dp(12) })
        val railScroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(rail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setBackgroundColor(a.colorOf(R.color.surface_variant))
        }
        rail.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(12))
        panel.addView(railScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The places.
        val list = a.column().apply { setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(16)) }
        val top = a.row()
        top.addWeighted(a.label(Profiles.name(a, current), 20f, bold = true).apply {
            tag = "drawer-profile-name"
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, startMargin = a.dp(8))
        top.addWrapped(a.textButton("✕") { close() }.apply {
            tag = "drawer-close"
            contentDescription = "Close menu"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            minWidth = a.dp(48)
            minimumWidth = a.dp(48)
        })
        list.add(top)

        /** [page]: the page it opens; the one you're on is highlighted and just closes the menu. */
        fun item(icon: String, text: String, key: String, page: Class<*>, open: () -> Intent) {
            val here = a.javaClass == page
            val onClick = {
                close()
                if (!here) {
                    a.startActivity(open())
                    // From one of the menu's pages to another: Back goes to the start page, not here.
                    if (a.javaClass in TOP && a.javaClass != MainActivity::class.java) a.finish()
                }
            }
            val r = a.row().apply {
                tag = "nav:$key"
                contentDescription = text
                isSelected = here
                minimumHeight = a.dp(52)
                setPadding(a.dp(12), 0, a.dp(12), 0)
                background = a.rounded(if (here) a.colorOf(R.color.primary_container) else Color.TRANSPARENT, 12).let { bg ->
                    android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(a.colorOf(R.color.ripple)), bg, a.rounded(Color.WHITE, 12))
                }
                isClickable = true
                setOnClickListener { onClick() }
            }
            r.addWrapped(a.label(icon, 20f).apply {
                gravity = Gravity.CENTER
                minWidth = a.dp(28)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            r.addWeighted(a.label(text, 16f, bold = here, color = if (here) a.colorOf(R.color.on_primary_container) else null),
                startMargin = a.dp(16))
            list.add(r, a.dp(2))
        }
        fun divider() {
            list.addView(View(a).apply { setBackgroundColor(a.colorOf(R.color.outline)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, a.dp(1))).apply {
                    topMargin = a.dp(10)
                    bottomMargin = a.dp(8)
                })
        }

        (top.layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(12)
        item("⌂", "Home", "home", MainActivity::class.java) {
            // Back to the start page under this one (or a new one), closing what's over it.
            Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        item("⇄", "Connections", "connections", ConnectionsActivity::class.java) { ConnectionsActivity.intent(a) }
        item("⤓", "Backup", "backup", BackupActivity::class.java) { BackupActivity.intent(a) }
        divider()
        item("⚙", "Settings", "settings", SettingsActivity::class.java) { Intent(a, SettingsActivity::class.java) }
        item("ⓘ", "About · Donate", "about", AboutActivity::class.java) { AboutActivity.intent(a) }

        val listScroll = ScrollView(a).apply { addView(list) }
        panel.addView(listScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Clear of the status and navigation bars (edge-to-edge).
        val base = a.dp(4)
        panel.setPadding(0, base, 0, base)
        if (Build.VERSION.SDK_INT >= 30) {
            panel.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, base + b.top, 0, base + b.bottom)
                insets
            }
        }
        return panel
    }

    companion object {
        private const val DURATION = 200L
        /** The pages the menu opens. */
        private val TOP = setOf<Class<*>>(MainActivity::class.java, ConnectionsActivity::class.java,
            BackupActivity::class.java, SettingsActivity::class.java, AboutActivity::class.java)
    }
}
