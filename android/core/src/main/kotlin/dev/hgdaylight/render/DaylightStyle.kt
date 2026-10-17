package dev.hgdaylight.render

import kotlin.math.roundToInt

/**
 * The Daylight look for the field, matched to the owner's mockup (`docs/art/mockup_new_bark.png`):
 *
 * - **Field gray:** HGSS's grass and paths have the same luminance (mint #73DEAB and tan #EFD692 are both
 *   about 0.84), so gray is not luminance: a fitted mix `0.568 R + 0.149 G + 0.243 B + 13`, then a curve
 *   through the mockup's values (path 224, grass 158, tree canopies 60..137, roofs and walls 180..210).
 * - **Sprite gray:** luminance with a gentle curve, so hair, jacket, cap and face stay apart, and the
 *   sprite's own black outline stays black.
 * - **Ink:** local contrast on the field (each pixel against its 3x3 mean, darks pushed harder than lights),
 *   which turns the grass fringes, tufts, window frames and roof seams into the mockup's dark inked detail.
 * - **Outlines:** solid black where one object stands in front of another, where polygon ids differ (the
 *   DS's own edge marking, `sAreaEdgeColorTable` in `src/field/area_data.c`, made black), and at depth
 *   creases; [Options.lineWidth] device pixels wide (the near side's whole canvas pixel, plus the rest on
 *   the far side).
 * - **Cracks:** a one-pixel gap between polygons shows its neighbour, not the background or an outline.
 *
 * Two steps, the same on the GPU (`app/Shaders.kt`): [canvasPass] decides tones and lines per canvas pixel,
 * [devicePass] draws them at the device scale.
 */
object DaylightStyle {
    class Options(
        val creaseRatio: Float = 0.012f,
        /** Ink: a field pixel darker than its 3x3 neighbourhood is pushed down by this much of the difference. */
        val inkDark: Float = 1.0f,
        /** And a lighter one is lifted by this much (highlights). */
        val inkLight: Float = 0.35f,
        /** Total outline width in device pixels (the canvas pixel is [scale] wide). */
        val lineWidth: Int = 6,
        val scale: Int = 4,
        val paper: Int = 0xF4,
    )

    /** The fitted field mix: R, G, B weights and bias (0..255 space). */
    val MIX = floatArrayOf(0.568f, 0.149f, 0.243f, 13.3f)

    /** (mix value, mockup gray) pairs: the field curve. */
    private val FIELD_POINTS = listOf(
        0f to 24f, 84.6f to 60f, 89.2f to 72f, 95f to 85f, 100.4f to 95f, 112.3f to 111f, 121.1f to 125f,
        134.5f to 136f, 153.2f to 158f, 188.2f to 190f, 209.5f to 210f, 216.4f to 224f, 258f to 250f,
    )

    /** 256-entry curves, row 0 field, row 1 sprites; the GPU uploads the same table. */
    val LUT: IntArray = IntArray(512).also { t ->
        for (i in 0 until 256) t[i] = curve(FIELD_POINTS, i.toFloat()).roundToInt().coerceIn(0, 255)
        for (i in 0 until 256) {
            val l = i / 255f
            t[256 + i] = if (l < 0.08f) 0 else {
                val n = ((l - 0.08f) / 0.87f).coerceIn(0f, 1f)
                (0x30 + Math.pow(n.toDouble(), 0.85).toFloat() * (0xFC - 0x30)).roundToInt()
            }
        }
    }

