package dev.hgdaylight.field

import dev.hgdaylight.rom.Bytes
import kotlin.math.abs

/**
 * A chunk's terrain heights (`src/field/bdhc.c`, `include/field/bdhc.h`): sloped plates over x/z, sorted into
 * strips along z. Coordinates are fx32, relative to the chunk centre (`Terrain_CalcHeight`).
 */
class Bdhc(b: Bytes) {
    private val px: IntArray; private val pz: IntArray
    private val nx: IntArray; private val ny: IntArray; private val nz: IntArray
    private val constants: IntArray
    private val plateA: IntArray; private val plateB: IntArray; private val plateN: IntArray; private val plateC: IntArray
    private val stripLower: IntArray; private val stripCount: IntArray; private val stripOffset: IntArray
    private val access: IntArray

    init {
        val points = b.u16(4); val normals = b.u16(6); val consts = b.u16(8)
        val plates = b.u16(10); val strips = b.u16(12); val accessCount = b.u16(14)
        var p = 16
        px = IntArray(points) { b.s32(p + 8 * it) }; pz = IntArray(points) { b.s32(p + 8 * it + 4) }; p += 8 * points
        nx = IntArray(normals) { b.s32(p + 12 * it) }; ny = IntArray(normals) { b.s32(p + 12 * it + 4) }; nz = IntArray(normals) { b.s32(p + 12 * it + 8) }; p += 12 * normals
        constants = IntArray(consts) { b.s32(p + 4 * it) }; p += 4 * consts
        plateA = IntArray(plates) { b.u16(p + 8 * it) }; plateB = IntArray(plates) { b.u16(p + 8 * it + 2) }
        plateN = IntArray(plates) { b.u16(p + 8 * it + 4) }; plateC = IntArray(plates) { b.u16(p + 8 * it + 6) }; p += 8 * plates
        stripLower = IntArray(strips) { b.s32(p + 8 * it) }; stripCount = IntArray(strips) { b.u16(p + 8 * it + 4) }
        stripOffset = IntArray(strips) { b.u16(p + 8 * it + 6) }; p += 8 * strips
        access = IntArray(accessCount) { b.u16(p + 2 * it) }
    }

    /** Bdhc_FindStrip, as written (its bounds walk is not a textbook binary search). */
    private fun findStrip(z: Int): Int {
        var count = stripLower.size
        if (count == 0) return -1
        if (count == 1) return 0
        count--
        var lo = 0
        var mid = count / 2
        while (true) {
            if (stripLower[mid] > z) {
                count--
                if (count > lo) { count = mid; mid = (lo + count) / 2 } else return mid
            } else {
                lo++
                if (lo < count) { lo = mid; mid = (lo + count) / 2 } else return mid + 1
            }
        }
    }

    /**
     * Bdhc_GetHeight in mode 0 (field objects): the plate height under (x, z) nearest [currentY], or null.
     * All values fx32, chunk-relative.
     */
    fun height(currentY: Int, x: Int, z: Int): Int? {
        val s = findStrip(z)
        if (s < 0 || s >= stripCount.size) return null
        var best: Int? = null
        for (k in 0 until stripCount[s]) {
            val plate = access[stripOffset[s] + k]
            val ax = px[plateA[plate]]; val az = pz[plateA[plate]]
            val bx = px[plateB[plate]]; val bz = pz[plateB[plate]]
            if (x < minOf(ax, bx) || x > maxOf(ax, bx) || z < minOf(az, bz) || z > maxOf(az, bz)) continue
            val n = plateN[plate]
            val y = fxDiv(-(constants[plateC[plate]] + fxMul(nx[n], x) + fxMul(nz[n], z)), ny[n])
            if (best == null || abs(y - currentY) < abs(best - currentY)) best = y
        }
        return best
    }

    private fun fxMul(a: Int, b: Int): Int = ((a.toLong() * b + 0x800) shr 12).toInt()
    private fun fxDiv(a: Int, b: Int): Int = if (b == 0) 0 else ((a.toLong() shl 12) / b).toInt()
}
