package dev.hgdaylight.g3d

import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.fx

/** One material (NNSG3dResMatData) with the texture and palette names its dictionaries bind to it. */
class Material(
    val name: String,
    val diffAmb: Int,
    val specEmi: Int,
    val polyAttr: Int,
    val texImageParam: Int,
    val flag: Int,
    val texName: String?,
    val plttName: String?,
    val texScaleS: Float,
    val texScaleT: Float,
    val texTransS: Float,
    val texTransT: Float,
) {
    val alpha: Int get() = (polyAttr ushr 16) and 31
    val polygonId: Int get() = (polyAttr ushr 24) and 63
    val lights: Int get() = polyAttr and 15
    val showBack: Boolean get() = polyAttr and 0x40 != 0
    val showFront: Boolean get() = polyAttr and 0x80 != 0
    val diffuse: Int get() = diffAmb and 0x7FFF
    val ambient: Int get() = (diffAmb ushr 16) and 0x7FFF
    val emission: Int get() = (specEmi ushr 16) and 0x7FFF
    val vtxColorIsDiffuse: Boolean get() = diffAmb and 0x8000 != 0
}

/** One shape: a GX display list. */
class Shape(val name: String, val dl: Bytes)

/** A node's local transform as a 4x3 matrix (row-major 3x3 + translation). */
class NodeSrt(val m: FloatArray)

/**
 * The MDL0 model (NNSG3dResMdl): nodes, render commands (SBC), materials and shapes. [draws] is the SBC
 * walked once into (matrix, material, shape) triples, which is all a static map model needs.
 */
class Model(val name: String, b: Bytes) {
    val posScale: Float
    val nodes: List<NodeSrt>
    val materials: List<Material>
    val shapes: List<Shape>
    val draws = ArrayList<Draw>()

    class Draw(val matrix: FloatArray, val material: Int, val shape: Int)

    init {
        val ofsSbc = b.u32(4)
        val ofsMat = b.u32(8)
        val ofsShp = b.u32(12)
        val info = 0x14 // NNSG3dResMdlInfo, 0x2C bytes, then the node dictionary
        posScale = fx(b.s32(info + 8))
        val nodeBase = b.at(info + 0x2C)
        val nodeDict = Dict(nodeBase)
        nodes = List(nodeDict.count) { readNode(nodeBase.at(nodeDict.data(it).u32(0))) }

        val mat = b.at(ofsMat)
        val matDict = Dict(mat.at(4))
        val texFor = arrayOfNulls<String>(matDict.count)
        val plttFor = arrayOfNulls<String>(matDict.count)
        bindNames(mat, Dict(mat.at(mat.u16(0))), texFor)
        bindNames(mat, Dict(mat.at(mat.u16(2))), plttFor)
        materials = List(matDict.count) { readMaterial(matDict.name(it), mat.at(matDict.data(it).u32(0)), texFor[it], plttFor[it]) }

        val shp = b.at(ofsShp)
        val shpDict = Dict(shp)
        shapes = List(shpDict.count) {
            val s = shp.at(shpDict.data(it).u32(0))
            Shape(shpDict.name(it), s.at(s.u32(8), s.u32(12)))
        }

        runSbc(b.at(ofsSbc))
    }

    private fun bindNames(mat: Bytes, dict: Dict, out: Array<String?>) {
        for (i in 0 until dict.count) {
            val d = dict.data(i).u32(0)
            val off = d and 0xFFFF
            val num = (d ushr 16) and 0xFF
            for (k in 0 until num) {
                val m = mat.u8(off + k)
                if (m < out.size) out[m] = dict.name(i)
            }
        }
    }

    private fun readMaterial(name: String, m: Bytes, tex: String?, pltt: String?): Material {
        val flag = m.u16(0x1E)
        var p = 0x2C
        var ss = 1f; var st = 1f; var ts = 0f; var tt = 0f
        if (flag and 1 != 0) {
            if (flag and 2 == 0) { ss = fx(m.s32(p)); st = fx(m.s32(p + 4)); p += 8 }
            if (flag and 4 == 0) p += 4
            if (flag and 8 == 0) { ts = fx(m.s32(p)); tt = fx(m.s32(p + 4)); p += 8 }
        }
        return Material(name, m.u32(4), m.u32(8), m.u32(12), m.u32(20), flag, tex, pltt, ss, st, ts, tt)
    }

