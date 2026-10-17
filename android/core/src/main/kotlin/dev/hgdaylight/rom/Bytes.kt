package dev.hgdaylight.rom

import java.nio.ByteBuffer

/**
 * Little-endian reads over a byte buffer (a memory-mapped ROM on the device), the way the DS lays out every
 * structure. Absolute reads only, so views are cheap and thread-safe.
 */
class Bytes(val a: ByteBuffer, val base: Int = 0, val size: Int = a.capacity() - base) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    fun u8(o: Int): Int = a.get(base + o).toInt() and 0xFF
    fun s8(o: Int): Int = a.get(base + o).toInt()
    fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    fun s16(o: Int): Int = u16(o).toShort().toInt()
    fun u32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
    fun s32(o: Int): Int = u32(o)

    /** A view starting at [o] (relative to this view). */
    fun at(o: Int, len: Int = size - o): Bytes = Bytes(a, base + o, len)

    fun copy(o: Int = 0, len: Int = size - o): ByteArray = ByteArray(len) { a.get(base + o + it) }

    fun ascii(o: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until len) {
            val c = u8(o + i)
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}

/** fx32 / fx16 (1.19.12 / 1.3.12 fixed point) to float. */
fun fx(v: Int): Float = v / 4096f
