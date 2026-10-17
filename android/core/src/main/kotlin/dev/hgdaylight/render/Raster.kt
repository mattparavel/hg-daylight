package dev.hgdaylight.render

import dev.hgdaylight.field.Placed
import dev.hgdaylight.g3d.Gx
import dev.hgdaylight.g3d.Material
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.g3d.Tri
import dev.hgdaylight.g3d.Vtx
import dev.hgdaylight.g3d.rgb555
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** The field camera (`src/field/field_camera.c` sFieldCameraPresets) looking at [target]. */
class Camera(
    val target: FloatArray,
    val distance: Float,
    /** CameraAngle.x in DS units (65536 = a turn); 0xDD62 for preset 0. */
    val angleX: Int,
    /** perspectiveAngle: half the vertical field of view, DS units. */
    val halfFovy: Int,
    val near: Float,
    val far: Float,
) {
    fun eye(): FloatArray {
        val a = -(angleX.toShort().toInt()) * (2 * Math.PI / 65536)
        return floatArrayOf(target[0], target[1] + (distance * Math.sin(a)).toFloat(), target[2] + (distance * Math.cos(a)).toFloat())
    }

    fun tanHalf(): Float = tan(halfFovy * (2 * Math.PI / 65536)).toFloat()

    companion object {
        fun preset0(target: FloatArray) = Camera(target, 666.922119140625f, 0xDD62, 0x5C1, 150f, 1200f)
    }
}

/**
 * A software rasterizer for DS-style geometry: nearest texels with the material's repeat/flip, modulate
 * shading, per-vertex DS lighting, alpha-cut texels, then translucent polygons back to front. Besides colour
 * it keeps depth and an object id per pixel, which [DaylightStyle] uses for the outlines.
 */
class Raster(val w: Int, val h: Int) {
    val color = IntArray(w * h)
    val depth = FloatArray(w * h)
    val id = IntArray(w * h)
    /** The material's polygon id (`polyAttr` bits 24-29), which the DS's own edge marking compares. */
    val polyId = IntArray(w * h)
    val kind = ByteArray(w * h)
    /** Which texture the pixel came from, so the style pass can find boundaries between ground materials. */
    val surface = IntArray(w * h)

    private lateinit var view: FloatArray
    private val right = FloatArray(3)
    private val up = FloatArray(3)
    private val spriteMat = Material("sprite", 0x7FFF, 0, (31 shl 16) or (63 shl 24) or 0xC0, 0, 0, null, null, 1f, 1f, 0f, 0f)
    private var focal = 1f
    private var near = 1f
    /** The area's light slot (`src/field/area_light.c`): lights plus the material colours every lit model uses. */
    var light: dev.hgdaylight.field.AreaLight.Entry? = null

    private val texCache = HashMap<String, Texture?>()
    private val deferred = ArrayList<Pending>()
    private class Pending(val p: Array<FloatArray>, val v: Array<Vtx>, val tex: Texture?, val mat: Material, val inst: Int, val kind: Int, val z: Float)

    fun clear(bg: Int) {
        color.fill(bg); depth.fill(Float.MAX_VALUE); id.fill(0); polyId.fill(-1); kind.fill(0); surface.fill(0)
        deferred.clear()
    }

    fun setCamera(cam: Camera) {
        val eye = cam.eye()
        val t = cam.target
        var fx = t[0] - eye[0]; var fy = t[1] - eye[1]; var fz = t[2] - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // right = forward x up(0,1,0); up' = right x forward
        var rx = -fz; var ry = 0f; var rz = fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        right[0] = rx; right[1] = ry; right[2] = rz
        up[0] = ux; up[1] = uy; up[2] = uz
        view = floatArrayOf(
            rx, ry, rz, -(rx * eye[0] + ry * eye[1] + rz * eye[2]),
            ux, uy, uz, -(ux * eye[0] + uy * eye[1] + uz * eye[2]),
            fx, fy, fz, -(fx * eye[0] + fy * eye[1] + fz * eye[2]),
        )
        // The DS's 192 rows span 2*tan(half fovy); keep that pixel density whatever the canvas height.
        focal = 192f / 2f / cam.tanHalf()
        near = cam.near
    }

    fun draw(p: Placed) {
        val tris = ArrayList<Tri>()
        for (model in p.bmd.models) {
            for (d in model.draws) {
                val mat = model.materials[d.material]
                if (mat.alpha == 0) continue // wireframe
                val tex = texture(mat, p.textures)
                tris.clear()
                Gx.run(model.shapes[d.shape].dl, dev.hgdaylight.g3d.Mat4.mul(p.world, d.matrix), model.posScale, mat, tex?.width ?: 1, tex?.height ?: 1, tris)
                for (t in tris) triangle(t, tex, mat, p.instance, p.kind.ordinal + 1)
            }
        }
    }

