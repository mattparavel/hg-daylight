package dev.hgdaylight.app

import android.opengl.GLES30.*
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.util.Log
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.render.DaylightStyle
import dev.hgdaylight.render.FieldMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the field: the 3D scene into a canvas-sized framebuffer (colour, ids, depth), then the Daylight
 * style pass to the screen at a whole-number scale. Chunk meshes are built on a worker thread and uploaded
 * here when ready.
 */
class FieldRenderer(private val game: FieldGame) : GLSurfaceView.Renderer {
    /** The canvas pixel size in device pixels (4 on the DC-1: 296x396 in its 1184x1584 window). */
    var scale = 4

    private var viewW = 0
    private var viewH = 0
    private var canvasW = 0
    private var canvasH = 0
    private var offX = 0f
    private var offY = 0f

    private var sceneProg = 0
    private var grayProg = 0
    private var linesProg = 0
    private var deviceProg = 0
    private var grayTex = 0
    private var grayFbo = 0
    private var styledTex = 0
    private var styledFbo = 0
    private var lutTex = 0
    private var fbo = 0
    private var colorTex = 0
    private var idTex = 0
    private var depthTex = 0
    private var quadVbo = 0
    private var spriteVbo = 0

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chunks").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val pending = ConcurrentHashMap<Long, FieldMesh>()
    private val requested = HashSet<Long>()
    private val chunks = HashMap<Long, GpuChunk>()
    private val textures = HashMap<Texture, Int>()

    private class GpuBatch(val b: FieldMesh.Batch, val first: Int, val count: Int)
    private class GpuChunk(val vbo: Int, val batches: List<GpuBatch>)

