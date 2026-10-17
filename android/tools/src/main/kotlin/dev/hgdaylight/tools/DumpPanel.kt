package dev.hgdaylight.tools

import dev.hgdaylight.g2d.Bg2d
import dev.hgdaylight.rom.NdsRom
import java.io.File

/** The field touch menu's background (a/0/1/4: palette 7, chars 8, screen 9) to out/panel.png. */
fun main() {
    val rom = NdsRom.load(File("rom/soulsilver.us.nds"))
    val t = Bg2d.render(rom.narc("a/0/1/4"), 7, 8, 9)
    println("panel ${t.width}x${t.height}")
    write(t.width, t.height, t.argb, 2, File("out/panel.png"))
}
