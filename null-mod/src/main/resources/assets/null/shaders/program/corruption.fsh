#version 150

// NULL's corruption pass. Every effect is driven by one uniform in 0..1 (HueShift is in
// radians); with all of them at zero the picture passes through untouched.

uniform sampler2D DiffuseSampler;

uniform vec2 OutSize;
uniform float NullTime;
uniform float NullSeed;
uniform float RgbSplit;
uniform float HueShift;
uniform float Desaturate;
uniform float Darken;
uniform float Grain;
uniform float Scanlines;
uniform float BlockShift;
uniform float Warp;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

vec3 hueRotate(vec3 c, float angle) {
    const vec3 k = vec3(0.57735027);
    float ca = cos(angle);
    return c * ca + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - ca);
}

vec3 sampleScene(vec2 uv) {
    return texture(DiffuseSampler, clamp(uv, oneTexel * 0.5, 1.0 - oneTexel * 0.5)).rgb;
}

void main() {
    vec2 uv = texCoord;
    float t = NullTime;
    float band = 0.0;

    // Wobble: the picture swims, then slowly breathes in and out.
    if (Warp > 0.0) {
        uv.x += (sin(uv.y * 13.0 + t * 2.1) * 0.0055 + sin(uv.y * 37.0 - t * 4.7) * 0.0022) * Warp;
        uv.y += (sin(uv.x * 9.0 + t * 1.6) * 0.0040 + sin(uv.x * 29.0 + t * 3.3) * 0.0012) * Warp;
        vec2 c = uv - 0.5;
        uv = 0.5 + c * (1.0 - 0.02 * Warp * (0.5 + 0.5 * sin(t * 1.1)));
    }

    // Horizontal displacement bands: slices of the world slide sideways in block-sized steps
    // and hold there for a few frames.
    if (BlockShift > 0.0) {
        float frame = floor(t * 11.0);
        float rows = 14.0 + floor(hash11(frame * 0.37 + NullSeed) * 26.0);
        float row = floor(uv.y * rows);
        if (hash12(vec2(row, frame + NullSeed)) < BlockShift * 0.5) {
            float s = hash12(vec2(row * 1.7 + 3.1, frame * 0.61 + NullSeed)) - 0.5;
            uv.x += floor(s * 160.0 * BlockShift) * 8.0 * oneTexel.x;
            band = 1.0;
        }
    }

    // RGB separation, stronger toward the edges, slowly turning.
    vec3 col;
    float split = RgbSplit + band * 0.15 * BlockShift;
    if (split > 0.0) {
        float a = t * 0.55 + NullSeed * 6.2831853;
        vec2 dir = vec2(cos(a), sin(a) * 0.4);
        float edge = 0.55 + 1.3 * length(texCoord - 0.5);
        vec2 off = dir * oneTexel * (2.0 + 22.0 * split) * edge;
        col.r = sampleScene(uv + off).r;
        col.g = sampleScene(uv).g;
        col.b = sampleScene(uv - off).b;
    } else {
        col = sampleScene(uv);
    }

    if (HueShift != 0.0) {
        col = clamp(hueRotate(col, HueShift), 0.0, 1.0);
    }

    if (Desaturate > 0.0) {
        float l = dot(col, vec3(0.299, 0.587, 0.114));
        // Drained colour turns slightly cold, like old film.
        col = mix(col, vec3(l * 0.97, l, l * 1.04), Desaturate);
    }

    if (Scanlines > 0.0) {
        float line = 0.5 + 0.5 * sin(texCoord.y * OutSize.y * 1.5707963);
        float roll = smoothstep(0.0, 0.05, abs(fract(texCoord.y * 0.6 - t * 0.12) - 0.5));
        col *= 1.0 - Scanlines * (0.28 * line + 0.18 * (1.0 - roll));
        col += Scanlines * 0.05 * (1.0 - roll);
    }

    if (Grain > 0.0) {
        float n = hash12(floor(texCoord * OutSize * 0.5) + fract(t * 7.13) * 917.0) - 0.5;
        // Now and then a whole row of the signal comes through brighter.
        float rowHit = step(0.985 - Grain * 0.02, hash12(vec2(floor(texCoord.y * OutSize.y * 0.25), floor(t * 24.0))));
        col += n * Grain * 0.32 + rowHit * Grain * 0.12;
    }

    if (Darken > 0.0) {
        vec2 v = (texCoord - 0.5) * vec2(1.15, 1.0);
        float vignette = smoothstep(0.85, 0.2, length(v));
        col *= (1.0 - 0.5 * Darken) * mix(1.0, vignette, Darken * 0.9);
    }

    col *= 1.0 - band * 0.08;
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
