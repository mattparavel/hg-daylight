package dev.hgdaylight.rom

/** A NARC archive: BTAF (offsets), BTNF (names, unused here), GMIF (data). Members are read by index. */
class Narc(private val b: Bytes) {
    private val starts: IntArray
    private val ends: IntArray
    private val gmif: Int

    init {
        require(b.ascii(0, 4) == "NARC") { "Not a NARC" }
        var p = b.u16(0x0C)
        require(b.ascii(p, 4) == "BTAF")
        val n = b.u32(p + 8)
        starts = IntArray(n) { b.u32(p + 12 + 8 * it) }
        ends = IntArray(n) { b.u32(p + 16 + 8 * it) }
        p += b.u32(p + 4)
        require(b.ascii(p, 4) == "BTNF")
        p += b.u32(p + 4)
        require(b.ascii(p, 4) == "GMIF")
        gmif = p + 8
    }

    val size: Int get() = starts.size

    operator fun get(i: Int): Bytes = b.at(gmif + starts[i], ends[i] - starts[i])
}
