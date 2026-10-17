package dev.hgdaylight.text

import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.NdsRom

/**
 * A message bank (`src/msgdata.c`): u16 count, u16 key, then count {u32 offset, u32 length} entries, each
 * XORed with (key * 765 * (n + 1)) & 0xFFFF repeated in both halves; the text's u16 characters are XORed
 * with a seed starting at (n + 1) * 596947 that grows by 18749 per character. Characters are charmap codes
 * (`charmap.txt`); 0xFFFF ends a string, 0xFFFE starts a control code.
 */
class MsgBank(private val b: Bytes) {
    val count = b.u16(0)
    private val key = b.u16(2)

    fun codes(n: Int): IntArray {
        var k = (key * 765 * (n + 1)) and 0xFFFF
        k = k or (k shl 16)
        val off = b.u32(4 + 8 * n) xor k
        val len = b.u32(8 + 8 * n) xor k
        var seed = ((n + 1) * 596947) and 0xFFFF
        return IntArray(len) {
            val c = b.u16(off + 2 * it) xor seed
            seed = (seed + 18749) and 0xFFFF
            c
        }.let { a -> a.copyOf(a.indexOf(0xFFFF).takeIf { it >= 0 } ?: a.size) }
    }

    companion object {
        const val MSG = "a/0/2/7"
        fun load(rom: NdsRom, bank: Int) = MsgBank(rom.narc(MSG)[bank])
    }
}

/**
 * A font (`src/font_data.c`): header {headerSize, widthDataStart, numGlyphs, fixed w/h, glyph w/h in tiles},
 * glyphs of 2-bpp 8x8 tiles (16 bytes each; a row is one u16, high byte first, 2 bits per pixel, high bits
 * first), and a width per glyph. A character code c draws glyph c - 1 (TryLoadGlyph).
 * Pixel values: 1 the letter, 2 its shadow, 0 and 3 the background.
 */
class Font(private val b: Bytes) {
    private val headerSize = b.u32(0)
    private val widthStart = b.u32(4)
    val glyphs = b.u32(8)
    private val tw = b.u8(14)
    private val th = b.u8(15)
    private val glyphSize = 16 * tw * th
    val height = 8 * th

    fun width(code: Int): Int {
        val g = code - 1
        return if (g in 0 until glyphs) b.u8(widthStart + g) else 0
    }

    fun textWidth(codes: IntArray, spacing: Int = 0): Int = codes.filter { it < 0xFFFE }.sumOf { width(it) + spacing }

    /** The glyph's pixel values (0..3), 8*tw wide and 8*th tall. */
    fun glyph(code: Int): IntArray {
        val g = (code - 1).let { if (it in 0 until glyphs) it else 427 - 1 }
        val w = 8 * tw
        val out = IntArray(w * height)
        val base = headerSize + g * glyphSize
        for (t in 0 until tw * th) {
            val tx = (t % tw) * 8; val ty = (t / tw) * 8
            for (row in 0 until 8) {
                val word = b.u16(base + 16 * t + 2 * row)
                for (px in 0 until 8) {
                    val byte = if (px < 4) word ushr 8 else word and 0xFF
                    val v = (byte ushr (6 - 2 * (px % 4))) and 3
                    out[(ty + row) * w + tx + px] = v
                }
            }
        }
        return out
    }

    /**
     * Draws [codes] into [out] (w wide) at (x, y): value 1 in [fg], 2 in [shadow] (null: no shadow); control
     * codes are skipped.
     */
    fun draw(codes: IntArray, out: IntArray, w: Int, h: Int, x: Int, y: Int, fg: Int, shadow: Int?, spacing: Int = 0) {
        var cx = x
        var i = 0
        while (i < codes.size) {
            val c = codes[i]
            if (c == 0xFFFE) { i += 3 + (codes.getOrElse(i + 2) { 0 }); continue } // {cmd, nparams, params...}
            val g = glyph(c)
            val gw = 8 * tw
            for (yy in 0 until height) for (xx in 0 until gw) {
                val v = g[yy * gw + xx]
                val col = when (v) { 1 -> fg; 2 -> shadow ?: continue; else -> continue }
                val X = cx + xx; val Y = y + yy
                if (X in 0 until w && Y in 0 until h) out[Y * w + X] = col
            }
            cx += width(c) + spacing
            i++
        }
    }

    companion object {
        const val FONTS = "a/0/1/6"
        fun load(rom: NdsRom, id: Int = 0) = Font(rom.narc(FONTS)[id])
    }
}
