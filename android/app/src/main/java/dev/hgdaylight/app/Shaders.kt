package dev.hgdaylight.app

/**
 * GLSL for the scene and the style passes. The style passes are `DaylightStyle` (core) on the GPU, step for
 * step: keep the two in step, so the desktop renders in out/ stay a faithful preview of the device.
 * Canvas rows here run bottom-up (GL); the core's run top-down, which only mirrors neighbour order.
 */
object Shaders {
    const val SCENE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec3 aColor;
uniform mat4 uMvp;
out vec2 vUv;
out vec3 vColor;
void main() {
    vUv = aUv;
    vColor = aColor;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    /** Modulate (texel x vertex colour), alpha-cut texels; ids go to the second target. */
    const val SCENE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec3 vColor;
uniform sampler2D uTex;
uniform bool uHasTex;
uniform float uAlpha;
uniform bool uTranslucent;
uniform vec4 uId;
layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oId;
void main() {
    vec4 t = uHasTex ? texture(uTex, vUv) : vec4(1.0);
    if (t.a == 0.0) discard;
    float a = t.a * uAlpha;
    if (!uTranslucent && a < 0.5) discard;
    oColor = vec4(vColor * t.rgb, uTranslucent ? a : 1.0);
    oId = uId;
}
"""

    const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** Shared helpers for the canvas passes: id decoding and the crack test. */
    private const val COMMON = """
uniform sampler2D uColor;
uniform sampler2D uIds;
uniform ivec2 uCanvas;
int inst(vec4 v) { return int(v.r * 255.0 + 0.5) + int(v.g * 255.0 + 0.5) * 256 + int(v.b * 255.0 + 0.5) * 65536; }
int code(vec4 v) { return int(v.a * 255.0 + 0.5); }
bool inside(ivec2 q) { return q.x >= 0 && q.y >= 0 && q.x < uCanvas.x && q.y < uCanvas.y; }
int idAt(ivec2 q) { return inst(texelFetch(uIds, q, 0)); }
/** A background pixel between two drawn ones; returns the pixel it shows, or itself. */
ivec2 fillOf(ivec2 p) {
    if (idAt(p) != 0) return p;
    if (p.x > 0 && p.x < uCanvas.x - 1 && idAt(p + ivec2(-1, 0)) != 0 && idAt(p + ivec2(1, 0)) != 0) return p + ivec2(-1, 0);
    if (p.y > 0 && p.y < uCanvas.y - 1 && idAt(p + ivec2(0, -1)) != 0 && idAt(p + ivec2(0, 1)) != 0) return p + ivec2(0, -1);
    return p;
}
"""

    /** Step 1 (canvas): gray per pixel. Field: the fitted mix then the curve; sprites: already gray. */
    const val GRAY_FS = """#version 300 es
precision highp float;
precision highp int;
""" + COMMON + """
uniform sampler2D uLut;
uniform vec4 uMix;
uniform float uPaper;
out vec4 oGray;
void main() {
    ivec2 p = fillOf(ivec2(gl_FragCoord.xy));
    vec4 idv = texelFetch(uIds, p, 0);
    vec3 c = texelFetch(uColor, p, 0).rgb * 255.0;
    float g;
    if (inst(idv) == 0) g = uPaper;
    else if (code(idv) >= 128) g = c.r / 255.0;
    else {
        int m = clamp(int(dot(c, uMix.rgb) + uMix.a + 0.5), 0, 255);
        g = texelFetch(uLut, ivec2(m, 0), 0).r;
    }
    oGray = vec4(g, 0.0, 0.0, 1.0);
}
"""

    /** Step 2 (canvas): ink against the 3x3 mean, and the outline mask (bit k: neighbour k is the far side). */
    const val LINES_FS = """#version 300 es
precision highp float;
precision highp int;
""" + COMMON + """
uniform sampler2D uGray;
uniform sampler2D uDepth;
uniform float uNear;
uniform float uFar;
uniform float uCrease;
uniform vec2 uInk;
uniform bool uOrtho;
out vec4 oOut;
float linz(ivec2 q) {
    float d = texelFetch(uDepth, q, 0).r * 2.0 - 1.0;
    if (uOrtho) return (d * (uFar - uNear) + uFar + uNear) * 0.5;
    return 2.0 * uFar * uNear / (uFar + uNear - d * (uFar - uNear));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float g = texelFetch(uGray, p, 0).r;
    ivec2 f = fillOf(p);
    vec4 fid = texelFetch(uIds, f, 0);
    bool field = inst(fid) != 0 && code(fid) < 128;
    if (field && p.x > 0 && p.y > 0 && p.x < uCanvas.x - 1 && p.y < uCanvas.y - 1) {
        float s = 0.0;
        for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) s += texelFetch(uGray, p + ivec2(ox, oy), 0).r;
        float diff = g - s / 9.0;
        g = clamp(g + diff * (diff < 0.0 ? uInk.x : uInk.y), 0.0, 1.0);
    }
    int mask = 0;
    vec4 idv = texelFetch(uIds, p, 0);
    int id = inst(idv);
    if (id != 0) {
        float d = linz(p);
        int pi = code(idv) & 63;
        ivec2 n[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
        for (int k = 0; k < 4; k++) {
            ivec2 q = p + n[k];
            if (!inside(q)) continue;
            if (fillOf(q) != q) continue;
            vec4 jv = texelFetch(uIds, q, 0);
            int j = inst(jv);
            if (j == 0) { mask |= 1 << k; continue; }
            float dj = linz(q);
            if (dj > d) {
                bool other = j != id || (code(jv) & 63) != pi;
                if (other ? dj - d > 2.0 : dj - d > d * uCrease) mask |= 1 << k;
            }
        }
    }
    oOut = vec4(g, float(mask) / 255.0, 0.0, 1.0);
}
"""

    /**
     * Step 3 (device): one canvas pixel per [uScale] square. The near side's canvas pixel is black; the far
     * side's device pixels within uExtra of the shared border are black too.
     */
    const val DEVICE_FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uStyled;
uniform ivec2 uCanvas;
uniform int uScale;
uniform vec2 uOffset;
uniform int uExtra;
uniform float uPaper;
out vec4 oColor;
int maskAt(ivec2 q) { return int(texelFetch(uStyled, q, 0).g * 255.0 + 0.5); }
void main() {
    ivec2 dp = ivec2(floor(gl_FragCoord.xy - uOffset));
    ivec2 p = dp / uScale;
    if (dp.x < 0 || dp.y < 0 || p.x >= uCanvas.x || p.y >= uCanvas.y) { oColor = vec4(vec3(uPaper), 1.0); return; }
    vec4 v = texelFetch(uStyled, p, 0);
    float g = maskAt(p) != 0 ? 0.0 : v.r;
    if (g > 0.0 && uExtra > 0) {
        ivec2 s = dp - p * uScale;
        ivec2 n[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
        int dist[4] = int[4](s.x, uScale - 1 - s.x, s.y, uScale - 1 - s.y);
        for (int k = 0; k < 4; k++) {
            ivec2 q = p + n[k];
            if (q.x < 0 || q.y < 0 || q.x >= uCanvas.x || q.y >= uCanvas.y) continue;
            if ((maskAt(q) & (1 << (k ^ 1))) != 0 && dist[k] < uExtra) { g = 0.0; break; }
        }
    }
    oColor = vec4(vec3(g), 1.0);
}
"""
}
