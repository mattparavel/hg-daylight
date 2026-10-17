package dev.hgdaylight.app

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * DQ Daylight's invisible joystick (`ui/TouchGrammar.kt`), anywhere on the screen: a drag past the deadzone
 * (max(6 dp, 0.3 r)) becomes a floating stick whose centre follows the finger past its rim; the direction is
 * the dominant axis with 1.4x hysteresis. A lift before the deadzone is a tap.
 */
class Joystick(density: Float) {
    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** Medium: 64 dp across. */
    private val radius = 32f * density
    private val deadzone = maxOf(6f * density, 0.3f * radius)

    @Volatile var dir: Dir? = null
        private set
    @Volatile var taps = 0

    private var pointer = -1
    private var downX = 0f; private var downY = 0f
    private var originX = 0f; private var originY = 0f
    private var sticking = false
    private var axis = 0

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = e.getPointerId(0)
                downX = e.x; downY = e.y
                sticking = false; axis = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0) return true
                val x = e.getX(i); val y = e.getY(i)
                if (!sticking && hypot(x - downX, y - downY) >= deadzone) {
                    sticking = true
                    originX = downX; originY = downY
                }
                if (sticking) stick(x, y)
            }
            MotionEvent.ACTION_UP -> {
                if (!sticking) taps++
                release()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        pointer = -1; sticking = false; axis = 0; dir = null
    }

    private fun stick(x: Float, y: Float) {
        var dx = x - originX; var dy = y - originY
        val dist = hypot(dx, dy)
        if (dist > radius) {
            originX = x - dx / dist * radius; originY = y - dy / dist * radius
            dx = x - originX; dy = y - originY
        }
        if (hypot(dx, dy) < radius * 0.25f) { axis = 0; dir = null; return }
        val ax = abs(dx); val ay = abs(dy)
        val horizontal = when (axis) {
            1 -> ay <= ax * HYSTERESIS
            2 -> ax > ay * HYSTERESIS
            else -> ax > ay
        }
        axis = if (horizontal) 1 else 2
        dir = if (horizontal) { if (dx > 0) Dir.RIGHT else Dir.LEFT } else { if (dy > 0) Dir.DOWN else Dir.UP }
    }

    companion object {
        const val HYSTERESIS = 1.4f
    }
}
