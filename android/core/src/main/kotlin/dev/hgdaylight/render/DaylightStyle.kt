package dev.hgdaylight.render

/**
 * The Daylight look for the field: colour to gray by luminance with a tone curve for the DC-1's reflective
 * panel, then solid black outlines where one object stands in front of another (the DS's own edge marking,
 * `sAreaEdgeColorTable` in `src/field/area_data.c`, made black and applied to every object).
 */
object DaylightStyle {
    class Options(
        /** Output gray range: the panel's black ink is reserved for outlines. */
        val floor: Int = 0x30,
        val ceil: Int = 0xF4,
        /** Input levels: HGSS's field is bright pastel, so its luminance spans roughly this range. */
        val inLow: Float = 0.32f,
        val inHigh: Float = 0.97f,
        /** > 1 darkens the mid-tones (grass, roofs) and keeps paths light. */
        val gamma: Float = 1.7f,
        val outlines: Boolean = true,
        /** A depth jump inside one object, relative to its depth, that still draws a line (silhouettes, eaves). */
        val creaseRatio: Float = 0.012f,
        /** On the ground, where a pixel is darker than its neighbour by at least [rimContrast], it gets a rim. */
        val rims: Boolean = true,
        val rimContrast: Int = 0x30,
        val rimShade: Int = 0x48,
    )

    fun luminance(c: Int): Float {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    fun gray(c: Int, o: Options): Int {
        var l = ((luminance(c) - o.inLow) / (o.inHigh - o.inLow)).coerceIn(0f, 1f)
        l = Math.pow(l.toDouble(), o.gamma.toDouble()).toFloat()
        return (o.floor + l * (o.ceil - o.floor)).toInt()
    }

    /** Gray values (0..255) per pixel; [Raster.color] untouched. */
    fun apply(r: Raster, o: Options = Options()): IntArray {
        val out = IntArray(r.w * r.h) { gray(r.color[it], o) }
        if (!o.outlines) return out
        val edge = BooleanArray(r.w * r.h)
        for (y in 0 until r.h) for (x in 0 until r.w) {
            val i = y * r.w + x
            if (r.id[i] == 0) continue
            val d = r.depth[i]
            fun check(j: Int): Boolean {
                if (r.id[j] == 0) return true
                val dj = r.depth[j]
                if (dj <= d) return false // the nearer pixel of the pair carries the line
                if (r.id[j] != r.id[i]) return dj - d > 2f
                if (r.polyId[j] != r.polyId[i]) return dj - d > 2f
                return dj - d > d * o.creaseRatio
            }
            if ((x > 0 && check(i - 1)) || (x < r.w - 1 && check(i + 1)) || (y > 0 && check(i - r.w)) || (y < r.h - 1 && check(i + r.w))) edge[i] = true
        }
        if (o.rims) {
            val base = out.copyOf()
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val i = y * r.w + x
                if (r.id[i] == 0 || edge[i]) continue
                fun lighterNeighbour(j: Int) = r.id[j] == r.id[i] && r.kind[i].toInt() == 1 &&
                    base[j] - base[i] >= o.rimContrast && kotlin.math.abs(r.depth[j] - r.depth[i]) < 4f
                if ((x > 0 && lighterNeighbour(i - 1)) || (x < r.w - 1 && lighterNeighbour(i + 1)) ||
                    (y > 0 && lighterNeighbour(i - r.w)) || (y < r.h - 1 && lighterNeighbour(i + r.w))) out[i] = minOf(out[i], o.rimShade)
            }
        }
        for (i in out.indices) if (edge[i]) out[i] = 0
        return out
    }
}
