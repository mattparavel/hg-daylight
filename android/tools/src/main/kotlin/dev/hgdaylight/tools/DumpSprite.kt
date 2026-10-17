package dev.hgdaylight.tools

import dev.hgdaylight.field.HeroSprite
import dev.hgdaylight.rom.NdsRom
import java.io.File

/** Writes the hero's 32 frames as one colour sheet (out/hero_sheet.png) and prints its palette. */
fun main() {
    val rom = NdsRom.load(File("rom/soulsilver.us.nds"))
    val hero = HeroSprite(rom)
    val w = 32 * 8; val h = 32 * 4
    val px = IntArray(w * h) { 0xFFFF00FF.toInt() }
    val colors = LinkedHashMap<Int, Int>()
    for (i in 0 until 32) {
        val f = hero.frame(i / 16 % 4 * 0 + i / 4 / 4, i >= 16, i % 16 * 0)
        val t = hero.frameByIndex(i)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = t.argb[y * 32 + x]
            if (c ushr 24 != 0) { px[(i / 8 * 32 + y) * w + i % 8 * 32 + x] = c; colors.merge(c and 0xFFFFFF, 1, Int::plus) }
        }
        f.hashCode()
    }
    write(w, h, px, 4, File("out/hero_sheet.png"))
    colors.entries.sortedByDescending { it.value }.forEach { println("#%06x %d".format(it.key, it.value)) }
}
