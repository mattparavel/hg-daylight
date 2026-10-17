package dev.hgdaylight.field

import dev.hgdaylight.g3d.NnsFile
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.render.DaylightStyle
import dev.hgdaylight.rom.NdsRom

/**
 * A field object's billboard textures and its animation (`src/field/map_object_render.c`,
 * `map_object_billboard_motion.c`): 32x32 frames hero.1..hero.32 in mmodel member 69 (MMODEL_HERO), shown
 * through sequences of 16 animation frames, one texture per 4 frames (frame table member 281):
 * sequences 0-3 walk/stand north, south, west, east; 4-7 run. Standing snaps to frame 0 or 8.
 */
class HeroSprite(rom: NdsRom, member: Int = MMODEL_HERO, private val prefix: String = "hero") {
    private val frames: List<Texture>

    init {
        val tex = Tex0(NnsFile(rom.narc(MMODEL)[member]).block("TEX0")!!)
        val pal = tex.paletteIndex(prefix).takeIf { it >= 0 } ?: 0
        frames = (1..32).map { n -> gray(tex.decode(tex.textureIndex("$prefix.$n"), pal)) }
    }

    /**
     * Sprites are converted to gray once, here: the hero by hand, colour by colour, to the owner's mockup
     * (white cap, near-black hair, light face, dark jacket); any other colour by the sprite curve.
     * The style pass then takes a sprite's gray as is.
     */
    private fun gray(t: Texture): Texture {
        val out = IntArray(t.argb.size) {
            val c = t.argb[it]
            if (c ushr 24 == 0) 0 else {
                val g = HERO_GRAYS[c and 0xFFFFFF] ?: DaylightStyle.spriteGray(c)
                (c and 0xFF000000.toInt()) or (g * 0x010101)
            }
        }
        return Texture(t.name, t.width, t.height, out)
    }

    fun frameByIndex(i: Int): Texture = frames[i]

    /** DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3 (`include/constants/global_fieldmap.h`). */
    fun frame(dir: Int, running: Boolean, animFrame: Int): Texture {
        val seq = dir + if (running) 4 else 0
        return frames[(seq * 16 + Math.floorMod(animFrame, 16)) / 4]
    }

    companion object {
        const val MMODEL = "a/0/8/1"
        const val MMODEL_HERO = 69
        /** The billboard (mmodel member 266, mmdl_m32x32) is 32x32 units, bottom-centred on the object. */
        const val SIZE = 32
        /** MapObject_UpdateBillboardPosition's z + 6, plus the walking player's facing vector z 0.5. */
        const val Z_BIAS = 6.5f

        /** The hero's palette (mmodel 69, palette "hero") to gray. */
        val HERO_GRAYS = mapOf(
            0x000000 to 0x00, // outline
            0x292939 to 0x18,
            0x394242 to 0x28, // hair, cap band
            0x4a6363 to 0x50,
            0x7b4242 to 0x30, // jacket shadow
            0xc63939 to 0x58, // jacket
            0xf76b4a to 0x80, // jacket light
            0xbd7321 to 0xB4, // cap shade
            0xffb521 to 0xF4, // cap
            0xdea57b to 0xC4, // skin shade
            0xffd6bd to 0xF2, // skin
            0x948cad to 0x90,
            0x9c94b5 to 0xA0,
            0xbdb5d6 to 0xCC, // shorts, shirt
            0xefefff to 0xFF,
        )
    }
}
