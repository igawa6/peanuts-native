package dev.peanuts.mii;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.peanuts.PeanutsPrefs;

/**
 * Draws Miis from the player's own firmware parts (MiiParts) with OpenGL ES 3, on a thread of its own: a picture for a tile, a list or a profile picture, and the
 * editor's live preview that the player turns. A head is built as the console builds it (vee's building.rs and draw/model.rs, MIT or Apache-2.0): the face
 * parts are first drawn into a face texture (MiiLook.mask) that lies on the face's mask mesh; the face shape carries the skin colour with wrinkles, make-up and a
 * drawn beard; nose, hair, forehead, glasses, beard and hat are meshes placed by the face shape's anchors. The light is the app's own: soft, from the upper
 * left. Shoulders in the favourite colour are the app's own too (the firmware has only heads). One EGL context serves every surface, so a part is uploaded once.
 */
public final class MiiGl {
    static final String TAG = "peanuts";
    private static MiiGl instance;

    public static synchronized MiiGl get(Context c) {
        if (instance == null) instance = new MiiGl(c.getApplicationContext());
        return instance;
    }

    /** Whether the player's firmware gave the Mii parts (Setup's firmware import) */
    public static boolean available(Context c) { return MiiParts.present(PeanutsPrefs.miiModelDir(c)); }

    private final File dir;
    private final Handler gl, ui = new Handler(Looper.getMainLooper());
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface pbuf = EGL14.EGL_NO_SURFACE;
    private final MiiGlRetry retry = new MiiGlRetry();   // after a start that failed: when to try again
    private MiiParts parts;
    private int prog2d, prog3d, quad;
    private final Map<Integer, int[]> textures = new HashMap<>();   // part → {GL texture, channels}
    private final Map<Long, Mesh> meshes = new HashMap<>();

    private MiiGl(Context c) {
        dir = PeanutsPrefs.miiModelDir(c);
        HandlerThread t = new HandlerThread("mii-gl");
        t.start();
        gl = new Handler(t.getLooper());
    }

    // ---- Pictures ----

    /** A picture of m, px square, the head turned yaw degrees, on a transparent background; done gets null when the firmware has no Mii parts or the GPU failed */
    public void picture(MiiStore.Mii m, int px, float yaw, Consumer<Bitmap> done) { picture(m, px, yaw, 1f, done); }

    /** The same, closer by zoom (1: head and shoulders; about 1.6: the face fills the picture, for a part's tile) */
    public void picture(MiiStore.Mii m0, int px, float yaw, float zoom, Consumer<Bitmap> done) {
        MiiStore.Mii m = m0.copy(m0.name);
        byte[] info = MiiStore.charInfo(m);
        gl.post(() -> {
            Bitmap b = null;
            try {
                if (ready() && EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) {
                    Head h = head(info, m);
                    try { b = offscreen(h, px, yaw, 0, zoom); } finally { h.release(); }
                }
            } catch (IOException | RuntimeException e) { Log.w(TAG, "Mii picture", e); }
            catch (OutOfMemoryError e) { Log.w(TAG, "Mii picture: out of memory (" + px + " px)"); b = null; }   // no picture this time; the thread lives on
            Bitmap out = b;
            ui.post(() -> done.accept(out));
        });
    }

    // ---- The live preview ----

    /** A surface the head is drawn on (a TextureView's): show() a Mii, turn() it; release() when the view goes */
    public final class Preview {
        private final SurfaceTexture st;
        private EGLSurface surf = EGL14.EGL_NO_SURFACE;
        private int w, h, fbo, color, depth;
        private Head head;
        private byte[] shown;
        private volatile byte[] want;              // the Mii asked for last (show), drawn by whichever posted apply runs first
        private volatile MiiStore.Mii wantMii;
        private volatile float yaw, pitch;
        private boolean gone;

        Preview(SurfaceTexture st, int w, int h) { this.st = st; this.w = w; this.h = h; }

        // The preview goes before the part tiles waiting on the same thread (a tab's tiles are a hundred pictures; on a slow phone the Mii stayed empty
        // until they were done): its work is posted at the front, and each run takes the latest Mii and turn, so the order of those posts does not matter

        public void size(int w, int h) { gl.postAtFrontOfQueue(() -> { this.w = w; this.h = h; freeTarget(); draw(); }); }

        public void show(MiiStore.Mii m) {
            wantMii = m.copy(m.name);
            want = MiiStore.charInfo(m);
            gl.postAtFrontOfQueue(this::apply);
        }

        private void apply() {
            byte[] info = want;
            MiiStore.Mii copy = wantMii;
            if (gone || info == null || Arrays.equals(info, shown)) return;
            if (!current()) return;
            try {
                Head next = head(info, copy);
                if (head != null) head.release();
                head = next; shown = info;
            } catch (IOException | RuntimeException e) { Log.w(TAG, "Mii preview", e); }
            catch (OutOfMemoryError e) { Log.w(TAG, "Mii preview: out of memory"); }   // the Mii shown before stays
            draw();
        }

