package dev.hgdaylight.g3d

import dev.hgdaylight.rom.Bytes
import kotlin.math.sqrt

/** One vertex after the display list: world position, texel coordinates, colour (0..1) and world normal. */
class Vtx(
    val x: Float, val y: Float, val z: Float,
    val s: Float, val t: Float,
    val r: Float, val g: Float, val b: Float,
    val nx: Float, val ny: Float, val nz: Float, val hasNormal: Boolean,
)

class Tri(val a: Vtx, val b: Vtx, val c: Vtx)

/**
 * Runs a GX display list (packed: four command bytes, then each command's parameter words) the way the
 * geometry engine does for static models: vertices through [matrix], primitives (triangles, quads and their
 * strips) split into triangles. Matrix commands inside the list are ignored: map models carry their
 * matrices in the SBC, which [Model.draws] already applied.
 */
object Gx {
    private val PARAMS = IntArray(0x80).also {
        it[0x10] = 1; it[0x12] = 1; it[0x13] = 1; it[0x14] = 1
        it[0x16] = 16; it[0x17] = 12; it[0x18] = 16; it[0x19] = 12; it[0x1A] = 9; it[0x1B] = 3; it[0x1C] = 3
        for (c in 0x20..0x2B) it[c] = 1
        it[0x23] = 2
        it[0x30] = 1; it[0x31] = 1; it[0x32] = 1; it[0x33] = 1; it[0x34] = 32
        it[0x40] = 1; it[0x50] = 1; it[0x60] = 1; it[0x70] = 3; it[0x71] = 2; it[0x72] = 1
    }

    fun run(dl: Bytes, matrix: FloatArray, posScale: Float, mat: Material, texW: Int, texH: Int, out: MutableList<Tri>) {
        var px = 0f; var py = 0f; var pz = 0f
        var s = 0f; var t = 0f
        val dif = rgb555(mat.diffuse)
        var cr = ((dif shr 16) and 255) / 255f; var cg = ((dif shr 8) and 255) / 255f; var cb = (dif and 255) / 255f
        var nx = 0f; var ny = 0f; var nz = 0f; var hasN = false
        var prim = 0
        val strip = ArrayList<Vtx>()
        val tmp = FloatArray(3)

        fun emit() {
            Mat4.apply(matrix, px * posScale, py * posScale, pz * posScale, tmp)
            val wx = tmp[0]; val wy = tmp[1]; val wz = tmp[2]
            var ox = 0f; var oy = 0f; var oz = 0f
            if (hasN) {
                Mat4.applyDir(matrix, nx, ny, nz, tmp)
                val l = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).coerceAtLeast(1e-6f)
                ox = tmp[0] / l; oy = tmp[1] / l; oz = tmp[2] / l
            }
            val ss = s * mat.texScaleS + mat.texTransS * texW
            val tt = t * mat.texScaleT + mat.texTransT * texH
            strip += Vtx(wx, wy, wz, ss, tt, cr, cg, cb, ox, oy, oz, hasN)
            val n = strip.size
            when (prim) {
                0 -> if (n == 3) { out += Tri(strip[0], strip[1], strip[2]); strip.clear() }
                1 -> if (n == 4) { out += Tri(strip[0], strip[1], strip[2]); out += Tri(strip[0], strip[2], strip[3]); strip.clear() }
                2 -> if (n >= 3) {
                    // Strip winding alternates; keep it consistent for back-face culling.
                    if (n % 2 == 1) out += Tri(strip[n - 3], strip[n - 2], strip[n - 1])
                    else out += Tri(strip[n - 2], strip[n - 3], strip[n - 1])
                }
                3 -> if (n >= 4 && n % 2 == 0) {
                    val a = strip[n - 4]; val b = strip[n - 3]; val c = strip[n - 1]; val d = strip[n - 2]
                    out += Tri(a, b, c); out += Tri(a, c, d)
                }
            }
        }

        var p = 0
        while (p + 4 <= dl.size) {
            val cmds = dl.u32(p); p += 4
            for (k in 0 until 4) {
                val op = (cmds ushr (8 * k)) and 0xFF
                val n = if (op < 0x80) PARAMS[op] else 0
                if (p + 4 * n > dl.size) return
                fun w(i: Int) = dl.u32(p + 4 * i)
                when (op) {
                    0x20 -> { val c = rgb555(w(0)); cr = ((c shr 16) and 255) / 255f; cg = ((c shr 8) and 255) / 255f; cb = (c and 255) / 255f }
                    0x21 -> {
                        val v = w(0)
                        nx = ((v shl 22) shr 22) / 512f; ny = ((v shl 12) shr 22) / 512f; nz = ((v shl 2) shr 22) / 512f
                        hasN = true
                    }
                    0x22 -> { val v = w(0); s = (v.toShort().toInt()) / 16f; t = ((v ushr 16).toShort().toInt()) / 16f }
                    0x23 -> { val a = w(0); val b = w(1); px = a.toShort() / 4096f; py = (a ushr 16).toShort() / 4096f; pz = b.toShort() / 4096f; emit() }
                    0x24 -> { val v = w(0); px = ((v shl 22) shr 16) / 4096f; py = ((v shl 12) shr 16) / 4096f; pz = ((v shl 2) shr 16) / 4096f; emit() }
                    0x25 -> { val v = w(0); px = v.toShort() / 4096f; py = (v ushr 16).toShort() / 4096f; emit() }
                    0x26 -> { val v = w(0); px = v.toShort() / 4096f; pz = (v ushr 16).toShort() / 4096f; emit() }
                    0x27 -> { val v = w(0); py = v.toShort() / 4096f; pz = (v ushr 16).toShort() / 4096f; emit() }
                    0x28 -> {
                        val v = w(0)
                        px += ((v shl 22) shr 22) / 4096f; py += ((v shl 12) shr 22) / 4096f; pz += ((v shl 2) shr 22) / 4096f
                        emit()
                    }
                    0x40 -> { prim = w(0) and 3; strip.clear() }
                    0x41 -> strip.clear()
                }
                p += 4 * n
            }
        }
    }
}