    /** A field object: a camera-facing [w] x [h] quad with its bottom centre at (x, y, z), as the DS's BB node. */
    fun billboard(tex: Texture, x: Float, y: Float, z: Float, w: Int, h: Int, instance: Int) {
        val hw = w / 2f
        fun v(cx: Float, cy: Float, s: Float, t: Float) = Vtx(
            x + right[0] * cx + up[0] * cy, y + right[1] * cx + up[1] * cy, z + right[2] * cx + up[2] * cy,
            s, t, 1f, 1f, 1f, 0f, 0f, 0f, false,
        )
        val a = v(-hw, 0f, 0f, tex.height.toFloat()); val b = v(hw, 0f, tex.width.toFloat(), tex.height.toFloat())
        val c = v(hw, h.toFloat(), tex.width.toFloat(), 0f); val d = v(-hw, h.toFloat(), 0f, 0f)
        for (t in listOf(Tri(a, b, c), Tri(a, c, d))) {
            val pa = project(t.a) ?: return; val pb = project(t.b) ?: return; val pc = project(t.c) ?: return
            fill(arrayOf(pa, pb, pc), arrayOf(t.a, t.b, t.c), tex, spriteMat, instance, 3, false)
        }
    }

    /** Draws the translucent polygons collected by [draw], farthest first. */
    fun flush() {
        deferred.sortByDescending { it.z }
        for (d in deferred) fill(d.p, d.v, d.tex, d.mat, d.inst, d.kind, true)
        deferred.clear()
    }

    private fun texture(mat: Material, files: List<Tex0>): Texture? {
        val name = mat.texName ?: return null
        val key = "${System.identityHashCode(files)}/$name/${mat.plttName}"
        return texCache.getOrPut(key) {
            for (f in files) {
                val ti = f.textureIndex(name)
                if (ti < 0) continue
                val fmt = (f.imageParam(ti) ushr 26) and 7
                val pi = mat.plttName?.let { n -> files.firstNotNullOfOrNull { g -> g.paletteIndex(n).takeIf { it >= 0 }?.let { g to it } } }
                if (fmt != 7 && pi == null) continue
                val src = pi?.first ?: f
                if (src !== f) {
                    // Palette in another file: decode with this file's texels and that palette.
                    return@getOrPut f.decodeWith(ti, src, pi!!.second)
                }
                return@getOrPut f.decode(ti, pi?.second ?: -1)
            }
            null
        }
    }

    private val lit = FloatArray(3)

    /**
     * DS per-vertex lighting: emission + sum over lights of (ambient + diffuse * max(0, -L.N)) * light colour.
     * Lit field models take their material colours from the area light (AreaLight_ClearMaterialModifyFlags,
     * the 0xF33 mask in MapPropManager_DrawWithOffset); specular is left out.
     */
    private fun shade(v: Vtx, mat: Material): FloatArray {
        val l = light
        if (mat.lights == 0 || !v.hasNormal || l == null) {
            lit[0] = v.r; lit[1] = v.g; lit[2] = v.b
            return lit
        }
        for (c in 0 until 3) lit[c] = l.emission[c]
        l.lights.forEachIndexed { k, li ->
            if (mat.lights and (1 shl k) == 0) return@forEachIndexed
            val nd = max(0f, -(li.dir[0] * v.nx + li.dir[1] * v.ny + li.dir[2] * v.nz))
            for (c in 0 until 3) lit[c] += (l.ambient[c] + l.diffuse[c] * nd) * li.color[c]
        }
        for (c in 0 until 3) lit[c] = min(1f, lit[c])
        return lit
    }

    private fun project(v: Vtx): FloatArray? {
        val x = view[0] * v.x + view[1] * v.y + view[2] * v.z + view[3]
        val y = view[4] * v.x + view[5] * v.y + view[6] * v.z + view[7]
        val z = view[8] * v.x + view[9] * v.y + view[10] * v.z + view[11]
        if (z < near * 0.5f) return null
        return floatArrayOf(w / 2f + x * focal / z, h / 2f - y * focal / z, z)
    }