    private var lastNanos = 0L
    private var accum = 0.0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        sceneProg = program(Shaders.SCENE_VS, Shaders.SCENE_FS)
        grayProg = program(Shaders.QUAD_VS, Shaders.GRAY_FS)
        linesProg = program(Shaders.QUAD_VS, Shaders.LINES_FS)
        deviceProg = program(Shaders.QUAD_VS, Shaders.DEVICE_FS)
        lutTex = makeLut()
        val ids = IntArray(2)
        glGenBuffers(2, ids, 0)
        quadVbo = ids[0]; spriteVbo = ids[1]
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, floats(quad), GL_STATIC_DRAW)
        // A fresh context has lost every GL object.
        chunks.clear(); textures.clear(); requested.clear(); pending.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        canvasW = width / scale; canvasH = height / scale
        offX = (width - canvasW * scale) / 2f
        offY = (height - canvasH * scale) / 2f
        makeFramebuffer()
        Log.i(TAG, "view ${width}x$height, canvas ${canvasW}x$canvasH at ${scale}x")
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos != 0L) accum += (now - lastNanos) / 1e9
        lastNanos = now
        var steps = 0
        while (accum >= TICK && steps < 4) { game.tick(); accum -= TICK; steps++ }
        if (steps == 4) accum = 0.0

        streamChunks()
        drawScene()
        drawStyle()

        fpsFrames++
        if (now - fpsStart > 5_000_000_000L) {
            Log.i(TAG, "%.1f fps, %d chunks".format(fpsFrames * 1e9 / (now - fpsStart), chunks.size))
            fpsStart = now; fpsFrames = 0
        }
    }

    private var fpsStart = System.nanoTime()
    private var fpsFrames = 0

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    /** Keeps the 3x3 chunks around the player built and uploaded, and frees the rest. */
    private fun streamChunks() {
        val (pcx, pcz) = game.chunkOfPlayer()
        val want = HashSet<Long>()
        for (dz in -1..1) for (dx in -1..1) {
            val cx = pcx + dx; val cz = pcz + dz
            if (!game.world.inside(cx, cz)) continue
            val k = key(cx, cz)
            want += k
            if (k !in chunks && requested.add(k)) worker.execute {
                runCatching { pending[k] = game.world.chunkMesh(cx, cz) }.onFailure { Log.e(TAG, "chunk $cx,$cz", it) }
            }
        }
        for (k in pending.keys.toList()) {
            val m = pending.remove(k) ?: continue
            if (k in want) chunks[k] = upload(m) else requested.remove(k)
        }
        val drop = chunks.keys.filter { it !in want }
        for (k in drop) {
            glDeleteBuffers(1, intArrayOf(chunks.remove(k)!!.vbo), 0)
            requested.remove(k)
        }
    }

    private fun upload(m: FieldMesh): GpuChunk {
        val total = m.batches.sumOf { it.vertices.size }
        val buf = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val list = ArrayList<GpuBatch>()
        var first = 0
        for (b in m.batches) {
            buf.put(b.vertices)
            list += GpuBatch(b, first, b.vertexCount)
            first += b.vertexCount
            b.texture?.let { texture(it) }
        }
        buf.position(0)
        val id = IntArray(1)
        glGenBuffers(1, id, 0)
        glBindBuffer(GL_ARRAY_BUFFER, id[0])
        glBufferData(GL_ARRAY_BUFFER, total * 4, buf, GL_STATIC_DRAW)
        return GpuChunk(id[0], list)
    }

    private fun texture(t: Texture): Int = textures.getOrPut(t) {
        val bytes = ByteBuffer.allocateDirect(t.argb.size * 4).order(ByteOrder.nativeOrder())
        for (c in t.argb) {
            bytes.put((c shr 16).toByte()); bytes.put((c shr 8).toByte()); bytes.put(c.toByte()); bytes.put((c ushr 24).toByte())
        }
        bytes.position(0)
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(GL_TEXTURE_2D, id[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, t.width, t.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        id[0]
    }

    private fun wrap(repeat: Boolean, flip: Boolean) = when {
        !repeat -> GL_CLAMP_TO_EDGE
        flip -> GL_MIRRORED_REPEAT
        else -> GL_REPEAT
    }

    private fun makeFramebuffer() {
        if (fbo != 0) {
            glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            glDeleteTextures(3, intArrayOf(colorTex, idTex, depthTex), 0)
        }
        val t = IntArray(3)
        glGenTextures(3, t, 0)
        colorTex = t[0]; idTex = t[1]; depthTex = t[2]
        for (tex in intArrayOf(colorTex, idTex)) {
            glBindTexture(GL_TEXTURE_2D, tex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, canvasW, canvasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            nearestClamp()
        }
        glBindTexture(GL_TEXTURE_2D, depthTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, canvasW, canvasH, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        nearestClamp()
        val f = IntArray(1)
        glGenFramebuffers(1, f, 0)
        fbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, idTex, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0)
        glDrawBuffers(2, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1), 0)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "framebuffer incomplete: $status")
        val (gt, gf) = colorTarget(grayTex, grayFbo); grayTex = gt; grayFbo = gf
        val (st, sf) = colorTarget(styledTex, styledFbo); styledTex = st; styledFbo = sf
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** A canvas-sized RGBA8 texture with its framebuffer (replacing [oldTex]/[oldFbo]). */
    private fun colorTarget(oldTex: Int, oldFbo: Int): Pair<Int, Int> {
        if (oldFbo != 0) { glDeleteFramebuffers(1, intArrayOf(oldFbo), 0); glDeleteTextures(1, intArrayOf(oldTex), 0) }
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, canvasW, canvasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        nearestClamp()
        val f = IntArray(1); glGenFramebuffers(1, f, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, f[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t[0], 0)
        glDrawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0), 0)
        return t[0] to f[0]
    }

    /** DaylightStyle.LUT's field row as a 256x1 texture. */
    private fun makeLut(): Int {
        val b = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder())
        for (i in 0 until 256) b.put(DaylightStyle.LUT[i].toByte())
        b.position(0)
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 256, 1, 0, GL_RED, GL_UNSIGNED_BYTE, b)
        nearestClamp()
        return t[0]
    }

    private fun nearestClamp() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    // ---- Camera (`src/field/field_camera.c` preset 0, at the DS's pixel density) ----

    private val mvp = FloatArray(16)
    private val right = FloatArray(3)
    private val up = FloatArray(3)

    private fun camera() {
        val t = game.cameraTarget()
        val a = -(CAM_ANGLE_X.toShort().toInt()) * (2 * Math.PI / 65536)
        val ex = t[0]; val ey = t[1] + (CAM_DISTANCE * Math.sin(a)).toFloat(); val ez = t[2] + (CAM_DISTANCE * Math.cos(a)).toFloat()
        var fx = t[0] - ex; var fy = t[1] - ey; var fz = t[2] - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; var rz = fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        right[0] = rx; right[1] = 0f; right[2] = rz
        up[0] = ux; up[1] = uy; up[2] = uz
        val view = floatArrayOf(
            rx, 0f, rz, -(rx * ex + rz * ez),
            ux, uy, uz, -(ux * ex + uy * ey + uz * ez),
            fx, fy, fz, -(fx * ex + fy * ey + fz * ez),
            0f, 0f, 0f, 1f,
        )
        // The DS's 192 rows span 2 tan(half fovy); the taller canvas keeps that density and shows more.
        val tanY = tan(CAM_HALF_FOVY * (2 * Math.PI / 65536)).toFloat() * canvasH / 192f
        val tanX = tanY * canvasW / canvasH
        val n = CAM_NEAR; val f = CAM_FAR
        val proj = floatArrayOf(
            1f / tanX, 0f, 0f, 0f,
            0f, 1f / tanY, 0f, 0f,
            0f, 0f, (f + n) / (f - n), -2f * f * n / (f - n),
            0f, 0f, 1f, 0f,
        )
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += proj[i * 4 + k] * view[k * 4 + j]
            mvp[i * 4 + j] = s
        }
    }

    private fun drawScene() {
        camera()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, canvasW, canvasH)
        GLES32.glColorMaski(0, true, true, true, true)
        GLES32.glColorMaski(1, true, true, true, true)
        glDepthMask(true)
        glClearBufferfv(GL_COLOR, 0, floatArrayOf(0.96f, 0.96f, 0.96f, 1f), 0)
        glClearBufferfv(GL_COLOR, 1, floatArrayOf(0f, 0f, 0f, 0f), 0)
        glClearBufferfv(GL_DEPTH, 0, floatArrayOf(1f), 0)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LESS)
        glUseProgram(sceneProg)
        glUniformMatrix4fv(glGetUniformLocation(sceneProg, "uMvp"), 1, true, mvp, 0)
        glUniform1i(glGetUniformLocation(sceneProg, "uTex"), 0)
        glActiveTexture(GL_TEXTURE0)

        glDisable(GL_BLEND)
        for (c in chunks.values) drawChunk(c, translucent = false)
        drawSprites()
        // Translucent last: no depth writes, ids kept from what is underneath.
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        GLES32.glColorMaski(1, false, false, false, false)
        for (c in chunks.values) drawChunk(c, translucent = true)
        glDepthMask(true)
        glDisable(GL_BLEND)
        GLES32.glColorMaski(1, true, true, true, true)
    }

    private fun bindAttribs(stride: Int) {
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1); glEnableVertexAttribArray(2)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 20)
    }

    private fun drawChunk(c: GpuChunk, translucent: Boolean) {
        glBindBuffer(GL_ARRAY_BUFFER, c.vbo)
        bindAttribs(FieldMesh.FLOATS * 4)
        val uHas = glGetUniformLocation(sceneProg, "uHasTex")
        val uAlpha = glGetUniformLocation(sceneProg, "uAlpha")
        val uTrans = glGetUniformLocation(sceneProg, "uTranslucent")
        val uId = glGetUniformLocation(sceneProg, "uId")
        for (g in c.batches) {
            val b = g.b
            if (b.translucent != translucent) continue
            when {
                b.showFront && b.showBack -> glDisable(GL_CULL_FACE)
                b.showFront -> { glEnable(GL_CULL_FACE); glCullFace(GL_BACK) }
                b.showBack -> { glEnable(GL_CULL_FACE); glCullFace(GL_FRONT) }
                else -> continue
            }
            val tex = b.texture
            glUniform1i(uHas, if (tex != null) 1 else 0)
            if (tex != null) {
                glBindTexture(GL_TEXTURE_2D, texture(tex))
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap(b.repeatS, b.flipS))
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap(b.repeatT, b.flipT))
            }
            glUniform1f(uAlpha, b.alpha)
            glUniform1i(uTrans, if (translucent) 1 else 0)
            setId(uId, b.instance, b.polygonId, b.ground)
            glDrawArrays(GL_TRIANGLES, g.first, g.count)
        }
        glDisable(GL_CULL_FACE)
    }

    /** Id target: instance in RGB; alpha = polygon id, +64 ground, +128 sprite (already gray). */
    private fun setId(loc: Int, instance: Int, polygonId: Int, ground: Boolean, sprite: Boolean = false) {
        glUniform4f(
            loc,
            (instance and 255) / 255f, ((instance shr 8) and 255) / 255f, ((instance shr 16) and 255) / 255f,
            ((polygonId and 63) or (if (ground) 64 else 0) or (if (sprite) 128 else 0)) / 255f,
        )
    }

    private val spriteVerts = FloatArray(6 * FieldMesh.FLOATS)

    /** Field objects as billboards facing the camera, their feet on the ground point. */
    private fun drawSprites() {
        val uHas = glGetUniformLocation(sceneProg, "uHasTex")
        val uAlpha = glGetUniformLocation(sceneProg, "uAlpha")
        val uTrans = glGetUniformLocation(sceneProg, "uTranslucent")
        val uId = glGetUniformLocation(sceneProg, "uId")
        glDisable(GL_CULL_FACE)
        for (s in game.sprites()) {
            val hw = s.width / 2f; val h = s.height.toFloat()
            // Corners: feet at (x, y, z); billboard spans right and up.
            val corners = arrayOf(floatArrayOf(-hw, 0f), floatArrayOf(hw, 0f), floatArrayOf(-hw, h), floatArrayOf(hw, 0f), floatArrayOf(hw, h), floatArrayOf(-hw, h))
            val uvs = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
            var k = 0
            for (i in 0 until 6) {
                val cx = corners[i][0]; val cy = corners[i][1]
                spriteVerts[k++] = s.x + right[0] * cx + up[0] * cy
                spriteVerts[k++] = s.y + right[1] * cx + up[1] * cy
                spriteVerts[k++] = s.z + right[2] * cx + up[2] * cy
                val u = if (s.flip) 1f - uvs[i][0] else uvs[i][0]
                spriteVerts[k++] = u; spriteVerts[k++] = uvs[i][1]
                spriteVerts[k++] = 1f; spriteVerts[k++] = 1f; spriteVerts[k++] = 1f
            }
            glBindBuffer(GL_ARRAY_BUFFER, spriteVbo)
            glBufferData(GL_ARRAY_BUFFER, spriteVerts.size * 4, floats(spriteVerts), GL_STREAM_DRAW)
            bindAttribs(FieldMesh.FLOATS * 4)
            glBindTexture(GL_TEXTURE_2D, texture(s.texture))
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glUniform1i(uHas, 1); glUniform1f(uAlpha, 1f); glUniform1i(uTrans, 0)
            setId(uId, s.instance, 63, false, sprite = true)
            glDrawArrays(GL_TRIANGLES, 0, 6)
        }
    }

    private fun quad() {
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        glEnableVertexAttribArray(0)
        glDisableVertexAttribArray(1); glDisableVertexAttribArray(2)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindTex(prog: Int, unit: Int, id: Int, name: String) {
        glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, id)
        glUniform1i(glGetUniformLocation(prog, name), unit)
    }

    /** DaylightStyle's canvasPass (gray, then ink and lines) and devicePass, on the GPU. */
    private fun drawStyle() {
        val st = game.style
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        glBindFramebuffer(GL_FRAMEBUFFER, grayFbo)
        glViewport(0, 0, canvasW, canvasH)
        glUseProgram(grayProg)
        bindTex(grayProg, 0, colorTex, "uColor"); bindTex(grayProg, 1, idTex, "uIds"); bindTex(grayProg, 2, lutTex, "uLut")
        glUniform2i(glGetUniformLocation(grayProg, "uCanvas"), canvasW, canvasH)
        val mix = DaylightStyle.MIX
        glUniform4f(glGetUniformLocation(grayProg, "uMix"), mix[0], mix[1], mix[2], mix[3])
        glUniform1f(glGetUniformLocation(grayProg, "uPaper"), st.paper / 255f)
        quad()

        glBindFramebuffer(GL_FRAMEBUFFER, styledFbo)
        glUseProgram(linesProg)
        bindTex(linesProg, 0, colorTex, "uColor"); bindTex(linesProg, 1, idTex, "uIds")
        bindTex(linesProg, 2, grayTex, "uGray"); bindTex(linesProg, 3, depthTex, "uDepth")
        glUniform2i(glGetUniformLocation(linesProg, "uCanvas"), canvasW, canvasH)
        glUniform1f(glGetUniformLocation(linesProg, "uNear"), CAM_NEAR)
        glUniform1f(glGetUniformLocation(linesProg, "uFar"), CAM_FAR)
        glUniform1f(glGetUniformLocation(linesProg, "uCrease"), st.creaseRatio)
        glUniform2f(glGetUniformLocation(linesProg, "uInk"), st.inkDark, st.inkLight)
        quad()

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glUseProgram(deviceProg)
        bindTex(deviceProg, 0, styledTex, "uStyled")
        glUniform2i(glGetUniformLocation(deviceProg, "uCanvas"), canvasW, canvasH)
        glUniform1i(glGetUniformLocation(deviceProg, "uScale"), scale)
        glUniform2f(glGetUniformLocation(deviceProg, "uOffset"), offX, offY)
        glUniform1i(glGetUniformLocation(deviceProg, "uExtra"), (st.lineWidth - scale).coerceAtLeast(0))
        glUniform1f(glGetUniformLocation(deviceProg, "uPaper"), st.paper / 255f)
        quad()
        glActiveTexture(GL_TEXTURE0)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: ${glGetShaderInfoLog(s)}")
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: ${glGetProgramInfoLog(p)}")
        return p
    }

    private fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    companion object {
        private const val TAG = "SSDaylight"
        const val TICK = 1.0 / 60.0
        const val CAM_DISTANCE = 666.922119140625f
        const val CAM_ANGLE_X = 0xDD62
        const val CAM_HALF_FOVY = 0x5C1
        const val CAM_NEAR = 150f
        const val CAM_FAR = 1200f
    }
}
