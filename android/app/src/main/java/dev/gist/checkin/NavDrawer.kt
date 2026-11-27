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
 * The menu that slides out from the left when you tap ☰ on the start page (like Outlook's):
 * a rail of profile pictures down its left side (tap one to switch to it, + for a new profile),
 * and the places to go: Home, Connections, Backup, Settings, About · Donate. The page
 * behind is dimmed; tap it, ✕ or Back to close.
 *
 * Call [attach] after each setContentView (the menu stays open across rebuilds).
 */
class NavDrawer(private val activity: Activity) {
    var isOpen = false
        private set

    private var scrim: View? = null
    private var panel: View? = null
    private var backCallback: Any? = null

    /**
     * Puts the menu over the page that was just set with setContentView (open if it was). The
     * page stays the content's first child; the menu is added after it.
     */
    fun attach() {
        val root = FrameLayout(activity)
        val s = View(activity).apply {
            tag = "drawer-scrim"
            contentDescription = "Close menu"
            setBackgroundColor(Color.argb(115, 0, 0, 0))
            isClickable = true
            setOnClickListener { close() }
        }
        root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = minOf(activity.dp(320), (activity.resources.displayMetrics.widthPixels * 0.85f).toInt())
        val p = build()
        root.addView(p, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        scrim = s
        panel = p
        show(isOpen, animate = false, width = width)
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        show(true, animate = true)
        listenForBack(true)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        show(false, animate = true)
        listenForBack(false)
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

    private fun show(open: Boolean, animate: Boolean, width: Int = panel?.layoutParams?.width ?: 0) {
        val s = scrim ?: return
        val p = panel ?: return
        val shut = -width.toFloat()
        s.animate().cancel()
        p.animate().cancel()
        if (!animate) {
            s.visibility = if (open) View.VISIBLE else View.GONE
            p.visibility = if (open) View.VISIBLE else View.GONE
            s.alpha = 1f
            p.translationX = if (open) 0f else shut
            return
        }
        if (open) {
            s.alpha = 0f
            s.visibility = View.VISIBLE
            p.translationX = shut
            p.visibility = View.VISIBLE
            s.animate().alpha(1f).setDuration(DURATION).start()
            p.animate().translationX(0f).setDuration(DURATION).start()
        } else {
            s.animate().alpha(0f).setDuration(DURATION).withEndAction { s.visibility = View.GONE }.start()
            p.animate().translationX(shut).setDuration(DURATION).withEndAction { p.visibility = View.GONE }.start()
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

        fun item(icon: String, text: String, key: String, onClick: () -> Unit) {
            val r = a.row().apply {
                tag = "nav:$key"
                contentDescription = text
                minimumHeight = a.dp(52)
                setPadding(a.dp(12), 0, a.dp(12), 0)
                background = a.rounded(Color.TRANSPARENT, 12).let { bg ->
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
            r.addWeighted(a.label(text, 16f), startMargin = a.dp(16))
            list.add(r, a.dp(2))
        }
        fun go(intent: Intent) {
            close()
            a.startActivity(intent)
        }
        fun divider() {
            list.addView(View(a).apply { setBackgroundColor(a.colorOf(R.color.outline)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, a.dp(1))).apply {
                    topMargin = a.dp(10)
                    bottomMargin = a.dp(8)
                })
        }

        (top.layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(12)
        item("⌂", "Home", "home") { close() }
        item("⇄", "Connections", "connections") { go(ConnectionsActivity.intent(a)) }
        item("⤓", "Backup", "backup") { go(BackupActivity.intent(a)) }
        divider()
        item("⚙", "Settings", "settings") { go(Intent(a, SettingsActivity::class.java)) }
        item("ⓘ", "About · Donate", "about") { go(AboutActivity.intent(a)) }

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
    }
}
