package dev.hgdaylight.field

import dev.hgdaylight.g3d.NnsFile
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.g3d.Texture
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
        frames = (1..32).map { n -> tex.decode(tex.textureIndex("$prefix.$n"), pal) }
    }

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
    }
}