        public void turn(float yaw, float pitch) {
            this.yaw = yaw; this.pitch = pitch;
            gl.postAtFrontOfQueue(this::draw);
        }

        public void release() {
            gl.post(() -> {
                gone = true;
                if (current()) { if (head != null) head.release(); freeTarget(); }
                head = null;
                if (surf != EGL14.EGL_NO_SURFACE) { EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx); EGL14.eglDestroySurface(dpy, surf); surf = EGL14.EGL_NO_SURFACE; }
                st.release();   // the view left it to us (MiiView.onSurfaceTextureDestroyed returns false)
            });
        }

        private boolean current() {
            if (gone || !ready()) return false;
            if (surf == EGL14.EGL_NO_SURFACE) {
                surf = EGL14.eglCreateWindowSurface(dpy, cfg, st, new int[] {EGL14.EGL_NONE}, 0);
                if (surf == EGL14.EGL_NO_SURFACE) { Log.w(TAG, "Mii preview: no window surface (" + EGL14.eglGetError() + ")"); return false; }
            }
            return EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        }

        private void freeTarget() {
            if (fbo != 0) { GLES30.glDeleteFramebuffers(1, new int[] {fbo}, 0); GLES30.glDeleteRenderbuffers(2, new int[] {color, depth}, 0); fbo = color = depth = 0; }
        }

        private void draw() {
            if (!current() || w <= 0 || h <= 0) return;
            if (fbo == 0) { int[] t = msaaTarget(w, h); fbo = t[0]; color = t[1]; depth = t[2]; }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glViewport(0, 0, w, h);
            GLES30.glClearColor(0, 0, 0, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            if (head != null) drawHead(head, w, h, yaw, pitch, 1f, false);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            EGL14.eglSwapBuffers(dpy, surf);
        }
    }

    /** A preview on st (a TextureView's surface texture) of w x h pixels */
    public Preview preview(SurfaceTexture st, int w, int h) { return new Preview(st, w, h); }

    // ---- EGL ----

