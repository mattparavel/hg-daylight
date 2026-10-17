package dev.hgdaylight.render

import dev.hgdaylight.field.AreaLight
import dev.hgdaylight.field.Placed
import dev.hgdaylight.g3d.Gx
import dev.hgdaylight.g3d.Mat4
import dev.hgdaylight.g3d.Material
import dev.hgdaylight.g3d.Tex0
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.g3d.Tri
import dev.hgdaylight.g3d.Vtx
import kotlin.math.max
import kotlin.math.min

/**
 * The field as GPU-ready batches: one per (placed model, material, shape), with DS lighting baked into the
 * vertex colours (the area light does not change while a scene is shown). Vertices are
 * [x, y, z, u, v, r, g, b] with u, v already divided by the texture size.
 */
class FieldMesh(val batches: List<Batch>) {
    class Batch(
        val vertices: FloatArray,
        val texture: Texture?,
        val repeatS: Boolean, val repeatT: Boolean, val flipS: Boolean, val flipT: Boolean,
        /** Polygon alpha 0..1 (material alpha / 31). */
        val alpha: Float,
        val translucent: Boolean,
        val showFront: Boolean, val showBack: Boolean,
        /** Outline ids: the placed model's instance and the material's polygon id. */
        val instance: Int, val polygonId: Int,
        val ground: Boolean,
    ) {
        val vertexCount: Int get() = vertices.size / FLOATS
    }

    companion object {
        const val FLOATS = 8

        fun build(placed: List<Placed>, light: AreaLight.Entry?): FieldMesh {
            val texCache = HashMap<String, Texture?>()
            val out = ArrayList<Batch>()
            val tris = ArrayList<Tri>()
            for (p in placed) for (model in p.bmd.models) for (d in model.draws) {
                val mat = model.materials[d.material]
                if (mat.alpha == 0) continue
                val tex = texture(mat, p.textures, texCache)
                tris.clear()
                Gx.run(model.shapes[d.shape].dl, Mat4.mul(p.world, d.matrix), model.posScale, mat, tex?.width ?: 1, tex?.height ?: 1, tris)
                if (tris.isEmpty()) continue
                val v = FloatArray(tris.size * 3 * FLOATS)
                var k = 0
                val tw = (tex?.width ?: 1).toFloat(); val th = (tex?.height ?: 1).toFloat()
                for (t in tris) for (x in arrayOf(t.a, t.b, t.c)) {
                    val c = Lighting.shade(x, mat, light)
                    v[k++] = x.x; v[k++] = x.y; v[k++] = x.z
                    v[k++] = x.s / tw; v[k++] = x.t / th
                    v[k++] = c[0]; v[k++] = c[1]; v[k++] = c[2]
                }
                val tp = mat.texImageParam
                out += Batch(
                    v, tex,
                    tp and (1 shl 16) != 0, tp and (1 shl 17) != 0, tp and (1 shl 18) != 0, tp and (1 shl 19) != 0,
                    mat.alpha / 31f, mat.alpha < 31 || tex?.translucent == true,
                    mat.showFront, mat.showBack,
                    p.instance, mat.polygonId, p.kind == Placed.Kind.GROUND,
                )
            }
            return FieldMesh(out)
        }

        /** The material's texture by name, with its palette from whichever file has it. */
        fun texture(mat: Material, files: List<Tex0>, cache: MutableMap<String, Texture?>): Texture? {
            val name = mat.texName ?: return null
            val key = "${files.joinToString(",") { System.identityHashCode(it).toString() }}/$name/${mat.plttName}"
            return cache.getOrPut(key) {
                for (f in files) {
                    val ti = f.textureIndex(name)
                    if (ti < 0) continue
                    val fmt = (f.imageParam(ti) ushr 26) and 7
                    if (fmt == 7) return@getOrPut f.decode(ti, -1)
                    val pn = mat.plttName ?: continue
                    val own = f.paletteIndex(pn)
                    if (own >= 0) return@getOrPut f.decode(ti, own)
                    for (g in files) {
                        val pi = g.paletteIndex(pn)
                        if (pi >= 0) return@getOrPut f.decodeWith(ti, g, pi)
                    }
                }
                null
            }
        }
    }
}

/**
 * DS per-vertex lighting: emission + sum over lights of (ambient + diffuse * max(0, -L.N)) * light colour.
 * Lit field models take their material colours from the area light (AreaLight_ClearMaterialModifyFlags,
 * the 0xF33 mask in MapPropManager_DrawWithOffset); specular is left out.
 */
object Lighting {
    private val out = FloatArray(3)

    fun shade(v: Vtx, mat: Material, l: AreaLight.Entry?): FloatArray {
        if (mat.lights == 0 || !v.hasNormal || l == null) {
            out[0] = v.r; out[1] = v.g; out[2] = v.b
            return out
        }
        for (c in 0 until 3) out[c] = l.emission[c]
        l.lights.forEachIndexed { k, li ->
            if (mat.lights and (1 shl k) == 0) return@forEachIndexed
            val nd = max(0f, -(li.dir[0] * v.nx + li.dir[1] * v.ny + li.dir[2] * v.nz))
            for (c in 0 until 3) out[c] += (l.ambient[c] + l.diffuse[c] * nd) * li.color[c]
        }
        for (c in 0 until 3) out[c] = min(1f, out[c])
        return out
    }
}
