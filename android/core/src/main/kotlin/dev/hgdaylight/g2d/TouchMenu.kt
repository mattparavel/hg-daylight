package dev.hgdaylight.g2d

import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.g3d.rgb555
import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.Narc
import dev.hgdaylight.rom.NdsRom

/** One OAM of a cell: position relative to the sprite, size, first tile, palette, flips. */
class Oam(val x: Int, val y: Int, val w: Int, val h: Int, val tile: Int, val palette: Int, val hFlip: Boolean, val vFlip: Boolean)

/** An NCER cell bank (RECN/CEBK): cells of OAMs. 1D tile mapping, 32-byte units (4 bpp). */
class CellBank(b: Bytes) {
    val cells: List<List<Oam>>

    init {
        val cs = 0x10
        val n = b.u16(cs + 8); val attr = b.u16(cs + 10)
        val base = cs + 8 + b.u32(cs + 12)
        val esz = if (attr == 1) 16 else 8
        val oams = base + n * esz
        cells = List(n) { i ->
            val e = base + esz * i
            val no = b.u16(e); val off = b.u32(e + 4)
            List(no) { k ->
                val a0 = b.u16(oams + off + 6 * k); val a1 = b.u16(oams + off + 6 * k + 2); val a2 = b.u16(oams + off + 6 * k + 4)
                val y = (a0 and 0xFF).let { if (it >= 128) it - 256 else it }
                val x = (a1 and 0x1FF).let { if (it >= 256) it - 512 else it }
                val (w, h) = SIZES[(a0 ushr 14) * 4 + (a1 ushr 14)]
                Oam(x, y, w, h, a2 and 0x3FF, a2 ushr 12, (a1 ushr 12) and 1 == 1, (a1 ushr 13) and 1 == 1)
            }
        }
    }

    companion object {
        /** OAM shape x size: square, wide, tall. */
        private val SIZES = listOf(
            8 to 8, 16 to 16, 32 to 32, 64 to 64,
            16 to 8, 32 to 8, 32 to 16, 64 to 32,
            8 to 16, 8 to 32, 16 to 32, 32 to 64,
        )
    }
}

/** Draws [cell] with 4-bpp 1D tiles [chars] and [colors] into [out] (w x h) at (ox, oy); colour 0 is clear. */
fun drawCell(cell: List<Oam>, chars: Bytes, colors: IntArray, palBase: Int, out: IntArray, w: Int, h: Int, ox: Int, oy: Int) {
    val tiles = chars.at(0x30)
    for (o in cell) for (ty in 0 until o.h / 8) for (tx in 0 until o.w / 8) {
        val tn = o.tile + ty * (o.w / 8) + tx
        for (py in 0 until 8) for (px in 0 until 8) {
            val off = tn * 32 + py * 4 + px / 2
            if (off >= tiles.size) continue
            val v = (tiles.u8(off) ushr (4 * (px and 1))) and 15
            if (v == 0) continue
            var x = tx * 8 + px; var y = ty * 8 + py
            if (o.hFlip) x = o.w - 1 - x
            if (o.vFlip) y = o.h - 1 - y
            val X = ox + o.x + x; val Y = oy + o.y + y
            if (X !in 0 until w || Y !in 0 until h) continue
            val ci = (o.palette + palBase) * 16 + v
            if (ci < colors.size) out[Y * w + X] = 0xFF000000.toInt() or colors[ci]
        }
    }
}

/**
 * The field touch menu (`src/field/touchscreen_menu.c`, data in `asm/touchscreen_menu_data.s`), drawn from
 * the ROM: the BG (palette 7, chars 8, screen 9 of a/0/1/4), then the icons of the standard layout
 * (sTouchscreenMenuLayouts[0]: icons 0-6) at sTouchscreenMenuIconPositions, each its own char file
 * (sTouchscreenMenuIcons) through cell bank 16, palette 14 (0-15 normal, 16-31 highlighted).
 */