    private boolean ready() {
        if (retry.waiting(System.currentTimeMillis(), dir)) return false;
        if (ctx != EGL14.EGL_NO_CONTEXT) return parts != null;
        try {
            parts = MiiParts.open(dir);
            if (parts == null) return false;
        } catch (IOException e) { Log.w(TAG, "Mii parts: " + e.getMessage()); return failed(); }
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, v, 0, v, 1)) { Log.w(TAG, "Mii pictures: no EGL display (" + EGL14.eglGetError() + ")"); return failed(); }
        int[] attr = {EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLConfig[] cf = new EGLConfig[1]; int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attr, 0, cf, 0, 1, n, 0) || n[0] < 1) { Log.w(TAG, "Mii pictures: no OpenGL ES 3 config"); return failed(); }
        cfg = cf[0];
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT || pbuf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) { Log.w(TAG, "Mii pictures: no EGL context (" + EGL14.eglGetError() + ")"); return failed(); }
        try {   // which formats this firmware's parts use, once, for the log
            StringBuilder f = new StringBuilder("Mii parts: ").append(parts.textureFile).append(", ").append(parts.shapeFile).append("; formats");
            int[][] probe = {{MiiParts.T_EYE, 0}, {MiiParts.T_EYEBROW, 0}, {MiiParts.T_MOUTH, 0}, {MiiParts.T_GLASS, 1}, {MiiParts.T_MAKEUP, 1}, {MiiParts.T_WRINKLE, 1},
                    {MiiParts.T_MOLE, 1}, {MiiParts.T_MUSTACHE, 1}, {MiiParts.T_NOSELINE, 0}, {MiiParts.T_BEARD, 0}, {MiiParts.T_HAT, 0}};
            for (int[] k : probe) { MiiParts.Texture t = parts.texture(k[0], k[1]); f.append(' ').append(k[0]).append(':').append(t == null ? "-" : t.format + "/" + t.width + "x" + t.height); }
            Log.i(TAG, f.toString());
        } catch (IOException e) { Log.w(TAG, "Mii parts: " + e.getMessage()); }
        prog2d = program(VS_2D, FS_2D);
        prog3d = program(VS_3D, FS_3D);
        if (prog2d == 0 || prog3d == 0) return failed();
        int[] b = new int[1];
        GLES30.glGenBuffers(1, b, 0); quad = b[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad);
        float[] q = {0, -0.5f, 0, 0,  1, -0.5f, 1, 0,  1, 0.5f, 1, 1,  0, 0.5f, 0, 1};   // x, y, s, t: a unit quad whose left edge is x 0
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.length * 4, floats(q), GLES30.GL_STATIC_DRAW);
        retry.ok();
        return true;
    }

    /** The start failed: what it made goes (the context takes its shaders with it), so the next try, when MiiGlRetry says, starts clean; false */
    private boolean failed() {
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
        }
        pbuf = EGL14.EGL_NO_SURFACE; ctx = EGL14.EGL_NO_CONTEXT;
        prog2d = prog3d = quad = 0;
        textures.clear(); meshes.clear();
        parts = null;
        retry.failed(System.currentTimeMillis(), dir);
        return false;
    }

    // ---- Shaders ----

    private static final String VS_2D = "#version 300 es\n"
            + "uniform mat4 uM; layout(location=0) in vec2 aPos; layout(location=1) in vec2 aUv; out vec2 vUv;\n"
            + "void main() { vUv = aUv; gl_Position = uM * vec4(aPos, 0.0, 1.0); }\n";
    /** A face part as colour, FFL's modulate modes (MiiLook), written premultiplied */
    private static final String FS_2D = "#version 300 es\nprecision mediump float;\n"
            + "uniform sampler2D uTex; uniform int uMode; uniform vec4 uR, uG, uB; in vec2 vUv; out vec4 o;\n"
            + "void main() {\n"
            + "  vec4 t = texture(uTex, vUv); vec4 c;\n"
            + "  if (uMode == 0) c = uR;\n"
            + "  else if (uMode == 1) c = t;\n"
            + "  else if (uMode == 2) c = vec4(t.r * uR.rgb + t.g * uG.rgb + t.b * uB.rgb, t.a * uR.a);\n"
            + "  else if (uMode == 3) c = vec4(uR.rgb, t.r);\n"
            + "  else if (uMode == 4) c = vec4(t.r * uR.rgb, t.g * uR.a);\n"
            + "  else c = vec4(t.r * uR.rgb, 1.0);\n"
            + "  o = vec4(c.rgb * c.a, c.a);\n"
            + "}\n";
    private static final String VS_3D = "#version 300 es\n"
            + "uniform mat4 uVP; uniform mat3 uRot; uniform vec3 uScale, uOffset, uPivot;\n"
            + "layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec2 aUv; out vec3 vN; out vec2 vUv;\n"
            + "void main() {\n"
            + "  vec3 p = aPos * uScale + uOffset;\n"
            + "  vN = uRot * normalize(aNor / uScale);\n"
            + "  vUv = aUv;\n"
            + "  gl_Position = uVP * vec4(uRot * (p - uPivot) + uPivot, 1.0);\n"
            + "}\n";
    /** Soft light from the upper left, a little rim and a small shine; colour and texture premultiplied */
    private static final String FS_3D = "#version 300 es\nprecision mediump float;\n"
            + "uniform vec4 uColor; uniform sampler2D uTex; uniform int uUseTex; uniform float uShine; in vec3 vN; in vec2 vUv; out vec4 o;\n"
            + "void main() {\n"
            + "  vec3 rgb = uColor.rgb * uColor.a; float a = uColor.a;\n"
            + "  if (uUseTex == 1) { vec4 t = texture(uTex, vUv); rgb = rgb * (1.0 - t.a) + t.rgb; a = a + t.a * (1.0 - a); }\n"
            + "  if (a < 0.004) discard;\n"
            + "  vec3 n = normalize(vN); vec3 l = normalize(vec3(-0.35, 0.5, 0.8));\n"
            + "  float d = max(dot(n, l), 0.0);\n"
            + "  float rim = pow(1.0 - clamp(n.z, 0.0, 1.0), 2.5);\n"
            + "  float s = pow(max(dot(n, normalize(l + vec3(0.0, 0.0, 1.0))), 0.0), 28.0) * uShine;\n"
            + "  o = vec4(rgb * (0.62 + 0.45 * d) + rgb * rim * 0.16 + vec3(s) * a, a);\n"
            + "}\n";

    private static int program(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) { Log.w(TAG, "Mii shader link: " + GLES30.glGetProgramInfoLog(p)); return 0; }
        return p;
    }

    private static int shader(int kind, String src) {
        int s = GLES30.glCreateShader(kind);
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { Log.w(TAG, "Mii shader: " + GLES30.glGetShaderInfoLog(s)); return 0; }
        return s;
    }

    // ---- Parts on the GPU ----

    /** A face part as a texture, uploaded once: {texture, channels}; null when the firmware has none */
    private int[] partTexture(int kind, int index) throws IOException {
        int key = kind * 1000 + index;
        if (textures.containsKey(key)) return textures.get(key);
        MiiParts.Texture t = parts.texture(kind, index);
        int[] r = null;
        if (t != null) {
            int[] id = new int[1];
            GLES30.glGenTextures(1, id, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            int ch = MiiPixels.channels(t.format);
            if (ch == 0) GLES30.glCompressedTexImage2D(GLES30.GL_TEXTURE_2D, 0, 0x93B0 /* GL_COMPRESSED_RGBA_ASTC_4x4_KHR */, t.width, t.height, 0, t.data.length, direct(t.data));
            else {
                int in = ch == 1 ? GLES30.GL_R8 : ch == 2 ? GLES30.GL_RG8 : GLES30.GL_RGBA8, fmt = ch == 1 ? GLES30.GL_RED : ch == 2 ? GLES30.GL_RG : GLES30.GL_RGBA;
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, in, t.width, t.height, 0, fmt, GLES30.GL_UNSIGNED_BYTE, direct(MiiPixels.decode(t)));
            }
            sampler(GLES30.GL_CLAMP_TO_EDGE);
            r = new int[] {id[0], ch == 0 ? 4 : ch};
        }
        textures.put(key, r);
        return r;
    }

    private static void sampler(int wrap) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);
    }

    /** A mesh on the GPU: interleaved position, normal, texture coordinate; and its index list */
    static final class Mesh {
        int vbo, ibo, count;
        final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    }

    private Mesh partMesh(int kind, int index) throws IOException {
        long key = (long) kind << 32 | index;
        if (meshes.containsKey(key)) return meshes.get(key);
        MiiParts.Shape s = parts.shape(kind, index);
        Mesh m = s == null ? null : mesh(s.position, s.normal, s.uv, s.index);
        meshes.put(key, m);
        return m;
    }

    static Mesh mesh(float[] pos, float[] nor, float[] uv, short[] idx) {
        int n = pos.length / 3;
        float[] v = new float[n * 8];
        Mesh m = new Mesh();
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                v[8 * i + c] = pos[3 * i + c]; v[8 * i + 3 + c] = nor[3 * i + c];
                m.min[c] = Math.min(m.min[c], pos[3 * i + c]); m.max[c] = Math.max(m.max[c], pos[3 * i + c]);
            }
            if (uv != null) { v[8 * i + 6] = uv[2 * i]; v[8 * i + 7] = uv[2 * i + 1]; }
        }
        int[] b = new int[2];
        GLES30.glGenBuffers(2, b, 0);
        m.vbo = b[0]; m.ibo = b[1]; m.count = idx.length;
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.length * 4, floats(v), GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
        ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx).position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES30.GL_STATIC_DRAW);
        return m;
    }

    private static ByteBuffer direct(byte[] b) {
        ByteBuffer d = ByteBuffer.allocateDirect(b.length).order(ByteOrder.nativeOrder());
        d.put(b).position(0);
        return d;
    }

    private static FloatBuffer floats(float[] f) {
        FloatBuffer b = ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(f).position(0);
        return b;
    }

    // ---- A head ----

    /** One drawn piece: a mesh, where it sits, its colour (0xAARRGGBB; alpha 0 for a decal that is only its texture) and its texture */
    static final class Piece {
        Mesh mesh; float[] scale = {1, 1, 1}, offset = {0, 0, 0}; int color; int tex; boolean decal; float shine;
    }

    final class Head {
        final List<Piece> pieces = new ArrayList<>();
        final List<Integer> own = new ArrayList<>();   // the face textures made for this Mii
        Mesh[] body; int bodyColor, skin;
        final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        void release() {
            if (!own.isEmpty()) { int[] t = new int[own.size()]; for (int i = 0; i < t.length; i++) t[i] = own.get(i); GLES30.glDeleteTextures(t.length, t, 0); }
            if (body != null) for (Mesh b : body) GLES30.glDeleteBuffers(2, new int[] {b.vbo, b.ibo}, 0);
            own.clear(); body = null;
        }
        Piece add(Mesh mesh, int color) {
            if (mesh == null) return null;
            Piece p = new Piece(); p.mesh = mesh; p.color = color;
            pieces.add(p);
            return p;
        }
    }

    private static final int OPAQUE = 0xff000000;

    /** m's head, its parts uploaded; a head that fails half-way (a broken part, out of memory) gives back the textures made for it */
    private Head head(byte[] info, MiiStore.Mii m) throws IOException {
        Head h = new Head();
        try { build(h, m); }
        catch (IOException | RuntimeException | OutOfMemoryError e) { h.release(); throw e; }
        return h;
    }

    private void build(Head h, MiiStore.Mii m) throws IOException {
        int face = m.get("faceline_type"), hair = m.get("hair_type");
        float[] ft = parts.facelineTransform(face);
        float[] hairAt = {ft[0], ft[1], ft[2]}, noseAt = {ft[3], ft[4], ft[5]}, beardAt = {ft[6], ft[7], ft[8]};
        float flip = m.get("hair_flip") != 0 ? -1 : 1;
        int skin = OPAQUE | MiiLook.skin(m.get("faceline_color"));

        Piece p = h.add(partMesh(MiiParts.S_FACELINE, face), skin);
        int faceTex = facelineTexture(m);
        if (p != null && faceTex != 0) { p.tex = faceTex; h.own.add(faceTex); }
        p = h.add(partMesh(MiiParts.S_FOREHEAD_NORMAL, hair), skin);
        if (p != null) { p.offset = hairAt; p.scale = new float[] {flip, 1, 1}; }
        p = h.add(partMesh(MiiParts.S_HAIR_NORMAL, hair), OPAQUE | MiiLook.common(m.get("hair_color")));
        if (p != null) { p.offset = hairAt; p.scale = new float[] {flip, 1, 1}; p.shine = 0.25f; }
        p = h.add(partMesh(MiiParts.S_HAT_NORMAL, hair), OPAQUE | MiiLook.favorite(m.get("favorite_color")));
        if (p != null) {
            p.offset = hairAt; p.scale = new float[] {flip, 1, 1};
            int t = decalTexture(MiiParts.T_HAT, hair, MiiLook.hat(m), 128, 32);
            if (t != 0) { p.tex = t; h.own.add(t); }
        }
        float ns = MiiLook.noseScale(m);
        float[] noseAt2 = {noseAt[0], noseAt[1] + MiiLook.noseDy(m), noseAt[2]};
        p = h.add(partMesh(MiiParts.S_NOSE, m.get("nose_type")), skin);
        if (p != null) { p.offset = noseAt2; p.scale = new float[] {ns, ns, ns}; }
        if (MiiLook.beardShape(m)) {
            p = h.add(partMesh(MiiParts.S_BEARD, m.get("beard_type")), OPAQUE | MiiLook.common(m.get("beard_color")));
            if (p != null) { p.offset = beardAt; p.shine = 0.15f; }
        }
        for (Piece q : h.pieces) grow(h, q);
        // decals: drawn over the opaque parts, in this order
        int mask = maskTexture(m);
        p = h.add(partMesh(MiiParts.S_MASK, face), 0);
        if (p != null && mask != 0) { p.tex = mask; p.decal = true; h.own.add(mask); }
        p = h.add(partMesh(MiiParts.S_NOSELINE, m.get("nose_type")), 0);
        if (p != null) {
            p.offset = noseAt2; p.scale = new float[] {ns, ns, ns}; p.decal = true;
            int t = decalTexture(MiiParts.T_NOSELINE, m.get("nose_type"), MiiLook.noseline(), 256, 256);
            if (t != 0) { p.tex = t; h.own.add(t); }
        }
        if (m.get("glass_type") != 0) {
            p = h.add(partMesh(MiiParts.S_GLASS, 0), 0);
            if (p != null) {
                float gs = MiiLook.glassScale(m);
                p.offset = new float[] {noseAt[0], noseAt[1] + MiiLook.glassDy(m), noseAt[2] + 2}; p.scale = new float[] {gs, gs, gs}; p.decal = true;
                int t = decalTexture(MiiParts.T_GLASS, m.get("glass_type"), MiiLook.glass(m), 512, 512);
                if (t != 0) { p.tex = t; h.own.add(t); }
            }
        }
        h.body = body(h, m);
        h.bodyColor = OPAQUE | MiiLook.favorite(m.get("favorite_color"));
        h.skin = skin;
    }

    private static void grow(Head h, Piece p) {
        for (int c = 0; c < 3; c++) {
            float a = p.mesh.min[c] * p.scale[c] + p.offset[c], b = p.mesh.max[c] * p.scale[c] + p.offset[c];
            h.min[c] = Math.min(h.min[c], Math.min(a, b)); h.max[c] = Math.max(h.max[c], Math.max(a, b));
        }
    }

    /**
     * The upper body under the head (the firmware has only heads): a short neck in the skin colour and a torso in the favourite colour, as a Mii's body is: the
     * shoulders run nearly flat from the neck and round off over the arms, which hang a little outwards with a soft groove where they meet the chest and back;
     * wider for a heavier build. Both are surfaces turned around the body's axis, oval across (wider than deep).
     */
    private Mesh[] body(Head h, MiiStore.Mii m) {
        Piece face = h.pieces.isEmpty() ? null : h.pieces.get(0);
        if (face == null) return null;
        float fw = face.mesh.max[0] - face.mesh.min[0], fh = face.mesh.max[1] - face.mesh.min[1], fd = face.mesh.max[2] - face.mesh.min[2];
        float cx = (face.mesh.min[0] + face.mesh.max[0]) / 2, cz = (face.mesh.min[2] + face.mesh.max[2]) / 2 - fd * 0.08f, chin = face.mesh.min[1];
        float build = 0.88f + 0.28f * m.get("build") / 127f;               // thin to heavy
        float shoulder = fw * 0.70f * build, depth = 0.56f, neckR = fw * 0.19f;
        float top = chin - fh * 0.03f, height = fh * 1.3f;                   // the collar a little under the chin, so a short neck shows; the picture shows the upper part
        int seg = 96, ring = 36;
        float[] pos = new float[(seg + 1) * (ring + 1) * 3];
        int round = ring * 2 / 5;                                             // the first rings make the shoulders, from the neck out over the arms
        float drop = height * 0.27f;                                          // how far the shoulders fall from the neck to where the arms hang
        for (int r = 0; r <= ring; r++) {
            float rad, y, arms;
            if (r <= round) {
                // a rounded square rather than a quarter circle: flat from the neck, then a round shoulder (exponent 2/3 on sine and cosine)
                double th = Math.PI / 2 * r / round;
                rad = neckR + (shoulder - neckR) * (float) Math.pow(Math.sin(th), 2 / 3.0);
                y = top - drop * (1f - (float) Math.pow(Math.cos(th), 2 / 3.0));
                arms = (float) Math.max(0, (th - 0.9) / (Math.PI / 2 - 0.9));   // the arm grooves begin over the shoulder
            } else {
                float t = (r - round) / (float) (ring - round);
                rad = shoulder * (1f + 0.07f * t);                           // the arms hang a little outwards
                y = top - drop - t * (height - drop);
                arms = 1;
            }
            for (int s = 0; s <= seg; s++) {
                double ph = 2 * Math.PI * s / seg;
                float c = (float) Math.cos(ph), g = (Math.abs(c) - 0.74f) / 0.06f;
                float rr = rad * (1f - 0.03f * arms * (float) Math.exp(-g * g));   // the groove between arm and chest, front and back
                int k = r * (seg + 1) + s;
                pos[3 * k] = cx + rr * c; pos[3 * k + 1] = y; pos[3 * k + 2] = cz + rr * depth * (float) Math.sin(ph);
            }
        }
        Mesh torso = lathe(pos, seg, ring);
        float[] npos = new float[(seg + 1) * 2 * 3];                            // the neck: from inside the head down into the torso
        for (int r = 0; r <= 1; r++) {
            float y = r == 0 ? chin + fh * 0.3f : top - height * 0.1f;
            for (int s = 0; s <= seg; s++) {
                double ph = 2 * Math.PI * s / seg;
                int k = r * (seg + 1) + s;
                npos[3 * k] = cx + neckR * (float) Math.cos(ph); npos[3 * k + 1] = y; npos[3 * k + 2] = cz + neckR * 0.9f * (float) Math.sin(ph);
            }
        }
        Mesh neck = lathe(npos, seg, 1);
        return new Mesh[] {torso, neck};
    }

    /** A turned surface's rings (seg + 1 points each, ring + 1 rings, top first) as a mesh, its normals smoothed over the faces around each point */
    private static Mesh lathe(float[] pos, int seg, int ring) {
        short[] idx = new short[seg * ring * 6];
        int i = 0;
        for (int r = 0; r < ring; r++)
            for (int s = 0; s < seg; s++) {
                int a = r * (seg + 1) + s, b = a + seg + 1;
                idx[i++] = (short) a; idx[i++] = (short) b; idx[i++] = (short) (a + 1);
                idx[i++] = (short) (a + 1); idx[i++] = (short) b; idx[i++] = (short) (b + 1);
            }
        float[] nor = new float[pos.length];
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
            float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int q : new int[] {a, b, c}) { nor[q] += nx; nor[q + 1] += ny; nor[q + 2] += nz; }
        }
        float ox = 0, oz = 0;   // the axis: normals point away from it
        for (int k = 0; k < pos.length; k += 3) { ox += pos[k]; oz += pos[k + 2]; }
        ox /= pos.length / 3f; oz /= pos.length / 3f;
        for (int k = 0; k < nor.length; k += 3) {
            float l = (float) Math.sqrt(nor[k] * nor[k] + nor[k + 1] * nor[k + 1] + nor[k + 2] * nor[k + 2]);
            if (l < 1e-6f) { nor[k + 2] = 1; continue; }
            float sign = (nor[k] * (pos[k] - ox) + nor[k + 2] * (pos[k + 2] - oz)) < 0 ? -1 : 1;
            nor[k] = sign * nor[k] / l; nor[k + 1] = sign * nor[k + 1] / l; nor[k + 2] = sign * nor[k + 2] / l;
        }
        return mesh(pos, nor, null, idx);
    }

    // ---- Face textures ----

    /** A texture of w x h drawn by draw (2D program bound, premultiplied blending); 0 when nothing could be made */
    private int drawTexture(int w, int h, float[] clear, Runnable draw) {
        int[] id = new int[1], fb = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        sampler(GLES30.GL_MIRRORED_REPEAT);
        GLES30.glGenFramebuffers(1, fb, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, id[0], 0);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(prog2d);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad);
        GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8);
        draw.run();
        GLES30.glDisableVertexAttribArray(0); GLES30.glDisableVertexAttribArray(1);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(1, fb, 0);
        GLES30.glDisable(GLES30.GL_BLEND);
        return id[0];
    }

    /** One face part's quad on a texture of res pixels: anchor x, y (y downward), size, angle, which edge is the anchor (MiiLook.Place) */
    private void quad(int[] part, MiiLook.Tint t, float res, float x, float y, float w, float h, float deg, int origin) {
        if (part == null || w <= 0 || h <= 0) return;
        float[] proj = new float[16], mv = new float[16], m = new float[16];
        // y runs down the face and becomes the texture's rows from the first one (the forehead) on, as the console lays its face texture out; in these
        // mirrored axes a positive angle turns clockwise, as the console's negative one does in its upward ones
        Matrix.orthoM(proj, 0, 0, res, 0, res, -1, 1);
        Matrix.setIdentityM(mv, 0);
        Matrix.translateM(mv, 0, x, y, 0);
        Matrix.rotateM(mv, 0, deg, 0, 0, 1);
        Matrix.scaleM(mv, 0, w * MiiLook.TEX_SCALE_X, h * MiiLook.TEX_SCALE_Y, 1);
        // the unit quad's left edge is x 0: a centred part moves left by half, a right-anchored one by all; a left-anchored one is the mirrored picture
        if (origin == MiiLook.CENTER) Matrix.translateM(mv, 0, -0.5f, 0, 0);
        else if (origin == MiiLook.RIGHT) Matrix.translateM(mv, 0, -1f, 0, 0);
        else { Matrix.translateM(mv, 0, 1f, 0, 0); Matrix.scaleM(mv, 0, -1f, 1, 1); }
        Matrix.multiplyMM(m, 0, proj, 0, mv, 0);
        draw2d(part[0], t, m);
    }

    private void draw2d(int tex, MiiLook.Tint t, float[] m) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog2d, "uM"), 1, false, m, 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog2d, "uMode"), t.mode);
        color(prog2d, "uR", OPAQUE | t.r); color(prog2d, "uG", OPAQUE | t.g); color(prog2d, "uB", OPAQUE | t.b);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog2d, "uTex"), 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
    }

    private static void color(int prog, String name, int argb) {
        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, name), (argb >> 16 & 0xff) / 255f, (argb >> 8 & 0xff) / 255f, (argb & 0xff) / 255f, (argb >>> 24) / 255f);
    }

    /** The face's parts on the mask texture (512 px, as the console draws it for a big picture) */
    private int maskTexture(MiiStore.Mii m) throws IOException {
        final float res = 256;
        MiiLook.Mask k = MiiLook.mask(m, res);
        int[] eye = partTexture(MiiParts.T_EYE, m.get("eye_type")), brow = partTexture(MiiParts.T_EYEBROW, m.get("eyebrow_type"));
        int[] mouth = partTexture(MiiParts.T_MOUTH, m.get("mouth_type")), must = partTexture(MiiParts.T_MUSTACHE, m.get("mustache_type"));
        int[] mole = partTexture(MiiParts.T_MOLE, m.get("mole_type") == 0 ? 0 : 1);
        return drawTexture(512, 512, new float[] {0, 0, 0, 0}, () -> {   // laid out on a 256 grid, drawn at 512
            place(eye, MiiLook.eye(m), res, k.eyeL); place(eye, MiiLook.eye(m), res, k.eyeR);
            place(brow, MiiLook.eyebrow(m), res, k.browL); place(brow, MiiLook.eyebrow(m), res, k.browR);
            place(mouth, MiiLook.mouth(m), res, k.mouth);
            place(must, MiiLook.mustache(m), res, k.mustacheL); place(must, MiiLook.mustache(m), res, k.mustacheR);
            if (m.get("mole_type") != 0) place(mole, MiiLook.mole(), res, k.mole);
        });
    }

    private void place(int[] part, MiiLook.Tint t, float res, MiiLook.Place p) { quad(part, t, res, p.x, p.y, p.w, p.h, p.deg, p.origin); }

    /** The face shape's texture: skin colour, wrinkles, make-up and a drawn beard; 0 when the face has none of them (the shape is its plain colour) */
    private int facelineTexture(MiiStore.Mii m) throws IOException {
        int[] wr = m.get("faceline_wrinkle") != 0 ? partTexture(MiiParts.T_WRINKLE, m.get("faceline_wrinkle")) : null;
        int[] mk = m.get("faceline_make") != 0 ? partTexture(MiiParts.T_MAKEUP, m.get("faceline_make")) : null;
        int bt = m.get("beard_type");
        int[] bd = bt >= 4 ? partTexture(MiiParts.T_BEARD, bt - 4) : null;
        if (wr == null && mk == null && bd == null) return 0;
        int skin = MiiLook.skin(m.get("faceline_color"));
        float[] full = new float[16];
        Matrix.orthoM(full, 0, 0, 1, -0.5f, 0.5f, -1, 1);
        return drawTexture(512, 512, new float[] {(skin >> 16 & 0xff) / 255f, (skin >> 8 & 0xff) / 255f, (skin & 0xff) / 255f, 1}, () -> {
            if (wr != null) draw2d(wr[0], MiiLook.wrinkle(m), full);
            if (mk != null) draw2d(mk[0], MiiLook.makeup(), full);
            if (bd != null) draw2d(bd[0], MiiLook.beardTexture(m), full);
        });
    }

    /** A part's picture coloured onto a texture of its own (nose line, glasses, hat); 0 when the firmware has none */
    private int decalTexture(int kind, int index, MiiLook.Tint t, int w, int h) throws IOException {
        int[] part = partTexture(kind, index);
        if (part == null) return 0;
        float[] full = new float[16];
        Matrix.orthoM(full, 0, 0, 1, -0.5f, 0.5f, -1, 1);
        return drawTexture(w, h, new float[] {0, 0, 0, 0}, () -> draw2d(part[0], t, full));
    }

    // ---- Drawing a head ----

    /** A multisampled colour and depth target of w x h: {framebuffer, colour, depth} */
    private static int[] msaaTarget(int w, int h) {
        int[] fb = new int[1], rb = new int[2];
        GLES30.glGenFramebuffers(1, fb, 0);
        GLES30.glGenRenderbuffers(2, rb, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0]);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0]);
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_RGBA8, w, h);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb[0]);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[1]);
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_DEPTH_COMPONENT24, w, h);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rb[1]);
        return new int[] {fb[0], rb[0], rb[1]};
    }

    /** The head and shoulders into the bound framebuffer of w x h: framed so the head fills the upper part, turned around its centre */
    private void drawHead(Head h, int w, int hgt, float yaw, float pitch, float zoom, boolean picture) {
        float cx = (h.min[0] + h.max[0]) / 2, cy = (h.min[1] + h.max[1]) / 2, cz = (h.min[2] + h.max[2]) / 2;
        float size = Math.max(h.max[0] - h.min[0], h.max[1] - h.min[1]);
        float fovy = 22, aspect = w / (float) hgt;
        float span = size * (picture ? 1.5f : 1.75f) / zoom;   // how much of the scene the height shows
        if (aspect < 0.8f) span = Math.max(span, size * 1.25f / zoom / aspect);   // a tall, narrow view: the head's width fits too
        float dist = (float) (span / 2 / Math.tan(Math.toRadians(fovy / 2)));
        float lookY = cy - size * 0.12f / zoom;
        float[] proj = new float[16], view = new float[16], vp = new float[16];
        Matrix.perspectiveM(proj, 0, fovy, aspect, dist * 0.2f, dist * 4);
        Matrix.setLookAtM(view, 0, cx, lookY, cz + dist, cx, lookY, cz, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        float[] rot4 = new float[16];
        Matrix.setIdentityM(rot4, 0);
        Matrix.rotateM(rot4, 0, yaw, 0, 1, 0);
        Matrix.rotateM(rot4, 0, pitch, 1, 0, 0);
        float[] rot = {rot4[0], rot4[1], rot4[2], rot4[4], rot4[5], rot4[6], rot4[8], rot4[9], rot4[10]};
        GLES30.glUseProgram(prog3d);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog3d, "uVP"), 1, false, vp, 0);
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(prog3d, "uRot"), 1, false, rot, 0);
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog3d, "uPivot"), cx, cy, cz);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog3d, "uTex"), 0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        if (h.body != null) {
            Piece b = new Piece(); b.mesh = h.body[0]; b.color = h.bodyColor; b.shine = 0.08f;
            drawPiece(b);
            Piece n = new Piece(); n.mesh = h.body[1]; n.color = h.skin;
            drawPiece(n);
        }
        for (Piece p : h.pieces) if (!p.decal) drawPiece(p);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(-1, -2);
        for (Piece p : h.pieces) if (p.decal && p.tex != 0) drawPiece(p);
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawPiece(Piece p) {
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog3d, "uScale"), p.scale[0], p.scale[1], p.scale[2]);
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog3d, "uOffset"), p.offset[0], p.offset[1], p.offset[2]);
        color(prog3d, "uColor", p.color);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog3d, "uShine"), p.shine);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog3d, "uUseTex"), p.tex != 0 ? 1 : 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.tex);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, p.mesh.vbo);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.mesh.ibo);
        GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1); GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12);
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.mesh.count, GLES30.GL_UNSIGNED_SHORT, 0);
        GLES30.glDisableVertexAttribArray(0); GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2);
    }

    /** The head drawn off screen and read back as a bitmap of px x px */
    private Bitmap offscreen(Head h, int px, float yaw, float pitch, float zoom) {
        int[] t = msaaTarget(px, px);
        GLES30.glViewport(0, 0, px, px);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        drawHead(h, px, px, yaw, pitch, zoom, true);
        int[] tex = new int[1], fb = new int[1];   // resolve the samples into a plain texture, then read it
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, px, px, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, fb, 0);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fb[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, t[0]);
        GLES30.glBlitFramebuffer(0, 0, px, px, 0, 0, px, px, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fb[0]);
        ByteBuffer buf = ByteBuffer.allocateDirect(px * px * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(0, 0, px, px, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(2, new int[] {fb[0], t[0]}, 0);
        GLES30.glDeleteRenderbuffers(2, new int[] {t[1], t[2]}, 0);
        GLES30.glDeleteTextures(1, tex, 0);
        byte[] rows = new byte[px * px * 4], src = new byte[px * px * 4];   // GL's rows run bottom up
        buf.get(src);
        for (int y = 0; y < px; y++) System.arraycopy(src, (px - 1 - y) * px * 4, rows, y * px * 4, px * 4);
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(rows));
        return b;
    }
}
