package dev.hgdaylight.field

import dev.hgdaylight.g3d.Bmd
import dev.hgdaylight.g3d.Mat4
import dev.hgdaylight.g3d.NnsFile
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.NdsRom
import dev.hgdaylight.rom.fx

/** NARC paths, from `include/filesystem_files_def.h`. */
object Narcs {
    const val MAP_MATRIX = "a/0/4/1"
    const val LAND_DATA = "a/0/6/5"
    const val AREA_DATA = "a/0/4/2"
    const val MAP_TEX = "a/0/4/4"
    const val BUILD_MODEL_FIELD = "a/0/4/0"
    const val BUILD_MODEL_ROOM = "a/1/4/8"
    const val MAP_PROP_TEX = "a/0/7/0"
}

/** `src/map_matrix.c` MapMatrix_MapMatrixData_Load. */
class MapMatrix(b: Bytes) {
    val width = b.u8(0)
    val height = b.u8(1)
    val name: String
    val headers: IntArray?
    val altitudes: IntArray
    val models: IntArray

    init {
        val hasHeaders = b.u8(2) != 0
        val hasAltitudes = b.u8(3) != 0
        val nameLen = b.u8(4)
        name = b.ascii(5, nameLen)
        var p = 5 + nameLen
        val n = width * height
        headers = if (hasHeaders) IntArray(n) { b.u16(p + 2 * it) }.also { p += 2 * n } else null
        altitudes = if (hasAltitudes) IntArray(n) { b.u8(p + it) }.also { p += n } else IntArray(n)
        models = IntArray(n) { b.u16(p + 2 * it) }
    }
}

/** One prop record (MapPropArcData, 0x30 bytes, `src/field/map_prop_manager.c`). */
class PropPlacement(val buildModel: Int, val x: Float, val y: Float, val z: Float, val rx: Float, val ry: Float, val rz: Float, val sx: Float, val sy: Float, val sz: Float)

/** A land_data member: header sizes, then sound plates, permissions, props, the BMD0 model, BDHC. */
class LandData(b: Bytes) {
    val permissions: Bytes
    val props: List<PropPlacement>
    val model: Bmd

    init {
        val permSize = b.u32(0); val propsSize = b.u32(4); val modelSize = b.u32(8)
        var p = 16
        val soundplates = b.u32(p) ushr 16
        p += 4 + soundplates
        permissions = b.at(p, permSize); p += permSize
        props = List(propsSize / 0x30) {
            val q = p + 0x30 * it
            PropPlacement(
                b.u32(q),
                fx(b.s32(q + 4)), fx(b.s32(q + 8)), fx(b.s32(q + 12)),
                fx(b.s32(q + 16)), fx(b.s32(q + 20)), fx(b.s32(q + 24)),
                fx(b.s32(q + 28)), fx(b.s32(q + 32)), fx(b.s32(q + 36)),
            )
        }
        p += propsSize
        model = Bmd(NnsFile(b.at(p, modelSize)))
    }

    /** Permission word for tile (x, z): low byte = behaviour, bit 15 = blocked (`src/field/map_load_manager.c`). */
    fun permission(x: Int, z: Int): Int = permissions.u16(2 * (z * 32 + x))
}

/** AreaDataHeader (`src/field/area_data.c`). */
class AreaData(b: Bytes) {
    val mapPropMember = b.u16(0)
    val mapTexMember = b.u16(2)
    val buildModelKind = b.u8(6)
    val lightKind = b.u8(7)
}

/**
 * A model placed in the world: the instance id is what the outline pass compares, so each prop and each map
 * chunk get their own.
 */
class Placed(val bmd: Bmd, val textures: List<Tex0>, val world: FloatArray, val instance: Int, val kind: Kind) {
    enum class Kind { GROUND, PROP }
}

/** The models of the 3x3 chunks around a matrix cell, placed as `CalcMapPosition` places them. */
class FieldScene(rom: NdsRom, matrixId: Int, val areaId: Int, val centerX: Int, val centerZ: Int, radius: Int = 1) {
    val matrix = MapMatrix(rom.narc(Narcs.MAP_MATRIX)[matrixId])
    val area = AreaData(rom.narc(Narcs.AREA_DATA)[areaId])
    val mapTex = Tex0(NnsFile(rom.narc(Narcs.MAP_TEX)[area.mapTexMember]).block("TEX0")!!)
    val propTex: Tex0? = NnsFile(rom.narc(Narcs.MAP_PROP_TEX)[area.mapPropMember]).block("TEX0")?.let { Tex0(it) }
    val placed = ArrayList<Placed>()
    val lands = HashMap<Int, LandData>()

    init {
        val land = rom.narc(Narcs.LAND_DATA)
        val props = rom.narc(if (area.buildModelKind != 0) Narcs.BUILD_MODEL_FIELD else Narcs.BUILD_MODEL_ROOM)
        val propModels = HashMap<Int, Bmd?>()
        var instance = 1
        for (dz in -radius..radius) for (dx in -radius..radius) {
            val cx = centerX + dx; val cz = centerZ + dz
            if (cx !in 0 until matrix.width || cz !in 0 until matrix.height) continue
            val idx = cz * matrix.width + cx
            val modelNo = matrix.models[idx]
            if (modelNo == 0xFFFF || modelNo >= land.size) continue
            val ld = LandData(land[modelNo])
            lands[idx] = ld
            val ox = cx * 512f + 256f; val oz = cz * 512f + 256f
            val oy = matrix.altitudes[idx] * 8f
            placed += Placed(ld.model, listOfNotNull(ld.model.tex, mapTex), Mat4.translate(ox, oy, oz), instance++, Placed.Kind.GROUND)
            for (pp in ld.props) {
                val bmd = propModels.getOrPut(pp.buildModel) {
                    if (pp.buildModel < props.size) runCatching { Bmd(NnsFile(props[pp.buildModel])) }.getOrNull() else null
                } ?: continue
                // MapPropManager_DrawWithOffset draws with an identity rotation, whatever the record says,
                // and offsets only x and z by the chunk.
                var m = Mat4.translate(ox + pp.x, pp.y, oz + pp.z)
                m = Mat4.mul(m, Mat4.scale(pp.sx.takeIf { it != 0f } ?: 1f, pp.sy.takeIf { it != 0f } ?: 1f, pp.sz.takeIf { it != 0f } ?: 1f))
                placed += Placed(bmd, listOfNotNull(bmd.tex, propTex, mapTex), m, instance++, Placed.Kind.PROP)
            }
        }
    }
}
