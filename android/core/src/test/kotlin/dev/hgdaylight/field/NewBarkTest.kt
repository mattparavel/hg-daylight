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

class GameDataTest {
    private val romFile = File("../../rom/soulsilver.us.nds")

    @Test
    fun headersEventsAndSprites() {
        if (!romFile.exists()) return
        val rom = NdsRom.load(romFile)
        val gd = GameData(rom)
        val nb = gd.headers[60]
        assertEquals(2, nb.areaDataBank); assertEquals(0, nb.matrixId); assertEquals(57, nb.eventsBank)
        val lab = gd.headers[61]
        println("lab: area ${lab.areaDataBank} matrix ${lab.matrixId} events ${lab.eventsBank}")
        val ev = MapEvents.load(rom, nb.eventsBank)
        println("New Bark: ${ev.bgs.size} bgs, ${ev.objects.size} objects, ${ev.warps.size} warps")
        for (o in ev.objects) println("  obj ${o.id} sprite ${o.spriteId} -> mmodel ${gd.mmodelOf(o.spriteId)} at (${o.x}, ${o.z}) facing ${o.facing} move ${o.movement}")
        for (w in ev.warps) println("  warp (${w.x}, ${w.z}) -> header ${w.header} anchor ${w.anchor}")
        assertEquals(5, ev.warps.size)
        assertEquals(61, ev.warps[0].header)
        val woman = ev.objects.first { it.spriteId == 325 }
        val s = ObjectSprite(rom, gd.mmodelOf(woman.spriteId)!!)
        println("woman frame ${s.frame(1).width}x${s.frame(1).height}")
        val labEv = MapEvents.load(rom, lab.eventsBank)
        for (w in labEv.warps) println("  lab warp (${w.x}, ${w.z}) -> header ${w.header} anchor ${w.anchor}")
    }
}
