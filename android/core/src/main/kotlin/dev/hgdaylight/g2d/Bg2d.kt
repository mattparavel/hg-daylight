package dev.hgdaylight.g2d

import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.g3d.rgb555
import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.Narc

/** LZ77 type 0x10 (the "compressed" flag of GfGfxLoader_Load*): 4-byte header, then flag bytes and blocks. */
object Lz10 {
    fun decode(b: Bytes): Bytes {
        if (b.u8(0) != 0x10) return b
        val size = b.u32(0) ushr 8
        val out = ByteArray(size)
        var s = 4; var d = 0
        while (d < size) {
            val flags = b.u8(s++)
            for (k in 7 downTo 0) {
                if (d >= size) break
                if (flags and (1 shl k) == 0) out[d++] = b.u8(s++).toByte()
                else {
                    val hi = b.u8(s); val lo = b.u8(s + 1); s += 2
                    val n = (hi ushr 4) + 3; val disp = (((hi and 15) shl 8) or lo) + 1
                    repeat(n) { if (d < size) { out[d] = out[d - disp]; d++ } }
                }
            }
        }
        return Bytes(out)
    }
}

/**
 * A 2D background from NCLR (palette), NCGR (tiles) and NSCR (tile map) members: the DS's text BG, drawn to
 * a texture. Tiles are 8x8, 4 or 8 bpp; map entries are tile (10 bits), h/v flip, palette (4 bits).
 */
object Bg2d {
    /** An NCLR's colours (RGB888), all sub-palettes in order. */
    fun palette(narc: Narc, member: Int): IntArray {
        val pal = Lz10.decode(narc[member])
        return IntArray((pal.size - 0x28) / 2) { rgb555(pal.u16(0x28 + 2 * it)) }
    }

    fun render(narc: Narc, pltt: Int, chars: Int, screen: Int): Texture {
        val pal = Lz10.decode(narc[pltt]); val chr = Lz10.decode(narc[chars]); val scr = Lz10.decode(narc[screen])
        val tt = 0x10 // TTLP
        val colors = IntArray((pal.size - tt - 0x18) / 2) { rgb555(pal.u16(tt + 0x18 + 2 * it)) }
        val cs = 0x10 // CHAR
        val bpp8 = chr.u32(cs + 0x0C) == 4
        val tileData = chr.at(cs + 0x20)
        val sc = 0x10 // SCRN
        val w = scr.u16(sc + 8); val h = scr.u16(sc + 10)
        val map = scr.at(sc + 0x14)
        val out = IntArray(w * h)
        val tw = w / 8
        for (ty in 0 until h / 8) for (tx in 0 until tw) {
            val e = map.u16(2 * (ty * tw + tx))
            val tile = e and 0x3FF; val hf = e and 0x400 != 0; val vf = e and 0x800 != 0; val pn = e ushr 12
            for (y in 0 until 8) for (x in 0 until 8) {
                val sx = if (hf) 7 - x else x; val sy = if (vf) 7 - y else y
                val idx = if (bpp8) tileData.u8(tile * 64 + sy * 8 + sx)
                else (tileData.u8(tile * 32 + sy * 4 + sx / 2) ushr (4 * (sx and 1))) and 15
                val ci = if (bpp8) idx else pn * 16 + idx
                out[(ty * 8 + y) * w + tx * 8 + x] = 0xFF000000.toInt() or colors.getOrElse(ci) { 0 }
            }
        }
        return Texture("bg", w, h, out)
    }
}
