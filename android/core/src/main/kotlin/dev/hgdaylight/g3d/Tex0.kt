package dev.hgdaylight.g3d

import dev.hgdaylight.rom.Bytes

/** A decoded texture: ARGB8888 texels, alpha from the format (A3I5/A5I3/colour-0 transparency/4x4 modes). */
class Texture(val name: String, val width: Int, val height: Int, val argb: IntArray) {
    /** True when some texels are fully transparent: the polygon's silhouette comes from the texture. */
    val cutout: Boolean = argb.any { it ushr 24 == 0 }
    val translucent: Boolean = argb.any { val a = it ushr 24; a in 1..0xF7 }
}

/**
 * The TEX0 block (NNSG3dResTex): texture images, 4x4-compressed images with their palette indices, and
 * palettes, each with a dictionary. Textures and palettes are separate resources bound by name through a
 * material, so decoding needs both: [decode].
 */
class Tex0(val b: Bytes) {
    private val texData = b.u32(0x14)
    private val tex4x4Data = b.u32(0x24)
    private val tex4x4Idx = b.u32(0x28)
    internal val plttData = b.u32(0x38)
    val textures = Dict(b.at(0x3C))
    val palettes = Dict(b.at(b.u16(0x34)))

    fun textureIndex(name: String): Int = textures.names().indexOf(name)
    fun paletteIndex(name: String): Int = palettes.names().indexOf(name)

    /** The texture's TEXIMAGE_PARAM bits (format, size, colour-0 transparency) as stored in its dict entry. */
    fun imageParam(i: Int): Int = textures.data(i).u32(0)

    /** Texels from this file with a palette from [src] (map props take palettes from the area's file). */
    fun decodeWith(texIdx: Int, src: Tex0, plttIdx: Int): Texture = decode(texIdx, plttIdx, src)

    fun decode(texIdx: Int, plttIdx: Int, src0: Tex0 = this): Texture {
        val param = imageParam(texIdx)
        val fmt = (param ushr 26) and 7
        val w = 8 shl ((param ushr 20) and 7)
        val h = 8 shl ((param ushr 23) and 7)
        val color0Clear = (param ushr 29) and 1 == 1
        val off = (param and 0xFFFF) shl 3
        val out = IntArray(w * h)
        val pb = src0.b
        val pal = if (plttIdx >= 0) src0.plttData + (src0.palettes.data(plttIdx).u16(0) shl 3) else -1
        fun pc(i: Int): Int = if (pal < 0) 0xFF000000.toInt() or 0xFF00FF else 0xFF000000.toInt() or rgb555(pb.u16(pal + 2 * i))
        val src = texData + off
        when (fmt) {
            1 -> for (i in 0 until w * h) { // A3I5
                val v = b.u8(src + i)
                val a3 = v ushr 5
                out[i] = (((a3 shl 2) + (a3 ushr 1)) * 255 / 31 shl 24) or (pc(v and 31) and 0xFFFFFF)
            }
            2 -> for (i in 0 until w * h) { // 4 colours
                val v = (b.u8(src + i / 4) ushr (2 * (i % 4))) and 3
                out[i] = if (v == 0 && color0Clear) 0 else pc(v)
            }
            3 -> for (i in 0 until w * h) { // 16 colours
                val v = (b.u8(src + i / 2) ushr (4 * (i % 2))) and 15
                out[i] = if (v == 0 && color0Clear) 0 else pc(v)
            }
            4 -> for (i in 0 until w * h) { // 256 colours
                val v = b.u8(src + i)
                out[i] = if (v == 0 && color0Clear) 0 else pc(v)
            }
            5 -> decode4x4(off, w, h, pal, pb, out)
            6 -> for (i in 0 until w * h) { // A5I3
                val v = b.u8(src + i)
                out[i] = ((v ushr 3) * 255 / 31 shl 24) or (pc(v and 7) and 0xFFFFFF)
            }
            7 -> for (i in 0 until w * h) { // direct
                val v = b.u16(src + 2 * i)
                out[i] = if (v and 0x8000 == 0) 0 else 0xFF000000.toInt() or rgb555(v)
            }
        }
        return Texture(textures.name(texIdx), w, h, out)
    }

    private fun decode4x4(off: Int, w: Int, h: Int, pal: Int, pb: Bytes, out: IntArray) {
        val bw = w / 4
        for (by in 0 until h / 4) for (bx in 0 until bw) {
            val blk = by * bw + bx
            val texels = b.u32(tex4x4Data + off + 4 * blk)
            val idx = b.u16(tex4x4Idx + (off shr 1) + 2 * blk)
            val mode = idx ushr 14
            val p = pal + ((idx and 0x3FFF) shl 2)
            fun c(i: Int) = pb.u16(p + 2 * i)
            val c0 = c(0); val c1 = c(1)
            val cols = IntArray(4)
            cols[0] = 0xFF000000.toInt() or rgb555(c0)
            cols[1] = 0xFF000000.toInt() or rgb555(c1)
            when (mode) {
                0 -> { cols[2] = 0xFF000000.toInt() or rgb555(c(2)); cols[3] = 0 }
                1 -> { cols[2] = 0xFF000000.toInt() or mix(c0, c1, 1, 1); cols[3] = 0 }
                2 -> { cols[2] = 0xFF000000.toInt() or rgb555(c(2)); cols[3] = 0xFF000000.toInt() or rgb555(c(3)) }
                else -> { cols[2] = 0xFF000000.toInt() or mix(c0, c1, 5, 3); cols[3] = 0xFF000000.toInt() or mix(c0, c1, 3, 5) }
            }
            for (y in 0 until 4) for (x in 0 until 4) {
                val v = (texels ushr (2 * (y * 4 + x))) and 3
                out[(by * 4 + y) * w + bx * 4 + x] = cols[v]
            }
        }
    }

    private fun mix(a: Int, b2: Int, wa: Int, wb: Int): Int {
        val ra = rgb555(a); val rb = rgb555(b2)
        fun ch(s: Int) = (((ra shr s) and 255) * wa + ((rb shr s) and 255) * wb) / (wa + wb)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** BGR555 to RGB888, expanding 5 bits to 8 the usual way. */
fun rgb555(c: Int): Int {
    val r = c and 31; val g = (c shr 5) and 31; val b = (c shr 10) and 31
    return (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((b shl 3) or (b shr 2))
}
