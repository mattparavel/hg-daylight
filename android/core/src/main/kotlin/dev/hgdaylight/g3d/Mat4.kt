package dev.hgdaylight.g3d

import kotlin.math.cos
import kotlin.math.sin

/** Row-major 4x4 float matrices: v' = M * v with column vectors. */
object Mat4 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** From a 4x3 (3 rows of [r r r t]). */
    fun from43(m: FloatArray) = floatArrayOf(
        m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], 0f, 0f, 0f, 1f,
    )

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[i * 4 + k] * b[k * 4 + j]
            r[i * 4 + j] = s
        }
        return r
    }

    fun translate(x: Float, y: Float, z: Float) = floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z, 0f, 0f, 0f, 1f)
    fun scale(x: Float, y: Float, z: Float) = floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f)

    fun rotX(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f) }
    fun rotY(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f, 0f, 0f, 0f, 1f) }
    fun rotZ(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f) }

    fun apply(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * x + m[1] * y + m[2] * z + m[3]
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
    }

    fun applyDir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * x + m[1] * y + m[2] * z
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z
    }
}