class TouchMenu(rom: NdsRom, private val playerName: IntArray = ETHAN) {
    private val narc: Narc = rom.narc("a/0/1/4")
    private val bg = Bg2d.render(narc, 7, 8, 9)
    private val cells = CellBank(Lz10.decode(narc[16]))
    private val iconColors: IntArray = Lz10.decode(narc[14]).let { p -> IntArray((p.size - 0x28) / 2) { rgb555(p.u16(0x28 + 2 * it)) } }
    private val iconChars = ICON_CHARS.map { Lz10.decode(narc[it]) }
    private val font = dev.hgdaylight.text.Font.load(rom)
    private val bgColors = Bg2d.palette(narc, 7)
    /** Labels: message bank 196 (msg_0196), sTouchscreenMenuIcons[i].msgId; the player's name stands in for its placeholder. */
    private val labels: List<IntArray> = dev.hgdaylight.text.MsgBank.load(rom, 196).let { bank ->
        ICON_MSGS.map { expand(bank.codes(it)) }
    }

    /** {STRVAR_n} (0xFFFE 0x01nn ...) becomes [playerName]; other control codes stay for Font to skip. */
    private fun expand(codes: IntArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < codes.size) {
            if (codes[i] == 0xFFFE && i + 2 < codes.size) {
                val n = 3 + codes[i + 2]
                if ((codes[i + 1] ushr 8) == 0x01) out += playerName.toList() else out += codes.copyOfRange(i, minOf(codes.size, i + n)).toList()
                i += n
            } else out += codes[i++]
        }
        return out.toIntArray()
    }

    /** Icon [i]'s touch box in screen pixels (sTouchscreenMenuIconHitboxes): left, top, right, bottom. */
    fun hitbox(i: Int): IntArray = HITBOXES[i]

    /**
     * The bottom screen (256x192) with icon [highlight] in its highlighted palette (-1: none). [inkLabels]:
     * the Daylight panel's labels, black ink with no shadow (the original: white, shadowed).
     */
    fun render(highlight: Int = -1, inkLabels: Boolean = false): Texture {
        val out = IntArray(256 * 192) { val y = it / 256; val x = it % 256; bg.argb[y * bg.width + x] }
        for (i in ICON_CHARS.indices) {
            val (x, y) = POSITIONS[i]
            drawCell(cells.cells[0], iconChars[i], iconColors, if (i == highlight) 1 else 0, out, 256, 192, x, y)
        }
        // TouchscreenMenu_PrintIconLabels: 9x2-tile windows (sTouchscreenMenuIconWindowPositions), the label
        // centred in 72 px, colour 14 with shadow 2 of BG palette 4.
        val fg = if (inkLabels) 0xFF000000.toInt() else 0xFF000000.toInt() or bgColors[4 * 16 + 14]
        val shadow = if (inkLabels) null else 0xFF000000.toInt() or bgColors[4 * 16 + 2]
        for (i in labels.indices) {
            val (tx, ty) = LABEL_WINDOWS[i]
            val margin = 72 - font.textWidth(labels[i])
            font.draw(labels[i], out, 256, 192, tx * 8 + margin / 2, ty * 8, fg, shadow)
        }
        return Texture("touchmenu", 256, 192, out)
    }

    companion object {
        /** sTouchscreenMenuIcons[0..6].charFileId (the male player's card is 0x18; the female's is 27). */
        val ICON_CHARS = listOf(0x12, 0x15, 0x18, 0x1E, 0x21, 0x24, 0x27)
        /** sTouchscreenMenuIcons[0..6].msgId. */
        val ICON_MSGS = listOf(0, 1, 2, 14, 3, 4, 5)
        /** sTouchscreenMenuIconWindowPositions (tiles), slots 0-6. */
        val LABEL_WINDOWS = listOf(1 to 6, 1 to 11, 1 to 16, 1 to 21, 11 to 6, 11 to 11, 11 to 16)
        /** "ETHAN" in charmap codes (A = 0x12B): the default name until a save gives one. */
        val ETHAN = intArrayOf(0x12F, 0x13E, 0x132, 0x12B, 0x138)
        /** sTouchscreenMenuIconPositions, slots 0-6. */
        val POSITIONS = listOf(24 to 22, 24 to 62, 24 to 102, 24 to 142, 104 to 22, 104 to 62, 104 to 102)
        /** sTouchscreenMenuIconHitboxes (top, bottom, left, right bytes) for slots 0-6, as left, top, right, bottom. */
        val HITBOXES = listOf(
            intArrayOf(16, 22, 76, 54), intArrayOf(16, 62, 76, 94), intArrayOf(16, 102, 76, 134), intArrayOf(16, 142, 76, 174),
            intArrayOf(96, 22, 156, 54), intArrayOf(96, 62, 156, 94), intArrayOf(96, 102, 156, 134),
        )
    }
}
