package dev.hgdaylight.field

import dev.hgdaylight.rom.NdsRom
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Needs the owner's ROM at rom/soulsilver.us.nds (git-ignored); skipped without it. */
class NewBarkTest {
    private val romFile = File("../../rom/soulsilver.us.nds")
    private fun rom(): NdsRom? = if (romFile.exists()) NdsRom.load(romFile) else null

    @Test
    fun heroHas32Frames() {
        val rom = rom() ?: return
        val hero = HeroSprite(rom)
        val f = hero.frame(1, false, 0)
        assertEquals(32, f.width); assertEquals(32, f.height)
        assertTrue(f.cutout)
    }

    @Test
    fun newBarkCollisionAndHeights() {
        val rom = rom() ?: return
        val w = World(rom, 0, 2)
        val (cx, cz) = assertNotNull(w.cellOf(60))
        val land = assertNotNull(w.land(cx, cz))
        val sb = StringBuilder()
        for (z in 0 until 32) {
            for (x in 0 until 32) sb.append(if (land.blocked(x, z)) '#' else when (land.behavior(x, z)) { 0 -> '.'; 2 -> '"'; else -> 'o' })
            sb.append('\n')
        }
        println(sb)
        val free = (0 until 1024).count { !land.blocked(it % 32, it / 32) }
        assertTrue(free in 200..1000, "free tiles $free")
        val h = w.height(cx * 512f + 16 * 16 + 8, cz * 512f + 16 * 16 + 8, 0f)
        println("height at chunk centre: $h, bdhc=${land.bdhc != null}")
        assertNotNull(h)
    }
}
