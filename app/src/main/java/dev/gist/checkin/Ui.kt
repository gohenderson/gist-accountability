package dev.gist.checkin

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers for building layouts in code (no XML layouts, no libraries). */

val COLOR_GOOD = 0xFF43A047.toInt()
val COLOR_BAD = 0xFFE53935.toInt()
val COLOR_MUTED = 0xFF9E9E9E.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(text: CharSequence, sizeSp: Float = 16f, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(text: String, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

fun Context.column(): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

/** Add a full-width child with an optional top margin (px). */
fun LinearLayout.add(v: View, topMargin: Int = 0) {
    addView(
        v,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }
    )
}

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
