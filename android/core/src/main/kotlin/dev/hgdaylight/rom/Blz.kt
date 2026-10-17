package dev.hgdaylight.rom

/**
 * Nintendo's backward LZ (BLZ), used for the ARM9 binary and the overlays: decoded in place from the end.
 * The footer's 8 bytes give the encoded length (low 24 bits), the header length (high byte) and the growth.
 */
object Blz {
    fun decode(src: ByteArray, end: Int): ByteArray {
        fun u32(o: Int) = (src[o].toInt() and 255) or ((src[o + 1].toInt() and 255) shl 8) or
            ((src[o + 2].toInt() and 255) shl 16) or ((src[o + 3].toInt() and 255) shl 24)
        val w0 = u32(end - 8); val extra = u32(end - 4)
        val start = end - (w0 and 0xFFFFFF)
        val out = src.copyOf(end + extra)
        var s = end - (w0 ushr 24)
        var d = end + extra
        while (s > start) {
            var flags = out[--s].toInt() and 255
            for (b in 0 until 8) {
                if (s <= start) break
                if (flags and 0x80 != 0) {
                    s -= 2
                    val x = (out[s].toInt() and 255) or ((out[s + 1].toInt() and 255) shl 8)
                    val n = (x ushr 12) + 3; val disp = (x and 0xFFF) + 3
                    repeat(n) { d--; out[d] = out[d + disp] }
                } else {
                    d--; s--; out[d] = out[s]
                }
                flags = (flags shl 1) and 255
            }
        }
        return out
    }
}