    private fun triangle(t: Tri, tex: Texture?, mat: Material, inst: Int, kind: Int) {
        val pa = project(t.a) ?: return
        val pb = project(t.b) ?: return
        val pc = project(t.c) ?: return
        val area = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pc[0] - pa[0]) * (pb[1] - pa[1])
        if (area == 0f) return
        // DS: front faces are counter-clockwise in model space; with y down on screen that is area < 0 here.
        val front = area < 0
        if (front && !mat.showFront) return
        if (!front && !mat.showBack) return
        val verts = arrayOf(t.a, t.b, t.c)
        val shaded = Array(3) { shade(verts[it], mat).copyOf() }
        val vv = Array(3) { Vtx(verts[it].x, verts[it].y, verts[it].z, verts[it].s, verts[it].t, shaded[it][0], shaded[it][1], shaded[it][2], 0f, 0f, 0f, false) }
        val ps = arrayOf(pa, pb, pc)
        if (mat.alpha < 31 || tex?.translucent == true) {
            deferred += Pending(ps, vv, tex, mat, inst, kind, (pa[2] + pb[2] + pc[2]) / 3)
        } else {
            fill(ps, vv, tex, mat, inst, kind, false)
        }
    }

    private fun fill(p: Array<FloatArray>, v: Array<Vtx>, tex: Texture?, mat: Material, inst: Int, k: Int, blend: Boolean) {
        val (pa, pb, pc) = p
        val area = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pc[0] - pa[0]) * (pb[1] - pa[1])
        val x0 = max(0, floor(min(pa[0], min(pb[0], pc[0]))).toInt())
        val x1 = min(w - 1, ceil(max(pa[0], max(pb[0], pc[0]))).toInt())
        val y0 = max(0, floor(min(pa[1], min(pb[1], pc[1]))).toInt())
        val y1 = min(h - 1, ceil(max(pa[1], max(pb[1], pc[1]))).toInt())
        if (x0 > x1 || y0 > y1) return
        val iw = FloatArray(3) { 1f / p[it][2] }
        val tp = mat.texImageParam
        val repS = tp and (1 shl 16) != 0; val repT = tp and (1 shl 17) != 0
        val flipS = tp and (1 shl 18) != 0; val flipT = tp and (1 shl 19) != 0
        val polyAlpha = mat.alpha / 31f
        for (y in y0..y1) {
            val sy = y + 0.5f
            for (x in x0..x1) {
                val sx = x + 0.5f
                var w0 = ((pb[0] - sx) * (pc[1] - sy) - (pc[0] - sx) * (pb[1] - sy)) / area
                var w1 = ((pc[0] - sx) * (pa[1] - sy) - (pa[0] - sx) * (pc[1] - sy)) / area
                var w2 = 1f - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val q0 = w0 * iw[0]; val q1 = w1 * iw[1]; val q2 = w2 * iw[2]
                val qs = q0 + q1 + q2
                val z = 1f / qs
                val i = y * w + x
                if (z >= depth[i]) continue
                w0 = q0 / qs; w1 = q1 / qs; w2 = q2 / qs
                var r = v[0].r * w0 + v[1].r * w1 + v[2].r * w2
                var g = v[0].g * w0 + v[1].g * w1 + v[2].g * w2
                var b = v[0].b * w0 + v[1].b * w1 + v[2].b * w2
                var a = polyAlpha
                if (tex != null) {
                    val s = v[0].s * w0 + v[1].s * w1 + v[2].s * w2
                    val t = v[0].t * w0 + v[1].t * w1 + v[2].t * w2
                    val tx = wrap(floor(s).toInt(), tex.width, repS, flipS)
                    val ty = wrap(floor(t).toInt(), tex.height, repT, flipT)
                    val c = tex.argb[ty * tex.width + tx]
                    val ta = (c ushr 24) / 255f
                    if (ta == 0f) continue
                    r *= ((c shr 16) and 255) / 255f; g *= ((c shr 8) and 255) / 255f; b *= (c and 255) / 255f
                    a *= ta
                }
                if (blend && a < 1f) {
                    val o = color[i]
                    r = r * a + ((o shr 16) and 255) / 255f * (1 - a)
                    g = g * a + ((o shr 8) and 255) / 255f * (1 - a)
                    b = b * a + (o and 255) / 255f * (1 - a)
                    if (a < 0.5f) { color[i] = pack(r, g, b); continue } // faint layers keep the depth and id below
                }
                color[i] = pack(r, g, b)
                depth[i] = z
                id[i] = inst
                polyId[i] = mat.polygonId
                kind[i] = k.toByte()
                surface[i] = if (tex != null) System.identityHashCode(tex) else mat.name.hashCode()
            }
        }
    }

    private fun wrap(v: Int, n: Int, repeat: Boolean, flip: Boolean): Int {
        if (!repeat) return v.coerceIn(0, n - 1)
        if (!flip) return Math.floorMod(v, n)
        val m = Math.floorMod(v, 2 * n)
        return if (m < n) m else 2 * n - 1 - m
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
}
