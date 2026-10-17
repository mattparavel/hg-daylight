package dev.hgdaylight.field

import dev.hgdaylight.rom.NdsRom

/**
 * An area light file (`data/area0Nlight.txt`, `src/field/area_light.c`): time slots of 10 lines each,
 * "endTime," then four lights "enabled,r,g,b,x,y,z", then diffuse, ambient, specular and emission as
 * "r,g,b" (5-bit), then a blank line; "EOF" ends the file. endTime is seconds since midnight / 2.
 */
class AreaLight(text: String) {
    class Light(val color: FloatArray, val dir: FloatArray)

    class Entry(
        val endTime: Int,
        val lights: List<Light>,
        val diffuse: FloatArray,
        val ambient: FloatArray,
        val specular: FloatArray,
        val emission: FloatArray,
    )

    val entries: List<Entry>

    init {
        val lines = text.split('\r').map { it.trim('\n', ' ') }
        val out = ArrayList<Entry>()
        var i = 0
        while (i < lines.size && !lines[i].startsWith("EOF")) {
            if (lines[i].isEmpty()) { i++; continue }
            fun nums(l: String) = l.split(',').mapNotNull { it.trim().toIntOrNull() }
            val end = nums(lines[i])[0]
            val lights = (1..4).mapNotNull { k ->
                val n = nums(lines[i + k])
                if (n.isEmpty() || n[0] != 1) null
                else Light(floatArrayOf(n[1] / 31f, n[2] / 31f, n[3] / 31f), floatArrayOf(n[4] / 4096f, n[5] / 4096f, n[6] / 4096f))
            }
            fun col(k: Int) = nums(lines[i + k]).let { floatArrayOf(it[0] / 31f, it[1] / 31f, it[2] / 31f) }
            out += Entry(end, lights, col(5), col(6), col(7), col(8))
            i += 9
        }
        entries = out
    }

    /** The slot in effect at [secondsSinceMidnight] (AreaLightManager_New's search). */
    fun at(secondsSinceMidnight: Int): Entry = entries.firstOrNull { it.endTime > secondsSinceMidnight / 2 } ?: entries.last()

    companion object {
        private val FILES = listOf("data/area00light.txt", "data/area01light.txt", "data/area02light.txt", "data/dun20_01light.txt", "data/dun20_02light.txt")

        fun load(rom: NdsRom, kind: Int): AreaLight {
            val f = rom.file(FILES[kind.coerceIn(0, FILES.size - 1)])
            return AreaLight(String(f.copy(), Charsets.US_ASCII))
        }
    }
}
