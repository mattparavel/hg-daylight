package dev.hgdaylight.tools

import dev.hgdaylight.rom.NdsRom
import java.io.File

/** The field touch menu's background (a/0/1/4: palette 7, chars 8, screen 9) to out/panel.png. */
fun main() {
    val rom = NdsRom.load(File("rom/soulsilver.us.nds"))
    val menu = dev.hgdaylight.g2d.TouchMenu(rom)
    val t = menu.render()
    write(t.width, t.height, t.argb, 2, File("out/panel.png"))
    val h = menu.render(highlight = 1)
    write(h.width, h.height, h.argb, 2, File("out/panel_hl.png"))
    val ink = menu.render(inkLabels = true)
    val g = IntArray(ink.argb.size) { 0xFF000000.toInt() or (dev.hgdaylight.render.DaylightStyle.uiGray(ink.argb[it]) * 0x010101) }
    write(t.width, t.height, g, 2, File("out/panel_gray.png"))
}
