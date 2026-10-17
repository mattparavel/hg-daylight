package dev.hgdaylight.field

import dev.hgdaylight.g3d.Bmd
import dev.hgdaylight.g3d.Mat4
import dev.hgdaylight.g3d.NnsFile
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.render.FieldMesh
import dev.hgdaylight.rom.NdsRom

/**
 * One map matrix with one area's textures and lights, loaded chunk by chunk (32x32 tiles of 16 units,
 * `CalcMapPosition` in `src/field/map_load_manager.c`). Thread-safe for building chunks off the render thread.
 */
class World(val rom: NdsRom, val matrixId: Int, areaId: Int, timeOfDaySeconds: Int = 43200) {
    val matrix = MapMatrix(rom.narc(Narcs.MAP_MATRIX)[matrixId])
    val area = AreaData(rom.narc(Narcs.AREA_DATA)[areaId])
    private val mapTex = Tex0(NnsFile(rom.narc(Narcs.MAP_TEX)[area.mapTexMember]).block("TEX0")!!)
    private val propTex: Tex0? = NnsFile(rom.narc(Narcs.MAP_PROP_TEX)[area.mapPropMember]).block("TEX0")?.let { Tex0(it) }
    private val landNarc = rom.narc(Narcs.LAND_DATA)
    private val propNarc = rom.narc(if (area.buildModelKind != 0) Narcs.BUILD_MODEL_FIELD else Narcs.BUILD_MODEL_ROOM)
    private val propModels = HashMap<Int, Bmd?>()
    private val lands = HashMap<Int, LandData?>()

    /** lightKind -> light file, AreaDataManager_GetAreaLightArchiveID. */
    val light: AreaLight.Entry = AreaLight.load(rom, when (area.lightKind) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 0 }).at(timeOfDaySeconds)

    fun inside(cx: Int, cz: Int) = cx in 0 until matrix.width && cz in 0 until matrix.height

    @Synchronized
    fun land(cx: Int, cz: Int): LandData? {
        if (!inside(cx, cz)) return null
        val idx = cz * matrix.width + cx
        return lands.getOrPut(idx) {
            val modelNo = matrix.models[idx]
            if (modelNo == 0xFFFF || modelNo >= landNarc.size) null else runCatching { LandData(landNarc[modelNo]) }.getOrNull()
        }
    }

    fun altitude(cx: Int, cz: Int): Float = if (inside(cx, cz)) matrix.altitudes[cz * matrix.width + cx] * 8f else 0f

    /** The chunk's ground model and its props, placed in world space. Instance ids are unique per world. */
    fun chunk(cx: Int, cz: Int): List<Placed> {
        val ld = land(cx, cz) ?: return emptyList()
        val idx = cz * matrix.width + cx
        val ox = cx * 512f + 256f; val oz = cz * 512f + 256f
        val out = ArrayList<Placed>()
        var local = 1
        out += Placed(ld.model, listOfNotNull(ld.model.tex, mapTex), Mat4.translate(ox, altitude(cx, cz), oz), idx * 512 + local++, Placed.Kind.GROUND)
        for (pp in ld.props) {
            val bmd = synchronized(propModels) {
                propModels.getOrPut(pp.buildModel) {
                    if (pp.buildModel < propNarc.size) runCatching { Bmd(NnsFile(propNarc[pp.buildModel])) }.getOrNull() else null
                }
            } ?: continue
            // MapPropManager_DrawWithOffset: identity rotation; x and z offset by the chunk.
            var m = Mat4.translate(ox + pp.x, pp.y, oz + pp.z)
            m = Mat4.mul(m, Mat4.scale(pp.sx.takeIf { it != 0f } ?: 1f, pp.sy.takeIf { it != 0f } ?: 1f, pp.sz.takeIf { it != 0f } ?: 1f))
            out += Placed(bmd, listOfNotNull(bmd.tex, propTex, mapTex), m, idx * 512 + local++, Placed.Kind.PROP)
        }
        return out
    }

    fun chunkMesh(cx: Int, cz: Int): FieldMesh = FieldMesh.build(chunk(cx, cz), light)

    /** The matrix cell holding map header [header] (e.g. 60, MAP_NEW_BARK). */
    fun cellOf(header: Int): Pair<Int, Int>? {
        val i = matrix.headers?.indexOfFirst { it == header } ?: return null
        return if (i < 0) null else (i % matrix.width) to (i / matrix.width)
    }
}

/**
 * Tile-level queries over a [World] for movement (`src/field_terrain.c`): tile (tx, tz) is in chunk
 * (tx / 32, tz / 32); heights come from the chunk's BDHC relative to its centre, without the altitude.
 */
fun World.blocked(tx: Int, tz: Int): Boolean {
    val ld = land(Math.floorDiv(tx, 32), Math.floorDiv(tz, 32)) ?: return true
    return ld.blocked(Math.floorMod(tx, 32), Math.floorMod(tz, 32))
}

fun World.behavior(tx: Int, tz: Int): Int {
    val ld = land(Math.floorDiv(tx, 32), Math.floorDiv(tz, 32)) ?: return 0xFF
    return ld.behavior(Math.floorMod(tx, 32), Math.floorMod(tz, 32))
}

/** Terrain_CalcHeight at world (x, z), nearest [currentY]; units, not fx32. */
fun World.height(x: Float, z: Float, currentY: Float): Float? {
    val cx = Math.floorDiv(x.toInt(), 512); val cz = Math.floorDiv(z.toInt(), 512)
    val b = land(cx, cz)?.bdhc ?: return null
    val lx = ((x - (cx * 512 + 256)) * 4096).toInt(); val lz = ((z - (cz * 512 + 256)) * 4096).toInt()
    return b.height((currentY * 4096).toInt(), lx, lz)?.let { it / 4096f }
}
