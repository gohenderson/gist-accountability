package dev.gist.checkin

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * A small design system built from plain Android views (no XML layouts, no libraries).
 * Colors come from res/values/colors.xml and res/values-night/colors.xml, so every
 * component follows the light / dark setting.
 */

// ------------------------------------------------------------------ colors & sizes

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.colorOf(id: Int): Int = getColor(id)

val Context.colorText get() = colorOf(R.color.on_surface)
val Context.colorMuted get() = colorOf(R.color.muted)
val Context.colorPrimary get() = colorOf(R.color.primary)
val Context.colorGood get() = colorOf(R.color.good)
val Context.colorBad get() = colorOf(R.color.bad)
val Context.colorWarn get() = colorOf(R.color.warn)

private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.rounded(color: Int, radiusDp: Int = 16, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

// ------------------------------------------------------------------ text

fun Context.label(text: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, color: Int? = null): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color ?: colorText)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

fun Context.muted(text: CharSequence, sizeSp: Float = 14f): TextView = label(text, sizeSp, color = colorMuted)

/** Big screen title with an optional muted subtitle. */
fun Context.header(title: String, subtitle: String? = null): LinearLayout = column().apply {
    add(label(title, 30f, bold = true))
    if (subtitle != null) add(muted(subtitle, 15f), dp(2))
}

/** Small all-caps section label, e.g. "CHECK-INS". */
fun Context.sectionTitle(text: String): TextView = label(text.uppercase(), 12f, color = colorMuted).apply {
    typeface = MEDIUM
    letterSpacing = 0.08f
}

fun todayLong(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))

// ------------------------------------------------------------------ layout

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Add a full-width child with an optional top margin (px). */
fun LinearLayout.add(v: View, topMargin: Int = 0) {
    addView(
        v,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { this.topMargin = topMargin }
    )
}

/** Add a child that takes the remaining width in a row. */
fun LinearLayout.addWeighted(v: View, weight: Float = 1f, startMargin: Int = 0) {
    addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginStart = startMargin })
}

/** Add a child at its natural size (e.g. a button at the end of a row). */
fun LinearLayout.addWrapped(v: View, startMargin: Int = 0) {
    addView(
        v,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = startMargin }
    )
}

/** A rounded surface that groups related content. */
fun Context.card(tint: Int? = null): LinearLayout = column().apply {
    background = rounded(tint ?: colorOf(R.color.surface), 20, if (tint == null) colorOf(R.color.outline) else null)
    setPadding(dp(16), dp(14), dp(16), dp(16))
}

/** A small rounded tag, e.g. "✓ done early". */
fun Context.chip(text: String, fg: Int, bg: Int): TextView = label(text, 13f, color = fg).apply {
    typeface = MEDIUM
    background = rounded(bg, 12)
    setPadding(dp(10), dp(4), dp(10), dp(4))
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

// ------------------------------------------------------------------ buttons

private fun Context.styledButton(text: String, bg: Int, fg: Int, outline: Int?, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        typeface = MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(18), dp(10), dp(18), dp(10))
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(colorMuted, fg)))
        val shape = rounded(bg, 14, outline)
        shape.setColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(colorOf(R.color.surface_variant), bg)))
        background = RippleDrawable(ColorStateList.valueOf(colorOf(R.color.ripple)), shape, null)
        setOnClickListener { onClick() }
    }

/** Main action of a screen: filled. */
fun Context.primaryButton(text: String, onClick: () -> Unit): Button =
    styledButton(text, colorPrimary, colorOf(R.color.on_primary), null, onClick)

/** Regular action: soft tinted. */
fun Context.button(text: String, onClick: () -> Unit): Button =
    styledButton(text, colorOf(R.color.primary_container), colorOf(R.color.on_primary_container), null, onClick)

/** Low-emphasis action: outlined. */
fun Context.outlineButton(text: String, onClick: () -> Unit): Button =
    styledButton(text, colorOf(R.color.surface), colorText, colorOf(R.color.outline), onClick)