    private fun curve(points: List<Pair<Float, Float>>, x: Float): Float {
        if (x <= points.first().first) return points.first().second
        for (k in 1 until points.size) {
            val (x1, y1) = points[k]
            if (x <= x1) {
                val (x0, y0) = points[k - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }

    fun fieldGray(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val m = (MIX[0] * r + MIX[1] * g + MIX[2] * b + MIX[3]).roundToInt().coerceIn(0, 255)
        return LUT[m]
    }

    fun spriteGray(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val l = ((0.299f * r + 0.587f * g + 0.114f * b)).roundToInt().coerceIn(0, 255)
        return LUT[256 + l]
    }

    /**
     * UI (the touch panel): four tones, as DQ Daylight's chrome. The menu's dark greens (frames, line art,
     * keylines, luminance < 0.45) become ink, its mid green box light gray, the icons' mint pale, white white.
     */
    fun uiGray(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        return when {
            l < 0.45f -> 0x00
            l < 0.62f -> 0xB4
            l < 0.85f -> 0xDC
            else -> 0xFF
        }
    }

    /** Per canvas pixel: gray, and which of its 4 sides carry a line (bit k: neighbour k is the far side). */
    class Canvas(val w: Int, val h: Int, val gray: IntArray, val lines: IntArray)

    private val NX = intArrayOf(-1, 1, 0, 0)
    private val NY = intArrayOf(0, 0, -1, 1)

    fun canvasPass(r: Raster, o: Options = Options()): Canvas {
        val w = r.w; val h = r.h
        // Crack fill: a background pixel between two drawn ones (left/right or up/down) takes the first.
        val src = IntArray(w * h) { it }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (r.id[i] != 0) continue
            if (x > 0 && x < w - 1 && r.id[i - 1] != 0 && r.id[i + 1] != 0) src[i] = i - 1
            else if (y > 0 && y < h - 1 && r.id[i - w] != 0 && r.id[i + w] != 0) src[i] = i - w
        }
        // Lines use the drawn pixels only: a crack carries none and a crack neighbour is skipped.
        fun crack(i: Int) = src[i] != i
        fun id(i: Int) = r.id[i]
        fun depth(i: Int) = r.depth[i]
        val gray = IntArray(w * h) {
            val s = src[it]
            when {
                r.id[s] == 0 -> o.paper
                r.kind[s].toInt() == 3 -> (r.color[s] shr 16) and 255 // sprites arrive in gray
                else -> fieldGray(r.color[s])
            }
        }
        // Ink, on field pixels only, against the mean of their 3x3 neighbourhood (same object or not).
        val base = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val s0 = src[i]
            if (r.id[s0] == 0 || r.kind[s0].toInt() == 3) continue
            var sum = 0
            for (oy in -1..1) for (ox in -1..1) sum += base[i + oy * w + ox]
            val diff = base[i] - sum / 9f
            gray[i] = (base[i] + diff * (if (diff < 0) o.inkDark else o.inkLight)).roundToInt().coerceIn(0, 255)
        }
        val lines = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (id(i) == 0) continue
            val d = depth(i)
            var mask = 0
            for (k in 0 until 4) {
                val qx = x + NX[k]; val qy = y + NY[k]
                if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                val j = qy * w + qx
                if (crack(j)) continue
                if (id(j) == 0) { mask = mask or (1 shl k); continue }
                val dj = depth(j)
                if (dj > d) {
                    val other = id(j) != id(i) || r.polyId[j] != r.polyId[i]
                    if (if (other) dj - d > 2f else dj - d > d * o.creaseRatio) mask = mask or (1 shl k)
                }
            }
            lines[i] = mask
        }
        return Canvas(w, h, gray, lines)
    }

    /**
     * Device pixels: the near side's canvas pixel is black; the far side's pixels within (lineWidth - scale)
     * device pixels of the shared border are black too. Rows run top-down here (screen order).
     */
    fun devicePass(c: Canvas, o: Options = Options()): IntArray {
        val s = o.scale
        val extra = (o.lineWidth - s).coerceAtLeast(0)
        val out = IntArray(c.w * s * c.h * s)
        val dw = c.w * s
        for (dy in 0 until c.h * s) for (dx in 0 until dw) {
            val x = dx / s; val y = dy / s
            val i = y * c.w + x
            var g = if (c.lines[i] != 0) 0 else c.gray[i]
            if (g != 0 && extra > 0) {
                val sx = dx % s; val sy = dy % s
                // Neighbour k of this pixel; its line toward us is bit (k xor 1) of its mask.
                for (k in 0 until 4) {
                    val qx = x + NX[k]; val qy = y + NY[k]
                    if (qx < 0 || qy < 0 || qx >= c.w || qy >= c.h) continue
                    if (c.lines[qy * c.w + qx] and (1 shl (k xor 1)) == 0) continue
                    val dist = when (k) { 0 -> sx; 1 -> s - 1 - sx; 2 -> sy; else -> s - 1 - sy }
                    if (dist < extra) { g = 0; break }
                }
            }
            out[dy * dw + dx] = 0xFF000000.toInt() or (g * 0x010101)
        }
        return out
    }
}
