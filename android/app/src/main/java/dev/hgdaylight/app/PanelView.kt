package dev.hgdaylight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.hgdaylight.g2d.TouchMenu
import dev.hgdaylight.render.DaylightStyle

/**
 * The DS bottom screen as a panel docked in a corner (owner, 2026-10-17): drawn at a whole-number scale in
 * the Daylight grays with a black keyline. Drag it toward another corner to dock it there (remembered);
 * double-tap folds it into a tab and back. Touches on it never reach the joystick.
 */
class PanelView(context: Context, private val menu: TouchMenu) : View(context) {
    enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)
    var corner = runCatching { Corner.valueOf(prefs.getString("corner", Corner.BOTTOM_RIGHT.name)!!) }.getOrDefault(Corner.BOTTOM_RIGHT)
        private set
    private var folded = prefs.getBoolean("folded", false)
    /** 2x: 512x384 device px, a little under half the DC-1's width. */
    var scale = 2

    /** The menu in the UI's four tones, plain and with each icon highlighted (pressed). */
    private fun bitmapOf(highlight: Int): Bitmap {
        val t = menu.render(highlight)
        val px = IntArray(256 * 192) { 0xFF000000.toInt() or (DaylightStyle.uiGray(t.argb[it]) * 0x010101) }
        return Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, 256, 0, 0, 256, 192) }
    }
    private val bitmaps = List(TouchMenu.ICON_CHARS.size + 1) { bitmapOf(it - 1) }
    /** The icon under the finger (-1: none). */
    private var pressed = -1
    /** Called with the icon's slot when a press on it is released on it. */
    var onIcon: (Int) -> Unit = {}

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val ink = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = false }
    private val paper = Paint().apply { color = 0xFFF4F4F4.toInt(); isAntiAlias = false }
    private val rect = Rect()
    private val line = 6

    private var dragging = false
    private var dragX = 0f; private var dragY = 0f
    private var downX = 0f; private var downY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            folded = !folded
            prefs.edit().putBoolean("folded", folded).apply()
            invalidate()
            return true
        }
    })

    /** The panel's rectangle in this (full-screen) view. */
    private fun bounds(out: Rect) {
        val w = if (folded) TAB_W else 256 * scale + 2 * line
        val h = if (folded) TAB_H else 192 * scale + 2 * line
        val left = when (corner) { Corner.BOTTOM_LEFT, Corner.TOP_LEFT -> 0; else -> width - w }
        val top = when (corner) { Corner.TOP_LEFT, Corner.TOP_RIGHT -> 0; else -> height - h }
        out.set(left, top, left + w, top + h)
    }

    override fun onDraw(c: Canvas) {
        bounds(rect)
        if (dragging) rect.offset(dragX.toInt(), dragY.toInt())
        c.drawRect(rect, ink)
        val inner = Rect(rect.left + line, rect.top + line, rect.right - line, rect.bottom - line)
        if (folded) {
            c.drawRect(inner, paper)
            // Three bars: the tab that opens the panel.
            for (k in 0 until 3) c.drawRect(inner.centerX() - 28f, inner.top + 18f + k * 20f, inner.centerX() + 28f, inner.top + 28f + k * 20f, ink)
        } else {
            c.drawBitmap(bitmaps[pressed + 1], null, inner, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        bounds(rect)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !rect.contains(e.x.toInt(), e.y.toInt())) return false
        if (pressed < 0) gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                pressed = if (folded) -1 else iconAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (Math.abs(e.x - downX) > 24 || Math.abs(e.y - downY) > 24)) { dragging = true; pressed = -1 }
                if (dragging) { dragX = e.x - downX; dragY = e.y - downY; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressed >= 0 && e.actionMasked == MotionEvent.ACTION_UP && iconAt(e.x, e.y) == pressed) onIcon(pressed)
                pressed = -1
                if (dragging) {
                    // Dock at the corner nearest where the panel's centre was let go.
                    val cx = rect.centerX() + dragX; val cy = rect.centerY() + dragY
                    val left = cx < width / 2f; val top = cy < height / 2f
                    corner = when {
                        top && left -> Corner.TOP_LEFT
                        top -> Corner.TOP_RIGHT
                        left -> Corner.BOTTOM_LEFT
                        else -> Corner.BOTTOM_RIGHT
                    }
                    prefs.edit().putString("corner", corner.name).apply()
                }
                dragging = false; dragX = 0f; dragY = 0f
                invalidate()
            }
        }
        return true
    }

    /** The icon slot at view point (x, y): the panel maps 1:[scale] onto the DS screen (sTouchscreenMenuIconHitboxes). */
    private fun iconAt(x: Float, y: Float): Int {
        bounds(rect)
        val sx = ((x - rect.left - line) / scale).toInt(); val sy = ((y - rect.top - line) / scale).toInt()
        for (i in TouchMenu.HITBOXES.indices) {
            val h = menu.hitbox(i)
            if (sx in h[0] until h[2] && sy in h[1] until h[3]) return i
        }
        return -1
    }

    companion object {
        const val TAB_W = 120
        const val TAB_H = 96
    }
}