    private fun readNode(n: Bytes): NodeSrt {
        val flag = n.u16(0)
        val m00 = fx(n.s16(2))
        var p = 4
        var tx = 0f; var ty = 0f; var tz = 0f
        if (flag and 1 == 0) { tx = fx(n.s32(p)); ty = fx(n.s32(p + 4)); tz = fx(n.s32(p + 8)); p += 12 }
        var r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        if (flag and 2 == 0) {
            if (flag and 8 != 0) {
                r = pivot(flag, fx(n.s16(p)), fx(n.s16(p + 2)))
                p += 4
            } else {
                r = FloatArray(9)
                r[0] = m00
                for (i in 1 until 9) r[i] = fx(n.s16(p + 2 * (i - 1)))
                p += 16
            }
        }
        var sx = 1f; var sy = 1f; var sz = 1f
        if (flag and 4 == 0) { sx = fx(n.s32(p)); sy = fx(n.s32(p + 4)); sz = fx(n.s32(p + 8)) }
        // Row-major: rows of the 3x3 scaled per column, as G3 applies scale before rotation.
        val m = floatArrayOf(
            r[0] * sx, r[1] * sy, r[2] * sz, tx,
            r[3] * sx, r[4] * sy, r[5] * sz, ty,
            r[6] * sx, r[7] * sy, r[8] * sz, tz,
        )
        return NodeSrt(m)
    }

    /** The compressed rotation: one axis is +-1, A and B fill the 2x2 minor. */
    private fun pivot(flag: Int, a: Float, b: Float): FloatArray {
        val pv = (flag ushr 4) and 15
        val neg = if (flag and 0x100 != 0) -1f else 1f
        val c = if (flag and 0x200 != 0) -b else b
        val d = if (flag and 0x400 != 0) -a else a
        val r = FloatArray(9)
        r[pv] = neg
        val row = pv / 3; val col = pv % 3
        val rows = (0 until 3).filter { it != row }
        val cols = (0 until 3).filter { it != col }
        r[rows[0] * 3 + cols[0]] = a
        r[rows[0] * 3 + cols[1]] = b
        r[rows[1] * 3 + cols[0]] = c
        r[rows[1] * 3 + cols[1]] = d
        return r
    }

    private fun runSbc(s: Bytes) {
        val stack = arrayOfNulls<FloatArray>(32)
        var cur = Mat4.identity()
        val nodeMtx = arrayOfNulls<FloatArray>(nodes.size)
        var curMat = -1
        var p = 0
        loop@ while (p < s.size) {
            val op = s.u8(p)
            when (op and 0x1F) {
                0x00 -> p += 1
                0x01 -> break@loop
                0x02 -> p += 3
                0x03 -> { stack[s.u8(p + 1)]?.let { cur = it.copyOf() }; p += 2 }
                0x04 -> { curMat = s.u8(p + 1); p += 2 }
                0x05 -> {
                    if (curMat >= 0) draws += Draw(cur.copyOf(), curMat, s.u8(p + 1))
                    p += 2
                }
                0x06 -> {
                    val node = s.u8(p + 1)
                    // [node, parent, flag] then [dest] if 0x20 and [src] if 0x40
                    val dest = if (op and 0x20 != 0) s.u8(p + 4) else -1
                    val src = if (op and 0x40 != 0) s.u8(p + 4 + (if (dest >= 0) 1 else 0)) else -1
                    if (src >= 0) stack[src]?.let { cur = it.copyOf() }
                    cur = Mat4.mul(cur, Mat4.from43(nodes[node].m))
                    nodeMtx[node] = cur
                    if (dest >= 0) stack[dest] = cur.copyOf()
                    p += 4 + (if (dest >= 0) 1 else 0) + (if (src >= 0) 1 else 0)
                }
                0x07, 0x08 -> p += 2 + (if (op and 0x20 != 0) 1 else 0) + (if (op and 0x40 != 0) 1 else 0)
                0x09 -> p += 3 + 3 * s.u8(p + 2)
                0x0A -> p += 9
                0x0B -> p += 1
                0x0C, 0x0D -> p += 3
                else -> break@loop
            }
        }
    }
}

/** A model set (MDL0 block of a BMD0) and its optional embedded TEX0. */
class Bmd(file: NnsFile) {
    val models: List<Model>
    val tex: Tex0?

    init {
        val mdl = file.block("MDL0") ?: error("No MDL0")
        val dict = Dict(mdl.at(8))
        models = List(dict.count) { Model(dict.name(it), mdl.at(dict.data(it).u32(0))) }
        tex = file.block("TEX0")?.let { Tex0(it) }
    }
}
