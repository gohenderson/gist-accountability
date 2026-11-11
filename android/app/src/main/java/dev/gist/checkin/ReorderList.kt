package dev.gist.checkin

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * A vertical list whose rows can be dragged up and down by a handle. While dragging, the
 * row follows your finger and the others make room; letting go calls [onMoved] with the
 * row's old and new position (nothing is saved until then).
 *
 * Add rows with [addRow], each with its handle (any view inside the row).
 */
@SuppressLint("ViewConstructor")
class ReorderList(context: Context, private val onMoved: (from: Int, to: Int) -> Unit) : LinearLayout(context) {
    private var dragging: View? = null
    private var fromIndex = -1
    private var anchorY = 0f
    private var awaitingLayout = false // positions are stale right after a swap

    init {
        orientation = VERTICAL
    }

    fun addRow(row: View, handle: View, gap: Int) {
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (childCount == 0) 0 else gap
        })
        @Suppress("ClickableViewAccessibility")
        handle.setOnTouchListener { _, ev -> onHandleTouch(row, ev) }
    }

    private fun onHandleTouch(row: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = row
                fromIndex = indexOfChild(row)
                anchorY = ev.rawY
                parent?.requestDisallowInterceptTouchEvent(true) // don't let the page scroll
                row.animate().cancel()
                row.elevation = context.dp(8).toFloat()
                row.scaleX = 1.02f
                row.scaleY = 1.02f
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging !== row) return false
                row.translationY = ev.rawY - anchorY
                swapIfPastNeighbour(row)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging !== row) return false
                dragging = null
                parent?.requestDisallowInterceptTouchEvent(false)
                row.translationY = 0f
                row.elevation = 0f
                row.scaleX = 1f
                row.scaleY = 1f
                val to = indexOfChild(row)
                if (to != fromIndex && ev.actionMasked == MotionEvent.ACTION_UP) onMoved(fromIndex, to)
            }
        }
        return true
    }

    /** Once the dragged row's middle passes a neighbour's middle, they swap places. */
    private fun swapIfPastNeighbour(row: View) {
        if (awaitingLayout) return
        val i = indexOfChild(row)
        val mid = row.top + row.translationY + row.height / 2f
        val below = if (i < childCount - 1) getChildAt(i + 1) else null
        val above = if (i > 0) getChildAt(i - 1) else null
        if (below != null && mid > below.top + below.height / 2f) {
            val shift = below.height + gapOf(below)
            moveChild(below, i)       // the neighbour goes above us
            anchorY += shift          // keep the row under the finger after relayout
            row.translationY -= shift
        } else if (above != null && mid < above.top + above.height / 2f) {
            val shift = above.height + gapOf(row)
            moveChild(above, i)       // the neighbour goes below us
            anchorY -= shift
            row.translationY += shift
        }
    }

    private fun gapOf(v: View): Int = (v.layoutParams as? MarginLayoutParams)?.topMargin ?: 0

    /** Moves [child] to [index], keeping the first row's top margin at 0. */
    private fun moveChild(child: View, index: Int) {
        val gap = (0 until childCount).map { getChildAt(it) }.maxOf { gapOf(it) }
        removeView(child)
        addView(child, index)
        for (k in 0 until childCount) {
            (getChildAt(k).layoutParams as MarginLayoutParams).topMargin = if (k == 0) 0 else gap
        }
        awaitingLayout = true
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        awaitingLayout = false
    }
}
