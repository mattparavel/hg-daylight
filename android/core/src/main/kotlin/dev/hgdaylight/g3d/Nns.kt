package dev.hgdaylight.g3d

import dev.hgdaylight.rom.Bytes

/**
 * NNSG3dResDict (`lib/include/nnsys/g3d/binres/res_struct.h`): a patricia tree we skip, then entries of
 * `sizeUnit` bytes and their 16-byte names.
 */
class Dict(val b: Bytes) {
    val count = b.u8(1)
    private val entry = b.u16(6)
    val unit = b.u16(entry)
    private val names = entry + b.u16(entry + 2)

    fun data(i: Int): Bytes = b.at(entry + 4 + unit * i)
    fun name(i: Int): String = b.ascii(names + 16 * i, 16)
    fun names(): List<String> = List(count) { name(it) }
}

/** An NNS G3D file (BMD0, BTX0, ...): the blocks after the 16-byte header, by their 4-char kind. */
class NnsFile(val b: Bytes) {
    val signature = b.ascii(0, 4)
    private val blocks: List<Bytes> = List(b.u16(14)) {
        val off = b.u32(16 + 4 * it)
        b.at(off, b.u32(off + 4))
    }

    fun block(kind: String): Bytes? = blocks.firstOrNull { it.ascii(0, 4) == kind }
}