/** Text-only action. */
fun Context.textButton(text: String, color: Int? = null, onClick: () -> Unit): Button =
    styledButton(text, android.graphics.Color.TRANSPARENT, color ?: colorPrimary, null, onClick).apply {
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

/** Destructive action. */
fun Context.dangerButton(text: String, onClick: () -> Unit): Button =
    styledButton(text, colorOf(R.color.bad_container), colorBad, null, onClick)

// ------------------------------------------------------------------ inputs

/** On/off switch in the app's colors. */
fun Context.styledSwitch(text: String? = null): Switch = Switch(this).apply {
    if (text != null) {
        this.text = text
        setTextColor(colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }
    val checked = intArrayOf(android.R.attr.state_checked)
    thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(colorPrimary, colorOf(R.color.surface)))
    trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(colorOf(R.color.primary_container), colorOf(R.color.outline)))
}

/** A big tappable choice (used for Yes / No): a RadioButton drawn as a rounded tile. */
fun Context.choiceTile(text: String): RadioButton = RadioButton(this).apply {
    this.text = text
    buttonDrawable = null
    gravity = Gravity.CENTER
    typeface = MEDIUM
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    minHeight = dp(52)
    setPadding(dp(12), dp(12), dp(12), dp(12))
    val checked = intArrayOf(android.R.attr.state_checked)
    setTextColor(ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(colorOf(R.color.on_primary_container), colorText)))
    val states = StateListDrawable().apply {
        addState(checked, rounded(colorOf(R.color.primary_container), 14, colorPrimary))
        addState(intArrayOf(), rounded(colorOf(R.color.surface_variant), 14))
    }
    background = RippleDrawable(ColorStateList.valueOf(colorOf(R.color.ripple)), states as Drawable, null)
}

// ------------------------------------------------------------------ insets

/** Pad the view by [basePad] plus whatever the status/navigation bars cover (edge-to-edge). */
fun applySystemBarInsets(view: View, basePad: Int) {
    view.setPadding(basePad, basePad, basePad, basePad)
    if (Build.VERSION.SDK_INT >= 30) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(basePad + b.left, basePad + b.top, basePad + b.right, basePad + b.bottom)
            insets
        }
    }
}

/** Standard scrolling screen: content padded and inset for the system bars. */
fun Context.screen(content: View): android.widget.ScrollView = PageScrollView(this).apply {
    clipToPadding = false
    isFillViewport = true
    addView(content)
    applySystemBarInsets(this, dp(20))
}

/**
 * A page's scroll view. After the page is rebuilt it's put back at the old position at the end
 * of its first layout, before anything is drawn: no flash of the top of the page.
 *
 * (It used to be done in a pre-draw listener that skipped that frame. On a Pixel 8 Pro, a
 * scrolled check-in page redrawn on unlocking, after ending a test ring on the lock screen,
 * then hung: "Gist isn't responding". Skipping a frame Android is waiting for is risky; this
 * skips nothing.)
 */
class PageScrollView(context: Context) : android.widget.ScrollView(context) {
    /** The position to put back at the next layout, or 0. */
    var pendingY = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pendingY > 0 && height > 0 && childCount > 0 && getChildAt(0).height > 0) {
            val y = pendingY
            pendingY = 0
            scrollTo(0, y) // clamped to the page's height
        }
    }
}

/** Puts a rebuilt page back at scroll position [y] (see [PageScrollView]). */
fun android.widget.ScrollView.restoreScroll(y: Int) {
    if (y <= 0) return
    if (this is PageScrollView) pendingY = y else post { scrollTo(0, y) }
}

/** Where the page is scrolled to, counting a position that hasn't been put back yet. */
val android.widget.ScrollView.pageScrollY: Int
    get() = (this as? PageScrollView)?.pendingY?.takeIf { it > 0 } ?: scrollY

// ------------------------------------------------------------------ dialogs
//
// Every list in a dialog is drawn with the app's tiles (instead of Android's plain rows),
// so dialogs match the rest of the app.

