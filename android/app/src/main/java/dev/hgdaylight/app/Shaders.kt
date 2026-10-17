package dev.hgdaylight.app

/**
 * GLSL for the two passes. The style pass is `DaylightStyle` (core) on the GPU: keep the two in step, so the
 * desktop renders in out/ stay a faithful preview of the device.
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

    const val POST_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /**
     * One canvas pixel per [uScale]x[uScale] block of the screen. Gray by luminance with levels and gamma,
     * black where this pixel is the nearer side of an object boundary, a polygon-id boundary or a depth
     * crease, and a dark rim on ground pixels much darker than a neighbour.
     */
    const val POST_FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uColor;
uniform sampler2D uIds;
uniform sampler2D uDepth;
uniform ivec2 uCanvas;
uniform int uScale;
uniform vec2 uOffset;
uniform float uNear;
uniform float uFar;
uniform vec4 uTone;   // inLow, inHigh, gamma, unused
uniform vec2 uRange;  // floor, ceil (0..1)
uniform float uCrease;
uniform float uRimContrast;
uniform float uRimShade;
uniform vec3 uPaper;
out vec4 oColor;

float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float gray(vec3 c) {
    float l = clamp((lum(c) - uTone.x) / (uTone.y - uTone.x), 0.0, 1.0);
    l = pow(l, uTone.z);
    return mix(uRange.x, uRange.y, l);
}
float linz(ivec2 p) {
    float d = texelFetch(uDepth, p, 0).r * 2.0 - 1.0;
    return 2.0 * uFar * uNear / (uFar + uNear - d * (uFar - uNear));
}
int inst(vec4 v) { return int(v.r * 255.0 + 0.5) + int(v.g * 255.0 + 0.5) * 256 + int(v.b * 255.0 + 0.5) * 65536; }
int pid(vec4 v) { return int(v.a * 255.0 + 0.5) & 63; }
bool ground(vec4 v) { return int(v.a * 255.0 + 0.5) >= 64; }

void main() {
    ivec2 p = ivec2(floor((gl_FragCoord.xy - uOffset) / float(uScale)));
    if (p.x < 0 || p.y < 0 || p.x >= uCanvas.x || p.y >= uCanvas.y) { oColor = vec4(uPaper, 1.0); return; }
    vec4 idv = texelFetch(uIds, p, 0);
    int id = inst(idv);
    float g = gray(texelFetch(uColor, p, 0).rgb);
    if (id == 0) { oColor = vec4(vec3(g), 1.0); return; }
    float d = linz(p);
    int pi = pid(idv);
    bool edge = false;
    bool rim = false;
    ivec2 n[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    for (int k = 0; k < 4; k++) {
        ivec2 q = p + n[k];
        if (q.x < 0 || q.y < 0 || q.x >= uCanvas.x || q.y >= uCanvas.y) continue;
        vec4 jv = texelFetch(uIds, q, 0);
        int j = inst(jv);
        if (j == 0) { edge = true; continue; }
        float dj = linz(q);
        if (dj > d) {
            if (j != id || pid(jv) != pi) { if (dj - d > 2.0) edge = true; }
            else if (dj - d > d * uCrease) edge = true;
        }
        if (j == id && ground(idv) && abs(dj - d) < 4.0) {
            float gj = gray(texelFetch(uColor, q, 0).rgb);
            if (gj - g >= uRimContrast) rim = true;
        }
    }
    if (edge) g = 0.0;
    else if (rim) g = min(g, uRimShade);
    oColor = vec4(vec3(g), 1.0);
}
"""
}
