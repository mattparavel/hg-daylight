package dev.hgdaylight.tools

import dev.hgdaylight.field.FieldScene
import dev.hgdaylight.field.MapMatrix
import dev.hgdaylight.field.Narcs
import dev.hgdaylight.render.Camera
import dev.hgdaylight.render.DaylightStyle
import dev.hgdaylight.render.Raster
import dev.hgdaylight.rom.NdsRom
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a field view from the ROM to PNGs in out/: the original colours at DS size, and the Daylight
 * gray with outlines on the portrait canvas, at 1x and at the DC-1's 4x.
 *
 *   ./gradlew :core:renderMap -Pargs="--header 60 --tile 16,16"
 */
fun main(args: Array<String>) {
    val opt = HashMap<String, String>()
    for (i in args.indices step 2) opt[args[i].removePrefix("--")] = args.getOrElse(i + 1) { "" }
    val romFile = File(opt["rom"] ?: "rom/soulsilver.us.nds")
    val rom = NdsRom.load(romFile)
    println("${rom.title} ${rom.gameCode}, ${rom.fileCount} files")

    val matrixId = opt["matrix"]?.toInt() ?: 0
    val header = opt["header"]?.toInt() ?: 60 // MAP_NEW_BARK
    val area = opt["area"]?.toInt() ?: 2
    val matrix = MapMatrix(rom.narc(Narcs.MAP_MATRIX)[matrixId])
    val cell = matrix.headers?.indexOfFirst { it == header }?.takeIf { it >= 0 } ?: error("Header $header not in matrix $matrixId")
    val cx = cell % matrix.width; val cz = cell / matrix.width
    val (tx, tz) = (opt["tile"] ?: "16,16").split(",").map { it.toInt() }
    println("matrix ${matrix.name} ${matrix.width}x${matrix.height}, header $header at cell ($cx, $cz)")

    val scene = FieldScene(rom, matrixId, area, cx, cz)
    println("placed ${scene.placed.size} models")
    if (opt.containsKey("debug")) for (p in scene.placed.take(12)) {
        for (m in p.bmd.models) {
            val tris = ArrayList<dev.hgdaylight.g3d.Tri>()
            for (d in m.draws) dev.hgdaylight.g3d.Gx.run(m.shapes[d.shape].dl, dev.hgdaylight.g3d.Mat4.mul(p.world, d.matrix), m.posScale, m.materials[d.material], 1, 1, tris)
            val xs = tris.flatMap { listOf(it.a.x, it.b.x, it.c.x) }; val ys = tris.flatMap { listOf(it.a.y, it.b.y, it.c.y) }; val zs = tris.flatMap { listOf(it.a.z, it.b.z, it.c.z) }
            println("${p.kind} ${m.name} nodes=${m.nodes.size} mats=${m.materials.size} shps=${m.shapes.size} draws=${m.draws.size} tris=${tris.size} posScale=${m.posScale} " +
                "x=${xs.minOrNull()}..${xs.maxOrNull()} y=${ys.minOrNull()}..${ys.maxOrNull()} z=${zs.minOrNull()}..${zs.maxOrNull()}")
            println("   mats: " + m.materials.take(4).joinToString { "${it.name}:${it.texName}/${it.plttName} a=${it.alpha} L=${it.lights} f=${it.showFront} b=${it.showBack}" })
        }
    }

    // The player stands on tile (tx, tz) of the chunk; tile centres are 16 units apart.
    val target = floatArrayOf(cx * 512f + tx * 16f + 8f, matrix.altitudes[cell] * 8f, cz * 512f + tz * 16f + 8f)
    val outDir = File(opt["out"] ?: "out").apply { mkdirs() }
    val tag = opt["tag"] ?: "map$header"

    // lightKind -> light file (AreaDataManager_GetAreaLightArchiveID); noon unless --time is given (seconds).
    val lightFile = when (scene.area.lightKind) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 0 }
    val light = dev.hgdaylight.field.AreaLight.load(rom, lightFile).at(opt["time"]?.toInt() ?: 43200)

    fun render(w: Int, h: Int): Raster {
        val r = Raster(w, h)
        r.clear(0xFF000000.toInt())
        r.setCamera(Camera.preset0(target))
        r.light = light
        for (p in scene.placed) r.draw(p)
        r.flush()
        return r
    }

    val ds = render(256, 192)
    write(ds.w, ds.h, ds.color, 1, File(outDir, "${tag}_ds_color.png"))

    // The DC-1 app window is 1184x1584: a 296x396 canvas at 4x.
    val canvas = render(296, 396)
    write(canvas.w, canvas.h, canvas.color, 4, File(outDir, "${tag}_portrait_color_4x.png"))
    val gray = DaylightStyle.apply(canvas)
    val g = IntArray(gray.size) { 0xFF000000.toInt() or (gray[it] * 0x010101) }
    write(canvas.w, canvas.h, g, 1, File(outDir, "${tag}_portrait_daylight.png"))
    write(canvas.w, canvas.h, g, 4, File(outDir, "${tag}_portrait_daylight_4x.png"))
    if (opt.containsKey("debug")) {
        val ids = IntArray(canvas.id.size) { val v = canvas.id[it] * 2654435761L.toInt(); 0xFF000000.toInt() or (v ushr 8) }
        write(canvas.w, canvas.h, ids, 2, File(outDir, "${tag}_ids.png"))
        val pids = IntArray(canvas.id.size) { val v = (canvas.polyId[it] + 1) * 0x9E3779B1.toInt(); 0xFF000000.toInt() or (v ushr 8) }
        write(canvas.w, canvas.h, pids, 2, File(outDir, "${tag}_polyids.png"))
    }
    println("wrote ${outDir.path}/${tag}_*")
}

fun write(w: Int, h: Int, argb: IntArray, scale: Int, f: File) {
    val img = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h * scale) for (x in 0 until w * scale) img.setRGB(x, y, argb[(y / scale) * w + x / scale])
    ImageIO.write(img, "png", f)
}