/** One choice in [pickDialog]: a title, an optional line under it, and a test tag. */
data class DialogOption(val title: String, val detail: String? = null, val tag: String? = null)

/** Padded, scrolling body for a dialog. */
fun Context.dialogBody(build: LinearLayout.() -> Unit): android.widget.ScrollView {
    val col = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
    col.build()
    return android.widget.ScrollView(this).apply { addView(col) }
}

/** A tappable tile: rounded, highlighted when [selected]. */
fun Context.optionTile(option: DialogOption, selected: Boolean = false, onClick: () -> Unit): LinearLayout = column().apply {
    val bg = if (selected) rounded(colorOf(R.color.primary_container), 14, colorPrimary)
        else rounded(colorOf(R.color.surface_variant), 14)
    background = RippleDrawable(ColorStateList.valueOf(colorOf(R.color.ripple)), bg, null)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    isClickable = true
    isFocusable = true
    option.tag?.let { tag = it }
    val fg = if (selected) colorOf(R.color.on_primary_container) else colorText
    add(label(option.title, 16f, bold = true, color = fg))
    option.detail?.let { add(label(it, 13f, color = if (selected) fg else colorMuted), dp(2)) }
    contentDescription = option.title
    setOnClickListener { onClick() }
}

/**
 * A titled dialog listing [options] as tiles; tapping one closes it and calls [onPick] with
 * its index. [selected] highlights the current choice.
 */
fun Context.pickDialog(
    title: String,
    options: List<DialogOption>,
    selected: Int = -1,
    message: String? = null,
    onPick: (Int) -> Unit,
): android.app.AlertDialog {
    lateinit var dialog: android.app.AlertDialog
    val body = dialogBody {
        if (message != null) add(muted(message, 14f))
        options.forEachIndexed { i, o ->
            add(optionTile(o, i == selected) { dialog.dismiss(); onPick(i) }, if (i == 0 && message == null) 0 else dp(8))
        }
    }
    dialog = android.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setView(body)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.show()
    return dialog
}

/** A checkbox drawn as a tile (for lists of things to tick). */
fun Context.checkTile(text: String, checked: Boolean, detail: String? = null): android.widget.CheckBox =
    android.widget.CheckBox(this).apply {
        this.text = if (detail == null) text else "$text\n$detail"
        isChecked = checked
        setTextColor(colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(48)
        setPadding(dp(8), dp(8), dp(12), dp(8))
        buttonTintList = ColorStateList.valueOf(colorPrimary)
        background = RippleDrawable(ColorStateList.valueOf(colorOf(R.color.ripple)), rounded(colorOf(R.color.surface_variant), 14), null)
    }

/**
 * A dialog of checkbox tiles in [columns] columns; [onSave] gets which are ticked. Short
 * labels (dates) fit several to a row.
 */
fun Context.checklistDialog(
    title: String,
    labels: List<String>,
    checked: BooleanArray,
    columns: Int = 1,
    message: String? = null,
    onSave: (BooleanArray) -> Unit,
) {
    val boxes = labels.mapIndexed { i, l -> checkTile(l, checked[i]).apply { tag = "check:$l" } }
    val body = dialogBody {
        if (message != null) add(muted(message, 14f), 0)
        boxes.chunked(columns).forEachIndexed { r, chunk ->
            val line = row()
            chunk.forEachIndexed { k, b -> line.addWeighted(b, startMargin = if (k == 0) 0 else dp(6)) }
            repeat(columns - chunk.size) { line.addWeighted(android.view.View(this@checklistDialog), startMargin = dp(6)) }
            add(line, if (r == 0 && message == null) 0 else dp(6))
        }
    }
    android.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setView(body)
        .setPositiveButton("Save") { _, _ -> onSave(BooleanArray(boxes.size) { boxes[it].isChecked }) }
        .setNegativeButton("Cancel", null)
        .show()
}

/** A card-like group inside a dialog (e.g. one check-in in the export / import lists). */
fun Context.dialogCard(): LinearLayout = card().apply { setPadding(dp(14), dp(10), dp(14), dp(12)) }
