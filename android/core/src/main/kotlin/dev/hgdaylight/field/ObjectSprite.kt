package dev.hgdaylight.field

import dev.hgdaylight.g3d.NnsFile
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.render.DaylightStyle
import dev.hgdaylight.rom.NdsRom

/**
 * A field object's textures from mmodel member [member]: "name.1".."name.N". Walking people have 16 or more
 * (4 per facing, north, south, west, east, as the hero); static objects (item balls, boulders) have a few
 * or one. Converted to gray once with the sprite curve.
 */
class ObjectSprite(rom: NdsRom, member: Int) {
    private val frames: List<Texture>

    init {
        val tex = Tex0(NnsFile(rom.narc(HeroSprite.MMODEL)[member]).block("TEX0")!!)
        val names = tex.textures.names()
        val prefix = names.firstOrNull()?.substringBefore('.') ?: ""
        val numbered = names.mapNotNull { n -> n.substringAfter('.', "").toIntOrNull()?.let { it to n } }.sortedBy { it.first }
        val order = if (numbered.isNotEmpty()) numbered.map { it.second } else names
        val pal = tex.paletteIndex(prefix).takeIf { it >= 0 } ?: 0
        frames = order.map { gray(tex.decode(tex.textureIndex(it), pal)) }
    }

    /** The standing frame for DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3; [step] 0..3 walks. */
    fun frame(dir: Int, step: Int = 0): Texture =
        if (frames.size >= 16) frames[(dir.coerceIn(0, 3) * 4 + step) % frames.size] else frames[0]

    private fun gray(t: Texture): Texture {
        val out = IntArray(t.argb.size) {
            val c = t.argb[it]
            if (c ushr 24 == 0) 0 else (c and 0xFF000000.toInt()) or (DaylightStyle.spriteGray(c) * 0x010101)
        }
        return Texture(t.name, t.width, t.height, out)
    }
}
