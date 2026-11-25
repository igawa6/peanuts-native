#version 450
// PEANUTS: the present's CRT look (vka_present.c crt_copy, DM_VK_CRT): the window texture drawn into the letterbox of the swapchain image as a CRT shows it: scanlines
// whose beam widens with brightness, an aperture-grille mask, an optional barrel curvature and a vignette. One pass, drawn with fsr.vert's covering triangle.
// The swapchain pixel q is turned back to the letterbox pixel by q.x * m.xy + q.y * m.zw + t (fsr_copy's turns, for a pre-rotated swapchain).
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P {
    ivec4 m; ivec2 t;
    vec2 size;   // the letterbox in pixels (dw, dh)
    vec4 uv;     // texture coordinates = uv.xy + l * uv.zw for the letterbox's own l in 0..1 (uv.w < 0: the texture is stored bottom-up)
    vec4 k;      // scanlines over the height, scanline strength 0..1, mask strength 0..1, curvature
    vec4 k2;     // vignette strength, brightness, the mask's pitch in pixels, unused
} pc;
layout(location = 0) out vec4 o;

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec2 p = vec2(q.x * pc.m.xy + q.y * pc.m.zw + pc.t) + 0.5;
    vec2 l = p / pc.size;
    // Barrel curvature: each axis bulges with the square of the other
    vec2 c = l * 2.0 - 1.0;
    c *= 1.0 + pc.k.w * c.yx * c.yx;
    l = c * 0.5 + 0.5;
    // A one-pixel soft edge where the curved picture ends (black outside)
    vec2 e = clamp((0.5 - abs(l - 0.5)) * pc.size, 0.0, 1.0);
    float edge = e.x * e.y;
    if (edge <= 0.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 col = texture(tex, pc.uv.xy + l * pc.uv.zw).rgb;
    col = col * col;   // to (about) linear light: gamma 2
    // Scanlines: a Gaussian beam per line, wider for brighter pixels (a CRT's beam blooms)
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    float f = fract(l.y * pc.k.x) - 0.5;
    float sigma = mix(0.22, 0.42, sqrt(lum));
    float beam = exp(-0.5 * (f * f) / (sigma * sigma));
    col *= mix(1.0, beam * 1.25, pc.k.y);
    // Aperture grille: red, green and blue stripes over the screen's columns
    if (pc.k.z > 0.0) {
        float s = fract(p.x / pc.k2.z) * 3.0;
        vec3 mk = vec3(s < 1.0 ? 1.0 : 0.0, s >= 1.0 && s < 2.0 ? 1.0 : 0.0, s >= 2.0 ? 1.0 : 0.0);
        col *= mix(vec3(1.0), mk * 2.0 + 0.25, pc.k.z);
    }
    // Vignette
    float v = 16.0 * l.x * l.y * (1.0 - l.x) * (1.0 - l.y);
    col *= mix(1.0, pow(clamp(v, 0.0, 1.0), 0.35), pc.k2.x);
    col = sqrt(clamp(col * pc.k2.y, 0.0, 1.0));
    o = vec4(col * edge, 1.0);
}
