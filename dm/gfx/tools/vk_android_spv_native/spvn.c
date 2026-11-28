/* Peanuts Native: vk_android_spv.py's SPIR-V module and its post-passes in C (libdmsh_spvn), which vk_android_spvn.py loads through ctypes so that
 * vk_android_build.py finish() has no Python loop over instructions. Each function here is the Python function of the same name, transliterated: the same
 * instruction list, the same index (names, decorations, types, constants, variables, capabilities, extensions, entry point, execution modes) with the same
 * update rules (what the Python updates by hand between two index() calls, this updates by hand; dictionary iteration in insertion order), the same new ids
 * in the same order. So a module comes out byte for byte as the Python makes it (android/SHADERS.md, Native post-passes).
 *
 * What is not here: the rare rewrites (the HMMA lowering, the viewport mask of an emulated passthrough geometry shader, the GlobalToStorage raw helpers'
 * rewrites, the sample offset lowering, per-primitive mesh inputs) and every shape the Python would answer with an exception. Those make a call return
 * SPVN_FALLBACK, and vk_android_build.py then redoes that shader in Python from its translated module: the Python stays the definition of the output.
 *
 * Interface: spvn_new(bytes) → module, spvn_free, spvn_copy (a fresh parse of the module's words, as vk_android_spv.Module(m.data())), spvn_data (the words),
 * spvn_call(module, pass, args, nargs) → a value (>= 0), or SPVN_FALLBACK; a call's list results in spvn_res. spvn_source() is the SHA-256 of this file,
 * passed by the build (-DSPVN_SOURCE=...), which vk_android_spvn.py checks against the file it reads. Little-endian hosts only (arm64, x86-64). */
#include <setjmp.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "libdmsh_spvn reads SPIR-V words in the host's byte order: little-endian only"
#endif
#ifndef SPVN_SOURCE
#define SPVN_SOURCE "unknown"
#endif
#define EXPORT __attribute__((visibility("default")))
#define SPVN_FALLBACK (-1)

/* ---- memory: an arena of the module's lifetime (instructions) and one reset at every call (scratch) */
typedef struct Chunk { struct Chunk *next; size_t used, size; } Chunk;
typedef struct { Chunk *head; } Arena;

static void *arena_alloc_raw(Arena *a, size_t n)
{
    n = (n + 15) & ~(size_t)15;
    Chunk *c = a->head;
    if (!c || c->used + n > c->size) {
        size_t sz = n + sizeof(Chunk) + 15 > (1u << 20) ? n + sizeof(Chunk) + 15 : (1u << 20);
        c = malloc(sz);
        if (!c) return NULL;
        c->next = a->head; c->used = (sizeof(Chunk) + 15) & ~(size_t)15; c->size = sz; a->head = c;
    }
    void *p = (char *)c + c->used; c->used += n; return p;
}
static void arena_free(Arena *a) { Chunk *c = a->head; while (c) { Chunk *n = c->next; free(c); c = n; } a->head = NULL; }
typedef struct { Chunk *c; size_t used; } Mark;
static Mark amark(Arena *a) { Mark k = {a->head, a->head ? a->head->used : 0}; return k; }
static void arelease(Arena *a, Mark k) { while (a->head && a->head != k.c) { Chunk *n = a->head->next; free(a->head); a->head = n; } if (a->head) a->head->used = k.used; }
static void arena_reset(Arena *a)   /* keep the newest chunk */
{
    Chunk *c = a->head;
    if (!c) return;
    Chunk *n = c->next; c->next = NULL; c->used = (sizeof(Chunk) + 15) & ~(size_t)15;
    while (n) { Chunk *x = n->next; free(n); n = x; }
}

typedef struct Ins { uint32_t n, cap; uint32_t *w; } Ins;
typedef struct DecoEnt { uint32_t deco, nops, ops, next; } DecoEnt;   /* ops: offset into the module's deco_words */

typedef struct M {
    jmp_buf jb; const char *why; int why_line;
    uint32_t header[5];
    Ins **ins; size_t nins, capins;
    Arena perm, tmp;
    /* the index (vk_android_spv.Module.index); per id, sized isz */
    size_t isz;
    Ins **names, **types, **consts;
    uint32_t *var_ptr, *var_sc; uint8_t *var_has;
    uint32_t *deco_head, *deco_tail; uint8_t *deco_has;
    DecoEnt *dent; size_t ndent, capdent; uint32_t *dwords; size_t ndw, capdw;
    uint32_t *ord_types, *ord_consts, *ord_vars, *ord_decos; size_t n_types, n_consts, n_vars, n_decos, c_types, c_consts, c_vars, c_decos;
    uint32_t *caps; size_t ncaps, capcaps;
    Ins **exts; size_t nexts, capexts;
    Ins *entry;
    uint32_t **modes; uint32_t *modes_n; size_t nmodes, capmodes;
    /* results of a call */
    int64_t *res; size_t nres, capres;
    uint8_t *out; size_t capout;
} M;

#define FAIL(m) fail_at(m, __func__, __LINE__)
static void __attribute__((noreturn)) fail_at(M *m, const char *why, int line) { m->why = why; m->why_line = line; longjmp(m->jb, 1); }
static void *palloc(M *m, size_t n) { void *p = arena_alloc_raw(&m->perm, n); if (!p) FAIL(m); return p; }
static void *talloc(M *m, size_t n) { void *p = arena_alloc_raw(&m->tmp, n); if (!p) FAIL(m); return p; }
static void *tcalloc(M *m, size_t n) { void *p = talloc(m, n); memset(p, 0, n); return p; }
static void *xrealloc(M *m, void *p, size_t n) { void *q = realloc(p, n ? n : 1); if (!q) FAIL(m); return q; }

#define OPC(x) ((x)->w[0] & 0xffffu)
static inline uint32_t AT(M *m, const Ins *x, size_t i) { if (i >= x->n) FAIL(m); return x->w[i]; }
static inline void SET(M *m, Ins *x, size_t i, uint32_t v) { if (i >= x->n) FAIL(m); x->w[i] = v; }

/* ---- result list */
static void res_clear(M *m) { m->nres = 0; }
static void res_push(M *m, int64_t v)
{
    if (m->nres == m->capres) { m->capres = m->capres ? 2 * m->capres : 256; m->res = xrealloc(m, m->res, m->capres * sizeof *m->res); }
    m->res[m->nres++] = v;
}

/* ---- instructions */
static Ins *ins_new(M *m, size_t n)
{
    Ins *x = palloc(m, sizeof *x); x->n = (uint32_t)n; x->cap = (uint32_t)(n ? n : 1); x->w = palloc(m, x->cap * 4); return x;
}
static Ins *mkv(M *m, const uint32_t *w, size_t n) { Ins *x = ins_new(m, n); memcpy(x->w, w, n * 4); return x; }
static Ins *mk(M *m, int n, ...)
{
    Ins *x = ins_new(m, (size_t)n); va_list ap; va_start(ap, n);
    for (int i = 0; i < n; i++) x->w[i] = va_arg(ap, uint32_t);
    va_end(ap); return x;
}
static Ins *ins_copy(M *m, const Ins *x) { return mkv(m, x->w, x->n); }
static void ins_reserve(M *m, Ins *x, size_t n)
{
    if (n <= x->cap) return;
    size_t c = x->cap * 2 > n ? x->cap * 2 : n; uint32_t *w = palloc(m, c * 4); memcpy(w, x->w, x->n * 4); x->w = w; x->cap = (uint32_t)c;
}
static void ins_append(M *m, Ins *x, uint32_t v) { ins_reserve(m, x, x->n + 1u); x->w[x->n++] = v; }
static void ins_assign(M *m, Ins *x, const uint32_t *w, size_t n)   /* x[:] = w */
{
    uint32_t tmp[16]; if (n > 16) FAIL(m);
    memcpy(tmp, w, n * 4); ins_reserve(m, x, n); memcpy(x->w, tmp, n * 4); x->n = (uint32_t)n;
}

/* ---- the instruction list */
static void ins_cap(M *m, size_t n)
{
    if (n <= m->capins) return;
    size_t c = m->capins * 2 > n ? m->capins * 2 : n + 16; m->ins = xrealloc(m, m->ins, c * sizeof *m->ins); m->capins = c;
}
static void ins_insert_n(M *m, size_t k, Ins *const *xs, size_t n)
{
    if (k > m->nins) k = m->nins;   /* list.insert past the end appends */
    ins_cap(m, m->nins + n);
    memmove(m->ins + k + n, m->ins + k, (m->nins - k) * sizeof *m->ins);
    memcpy(m->ins + k, xs, n * sizeof *m->ins); m->nins += n;
}
static void ins_insert(M *m, size_t k, Ins *x) { ins_insert_n(m, k, &x, 1); }
static void ins_replace(M *m, size_t a, size_t b, Ins *const *xs, size_t n)   /* m.ins[a:b] = xs */
{
    if (b < a) b = a;
    if (b > m->nins) b = m->nins;
    if (a > m->nins) a = m->nins;
    size_t tail = m->nins - b;
    ins_cap(m, a + n + tail);
    memmove(m->ins + a + n, m->ins + b, tail * sizeof *m->ins);
    memcpy(m->ins + a, xs, n * sizeof *m->ins); m->nins = a + n + tail;
}
static void ins_set(M *m, Ins *const *xs, size_t n) { ins_replace(m, 0, m->nins, xs, n); }

/* a growable list of instructions in the scratch arena */
typedef struct { Ins **v; size_t n, cap; } IV;
static void iv_push(M *m, IV *a, Ins *x)
{
    if (a->n == a->cap) { size_t c = a->cap ? 2 * a->cap : 64; Ins **v = talloc(m, c * sizeof *v); if (a->n) memcpy(v, a->v, a->n * sizeof *v); a->v = v; a->cap = c; }
    a->v[a->n++] = x;
}
typedef struct { uint32_t *v; size_t n, cap; } UV;
static void uv_push(M *m, UV *a, uint32_t x)
{
    if (a->n == a->cap) { size_t c = a->cap ? 2 * a->cap : 64; uint32_t *v = talloc(m, c * sizeof *v); if (a->n) memcpy(v, a->v, a->n * sizeof *v); a->v = v; a->cap = c; }
    a->v[a->n++] = x;
}
static int uv_has(const UV *a, uint32_t x) { for (size_t i = 0; i < a->n; i++) if (a->v[i] == x) return 1; return 0; }

/* ---- a hash map uint32 → uint32 in the scratch arena (keys of any value: literal words too); sets are maps whose value is unused */
typedef struct { uint32_t *k, *v; uint8_t *used; size_t cap, n; } HM;
static void hm_init(M *m, HM *h, size_t want)
{
    size_t c = 16; while (c < want * 2) c <<= 1;
    h->k = talloc(m, c * 4); h->v = talloc(m, c * 4); h->used = tcalloc(m, c); h->cap = c; h->n = 0;
}
static inline size_t hm_slot(const HM *h, uint32_t k)
{
    size_t i = (k * 2654435761u) & (h->cap - 1);
    while (h->used[i] && h->k[i] != k) i = (i + 1) & (h->cap - 1);
    return i;
}
static void hm_put(M *m, HM *h, uint32_t k, uint32_t v);
static void hm_grow(M *m, HM *h)
{
    HM g; hm_init(m, &g, h->cap);
    for (size_t i = 0; i < h->cap; i++) if (h->used[i]) hm_put(m, &g, h->k[i], h->v[i]);
    *h = g;
}
static void hm_put(M *m, HM *h, uint32_t k, uint32_t v)
{
    if ((h->n + 1) * 2 > h->cap) hm_grow(m, h);
    size_t i = hm_slot(h, k);
    if (!h->used[i]) { h->used[i] = 1; h->k[i] = k; h->n++; }
    h->v[i] = v;
}
static inline int hm_get(const HM *h, uint32_t k, uint32_t *v)
{
    if (!h->cap) return 0;
    size_t i = hm_slot(h, k);
    if (!h->used[i]) return 0;
    if (v) *v = h->v[i];
    return 1;
}
static inline int hm_has(const HM *h, uint32_t k) { return hm_get(h, k, NULL); }
/* ---- opcodes and enums (vk_android_spv.py) */
enum {
    OP_NAME = 5, OP_EXTENSION = 10, OP_EXT_INST_IMPORT = 11, OP_EXT_INST = 12, OP_MEMORY_MODEL = 14, OP_ENTRY_POINT = 15, OP_EXECUTION_MODE = 16, OP_CAPABILITY = 17,
    OP_TYPE_VOID = 19, OP_TYPE_BOOL = 20, OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23, OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLER = 26, OP_TYPE_SAMPLED_IMAGE = 27,
    OP_TYPE_ARRAY = 28, OP_TYPE_RUNTIME_ARRAY = 29, OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_TYPE_FUNCTION = 33,
    OP_CONSTANT_TRUE = 41, OP_CONSTANT_FALSE = 42, OP_CONSTANT = 43, OP_CONSTANT_COMPOSITE = 44, OP_CONSTANT_NULL = 46, OP_SPEC_CONSTANT_FALSE = 49, OP_SPEC_CONSTANT = 50,
    OP_FUNCTION = 54, OP_FUNCTION_PARAMETER = 55, OP_FUNCTION_END = 56, OP_FUNCTION_CALL = 57, OP_VARIABLE = 59, OP_IMAGE_TEXEL_POINTER = 60, OP_LOAD = 61, OP_STORE = 62,
    OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66, OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_VECTOR_SHUFFLE = 79,
    OP_COMPOSITE_EXTRACT = 81, OP_COMPOSITE_INSERT = 82, OP_COPY_OBJECT = 83, OP_SAMPLED_IMAGE = 86, OP_IMAGE_FETCH = 95, OP_IMAGE_READ = 98, OP_IMAGE_WRITE = 99,
    OP_IMAGE = 100, OP_CONVERT_U_TO_PTR = 120, OP_BITCAST = 124, OP_IADD = 128, OP_FMUL = 133, OP_SELECT = 169, OP_IEQUAL = 170,
    OP_FORD_EQUAL = 180, OP_FUNORD_NOT_EQUAL = 183, OP_FORD_LESS = 184, OP_FORD_GREATER = 186, OP_FORD_LESS_EQUAL = 188, OP_FORD_GREATER_EQUAL = 190,
    OP_EMIT_VERTEX = 218, OP_EMIT_STREAM_VERTEX = 220, OP_CONTROL_BARRIER = 224, OP_MEMORY_BARRIER = 225, OP_ATOMIC_STORE = 228,
    OP_LOOP_MERGE = 246, OP_SELECTION_MERGE = 247, OP_LABEL = 248, OP_BRANCH = 249, OP_BRANCH_CONDITIONAL = 250, OP_SWITCH = 251, OP_KILL = 252,
    OP_RETURN = 253, OP_RETURN_VALUE = 254, OP_GROUP_NON_UNIFORM_SHUFFLE = 345,
};
enum { DEC_SPEC_ID = 1, DEC_BUFFER_BLOCK = 3, DEC_ARRAY_STRIDE = 6, DEC_BUILTIN = 11, DEC_LOCATION = 30, DEC_BINDING = 33, DEC_SET = 34, DEC_NON_UNIFORM = 5300 };
enum { SC_UNIFORM_CONSTANT = 0, SC_INPUT = 1, SC_UNIFORM = 2, SC_OUTPUT = 3, SC_WORKGROUP = 4, SC_FUNCTION = 7, SC_STORAGE_BUFFER = 12 };
enum { VK_SAMPLER = 0, VK_COMBINED = 1, VK_SAMPLED_IMAGE = 2, VK_STORAGE_IMAGE = 3, VK_UNIFORM_TEXEL = 4, VK_STORAGE_TEXEL = 5, VK_UNIFORM_BUFFER = 6, VK_STORAGE_BUFFER = 7 };
#define NONE UINT32_MAX   /* Python's None where a value is an id (ids are below the bound) */
#define NO_RESULT(op) ((op) == OP_STORE || (op) == OP_CONTROL_BARRIER || (op) == OP_MEMORY_BARRIER || (op) == OP_RETURN || (op) == OP_RETURN_VALUE || \
    ((op) >= 246 && (op) <= 252) || (op) == OP_FUNCTION_END || (op) == 63 || (op) == 64 || (op) == OP_IMAGE_WRITE || ((op) >= 218 && (op) <= 221) || (op) == 228 || \
    (op) == 5294 || (op) == 5295)

/* ---- strings (string_at, lit_string) */
static size_t str_at(const Ins *x, size_t i, char *buf, size_t cap, size_t *next)   /* the bytes of a nul-terminated literal at word i (Python decodes them) */
{
    size_t n = 0;
    while (i < x->n) {
        uint32_t w = x->w[i++]; int end = 0;
        for (int b = 0; b < 4; b++) {
            char c = (char)(w >> (8 * b) & 0xff);
            if (!c) { end = 1; break; }
            if (n < cap) buf[n] = c;
            n++;
        }
        if (end) break;
    }
    if (next) *next = i;
    return n;
}
static int str_is(const Ins *x, size_t i, const char *s)
{
    char buf[64]; size_t n = str_at(x, i, buf, sizeof buf, NULL), l = strlen(s);
    return n == l && n <= sizeof buf && !memcmp(buf, s, l);
}
static size_t str_end(const Ins *x, size_t i) { size_t k; char b[1]; str_at(x, i, b, 0, &k); return k; }
static size_t lit_string(const char *s, uint32_t *out)   /* → words */
{
    size_t l = strlen(s) + 1, n = (l + 3) / 4;
    memset(out, 0, n * 4); memcpy(out, s, l - 1);
    return n;
}

/* ---- the index */
static void idx_grow(M *m, size_t need)
{
    if (need <= m->isz) return;
    size_t o = m->isz, n = need + need / 2 + 64;
#define G(p) do { m->p = xrealloc(m, m->p, n * sizeof *m->p); memset(m->p + o, 0, (n - o) * sizeof *m->p); } while (0)
    G(names); G(types); G(consts); G(var_ptr); G(var_sc); G(var_has); G(deco_head); G(deco_tail); G(deco_has);
#undef G
    m->isz = n;
}
static void ord_push(M *m, uint32_t **v, size_t *n, size_t *c, uint32_t id)
{
    if (*n == *c) { *c = *c ? 2 * *c : 256; *v = xrealloc(m, *v, *c * sizeof **v); }
    (*v)[(*n)++] = id;
}
static inline Ins *TY(M *m, uint32_t id) { return id < m->isz ? m->types[id] : NULL; }
static inline Ins *CO(M *m, uint32_t id) { return id < m->isz ? m->consts[id] : NULL; }
static inline int HAS_VAR(M *m, uint32_t id) { return id < m->isz && m->var_has[id]; }
static void set_type(M *m, uint32_t id, Ins *x) { idx_grow(m, (size_t)id + 1); if (!m->types[id]) ord_push(m, &m->ord_types, &m->n_types, &m->c_types, id); m->types[id] = x; }
static void set_const(M *m, uint32_t id, Ins *x) { idx_grow(m, (size_t)id + 1); if (!m->consts[id]) ord_push(m, &m->ord_consts, &m->n_consts, &m->c_consts, id); m->consts[id] = x; }
static void set_var(M *m, uint32_t id, uint32_t ptr, uint32_t sc)
{
    idx_grow(m, (size_t)id + 1);
    if (!m->var_has[id]) { ord_push(m, &m->ord_vars, &m->n_vars, &m->c_vars, id); m->var_has[id] = 1; }
    m->var_ptr[id] = ptr; m->var_sc[id] = sc;
}
static DecoEnt *deco_find(M *m, uint32_t id, uint32_t deco)
{
    if (id >= m->isz || !m->deco_has[id]) return NULL;
    for (uint32_t e = m->deco_head[id]; e; e = m->dent[e].next) if (m->dent[e].deco == deco) return &m->dent[e];
    return NULL;
}
static int deco_present(M *m, uint32_t id) { return id < m->isz && m->deco_has[id]; }
static void deco_touch(M *m, uint32_t id)   /* decos.setdefault(id, {}) */
{
    idx_grow(m, (size_t)id + 1);
    if (!m->deco_has[id]) { m->deco_has[id] = 1; m->deco_head[id] = m->deco_tail[id] = 0; ord_push(m, &m->ord_decos, &m->n_decos, &m->c_decos, id); }
}
static void deco_set(M *m, uint32_t id, uint32_t deco, const uint32_t *ops, size_t n)   /* decos.setdefault(id, {})[deco] = list(ops) */
{
    deco_touch(m, id);
    if (m->ndw + n > m->capdw) { m->capdw = (m->capdw + n) * 2 + 256; m->dwords = xrealloc(m, m->dwords, m->capdw * 4); }
    uint32_t off = (uint32_t)m->ndw; memcpy(m->dwords + off, ops, n * 4); m->ndw += n;
    DecoEnt *e = deco_find(m, id, deco);
    if (e) { e->ops = off; e->nops = (uint32_t)n; return; }
    if (m->ndent + 1 >= m->capdent) { m->capdent = m->capdent * 2 + 256; m->dent = xrealloc(m, m->dent, m->capdent * sizeof *m->dent); }
    if (!m->ndent) m->ndent = 1;   /* entry 0 is the list end */
    uint32_t k = (uint32_t)m->ndent++; m->dent[k] = (DecoEnt){deco, (uint32_t)n, off, 0};
    if (m->deco_tail[id]) m->dent[m->deco_tail[id]].next = k; else m->deco_head[id] = k;
    m->deco_tail[id] = k;
}
static void deco_reset_one(M *m, uint32_t id)   /* decos[id] = {} (keeps its place) */
{
    deco_touch(m, id); m->deco_head[id] = m->deco_tail[id] = 0;
}
static uint32_t deco_op0(M *m, DecoEnt *e) { if (!e->nops) FAIL(m); return m->dwords[e->ops]; }   /* d[k][0] */
static int deco_is1(M *m, DecoEnt *e, uint32_t v) { return e && e->nops == 1 && m->dwords[e->ops] == v; }   /* d.get(k) == [v] */

static void add_cap_list(M *m, uint32_t c) { if (m->ncaps == m->capcaps) { m->capcaps = m->capcaps * 2 + 16; m->caps = xrealloc(m, m->caps, m->capcaps * 4); } m->caps[m->ncaps++] = c; }
static int has_cap(M *m, uint32_t c) { for (size_t i = 0; i < m->ncaps; i++) if (m->caps[i] == c) return 1; return 0; }

static void index_(M *m)
{
    size_t bound = m->header[3];
    if (bound > (1u << 28)) FAIL(m);
    idx_grow(m, bound + 1);
    memset(m->names, 0, m->isz * sizeof *m->names); memset(m->types, 0, m->isz * sizeof *m->types); memset(m->consts, 0, m->isz * sizeof *m->consts);
    memset(m->var_has, 0, m->isz); memset(m->deco_has, 0, m->isz);
    m->n_types = m->n_consts = m->n_vars = m->n_decos = 0; m->ndent = 1; m->ndw = 0; m->ncaps = 0; m->nexts = 0; m->entry = NULL; m->nmodes = 0;
    if (!m->dent) { m->capdent = 1024; m->dent = xrealloc(m, NULL, m->capdent * sizeof *m->dent); }
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_NAME) { uint32_t id = AT(m, x, 1); if (id >= bound) FAIL(m); m->names[id] = x; }
        else if (op == OP_DECORATE) { uint32_t id = AT(m, x, 1), d = AT(m, x, 2); if (id >= bound) FAIL(m); deco_set(m, id, d, x->w + 3, x->n - 3); }
        else if (op == OP_CAPABILITY) add_cap_list(m, AT(m, x, 1));
        else if (op == OP_EXTENSION) { if (m->nexts == m->capexts) { m->capexts = m->capexts * 2 + 8; m->exts = xrealloc(m, m->exts, m->capexts * sizeof *m->exts); } m->exts[m->nexts++] = x; }
        else if (op == OP_ENTRY_POINT) m->entry = x;
        else if (op == OP_EXECUTION_MODE) {
            if (m->nmodes == m->capmodes) { m->capmodes = m->capmodes * 2 + 8; m->modes = xrealloc(m, m->modes, m->capmodes * sizeof *m->modes); m->modes_n = xrealloc(m, m->modes_n, m->capmodes * 4); }
            size_t n = x->n > 2 ? x->n - 2 : 0; uint32_t *c = palloc(m, n * 4 + 4); memcpy(c, x->w + 2, n * 4); m->modes[m->nmodes] = c; m->modes_n[m->nmodes++] = (uint32_t)n;
        }
        else if (op >= 19 && op <= 39) { uint32_t id = AT(m, x, 1); if (id >= bound) FAIL(m); set_type(m, id, x); }
        else if (op == OP_CONSTANT) { uint32_t id = AT(m, x, 2); AT(m, x, 1); if (id >= bound) FAIL(m); set_const(m, id, x); }
        else if (op == OP_VARIABLE && x->n >= 4) { uint32_t id = x->w[2]; if (id >= bound) FAIL(m); set_var(m, id, x->w[1], x->w[3]); }
    }
}

/* ---- Module's methods */
static uint32_t new_id(M *m) { uint32_t i = m->header[3]; m->header[3] = i + 1; if (m->header[3] > (1u << 28)) FAIL(m); return i; }
static int op_of(M *m, uint32_t id) { Ins *t = TY(m, id); return t ? (int)OPC(t) : -1; }   /* Module.op: None = -1 */
static void pointee(M *m, uint32_t ptr, uint32_t *sc, uint32_t *t) { Ins *x = TY(m, ptr); if (!x) FAIL(m); uint32_t a = AT(m, x, 2), b = AT(m, x, 3); if (sc) *sc = a; if (t) *t = b; }
static int int_type(M *m, uint32_t t, uint32_t *w, uint32_t *s)
{
    Ins *x = TY(m, t);
    if (!x || OPC(x) != OP_TYPE_INT) return 0;
    uint32_t a = AT(m, x, 2), b = AT(m, x, 3); if (w) *w = a; if (s) *s = b; return 1;
}
static uint32_t scalar_of(M *m, uint32_t t) { return op_of(m, t) == OP_TYPE_VECTOR ? AT(m, m->types[t], 2) : t; }
static uint32_t strip_arrays(M *m, uint32_t t, uint64_t *count)
{
    uint64_t c = 1;
    for (;;) {
        int o = op_of(m, t);
        if (o != OP_TYPE_ARRAY && o != OP_TYPE_RUNTIME_ARRAY) break;
        Ins *x = m->types[t];
        if (o == OP_TYPE_RUNTIME_ARRAY) { c = 0; t = AT(m, x, 2); continue; }
        Ins *k = CO(m, AT(m, x, 3)); uint64_t n = k ? AT(m, k, 3) : 0;
        if (n && c > (UINT64_C(1) << 62) / n) FAIL(m);
        c *= n; t = AT(m, x, 2);
    }
    if (count) *count = c;
    return t;
}
static uint32_t find_type(M *m, uint32_t op, const uint32_t *ops, size_t n)
{
    for (size_t i = 0; i < m->n_types; i++) {
        uint32_t id = m->ord_types[i]; Ins *x = m->types[id];
        if (OPC(x) == op && x->n == n + 2 && !memcmp(x->w + 2, ops, n * 4)) return id;
    }
    return NONE;
}
static uint32_t find_type_v(M *m, uint32_t op, int n, ...)
{
    uint32_t ops[8]; va_list ap; va_start(ap, n); for (int i = 0; i < n; i++) ops[i] = va_arg(ap, uint32_t); va_end(ap);
    return find_type(m, op, ops, (size_t)n);
}
static size_t first_op(M *m, uint32_t op)   /* next(k for k, y in enumerate(m.ins) if op): StopIteration → fallback */
{
    for (size_t k = 0; k < m->nins; k++) if (OPC(m->ins[k]) == op) return k;
    FAIL(m);
}
static void insert_decl(M *m, Ins *x) { ins_insert(m, first_op(m, OP_FUNCTION), x); }
static uint32_t add_type(M *m, uint32_t op, const uint32_t *ops, size_t n)
{
    for (size_t i = 0; i < n; i++) if (ops[i] == NONE) FAIL(m);   /* (a None operand: the Python's module would not serialise) */
    uint32_t i = find_type(m, op, ops, n);
    if (i != NONE) return i;
    i = new_id(m); Ins *x = ins_new(m, 2 + n); x->w[0] = (uint32_t)(2 + n) << 16 | op; x->w[1] = i; memcpy(x->w + 2, ops, n * 4);
    insert_decl(m, x); set_type(m, i, x);
    return i;
}
static uint32_t add_type_v(M *m, uint32_t op, int n, ...)
{
    uint32_t ops[8]; va_list ap; va_start(ap, n); for (int i = 0; i < n; i++) ops[i] = va_arg(ap, uint32_t); va_end(ap);
    return add_type(m, op, ops, (size_t)n);
}
static uint32_t add_const(M *m, uint32_t ty, uint32_t value)
{
    if (ty == NONE) FAIL(m);
    for (size_t i = 0; i < m->n_consts; i++) {
        uint32_t id = m->ord_consts[i]; Ins *x = m->consts[id];
        if (x->w[1] == ty && x->n == 4 && x->w[3] == value) return id;
    }
    uint32_t i = new_id(m); Ins *x = mk(m, 4, 4u << 16 | OP_CONSTANT, ty, i, value);
    insert_decl(m, x); set_const(m, i, x);
    return i;
}
static size_t interface(M *m, uint32_t **out)   /* entry[k:] after its name */
{
    if (!m->entry) FAIL(m);
    size_t k = str_end(m->entry, 3);
    *out = m->entry->w + (k < m->entry->n ? k : m->entry->n);
    return k < m->entry->n ? m->entry->n - k : 0;
}
static uint32_t entry_function(M *m) { if (!m->entry) FAIL(m); return AT(m, m->entry, 2); }
static void function_range(M *m, uint32_t fid, size_t *a, size_t *e)
{
    size_t k = 0;
    for (; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_FUNCTION && AT(m, x, 2) == fid) break; }
    if (k == m->nins) FAIL(m);
    *a = k;
    for (; k < m->nins; k++) if (OPC(m->ins[k]) == OP_FUNCTION_END) { *e = k; return; }
    FAIL(m);
}

/* io(sc): Location-decorated Input / Output variables of the entry point, in the dictionary's order (a later variable of a location replaces the value, not the place) */
typedef struct { uint32_t loc, var, type; } IO;
static size_t io(M *m, uint32_t sc, IO **out)
{
    uint32_t *itf; size_t n = interface(m, &itf), k = 0;
    IO *r = talloc(m, (n + 1) * sizeof *r);
    for (size_t i = 0; i < n; i++) {
        uint32_t vid = itf[i];
        if (!HAS_VAR(m, vid) || m->var_sc[vid] != sc) continue;
        DecoEnt *d = deco_find(m, vid, DEC_LOCATION);
        if (!d) continue;
        uint32_t loc = deco_op0(m, d), t; pointee(m, m->var_ptr[vid], NULL, &t);
        size_t j = 0; while (j < k && r[j].loc != loc) j++;
        r[j] = (IO){loc, vid, t}; if (j == k) k++;
    }
    *out = r; return k;
}
static int io_find(IO *v, size_t n, uint32_t loc) { for (size_t i = 0; i < n; i++) if (v[i].loc == loc) return (int)i; return -1; }

static void builtin_var(M *m, uint32_t sc, uint32_t builtin, uint32_t *var, uint32_t *member)
{
    uint32_t *itf; size_t n = interface(m, &itf);
    for (size_t i = 0; i < n; i++) {
        uint32_t vid = itf[i];
        if (!HAS_VAR(m, vid) || m->var_sc[vid] != sc) continue;
        if (deco_is1(m, deco_find(m, vid, DEC_BUILTIN), builtin)) { *var = vid; *member = NONE; return; }
        uint32_t t; pointee(m, m->var_ptr[vid], NULL, &t);
        for (size_t k = 0; k < m->nins; k++) {
            Ins *x = m->ins[k];
            if (OPC(x) == OP_MEMBER_DECORATE && AT(m, x, 1) == t && AT(m, x, 3) == DEC_BUILTIN && AT(m, x, 4) == builtin) { *var = vid; *member = AT(m, x, 2); return; }
        }
    }
    *var = *member = NONE;
}

/* ---- parse and serialise */
static M *m_alloc(void) { M *m = calloc(1, sizeof *m); return m; }
static void m_free(M *m)
{
    if (!m) return;
    arena_free(&m->perm); arena_free(&m->tmp);
    free(m->ins); free(m->names); free(m->types); free(m->consts); free(m->var_ptr); free(m->var_sc); free(m->var_has); free(m->deco_head); free(m->deco_tail);
    free(m->deco_has); free(m->dent); free(m->dwords); free(m->ord_types); free(m->ord_consts); free(m->ord_vars); free(m->ord_decos); free(m->caps); free(m->exts);
    free(m->modes); free(m->modes_n); free(m->res); free(m->out); free(m);
}
static int parse(M *m, const uint8_t *data, size_t len)   /* Module.__init__: 0, or -1 (the Python raises ValueError) */
{
    if (len % 4 || len < 20) return -1;
    size_t nw = len / 4; uint32_t *w = palloc(m, len); memcpy(w, data, len);
    if (w[0] != 0x07230203u) return -1;
    memcpy(m->header, w, 20);
    size_t i = 5, cnt = 0;
    while (i < nw) { uint32_t n = w[i] >> 16; if (!n || i + n > nw) return -1; i += n; cnt++; }
    ins_cap(m, cnt + 16);
    Ins *xs = palloc(m, cnt * sizeof *xs); i = 5;
    for (size_t k = 0; k < cnt; k++) { uint32_t n = w[i] >> 16; xs[k] = (Ins){n, n, w + i}; m->ins[k] = &xs[k]; i += n; }
    m->nins = cnt;
    return 0;
}
static size_t data_(M *m)   /* Module.data() into m->out; → bytes */
{
    size_t nw = 5; for (size_t k = 0; k < m->nins; k++) nw += m->ins[k]->n;
    if (nw * 4 > m->capout) { m->capout = nw * 4 + 4096; free(m->out); m->out = malloc(m->capout); if (!m->out) FAIL(m); }
    uint32_t *o = (uint32_t *)m->out; memcpy(o, m->header, 20); o += 5;
    for (size_t k = 0; k < m->nins; k++) { memcpy(o, m->ins[k]->w, m->ins[k]->n * 4); o += m->ins[k]->n; }
    return nw * 4;
}

/* ================================================================================================ the passes */

static uint32_t ensure_glsl(M *m)
{
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_EXT_INST_IMPORT && str_is(x, 2, "GLSL.std.450")) return AT(m, x, 1); }
    uint32_t i = new_id(m), s[8]; size_t n = lit_string("GLSL.std.450", s);
    Ins *x = ins_new(m, 2 + n); x->w[0] = (uint32_t)(2 + n) << 16 | OP_EXT_INST_IMPORT; x->w[1] = i; memcpy(x->w + 2, s, n * 4);
    ins_insert(m, first_op(m, OP_MEMORY_MODEL), x);
    return i;
}

static int in_set(uint32_t v, const uint32_t *s, size_t n) { for (size_t i = 0; i < n; i++) if (s[i] == v) return 1; return 0; }
static const uint32_t SUBGROUP_BUILTINS[] = {36, 37, 40, 41, 4416, 4417, 4418, 4419, 4420};
static const uint32_t CAP_SUBGROUP[] = {61, 62, 63, 64, 65, 66, 67, 68, 4423, 4431};
static const uint32_t CAP_INTERLOCK[] = {5363, 5372, 5378};
static const uint32_t INTERLOCK_MODES[] = {5366, 5367, 5368, 5369, 5370, 5371};
static int is_subgroup_op(uint32_t op) { return (op >= 333 && op < 367) || op == 4421 || op == 4422 || op == 4428 || op == 4429 || op == 4430 || op == 4432; }

static int subgroup_used(M *m)
{
    for (size_t k = 0; k < m->nins; k++) if (is_subgroup_op(OPC(m->ins[k]))) return 1;
    for (size_t i = 0; i < m->n_decos; i++) {
        DecoEnt *d = deco_find(m, m->ord_decos[i], DEC_BUILTIN);
        if (d && in_set(deco_op0(m, d), SUBGROUP_BUILTINS, 9)) return 1;
    }
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) == OP_MEMBER_DECORATE && AT(m, x, 3) == DEC_BUILTIN && in_set(AT(m, x, 4), SUBGROUP_BUILTINS, 9)) return 1;
    }
    return 0;
}

static int64_t strip_unused_caps(M *m)   /* res: the capabilities dropped */
{
    int f64 = 0, ilk = 0;
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_TYPE_FLOAT && AT(m, x, 2) == 64) { f64 = 1; break; } }
    for (size_t k = 0; k < m->nins; k++) { uint32_t op = OPC(m->ins[k]); if (op == 5364 || op == 5365) { ilk = 1; break; } }
    uint32_t drop[16]; size_t nd = 0;
    if (!f64) drop[nd++] = 10;
    if (!ilk) for (int i = 0; i < 3; i++) drop[nd++] = CAP_INTERLOCK[i];
    if (!subgroup_used(m)) for (int i = 0; i < 10; i++) drop[nd++] = CAP_SUBGROUP[i];
    uint32_t keep[16]; size_t nk = 0;
    for (size_t i = 0; i < nd; i++) if (has_cap(m, drop[i])) keep[nk++] = drop[i];
    int ilk_drop = 0; for (size_t i = 0; i < nk; i++) if (in_set(keep[i], CAP_INTERLOCK, 3)) ilk_drop = 1;
    size_t o = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_CAPABILITY && in_set(AT(m, x, 1), keep, nk)) continue;
        if (op == OP_EXTENSION && ilk_drop && str_is(x, 1, "SPV_EXT_fragment_shader_interlock")) continue;
        if (op == OP_EXECUTION_MODE && ilk_drop && in_set(AT(m, x, 2), INTERLOCK_MODES, 6)) continue;
        m->ins[o++] = x;
    }
    m->nins = o; index_(m);
    res_clear(m); for (size_t i = 0; i < nk; i++) res_push(m, keep[i]);
    return (int64_t)nk;
}

static int64_t precise_minmax(M *m)
{
    UV glsl = {0};
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_EXT_INST_IMPORT && str_is(x, 2, "GLSL.std.450")) uv_push(m, &glsl, AT(m, x, 1)); }
    int64_t n = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) != OP_EXT_INST || !uv_has(&glsl, AT(m, x, 3))) continue;
        uint32_t f = AT(m, x, 4), to = f == 37 ? 79 : f == 40 ? 80 : f == 43 ? 81 : 0;
        if (!to) continue;
        uint32_t t = AT(m, x, 1);
        if (op_of(m, t) != OP_TYPE_INT && op_of(m, scalar_of(m, t)) == OP_TYPE_FLOAT) { x->w[4] = to; n++; }
    }
    return n;
}

static size_t last_op(M *m, uint32_t op)   /* max(k for k, y in enumerate(m.ins) if op): ValueError → fallback */
{
    for (size_t k = m->nins; k-- > 0;) if (OPC(m->ins[k]) == op) return k;
    FAIL(m);
}

static int64_t formatless_storage_caps(M *m)
{
    HM imgs = {0}; hm_init(m, &imgs, 16);
    for (size_t i = 0; i < m->n_types; i++) {
        Ins *x = m->types[m->ord_types[i]];
        if (OPC(x) == OP_TYPE_IMAGE && AT(m, x, 7) == 2 && AT(m, x, 8) == 0) hm_put(m, &imgs, m->ord_types[i], 1);
    }
    res_clear(m);
    if (!imgs.n) return 0;
    HM typed = {0}; hm_init(m, &typed, 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_LOAD && hm_has(&imgs, AT(m, x, 1))) hm_put(m, &typed, AT(m, x, 2), 1); }
    int r = 0, w = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_IMAGE_READ && hm_has(&typed, AT(m, x, 3))) r = 1;
        else if (op == OP_IMAGE_WRITE && hm_has(&typed, AT(m, x, 1))) w = 1;
    }
    if (r && has_cap(m, 55)) r = 0;
    if (w && has_cap(m, 56)) w = 0;
    if (!r && !w) return 0;
    size_t at = last_op(m, OP_CAPABILITY) + 1;
    if (r) ins_insert(m, at, mk(m, 2, 2u << 16 | OP_CAPABILITY, 55u));   /* each inserted at `at`, in sorted order: the last ends first */
    if (w) ins_insert(m, at, mk(m, 2, 2u << 16 | OP_CAPABILITY, 56u));
    index_(m);
    if (r) res_push(m, 55);
    if (w) res_push(m, 56);
    return r + w;
}

static int64_t extended_format_caps(M *m)
{
    res_clear(m);
    if (has_cap(m, 49)) return 0;
    static const uint32_t base[] = {0, 1, 2, 3, 4, 5, 21, 22, 23, 24, 30, 31, 32, 33};
    int any = 0;
    for (size_t i = 0; i < m->n_types && !any; i++) {
        Ins *x = m->types[m->ord_types[i]];
        if (OPC(x) == OP_TYPE_IMAGE) { uint32_t f = AT(m, x, 8); if (!in_set(f, base, 14) && f < 40) any = 1; }
    }
    if (!any) return 0;
    ins_insert(m, last_op(m, OP_CAPABILITY) + 1, mk(m, 2, 2u << 16 | OP_CAPABILITY, 49u));
    index_(m);
    res_push(m, 49);
    return 1;
}

static int64_t workgroup_plain_arrays(M *m)
{
    int64_t done = 0; HM made = {0}; hm_init(m, &made, 16);
    size_t nv = m->n_vars; uint32_t *vs = talloc(m, (nv + 1) * 4), *ptrs = talloc(m, (nv + 1) * 4), *scs = talloc(m, (nv + 1) * 4);
    for (size_t i = 0; i < nv; i++) { uint32_t v = m->ord_vars[i]; vs[i] = v; ptrs[i] = m->var_ptr[v]; scs[i] = m->var_sc[v]; }
    for (size_t i = 0; i < nv; i++) {
        if (scs[i] != SC_WORKGROUP) continue;
        uint32_t t; pointee(m, ptrs[i], NULL, &t);
        if (op_of(m, t) != OP_TYPE_ARRAY || !deco_find(m, t, DEC_ARRAY_STRIDE)) continue;
        size_t k = 0;
        for (; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_VARIABLE && AT(m, x, 2) == vs[i]) break; }
        if (k == m->nins) FAIL(m);
        uint32_t p;
        if (!hm_get(&made, t, &p)) {
            uint32_t arr = new_id(m); p = new_id(m);
            Ins *tt = m->types[t]; size_t nn = tt->n > 4 ? 2 : (tt->n > 2 ? tt->n - 2 : 0);
            Ins *a = ins_new(m, 2 + nn); a->w[0] = 4u << 16 | OP_TYPE_ARRAY; a->w[1] = arr; memcpy(a->w + 2, tt->w + 2, nn * 4);
            Ins *two[2] = {a, mk(m, 4, 4u << 16 | OP_TYPE_POINTER, p, scs[i], arr)};
            ins_insert_n(m, k, two, 2); hm_put(m, &made, t, p); k += 2;
        }
        SET(m, m->ins[k], 1, p); done++;
    }
    if (done) index_(m);
    return done;
}

/* maybe_ids(x): the words that may be ids; *n words at out (a scratch array) */
static size_t maybe_ids(M *m, const Ins *x, uint32_t **out)
{
    uint32_t op = OPC(x); uint32_t *r = talloc(m, (x->n + 2) * 4); size_t n = 0;
#define PUT(i) (r[n++] = x->w[i])
    if (op == OP_EXT_INST) { r[n++] = AT(m, x, 1); r[n++] = AT(m, x, 3); for (size_t i = 5; i < x->n; i++) PUT(i); }
    else if (op == OP_COMPOSITE_EXTRACT) { for (size_t i = 1; i < 4 && i < x->n; i++) PUT(i); }
    else if (op == OP_COMPOSITE_INSERT || op == OP_VECTOR_SHUFFLE) { for (size_t i = 1; i < 5 && i < x->n; i++) PUT(i); }
    else if (op == OP_SWITCH) { r[n++] = AT(m, x, 1); if (x->n > 2) PUT(2); for (size_t i = 4; i < x->n; i += 2) PUT(i); }
    else {
        int mask_at = -1;
        switch (op) { case 87: case 88: case 91: case 92: case 95: case 98: mask_at = 5; break; case 89: case 90: case 93: case 94: case 96: case 97: mask_at = 6; break; case 99: mask_at = 4; break; }
        if (mask_at >= 0 && x->n > (uint32_t)mask_at) { for (size_t i = 1; i < (size_t)mask_at; i++) PUT(i); for (size_t i = (size_t)mask_at + 1; i < x->n; i++) PUT(i); }
        else for (size_t i = 1; i < x->n; i++) PUT(i);
    }
#undef PUT
    *out = r; return n;
}

/* value_uses(m): id → [(instruction, operand position)], in instruction order */
typedef struct { Ins *x; uint32_t p; uint32_t next; } Use;
typedef struct { HM head, tail; Use *u; size_t n, cap; } Uses;
static void uses_add(M *m, Uses *U, uint32_t v, Ins *x, uint32_t p)
{
    if (U->n == U->cap) { size_t c = U->cap * 2 + 1024; Use *nu = talloc(m, c * sizeof *nu); if (U->n) memcpy(nu, U->u, U->n * sizeof *nu); U->u = nu; U->cap = c; }
    uint32_t k = (uint32_t)U->n++; U->u[k] = (Use){x, p, 0};
    uint32_t t;
    if (hm_get(&U->tail, v, &t)) U->u[t].next = k; else hm_put(m, &U->head, v, k);
    hm_put(m, &U->tail, v, k);
}
#define USES_FOR(U, v, it) for (uint32_t _h, it = hm_get(&(U)->head, (v), &_h) ? _h : UINT32_MAX; it != UINT32_MAX; it = (U)->u[it].next ? (U)->u[it].next : UINT32_MAX)
static void value_uses(M *m, Uses *U)
{
    memset(U, 0, sizeof *U); hm_init(m, &U->head, m->header[3] + 64); hm_init(m, &U->tail, m->header[3] + 64);
    U->cap = m->nins * 3 + 1024; U->u = talloc(m, U->cap * sizeof *U->u); U->n = 1;   /* entry 0 unused (next 0 = end) */
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_LOAD) uses_add(m, U, AT(m, x, 3), x, 3);
        else if (op == OP_STORE) { uint32_t a = AT(m, x, 1), b = AT(m, x, 2); uses_add(m, U, a, x, 1); uses_add(m, U, b, x, 2); }
        else if (op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) { for (uint32_t p = 3; p < x->n; p++) uses_add(m, U, x->w[p], x, p); }
        else if (op == OP_COMPOSITE_EXTRACT || op == OP_COPY_OBJECT || op == OP_BITCAST) uses_add(m, U, AT(m, x, 3), x, 3);
        else if (op == OP_ENTRY_POINT || op == OP_NAME || op == OP_DECORATE || op == OP_MEMBER_DECORATE) continue;
        else for (uint32_t p = 1; p < x->n; p++) uses_add(m, U, x->w[p], x, p);
    }
}

static int defined_id(const Ins *x, uint32_t *id)
{
    uint32_t op = OPC(x);
    if ((op >= 19 && op <= 39) || op == OP_LABEL || op == OP_EXT_INST_IMPORT) { if (x->n < 2) return -1; *id = x->w[1]; return 1; }
    if (op == OP_LOAD || op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_COMPOSITE_EXTRACT || op == OP_COPY_OBJECT || op == OP_BITCAST || op == OP_VARIABLE ||
        op == OP_CONSTANT || op == OP_CONSTANT_COMPOSITE) { if (x->n < 3) return -1; *id = x->w[2]; return 1; }
    return 0;
}

static int64_t int_outputs(M *m)   /* res: the locations */
{
    Uses U; value_uses(m, &U);
    HM defs = {0}; hm_init(m, &defs, m->header[3] + 64);   /* id → instruction index (the last definition) */
    for (size_t k = 0; k < m->nins; k++) { uint32_t d; int r = defined_id(m->ins[k], &d); if (r < 0) FAIL(m); if (r) hm_put(m, &defs, d, (uint32_t)k); }
    IO *o; size_t no = io(m, SC_OUTPUT, &o);
    res_clear(m);
    for (size_t i = 0; i < no; i++) {
        UV ptrs = {0}; uv_push(m, &ptrs, o[i].var);
        USES_FOR(&U, o[i].var, it) { Ins *x = U.u[it].x; uint32_t op = OPC(x); if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && U.u[it].p == 3) uv_push(m, &ptrs, AT(m, x, 2)); }
        int found = 0;
        for (size_t j = 0; j < ptrs.n && !found; j++) {
            USES_FOR(&U, ptrs.v[j], it) {
                Ins *x = U.u[it].x;
                if (OPC(x) == OP_STORE && U.u[it].p == 1) {
                    uint32_t k;
                    if (hm_get(&defs, AT(m, x, 2), &k) && OPC(m->ins[k]) == OP_BITCAST) { found = 1; break; }
                }
            }
        }
        if (found) res_push(m, o[i].loc);
    }
    return (int64_t)m->nres;
}

/* integer_only_inputs: int_bits with its shared `seen` set */
typedef struct { M *m; Uses *U; } IOI;
static int int_bits(IOI *c, uint32_t val, HM *seen, int depth)
{
    M *m = c->m;
    if (depth > 900) FAIL(m);   /* (Python's recursion limit) */
    if (hm_has(seen, val)) return 1;
    hm_put(m, seen, val, 1);
    uint32_t h;
    if (!hm_get(&c->U->head, val, &h)) return 0;
    USES_FOR(c->U, val, it) {
        Ins *x = c->U->u[it].x; uint32_t p = c->U->u[it].p, op = OPC(x);
        if (op == OP_BITCAST && int_type(m, scalar_of(m, AT(m, x, 1)), NULL, NULL)) continue;
        if ((op == OP_COPY_OBJECT || op == OP_COMPOSITE_EXTRACT) && p == 3) { if (!int_bits(c, AT(m, x, 2), seen, depth + 1)) return 0; continue; }
        if (op == OP_STORE && p == 2) {
            uint32_t var = AT(m, x, 1);
            if (!HAS_VAR(m, var) || m->var_sc[var] != SC_FUNCTION) return 0;
            UV loads = {0};
            USES_FOR(c->U, var, jt) { Ins *y = c->U->u[jt].x; if (OPC(y) == OP_LOAD && c->U->u[jt].p == 3) uv_push(m, &loads, AT(m, y, 2)); }
            USES_FOR(c->U, var, jt) { uint32_t yo = OPC(c->U->u[jt].x); if (yo != OP_LOAD && yo != OP_STORE) return 0; }
            for (size_t i = 0; i < loads.n; i++) if (!int_bits(c, loads.v[i], seen, depth + 1)) return 0;
            continue;
        }
        return 0;
    }
    return 1;
}
static int64_t integer_only_inputs(M *m)   /* res: the locations */
{
    Uses U; value_uses(m, &U); IOI c = {m, &U};
    IO *in; size_t ni = io(m, SC_INPUT, &in);
    res_clear(m);
    for (size_t i = 0; i < ni; i++) {
        if (int_type(m, scalar_of(m, in[i].type), NULL, NULL)) continue;
        UV vals = {0}; int ok = 1;
        USES_FOR(&U, in[i].var, it) {
            Ins *x = U.u[it].x; uint32_t p = U.u[it].p, op = OPC(x);
            if (op == OP_LOAD && p == 3) uv_push(m, &vals, AT(m, x, 2));
            else if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && p == 3) {
                USES_FOR(&U, AT(m, x, 2), jt) { Ins *y = U.u[jt].x; if (OPC(y) == OP_LOAD && U.u[jt].p == 3) uv_push(m, &vals, AT(m, y, 2)); else ok = 0; }
            } else ok = 0;
        }
        if (!ok || !vals.n) continue;
        int all = 1;
        for (size_t j = 0; j < vals.n && all; j++) { HM seen = {0}; hm_init(m, &seen, 64); if (!int_bits(&c, vals.v[j], &seen, 0)) all = 0; }
        if (all) res_push(m, in[i].loc);
    }
    return (int64_t)m->nres;
}

/* _float_vec: (components, float32 type) or 0 */
static int float_vec(M *m, uint32_t t, uint32_t *comps, uint32_t *f32)
{
    uint32_t s = scalar_of(m, t);
    if (op_of(m, s) != OP_TYPE_FLOAT || AT(m, m->types[s], 2) != 32) return 0;
    *comps = op_of(m, t) == OP_TYPE_VECTOR ? AT(m, m->types[t], 3) : 1; *f32 = s;
    return 1;
}

static int64_t const_written_outputs(M *m)   /* res: the locations */
{
    HM consts = {0}; hm_init(m, &consts, 1024);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; uint32_t op = OPC(x); if (op == OP_CONSTANT || op == OP_CONSTANT_COMPOSITE || op == OP_CONSTANT_NULL) hm_put(m, &consts, AT(m, x, 2), 1); }
    IO *o; size_t no = io(m, SC_OUTPUT, &o);
    HM ptrs = {0}; hm_init(m, &ptrs, 64);
    for (size_t i = 0; i < no; i++) hm_put(m, &ptrs, o[i].var, o[i].loc);
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x), l;
        if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && hm_get(&ptrs, AT(m, x, 3), &l)) hm_put(m, &ptrs, AT(m, x, 2), l);
    }
    UV locs = {0}; int all = 1;   /* stored: the locations in first-store order, and whether every store writes a constant */
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t l;
        if (OPC(x) == OP_STORE && hm_get(&ptrs, AT(m, x, 1), &l)) {
            if (!uv_has(&locs, l)) uv_push(m, &locs, l);
            if (!hm_has(&consts, AT(m, x, 2))) all = 0;
        }
    }
    res_clear(m);
    if (locs.n && all) for (size_t i = 0; i < locs.n; i++) res_push(m, locs.v[i]);
    return (int64_t)m->nres;
}

static int64_t retype_outputs(M *m, const int64_t *locs_in, size_t nlocs)   /* locs: sorted by the caller; res: the locations retyped */
{
    IO *o; size_t no = io(m, SC_OUTPUT, &o);
    uint32_t u32 = add_type_v(m, OP_TYPE_INT, 2, 32u, 0u);
    uint32_t wv[64], wl[64], wn[64]; size_t nw = 0;   /* want: var → (loc, type, components), in sorted location order */
    for (size_t i = 0; i < nlocs; i++) {
        if (locs_in[i] < 0 || locs_in[i] > UINT32_MAX) FAIL(m);
        int j = io_find(o, no, (uint32_t)locs_in[i]);
        if (j < 0) continue;
        uint32_t c, f;
        if (float_vec(m, o[j].type, &c, &f)) {
            size_t q = 0; while (q < nw && wv[q] != o[j].var) q++;
            if (q == 64) FAIL(m);
            wv[q] = o[j].var; wl[q] = o[j].loc; wn[q] = c; if (q == nw) nw++;
        }
    }
    res_clear(m);
    if (!nw) return 0;
#define UTYPE(n) ((n) == 1 ? u32 : add_type_v(m, OP_TYPE_VECTOR, 2, u32, (n)))
    uint32_t newptr[64];
    for (size_t q = 0; q < nw; q++) { uint32_t ut = UTYPE(wn[q]); newptr[q] = add_type_v(m, OP_TYPE_POINTER, 2, (uint32_t)SC_OUTPUT, ut); }
    HM ptr_of = {0}; hm_init(m, &ptr_of, 256);   /* pointer id → uint value type */
    for (size_t q = 0; q < nw; q++) hm_put(m, &ptr_of, wv[q], UTYPE(wn[q]));
    IV chains = {0}; UV cn = {0};
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && hm_has(&ptr_of, AT(m, x, 3))) {
            uint32_t et, c, f; pointee(m, AT(m, x, 1), NULL, &et);
            if (!float_vec(m, et, &c, &f)) FAIL(m);   /* (ValueError) */
            iv_push(m, &chains, x); uv_push(m, &cn, c);
        }
    }
    for (size_t i = 0; i < chains.n; i++) {
        Ins *x = chains.v[i]; uint32_t ut = UTYPE(cn.v[i]);
        hm_put(m, &ptr_of, AT(m, x, 2), ut); x->w[1] = add_type_v(m, OP_TYPE_POINTER, 2, (uint32_t)SC_OUTPUT, UTYPE(cn.v[i]));
    }
#undef UTYPE
    IV out = {0}; int in_fn = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x), ut;
        in_fn = (in_fn || op == OP_FUNCTION) && op != OP_FUNCTION_END;
        if (op == OP_VARIABLE && uv_has(&(UV){wv, nw, nw}, AT(m, x, 2))) continue;
        if (op == OP_STORE && hm_get(&ptr_of, AT(m, x, 1), &ut)) {
            uint32_t v = new_id(m);
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_BITCAST, ut, v, AT(m, x, 2)));
            Ins *s = ins_new(m, 3 + (x->n > 3 ? x->n - 3 : 0)); s->w[0] = 3u << 16 | OP_STORE; s->w[1] = x->w[1]; s->w[2] = v;
            for (uint32_t i = 3; i < x->n; i++) s->w[i] = x->w[i];
            iv_push(m, &out, s); continue;
        }
        if (op == OP_LOAD && hm_get(&ptr_of, AT(m, x, 3), &ut)) {
            uint32_t v = new_id(m);
            Ins *l = ins_copy(m, x); l->w[1] = ut; l->w[2] = v;
            iv_push(m, &out, l); iv_push(m, &out, mk(m, 4, 4u << 16 | OP_BITCAST, AT(m, x, 1), AT(m, x, 2), v)); continue;
        }
        if (op == OP_STORE || op == OP_LOAD || op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) { iv_push(m, &out, x); continue; }
        if (in_fn) {
            uint32_t *ids; size_t n = maybe_ids(m, x, &ids);
            for (size_t i = 0; i < n; i++) if (hm_has(&ptr_of, ids[i])) FAIL(m);   /* (ValueError) */
        }
        iv_push(m, &out, x);
    }
    ins_set(m, out.v, out.n);
    for (size_t q = 0; q < nw; q++) insert_decl(m, mk(m, 4, 4u << 16 | OP_VARIABLE, newptr[q], wv[q], (uint32_t)SC_OUTPUT));
    index_(m);
    int64_t s[64]; for (size_t q = 0; q < nw; q++) s[q] = wl[q];
    for (size_t a = 1; a < nw; a++) for (size_t b = a; b > 0 && s[b - 1] > s[b]; b--) { int64_t t = s[b]; s[b] = s[b - 1]; s[b - 1] = t; }
    for (size_t q = 0; q < nw; q++) res_push(m, s[q]);
    return (int64_t)nw;
}

static uint32_t vec4_const(M *m, uint32_t f32, const float *vals)
{
    uint32_t vt = add_type_v(m, OP_TYPE_VECTOR, 2, f32, 4u), ids[4];
    for (int i = 0; i < 4; i++) { uint32_t b; memcpy(&b, &vals[i], 4); ids[i] = add_const(m, f32, b); }
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) == OP_CONSTANT_COMPOSITE && AT(m, x, 1) == vt && x->n == 7 && !memcmp(x->w + 3, ids, 16)) return AT(m, x, 2);
    }
    uint32_t c = new_id(m);
    insert_decl(m, mk(m, 7, 7u << 16 | OP_CONSTANT_COMPOSITE, vt, c, ids[0], ids[1], ids[2], ids[3]));
    return c;
}

static size_t first_block_insert_point(M *m, uint32_t fid)
{
    size_t a, e; function_range(m, fid, &a, &e);
    size_t k = a; while (k < m->nins && OPC(m->ins[k]) != OP_LABEL) k++;
    if (k == m->nins) FAIL(m);
    k++;
    for (;;) { if (k >= m->nins) FAIL(m); if (OPC(m->ins[k]) != OP_VARIABLE) break; k++; }
    return k;
}

static size_t first_op2(M *m, uint32_t a, uint32_t b)
{
    for (size_t k = 0; k < m->nins; k++) { uint32_t op = OPC(m->ins[k]); if (op == a || op == b) return k; }
    FAIL(m);
}
static Ins *name_ins(M *m, uint32_t id, const char *s)
{
    uint32_t w[16]; size_t n = lit_string(s, w);
    Ins *x = ins_new(m, 2 + n); x->w[0] = (uint32_t)(2 + n) << 16 | OP_NAME; x->w[1] = id; memcpy(x->w + 2, w, n * 4); return x;
}

static int64_t init_outputs(M *m, const int64_t *extra, size_t nextra)   /* extra: sorted, distinct; res: the locations added */
{
    IO *o; size_t no = io(m, SC_OUTPUT, &o);
    res_clear(m);
    uint32_t f32 = find_type_v(m, OP_TYPE_FLOAT, 1, 32u);
    if (f32 == NONE) return 0;
    static const float one_v[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    uint32_t one = vec4_const(m, f32, one_v);
    uint32_t vt = find_type_v(m, OP_TYPE_VECTOR, 2, f32, 4u);
    IO *outs = talloc(m, (no + nextra + 1) * sizeof *outs); memcpy(outs, o, no * sizeof *o); size_t nouts = no;
    for (size_t i = 0; i < nextra; i++) {
        if (extra[i] < 0 || extra[i] > UINT32_MAX) FAIL(m);
        uint32_t loc = (uint32_t)extra[i];
        if (io_find(o, no, loc) >= 0) continue;
        uint32_t ptr = add_type_v(m, OP_TYPE_POINTER, 2, (uint32_t)SC_OUTPUT, vt);
        uint32_t v = new_id(m); insert_decl(m, mk(m, 4, 4u << 16 | OP_VARIABLE, ptr, v, (uint32_t)SC_OUTPUT)); set_var(m, v, ptr, SC_OUTPUT);
        size_t at = first_op(m, OP_DECORATE);
        ins_insert(m, at, mk(m, 4, 4u << 16 | OP_DECORATE, v, (uint32_t)DEC_LOCATION, loc)); deco_reset_one(m, v); deco_set(m, v, DEC_LOCATION, &loc, 1);
        char nm[32]; snprintf(nm, sizeof nm, "dm_pad%u", loc); ins_insert(m, at, name_ins(m, v, nm));
        Ins *ep = m->entry; ins_append(m, ep, v); ep->w[0] = ep->n << 16 | OP_ENTRY_POINT;
        outs[nouts++] = (IO){loc, v, vt}; res_push(m, loc);
    }
    size_t k = first_block_insert_point(m, entry_function(m));
    /* stores for sorted(outs.items()) whose type is vt */
    for (size_t a = 1; a < nouts; a++) for (size_t b = a; b > 0 && outs[b - 1].loc > outs[b].loc; b--) { IO t = outs[b]; outs[b] = outs[b - 1]; outs[b - 1] = t; }
    IV st = {0};
    for (size_t i = 0; i < nouts; i++) if (outs[i].type == vt) iv_push(m, &st, mk(m, 3, 3u << 16 | OP_STORE, outs[i].var, one));
    ins_insert_n(m, k, st.v, st.n);
    return (int64_t)m->nres;
}

static int is_gs(M *m) { return m->entry && AT(m, m->entry, 1) == 3; }   /* Module.model == 'gs' */
static int is_cs(M *m) { return m->entry && AT(m, m->entry, 1) == 5; }

static int64_t gl_depth(M *m, uint32_t spec_id)
{
    uint32_t pos, member; builtin_var(m, SC_OUTPUT, 0, &pos, &member);
    if (pos == NONE) return 0;
    uint32_t f32 = find_type_v(m, OP_TYPE_FLOAT, 1, 32u), vt = f32 == NONE ? NONE : find_type_v(m, OP_TYPE_VECTOR, 2, f32, 4u);
    uint32_t pvec = add_type_v(m, OP_TYPE_POINTER, 2, (uint32_t)SC_OUTPUT, vt);
    uint32_t half = add_const(m, f32, 0x3f000000u);
    uint32_t i32 = find_type_v(m, OP_TYPE_INT, 2, 32u, 1u); if (i32 == NONE) i32 = add_type_v(m, OP_TYPE_INT, 2, 32u, 1u);
    uint32_t midx = member != NONE ? add_const(m, i32, member) : NONE;
    uint32_t glsl = ensure_glsl(m);
    uint32_t boolean = add_type(m, OP_TYPE_BOOL, NULL, 0);
    uint32_t spec = new_id(m); insert_decl(m, mk(m, 3, 3u << 16 | OP_SPEC_CONSTANT_FALSE, boolean, spec));
    size_t at = first_op(m, OP_DECORATE);
    ins_insert(m, at, mk(m, 4, 4u << 16 | OP_DECORATE, spec, (uint32_t)DEC_SPEC_ID, spec_id)); deco_reset_one(m, spec); deco_set(m, spec, DEC_SPEC_ID, &spec_id, 1);
    ins_insert(m, first_op2(m, OP_NAME, OP_DECORATE), name_ins(m, spec, "dm_gl_depth"));
    size_t a, e; function_range(m, entry_function(m), &a, &e);
    int gs = is_gs(m); uint32_t s1 = gs ? OP_EMIT_VERTEX : OP_RETURN, s2 = gs ? OP_EMIT_STREAM_VERTEX : OP_RETURN_VALUE;
    IV out = {0}; int64_t n = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (a < k && k < e && (op == s1 || op == s2)) {
            uint32_t p = pos;
            if (midx != NONE) { p = new_id(m); iv_push(m, &out, mk(m, 5, 5u << 16 | OP_ACCESS_CHAIN, pvec, p, pos, midx)); }
            uint32_t v = new_id(m), z = new_id(m), w = new_id(m), wh = new_id(m), z2 = new_id(m), z3 = new_id(m), v2 = new_id(m);
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_LOAD, vt, v, p));
            iv_push(m, &out, mk(m, 5, 5u << 16 | OP_COMPOSITE_EXTRACT, f32, z, v, 2u));
            iv_push(m, &out, mk(m, 5, 5u << 16 | OP_COMPOSITE_EXTRACT, f32, w, v, 3u));
            iv_push(m, &out, mk(m, 5, 5u << 16 | OP_FMUL, f32, wh, w, half));
            iv_push(m, &out, mk(m, 8, 8u << 16 | OP_EXT_INST, f32, z2, glsl, 50u, z, half, wh));
            iv_push(m, &out, mk(m, 6, 6u << 16 | OP_SELECT, f32, z3, spec, z2, z));
            iv_push(m, &out, mk(m, 6, 6u << 16 | OP_COMPOSITE_INSERT, vt, v2, z3, v, 2u));
            iv_push(m, &out, mk(m, 3, 3u << 16 | OP_STORE, p, v2));
            n++;
        }
        iv_push(m, &out, x);
    }
    ins_set(m, out.v, out.n);
    return n > 0;
}

static int64_t alpha_test(M *m, uint32_t spec_id)
{
    IO *o; size_t no = io(m, SC_OUTPUT, &o);
    int j = io_find(o, no, 0);
    if (j < 0) return 0;
    uint32_t c0 = o[j].var, t = o[j].type, comps, f32;
    if (!float_vec(m, t, &comps, &f32) || comps != 4) return 0;
    uint32_t sb = NONE;
    for (size_t i = 0; i < m->n_vars; i++) {
        uint32_t v = m->ord_vars[i];
        if (m->var_sc[v] == SC_UNIFORM && v < m->isz && m->names[v] && str_is(m->names[v], 2, "support_buffer")) { sb = v; break; }
    }
    if (sb == NONE) return 0;
    uint32_t st; pointee(m, m->var_ptr[sb], NULL, &st);
    if (op_of(m, st) != OP_TYPE_STRUCT) return 0;
    uint32_t mw, ms; uint32_t u32 = AT(m, m->types[st], 2);
    if (!int_type(m, u32, &mw, &ms) || mw != 32 || ms != 0) return 0;
    uint32_t i32 = find_type_v(m, OP_TYPE_INT, 2, 32u, 1u); if (i32 == NONE) i32 = add_type_v(m, OP_TYPE_INT, 2, 32u, 1u);
    uint32_t boolean = add_type(m, OP_TYPE_BOOL, NULL, 0), pu = add_type_v(m, OP_TYPE_POINTER, 2, (uint32_t)SC_UNIFORM, u32);
    uint32_t zero = add_const(m, i32, 0);
    static const uint32_t fns[7] = {1, 2, 3, 4, 5, 6, 7}, cmpop[8] = {0, 0, OP_FORD_LESS, OP_FORD_EQUAL, OP_FORD_LESS_EQUAL, OP_FORD_GREATER, OP_FUNORD_NOT_EQUAL, OP_FORD_GREATER_EQUAL};
    uint32_t fconst[8];
    for (int i = 0; i < 7; i++) fconst[fns[i]] = add_const(m, u32, fns[i]);
    uint32_t spec = new_id(m); insert_decl(m, mk(m, 4, 4u << 16 | OP_SPEC_CONSTANT, u32, spec, 0u));
    uint32_t t_ = NONE, f_ = NONE;
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_CONSTANT_TRUE && AT(m, x, 1) == boolean) { t_ = AT(m, x, 2); break; } }
    if (t_ == NONE) { t_ = new_id(m); insert_decl(m, mk(m, 3, 3u << 16 | OP_CONSTANT_TRUE, boolean, t_)); }
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_CONSTANT_FALSE && AT(m, x, 1) == boolean) { f_ = AT(m, x, 2); break; } }
    if (f_ == NONE) { f_ = new_id(m); insert_decl(m, mk(m, 3, 3u << 16 | OP_CONSTANT_FALSE, boolean, f_)); }
    size_t at = first_op(m, OP_DECORATE);
    ins_insert(m, at, mk(m, 4, 4u << 16 | OP_DECORATE, spec, (uint32_t)DEC_SPEC_ID, spec_id));
    ins_insert(m, first_op2(m, OP_NAME, OP_DECORATE), name_ins(m, spec, "dm_alpha_func"));
    size_t a, e; function_range(m, entry_function(m), &a, &e);
    IV out = {0}; int64_t n = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (a < k && k < e && OPC(x) == OP_RETURN) {
            uint32_t col = new_id(m), al = new_id(m), pw = new_id(m), rw = new_id(m), ref = new_id(m);
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_LOAD, t, col, c0));
            iv_push(m, &out, mk(m, 5, 5u << 16 | OP_COMPOSITE_EXTRACT, f32, al, col, 3u));
            iv_push(m, &out, mk(m, 5, 5u << 16 | OP_ACCESS_CHAIN, pu, pw, sb, zero));
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_LOAD, u32, rw, pw));
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_BITCAST, f32, ref, rw));
            uint32_t res = t_;
            for (int i = 0; i < 7; i++) {
                uint32_t fn = fns[i], is_fn = new_id(m), cmp_ = new_id(m), r2 = new_id(m);
                iv_push(m, &out, mk(m, 5, 5u << 16 | OP_IEQUAL, boolean, is_fn, spec, fconst[fn]));
                if (fn == 1) cmp_ = f_;
                else iv_push(m, &out, mk(m, 5, 5u << 16 | cmpop[fn], boolean, cmp_, al, ref));
                iv_push(m, &out, mk(m, 6, 6u << 16 | OP_SELECT, boolean, r2, is_fn, cmp_, res)); res = r2;
            }
            uint32_t ok = new_id(m), kill = new_id(m);
            iv_push(m, &out, mk(m, 3, 3u << 16 | OP_SELECTION_MERGE, ok, 0u));
            iv_push(m, &out, mk(m, 4, 4u << 16 | OP_BRANCH_CONDITIONAL, res, ok, kill));
            iv_push(m, &out, mk(m, 2, 2u << 16 | OP_LABEL, kill));
            iv_push(m, &out, mk(m, 1, 1u << 16 | OP_KILL));
            iv_push(m, &out, mk(m, 2, 2u << 16 | OP_LABEL, ok));
            n++;
        }
        iv_push(m, &out, x);
    }
    ins_set(m, out.v, out.n); index_(m);
    return n > 0;
}

/* ---- buffer textures, non-uniform arrays */
static int64_t buffer_textures_split(M *m)
{
    HM imgs = {0}, si = {0}; hm_init(m, &imgs, 16); hm_init(m, &si, 16);
    for (size_t i = 0; i < m->n_types; i++) {
        Ins *x = m->types[m->ord_types[i]];
        if (OPC(x) == OP_TYPE_IMAGE && AT(m, x, 3) == 5 && AT(m, x, 7) == 1) hm_put(m, &imgs, m->ord_types[i], 1);
    }
    for (size_t i = 0; i < m->n_types; i++) {
        Ins *x = m->types[m->ord_types[i]];
        if (OPC(x) == OP_TYPE_SAMPLED_IMAGE && hm_has(&imgs, AT(m, x, 2))) hm_put(m, &si, m->ord_types[i], x->w[2]);
    }
    if (!si.n) return 0;
    HM loaded = {0}; hm_init(m, &loaded, 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; uint32_t op = OPC(x); if ((op == OP_LOAD || op == OP_SAMPLED_IMAGE) && hm_has(&si, AT(m, x, 1))) hm_put(m, &loaded, AT(m, x, 2), 1); }
    int body = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        body = body || op == OP_FUNCTION;
        if (!body || op == OP_LOAD || (op == OP_SAMPLED_IMAGE && hm_has(&si, AT(m, x, 1))) || (op == OP_IMAGE && hm_has(&loaded, AT(m, x, 3)) && x->n == 4)) continue;
        int any = 0; for (uint32_t i = 3; i < x->n; i++) if (hm_has(&loaded, x->w[i])) { any = 1; break; }
        if ((any && op != OP_STORE && op != OP_ACCESS_CHAIN && op != OP_IN_BOUNDS_ACCESS_CHAIN) || (op == OP_STORE && hm_has(&loaded, AT(m, x, 2)))) return 0;
    }
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x), v;
        if (op == OP_TYPE_POINTER || op == OP_TYPE_ARRAY || op == OP_TYPE_RUNTIME_ARRAY) { size_t i = op == OP_TYPE_POINTER ? 3 : 2; if (hm_get(&si, AT(m, x, i), &v)) x->w[i] = v; }
        else if (op == OP_LOAD && hm_get(&si, AT(m, x, 1), &v)) x->w[1] = v;
        else if (op == OP_SAMPLED_IMAGE && hm_get(&si, AT(m, x, 1), &v)) { uint32_t w[4] = {4u << 16 | OP_COPY_OBJECT, v, AT(m, x, 2), AT(m, x, 3)}; ins_assign(m, x, w, 4); }
        else if (op == OP_IMAGE && hm_has(&loaded, AT(m, x, 3))) x->w[0] = 4u << 16 | OP_COPY_OBJECT;
    }
    HM used = {0}; hm_init(m, &used, m->header[3] + 64);
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_NAME || op == OP_DECORATE) continue;
        for (uint32_t i = (op >= 19 && op <= 39) ? 2 : 1; i < x->n; i++) hm_put(m, &used, x->w[i], 1);
    }
    size_t o = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) == OP_TYPE_SAMPLED_IMAGE && hm_has(&si, AT(m, x, 1)) && !hm_has(&used, x->w[1])) continue;
        m->ins[o++] = x;
    }
    m->nins = o; index_(m);
    return (int64_t)si.n;
}

static int64_t nonuniform_arrays(M *m)
{
    HM arrays = {0}; hm_init(m, &arrays, 64); int storage = 0;
    for (size_t i = 0; i < m->n_vars; i++) {
        uint32_t v = m->ord_vars[i];
        if (m->var_sc[v] != SC_UNIFORM_CONSTANT) continue;
        uint32_t t; pointee(m, m->var_ptr[v], NULL, &t);
        uint32_t el = strip_arrays(m, t, NULL); int ot = op_of(m, t), oe = op_of(m, el);
        if ((ot == OP_TYPE_ARRAY || ot == OP_TYPE_RUNTIME_ARRAY) && (oe == OP_TYPE_IMAGE || oe == OP_TYPE_SAMPLER || oe == OP_TYPE_SAMPLED_IMAGE)) hm_put(m, &arrays, v, 1);
    }
    HM marked = {0}; hm_init(m, &marked, 256);
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && hm_has(&arrays, AT(m, x, 3))) {
            int dyn = 0; for (uint32_t i = 4; i < x->n; i++) if (!CO(m, x->w[i])) { dyn = 1; break; }
            if (dyn) {
                hm_put(m, &marked, AT(m, x, 2), 1);
                uint32_t pv = x->w[3]; if (!HAS_VAR(m, pv)) FAIL(m);
                uint32_t t; pointee(m, m->var_ptr[pv], NULL, &t); uint32_t el = strip_arrays(m, t, NULL);
                storage = storage || (op_of(m, el) == OP_TYPE_IMAGE && AT(m, m->types[el], 7) == 2);
                continue;
            }
        }
        if ((op == OP_LOAD || op == OP_COPY_OBJECT || op == OP_IMAGE) && hm_has(&marked, AT(m, x, 3))) hm_put(m, &marked, AT(m, x, 2), 1);
        else if (op == OP_SAMPLED_IMAGE && (hm_has(&marked, AT(m, x, 3)) || hm_has(&marked, AT(m, x, 4)))) hm_put(m, &marked, AT(m, x, 2), 1);
    }
    UV mk_ = {0};
    for (size_t i = 0; i < marked.cap; i++) if (marked.used[i] && !deco_find(m, marked.k[i], DEC_NON_UNIFORM)) uv_push(m, &mk_, marked.k[i]);
    if (!mk_.n) return 0;
    for (size_t a = 1; a < mk_.n; a++) for (size_t b = a; b > 0 && mk_.v[b - 1] > mk_.v[b]; b--) { uint32_t t = mk_.v[b]; mk_.v[b] = mk_.v[b - 1]; mk_.v[b - 1] = t; }
    uint32_t want[3] = {5301, 5307, 5309}; IV caps = {0};
    for (int i = 0; i < (storage ? 3 : 2); i++) if (!has_cap(m, want[i])) iv_push(m, &caps, mk(m, 2, 2u << 16 | OP_CAPABILITY, want[i]));
    size_t k = last_op(m, OP_CAPABILITY) + 1;
    ins_insert_n(m, k, caps.v, caps.n);
    int have = 0; for (size_t i = 0; i < m->nexts; i++) if (str_is(m->exts[i], 1, "SPV_EXT_descriptor_indexing")) have = 1;
    if (m->header[1] < 0x10500 && !have) {
        uint32_t w[16]; size_t n = lit_string("SPV_EXT_descriptor_indexing", w);
        Ins *x = ins_new(m, 1 + n); x->w[0] = (uint32_t)(1 + n) << 16 | OP_EXTENSION; memcpy(x->w + 1, w, n * 4);
        ins_insert(m, k + caps.n, x);
    }
    size_t d = SIZE_MAX;
    for (size_t i = m->nins; i-- > 0;) {
        uint32_t op = OPC(m->ins[i]);
        if (op == OP_DECORATE || op == OP_MEMBER_DECORATE || op == OP_NAME || op == 6 || op == 7 || op == 3 || op == 4 || op == OP_EXTENSION || op == OP_CAPABILITY ||
            op == OP_EXT_INST_IMPORT || op == OP_MEMORY_MODEL || op == OP_ENTRY_POINT || op == OP_EXECUTION_MODE) { d = i; break; }
    }
    if (d == SIZE_MAX) FAIL(m);
    IV dec = {0}; for (size_t i = 0; i < mk_.n; i++) iv_push(m, &dec, mk(m, 3, 3u << 16 | OP_DECORATE, mk_.v[i], (uint32_t)DEC_NON_UNIFORM));
    ins_insert_n(m, d + 1, dec.v, dec.n);
    index_(m);
    return (int64_t)mk_.n;
}

static int64_t txq_extra_components(M *m)
{
    HM sizes = {0}; hm_init(m, &sizes, 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; uint32_t op = OPC(x); if ((op == 103 || op == 104) && x->n >= 4) hm_put(m, &sizes, x->w[2], x->w[1]); }
    if (!sizes.n) return 0;
    uint32_t i32 = find_type_v(m, OP_TYPE_INT, 2, 32u, 1u);
    IV bad = {0};
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t t;
        if (OPC(x) != OP_COMPOSITE_EXTRACT || x->n != 5 || !hm_get(&sizes, x->w[3], &t) || x->w[1] != i32) continue;
        uint32_t dims = op_of(m, t) == OP_TYPE_VECTOR ? AT(m, m->types[t], 3) : 1;
        if (x->w[4] >= dims) iv_push(m, &bad, x);
    }
    if (!bad.n) return 0;
    uint32_t one = add_const(m, i32, 1);
    for (size_t i = 0; i < bad.n; i++) { Ins *x = bad.v[i]; uint32_t w[4] = {4u << 16 | OP_COPY_OBJECT, x->w[1], x->w[2], one}; ins_assign(m, x, w, 4); }
    return (int64_t)bad.n;
}

/* ---- promote_locals and what it runs first */
static void remap_operands(Ins *x, HM *remap)
{
    uint32_t op = OPC(x), v;
#define RM(i) do { if (hm_get(remap, x->w[i], &v)) x->w[i] = v; } while (0)
    if (op == OP_EXT_INST) { for (uint32_t i = 5; i < x->n; i++) RM(i); }
    else if (op == OP_COMPOSITE_EXTRACT) { if (x->n > 3) RM(3); }
    else if (op == OP_COMPOSITE_INSERT || op == OP_VECTOR_SHUFFLE) { if (x->n > 3) RM(3); if (x->n > 4) RM(4); }
    else if (op == OP_LOOP_MERGE || op == OP_SELECTION_MERGE) {}
    else if (op == OP_IMAGE_WRITE) { for (uint32_t i = 1; i < 4 && i < x->n; i++) RM(i); for (uint32_t i = 5; i < x->n; i++) RM(i); }
    else if (op == OP_ATOMIC_STORE) { for (uint32_t i = 1; i < x->n; i++) RM(i); }
    else if (op == OP_SWITCH) { if (x->n > 1) RM(1); }
    else if (NO_RESULT(op)) { for (uint32_t i = 1; i < x->n; i++) RM(i); }
    else { for (uint32_t i = 3; i < x->n; i++) RM(i); }
#undef RM
}

static int has_raw_helper(M *m)   /* a function but the entry with an OpConvertUToPtr: the GlobalToStorage raw helpers' rewrites, which stay in Python */
{
    uint32_t ent = entry_function(m); int in = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_FUNCTION) in = AT(m, x, 2) != ent;
        else if (op == OP_CONVERT_U_TO_PTR && in) return 1;
        else if (op == OP_FUNCTION_END) in = 0;
    }
    return 0;
}

static int64_t lower_image_offsets(M *m)
{
    HM type_of = {0}; hm_init(m, &type_of, m->header[3] + 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (x->n >= 3 && TY(m, x->w[1])) hm_put(m, &type_of, x->w[2], x->w[1]); }
    IV out = {0}; int changed = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if (op == OP_IMAGE_FETCH && x->n >= 6) {
            uint32_t mask = x->w[5];
            if (mask & 0x10) {
                size_t off_idx = 6 + !!(mask & 1) + !!(mask & 2) + 2 * !!(mask & 4) + !!(mask & 8);
                uint32_t offset_id = AT(m, x, off_idx), coord_id = AT(m, x, 4), ct;
                if (!hm_get(&type_of, coord_id, &ct) && !hm_get(&type_of, offset_id, &ct)) FAIL(m);
                uint32_t nc = new_id(m);
                iv_push(m, &out, mk(m, 5, 5u << 16 | OP_IADD, ct, nc, coord_id, offset_id));
                uint32_t nm = mask & ~0x10u;
                Ins *y = ins_new(m, x->n + 1); y->n = 0;
                for (uint32_t i = 0; i < 4; i++) y->w[y->n++] = x->w[i];
                y->w[y->n++] = nc;
                if (nm) {
                    y->w[y->n++] = nm; size_t cur = 6;
                    static const uint32_t bits[8][2] = {{1, 1}, {2, 1}, {4, 2}, {8, 1}, {0x10, 1}, {0x20, 1}, {0x40, 1}, {0x80, 1}};
                    for (int b = 0; b < 8; b++) if (mask & bits[b][0]) {
                        if (bits[b][0] != 0x10) for (size_t i = cur; i < cur + bits[b][1] && i < x->n; i++) y->w[y->n++] = x->w[i];
                        cur += bits[b][1];
                    }
                }
                y->w[0] = y->n << 16 | op;
                iv_push(m, &out, y); changed = 1; continue;
            }
        }
        iv_push(m, &out, x);
    }
    if (changed) { ins_set(m, out.v, out.n); index_(m); }
    /* lower_sample_offsets: a non-constant Offset on a sample (rare, Python) */
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x); size_t mi = op == 87 || op == 88 ? 5 : op == 89 || op == 90 ? 6 : 0;
        if (mi && x->n > mi && x->w[mi] & 0x10) FAIL(m);
    }
    return changed;
}

static void simplify_helpers(M *m)
{
    if (has_raw_helper(m)) FAIL(m);
    uint32_t ent = entry_function(m);
    UV fns = {0};
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_FUNCTION && AT(m, x, 2) != ent) uv_push(m, &fns, x->w[2]); }
    /* ro_fns: function → [(param index, value type)] */
    HM ro_fns = {0}; hm_init(m, &ro_fns, 64); UV ro_idx = {0}, ro_vty = {0}; UV ro_start = {0}, ro_cnt = {0};
    for (size_t fi = 0; fi < fns.n; fi++) {
        uint32_t f = fns.v[fi]; size_t a, e; function_range(m, f, &a, &e);
        size_t bn = e + 1 - a; Ins **body = talloc(m, bn * sizeof *body); memcpy(body, m->ins + a, bn * sizeof *body);
        size_t k = 1; UV pk = {0}, pty = {0}, pid = {0};
        while (k < bn && OPC(body[k]) == OP_FUNCTION_PARAMETER) { uv_push(m, &pk, (uint32_t)k); uv_push(m, &pty, AT(m, body[k], 1)); uv_push(m, &pid, AT(m, body[k], 2)); k++; }
        if (!pk.n) continue;
        HM pids = {0}, bad = {0}; hm_init(m, &pids, pk.n * 2 + 8); hm_init(m, &bad, pk.n * 2 + 8);
        for (size_t i = 0; i < pid.n; i++) hm_put(m, &pids, pid.v[i], 1);
        for (size_t j = k; j < bn; j++) {
            Ins *x = body[j];
            if (OPC(x) == OP_LOAD && x->n == 4 && hm_has(&pids, x->w[3])) continue;
            for (uint32_t i = 1; i < x->n; i++) if (hm_has(&pids, x->w[i])) hm_put(m, &bad, x->w[i], 1);
        }
        UV new_ptys = {0}; HM ro_pid = {0}; hm_init(m, &ro_pid, pk.n * 2 + 8); size_t start = ro_idx.n, nro = 0;
        for (size_t i = 0; i < pk.n; i++) {
            uint32_t t = pty.v[i];
            if (!hm_has(&bad, pid.v[i]) && op_of(m, t) == OP_TYPE_POINTER && AT(m, m->types[t], 2) == SC_FUNCTION) {
                uint32_t vty = AT(m, m->types[t], 3);
                uv_push(m, &ro_idx, (uint32_t)i); uv_push(m, &ro_vty, vty); nro++; hm_put(m, &ro_pid, pid.v[i], vty);
                SET(m, body[i + 1], 1, vty); uv_push(m, &new_ptys, vty);
            } else uv_push(m, &new_ptys, t);
        }
        if (!nro) continue;
        HM remap = {0}; hm_init(m, &remap, 64); IV out = {0};
        for (size_t j = 0; j < bn; j++) {
            Ins *x = body[j];
            if (OPC(x) == OP_LOAD && x->n == 4 && hm_has(&ro_pid, x->w[3])) { hm_put(m, &remap, x->w[2], x->w[3]); continue; }
            if (remap.n) remap_operands(x, &remap);
            iv_push(m, &out, x);
        }
        uint32_t *ops = talloc(m, (new_ptys.n + 1) * 4); ops[0] = AT(m, out.v[0], 1); memcpy(ops + 1, new_ptys.v, new_ptys.n * 4);
        uint32_t ft = add_type(m, OP_TYPE_FUNCTION, ops, new_ptys.n + 1);
        SET(m, out.v[0], 4, ft);
        function_range(m, f, &a, &e);
        ins_replace(m, a, e + 1, out.v, out.n);
        hm_put(m, &ro_fns, f, (uint32_t)ro_start.n); uv_push(m, &ro_start, (uint32_t)start); uv_push(m, &ro_cnt, (uint32_t)nro);
    }
    if (ro_fns.n) {
        IV out = {0};
        for (size_t k = 0; k < m->nins; k++) {
            Ins *x = m->ins[k]; uint32_t r;
            if (OPC(x) == OP_FUNCTION_CALL && hm_get(&ro_fns, AT(m, x, 3), &r)) {
                x = ins_copy(m, x);
                for (uint32_t i = 0; i < ro_cnt.v[r]; i++) {
                    uint32_t idx = ro_idx.v[ro_start.v[r] + i], vty = ro_vty.v[ro_start.v[r] + i], nr = new_id(m);
                    iv_push(m, &out, mk(m, 4, 4u << 16 | OP_LOAD, vty, nr, AT(m, x, 4 + idx)));
                    x->w[4 + idx] = nr;
                }
            }
            iv_push(m, &out, x);
        }
        ins_set(m, out.v, out.n);
    }
    index_(m);
}

/* _dominance on block indices 0..nb-1 (0 the entry); preds as lists. → a test dominates(a, b) through pre / out times */
typedef struct { int32_t *num, *pre, *out_; } Dom;
static void dominance(M *m, size_t nb, uint32_t **preds, uint32_t *npred, Dom *D)
{
    /* roots */
    uint32_t *roots = talloc(m, (nb + 1) * 4); size_t nr = 0; roots[nr++] = 0;
    for (size_t b = 1; b < nb; b++) if (!npred[b]) roots[nr++] = (uint32_t)b;
    /* succs: for b in blks[1:]: for p in preds[b]: succs[p].append(b) */
    uint32_t *nsucc = tcalloc(m, (nb + 1) * 4);
    for (size_t b = 1; b < nb; b++) for (uint32_t i = 0; i < npred[b]; i++) nsucc[preds[b][i]]++;
    uint32_t *soff = talloc(m, (nb + 1) * 4), *succ, tot = 0;
    for (size_t b = 0; b < nb; b++) { soff[b] = tot; tot += nsucc[b]; }
    soff[nb] = tot; succ = talloc(m, (tot + 1) * 4); uint32_t *fill = tcalloc(m, (nb + 1) * 4);
    for (size_t b = 1; b < nb; b++) for (uint32_t i = 0; i < npred[b]; i++) { uint32_t p = preds[b][i]; succ[soff[p] + fill[p]++] = (uint32_t)b; }
    /* postorder */
    uint8_t *seen = tcalloc(m, nb + 1); uint32_t *post = talloc(m, (nb + 1) * 4); size_t np = 0;
    uint32_t *stk = talloc(m, (nb + 1) * 4), *it = talloc(m, (nb + 1) * 4);
    for (size_t r = 0; r < nr; r++) seen[roots[r]] = 1;
    for (size_t r = 0; r < nr; r++) {
        size_t sp = 0; stk[sp] = roots[r]; it[sp] = soff[roots[r]]; sp++;
        while (sp) {
            uint32_t b = stk[sp - 1]; int pushed = 0;
            while (it[sp - 1] < soff[b + 1]) {
                uint32_t s = succ[it[sp - 1]++];
                if (!seen[s]) { seen[s] = 1; stk[sp] = s; it[sp] = soff[s]; sp++; pushed = 1; break; }
            }
            if (!pushed) { post[np++] = b; sp--; }
        }
    }
    int32_t *num = talloc(m, (nb + 1) * 4); for (size_t b = 0; b < nb; b++) num[b] = -1;
    for (size_t i = 0; i < np; i++) num[post[i]] = (int32_t)i;
    int32_t top = (int32_t)np;
    int32_t *num_idom = talloc(m, (np + 2) * 4); for (size_t i = 0; i <= np; i++) num_idom[i] = -1;
    num_idom[top] = top;
    uint8_t *isroot = tcalloc(m, nb + 1); for (size_t r = 0; r < nr; r++) { isroot[roots[r]] = 1; num_idom[num[roots[r]]] = top; }
    uint32_t *order = talloc(m, (np + 1) * 4); size_t no = 0;
    for (size_t i = np; i-- > 0;) if (!isroot[post[i]]) order[no++] = post[i];
    for (int chg = 1; chg;) {
        chg = 0;
        for (size_t i = 0; i < no; i++) {
            uint32_t b = order[i]; int32_t nb_ = num[b], nw = -1;
            for (uint32_t j = 0; j < npred[b]; j++) {
                int32_t q = num[preds[b][j]];
                if (q < 0 || num_idom[q] < 0) continue;
                if (nw < 0) nw = q;
                else { int32_t a = q, c = nw; while (a != c) { while (a < c) { a = num_idom[a]; if (a < 0) FAIL(m); } while (c < a) { c = num_idom[c]; if (c < 0) FAIL(m); } } nw = a; }
            }
            if (nw >= 0 && num_idom[nb_] != nw) { num_idom[nb_] = nw; chg = 1; }
        }
    }
    /* entry / exit times in the dominator tree (children in any order: the answers do not depend on it) */
    uint32_t *nk = tcalloc(m, (np + 2) * 4), *koff = talloc(m, (np + 2) * 4), *kids, *kf = tcalloc(m, (np + 2) * 4);
    for (int32_t n = 0; n < top; n++) if (num_idom[n] >= 0) nk[num_idom[n]]++;
    uint32_t t2 = 0; for (int32_t n = 0; n <= top; n++) { koff[n] = t2; t2 += nk[n]; }
    kids = talloc(m, (t2 + 1) * 4);
    for (int32_t n = 0; n < top; n++) if (num_idom[n] >= 0) { int32_t d = num_idom[n]; kids[koff[d] + kf[d]++] = (uint32_t)n; }
    int32_t *pre = talloc(m, (np + 2) * 4), *out_ = talloc(m, (np + 2) * 4), t = 0;
    uint32_t *s2 = talloc(m, (2 * np + 4) * 4); size_t sp = 0; s2[sp++] = (uint32_t)top << 1;
    while (sp) {
        uint32_t v = s2[--sp], n = v >> 1;
        if (v & 1) { out_[n] = t++; continue; }
        pre[n] = t++; s2[sp++] = n << 1 | 1;
        for (uint32_t i = 0; i < nk[n]; i++) s2[sp++] = kids[koff[n] + i] << 1;
    }
    D->num = num; D->pre = pre; D->out_ = out_;
}
static int dominates(const Dom *D, uint32_t a, uint32_t b)
{
    int32_t nb = D->num[b];
    if (nb < 0) return 1;
    int32_t na = D->num[a];
    if (na < 0) return 0;
    return D->pre[na] <= D->pre[nb] && D->out_[nb] <= D->out_[na];
}

static void promote(M *m)   /* promote_locals' _promote */
{
    UV fns = {0};
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_FUNCTION) uv_push(m, &fns, AT(m, x, 2)); }
    for (size_t fi = 0; fi < fns.n; fi++) {
        for (;;) {
            Mark mark = amark(&m->tmp);
            size_t a, e; function_range(m, fns.v[fi], &a, &e);
            size_t bn = e + 1 - a; Ins **body = m->ins + a;
            HM blk_of = {0}; hm_init(m, &blk_of, 64); size_t nb = 0;   /* label → block index */
            for (size_t j = 0; j < bn; j++) if (OPC(body[j]) == OP_LABEL) { uint32_t l = AT(m, body[j], 1); if (hm_has(&blk_of, l)) FAIL(m); hm_put(m, &blk_of, l, (uint32_t)nb++); }
            HM fvars = {0}; hm_init(m, &fvars, 256);
            for (size_t j = 0; j < bn; j++) { Ins *x = body[j]; if (OPC(x) == OP_VARIABLE && x->n == 4 && x->w[3] == SC_FUNCTION && !deco_present(m, x->w[2])) hm_put(m, &fvars, x->w[2], 1); }
            if (!fvars.n || !nb) { arelease(&m->tmp, mark); break; }
            /* preds as sets (a block once per predecessor) */
            uint32_t **preds = talloc(m, nb * sizeof *preds), *npred = tcalloc(m, nb * 4), *cpred = tcalloc(m, nb * 4);
            HM bad = {0}; hm_init(m, &bad, 256);
            HM st_blk = {0}, st_idx = {0}, st_val = {0}; hm_init(m, &st_blk, fvars.n); hm_init(m, &st_idx, fvars.n); hm_init(m, &st_val, fvars.n);
            Uses L; memset(&L, 0, sizeof L); hm_init(m, &L.head, fvars.n); hm_init(m, &L.tail, fvars.n); L.cap = 1024; L.u = talloc(m, L.cap * sizeof *L.u); L.n = 1;
            UV lblk = {0}, lidx = {0}, lres = {0};   /* load k: (block, index, result) at L entry k */
            uv_push(m, &lblk, 0); uv_push(m, &lidx, 0); uv_push(m, &lres, 0);
            uint32_t blk = UINT32_MAX;   /* None */
#define ADDPRED(lbl) do { uint32_t _t; if (!hm_get(&blk_of, (lbl), &_t)) FAIL(m); if (blk == UINT32_MAX) FAIL(m); \
                int _d = 0; for (uint32_t _i = 0; _i < npred[_t]; _i++) if (preds[_t][_i] == blk) { _d = 1; break; } \
                if (!_d) { if (npred[_t] == cpred[_t]) { uint32_t _c = cpred[_t] ? cpred[_t] * 2 : 4; uint32_t *_p = talloc(m, _c * 4); if (npred[_t]) memcpy(_p, preds[_t], npred[_t] * 4); preds[_t] = _p; cpred[_t] = _c; } \
                preds[_t][npred[_t]++] = blk; } } while (0)
            for (size_t j = 0; j < bn; j++) {
                Ins *x = body[j]; uint32_t op = OPC(x);
                if (op == OP_LABEL) { if (!hm_get(&blk_of, x->w[1], &blk)) FAIL(m); }
                else if (op == OP_BRANCH) ADDPRED(AT(m, x, 1));
                else if (op == OP_BRANCH_CONDITIONAL) { ADDPRED(AT(m, x, 2)); ADDPRED(AT(m, x, 3)); }
                else if (op == OP_SWITCH) { ADDPRED(AT(m, x, 2)); for (uint32_t i = 4; i < x->n; i += 2) ADDPRED(x->w[i]); }
                if (op == OP_VARIABLE && hm_has(&fvars, AT(m, x, 2))) continue;
                if (op == OP_STORE && x->n == 3 && hm_has(&fvars, x->w[1]) && !hm_has(&fvars, x->w[2])) {
                    if (hm_has(&st_blk, x->w[1])) hm_put(m, &bad, x->w[1], 1);
                    else { if (blk == UINT32_MAX) FAIL(m); hm_put(m, &st_blk, x->w[1], blk); hm_put(m, &st_idx, x->w[1], (uint32_t)j); hm_put(m, &st_val, x->w[1], x->w[2]); }
                    continue;
                }
                if (op == OP_LOAD && x->n == 4 && hm_has(&fvars, x->w[3])) {
                    if (blk == UINT32_MAX) FAIL(m);
                    uv_push(m, &lblk, blk); uv_push(m, &lidx, (uint32_t)j); uv_push(m, &lres, x->w[2]);
                    uses_add(m, &L, x->w[3], NULL, (uint32_t)(lblk.n - 1));
                    continue;
                }
                for (uint32_t i = 1; i < x->n; i++) if (hm_has(&fvars, x->w[i])) hm_put(m, &bad, x->w[i], 1);
            }
#undef ADDPRED
            Dom D; dominance(m, nb, preds, npred, &D);
            HM good = {0}, remap = {0}; hm_init(m, &good, fvars.n); hm_init(m, &remap, 256);
            for (size_t i = 0; i < fvars.cap; i++) {
                if (!fvars.used[i]) continue;
                uint32_t v = fvars.k[i];
                if (hm_has(&bad, v)) continue;
                uint32_t h; int has_l = hm_get(&L.head, v, &h), sb;
                uint32_t sblk;
                if (!hm_get(&st_blk, v, &sblk)) { if (!has_l) hm_put(m, &good, v, 1); continue; }
                uint32_t sidx = 0, val = 0; hm_get(&st_idx, v, &sidx); hm_get(&st_val, v, &val);
                sb = 1;
                USES_FOR(&L, v, it) { uint32_t q = L.u[it].p; if (!(dominates(&D, sblk, lblk.v[q]) && (lblk.v[q] != sblk || lidx.v[q] > sidx))) { sb = 0; break; } }
                if (sb) { hm_put(m, &good, v, 1); USES_FOR(&L, v, it) hm_put(m, &remap, lres.v[L.u[it].p], val); }
            }
            if (!good.n) { arelease(&m->tmp, mark); break; }
            for (size_t i = 0; i < remap.cap; i++) {   /* path compression (order-free: the result is the closure) */
                if (!remap.used[i]) continue;
                uint32_t v = remap.v[i], w, guard = 0;
                while (hm_get(&remap, v, &w)) { v = w; if (++guard > 1000000) FAIL(m); }
                remap.v[i] = v;
            }
            IV out = {0};
            for (size_t j = 0; j < bn; j++) {
                Ins *x = body[j]; uint32_t op = OPC(x);
                if (op == OP_VARIABLE && hm_has(&good, AT(m, x, 2))) continue;
                if (op == OP_STORE && x->n == 3 && hm_has(&good, x->w[1])) continue;
                if (op == OP_LOAD && x->n == 4 && hm_has(&good, x->w[3])) continue;
                if (remap.n) remap_operands(x, &remap);
                iv_push(m, &out, x);
            }
            ins_replace(m, a, e + 1, out.v, out.n);
            size_t o = 0;
            for (size_t k = 0; k < m->nins; k++) {
                Ins *y = m->ins[k]; uint32_t op = OPC(y);
                if ((op == OP_NAME || op == OP_DECORATE) && hm_has(&good, AT(m, y, 1))) continue;
                m->ins[o++] = y;
            }
            m->nins = o;
            arelease(&m->tmp, mark);   /* nothing of this round is kept */
        }
    }
    index_(m);
}

static int64_t promote_locals(M *m)
{
    lower_image_offsets(m);
    simplify_helpers(m);
    promote(m);
    simplify_helpers(m);
    promote(m);
    return 0;
}

/* ---- reflection and the build's smaller rewrites */
static int64_t resources(M *m)   /* res per resource: id, set, binding, count, desc, has dim, dim, depth, array, ms, has type, width, signed, then its OpName's word count (-1: none) and words */
{
    res_clear(m); int64_t n = 0;
    for (size_t i = 0; i < m->n_vars; i++) {
        uint32_t vid = m->ord_vars[i], ptr = m->var_ptr[vid], sc = m->var_sc[vid];
        DecoEnt *db = deco_find(m, vid, DEC_BINDING);
        if (!db) continue;
        uint32_t t; pointee(m, ptr, NULL, &t);
        uint64_t count; uint32_t elem = strip_arrays(m, t, &count);
        DecoEnt *ds = deco_find(m, vid, DEC_SET);
        uint32_t set = ds ? deco_op0(m, ds) : 0, binding = deco_op0(m, db);
        int eop = op_of(m, elem); int64_t desc; int has_dim = 0; uint32_t dim = 0, depth = 0, arrayed = 0, ms = 0, iw = 0, is = 0; int has_t = 0;
        if (sc == SC_UNIFORM && eop == OP_TYPE_STRUCT) desc = deco_find(m, elem, DEC_BUFFER_BLOCK) ? VK_STORAGE_BUFFER : VK_UNIFORM_BUFFER;
        else if (sc == SC_STORAGE_BUFFER) desc = VK_STORAGE_BUFFER;
        else if (sc == SC_UNIFORM_CONSTANT) {
            if (eop == OP_TYPE_SAMPLER) desc = VK_SAMPLER;
            else {
                uint32_t img = eop == OP_TYPE_SAMPLED_IMAGE ? AT(m, m->types[elem], 2) : elem;
                if (op_of(m, img) != OP_TYPE_IMAGE) FAIL(m);   /* (ValueError) */
                Ins *x = m->types[img]; if (x->n < 8) FAIL(m);
                uint32_t sampled_t = x->w[2], sampled = x->w[7]; dim = x->w[3]; depth = x->w[4]; arrayed = x->w[5]; ms = x->w[6];
                has_dim = 1; has_t = int_type(m, sampled_t, &iw, &is);
                if (eop == OP_TYPE_SAMPLED_IMAGE) desc = VK_COMBINED;
                else if (sampled == 2) desc = dim == 5 ? VK_STORAGE_TEXEL : VK_STORAGE_IMAGE;
                else desc = dim == 5 ? VK_UNIFORM_TEXEL : VK_SAMPLED_IMAGE;
            }
        } else FAIL(m);   /* (ValueError) */
        res_push(m, vid); res_push(m, set); res_push(m, binding); res_push(m, (int64_t)count); res_push(m, desc);
        res_push(m, has_dim); res_push(m, dim); res_push(m, depth); res_push(m, arrayed); res_push(m, ms); res_push(m, has_t); res_push(m, iw); res_push(m, is);
        Ins *nx = vid < m->isz ? m->names[vid] : NULL;
        res_push(m, nx ? (int64_t)nx->n : -1); if (nx) for (uint32_t i = 0; i < nx->n; i++) res_push(m, nx->w[i]);
        n++;
    }
    return n;
}

static int64_t set_binding(M *m, uint32_t vid, uint32_t s, uint32_t b)
{
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) == OP_DECORATE && AT(m, x, 1) == vid) { uint32_t d = AT(m, x, 2); if (d == DEC_SET) SET(m, x, 3, s); else if (d == DEC_BINDING) SET(m, x, 3, b); }
    }
    deco_touch(m, vid);
    if (!deco_find(m, vid, DEC_SET)) {
        size_t at = 0;
        for (; at < m->nins; at++) { Ins *y = m->ins[at]; if (OPC(y) == OP_DECORATE && AT(m, y, 1) == vid) break; }
        if (at == m->nins) FAIL(m);
        ins_insert(m, at, mk(m, 4, 4u << 16 | OP_DECORATE, vid, (uint32_t)DEC_SET, s));
    }
    deco_set(m, vid, DEC_SET, &s, 1); deco_set(m, vid, DEC_BINDING, &b, 1);
    return 0;
}

static int image_atomic(M *m, uint32_t vid)   /* vk_android_build.py m_atomic */
{
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_IMAGE_TEXEL_POINTER && AT(m, x, 3) == vid) return 1; }
    HM ch = {0}; hm_init(m, &ch, 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *y = m->ins[k]; uint32_t op = OPC(y); if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && AT(m, y, 3) == vid) hm_put(m, &ch, AT(m, y, 2), 1); }
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_IMAGE_TEXEL_POINTER && hm_has(&ch, AT(m, x, 3))) return 1; }
    return 0;
}
static int image_writes(M *m, uint32_t vid)   /* m_writes */
{
    HM loads = {0}; hm_init(m, &loads, 64);
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_LOAD && AT(m, x, 3) == vid) hm_put(m, &loads, AT(m, x, 2), 1); }
    for (size_t k = 0; k < m->nins; k++) { Ins *x = m->ins[k]; if (OPC(x) == OP_IMAGE_WRITE && hm_has(&loads, AT(m, x, 1))) return 1; }
    return image_atomic(m, vid);
}

static int64_t block_rows(M *m, uint32_t vid)   /* res: the sorted rows; → whether any index is computed */
{
    int dyn = 0; res_clear(m); HM rows = {0}; hm_init(m, &rows, 64); UV r64 = {0};
#define CONST_INT(id, out) ({ Ins *_c = CO(m, (id)); int _ok = _c && int_type(m, _c->w[1], NULL, NULL); if (_ok) (out) = AT(m, _c, 3); _ok; })
    int64_t *vals = NULL; size_t nv = 0, cv = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k]; uint32_t op = OPC(x);
        if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && AT(m, x, 3) == vid) {
            if (x->n < 6) { dyn = 1; continue; }
            uint32_t r, c; int64_t v;
            if (!CONST_INT(x->w[5], r)) { dyn = 1; continue; }
            v = (int64_t)r * 16 + (x->n > 6 && CONST_INT(x->w[6], c) ? 4 * (int64_t)c : 0);
            int dup = 0; for (size_t i = 0; i < nv; i++) if (vals[i] == v) { dup = 1; break; }
            if (!dup) { if (nv == cv) { cv = cv * 2 + 64; int64_t *nvp = talloc(m, cv * 8); if (nv) memcpy(nvp, vals, nv * 8); vals = nvp; } vals[nv++] = v; }
        } else if (op == OP_LOAD && AT(m, x, 3) == vid) dyn = 1;
    }
#undef CONST_INT
    (void)rows; (void)r64;
    for (size_t a = 1; a < nv; a++) for (size_t b = a; b > 0 && vals[b - 1] > vals[b]; b--) { int64_t t = vals[b]; vals[b] = vals[b - 1]; vals[b - 1] = t; }
    for (size_t i = 0; i < nv; i++) res_push(m, vals[i]);
    return dyn;
}

static int64_t functions_named(M *m)   /* res per function in order: id, its OpName's word count (-1: none) and words */
{
    res_clear(m); int64_t n = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) != OP_FUNCTION) continue;
        uint32_t f = AT(m, x, 2); Ins *nx = f < m->isz ? m->names[f] : NULL;
        res_push(m, f); res_push(m, nx ? (int64_t)nx->n : -1); if (nx) for (uint32_t i = 0; i < nx->n; i++) res_push(m, nx->w[i]);
        n++;
    }
    return n;
}

static int64_t hmma_used(M *m)   /* hmma_lds when the module has the helper (Python) */
{
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) != OP_FUNCTION) continue;
        uint32_t f = AT(m, x, 2);
        if (f < m->isz && m->names[f] && str_is(m->names[f], 2, "dmsh_hmma16816_f16")) FAIL(m);
    }
    return 0;
}

static int64_t private_viewport_mask(M *m)
{
    for (size_t i = 0; i < m->n_decos; i++) {
        uint32_t v = m->ord_decos[i];
        if (deco_is1(m, deco_find(m, v, DEC_BUILTIN), 5253) && HAS_VAR(m, v) && m->var_sc[v] == SC_OUTPUT) FAIL(m);
    }
    return 0;
}

static int64_t per_primitive_inputs(M *m, const int64_t *locs, size_t n)
{
    IO *in; size_t ni = io(m, SC_INPUT, &in);
    for (size_t i = 0; i < n; i++) if (locs[i] >= 0 && locs[i] <= UINT32_MAX && io_find(in, ni, (uint32_t)locs[i]) >= 0) FAIL(m);
    res_clear(m);
    return 0;
}

static int64_t shuffle_precheck(M *m)   /* shuffle_lds goes past its checks (then the Python runs it on the copy): 1, else 0 (the module unchanged) */
{
    if (!is_cs(m)) return 0;
    int shuf = 0, ls = 0, used = 1;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) != OP_GROUP_NON_UNIFORM_SHUFFLE) continue;
        uint32_t t = AT(m, x, 1); int o = op_of(m, t);
        if ((o == OP_TYPE_INT || o == OP_TYPE_FLOAT) && AT(m, m->types[t], 2) == 32) { Ins *c = CO(m, AT(m, x, 3)); if (c && c->n == 4 && c->w[3] == 3) shuf = 1; }
    }
    for (size_t i = 0; i < m->nmodes; i++) { if (!m->modes_n[i]) FAIL(m); if (m->modes[i][0] == 17) { ls = 1; break; } }
    for (size_t i = 0; i < m->n_vars; i++) {   /* workgroup_bytes */
        uint32_t v = m->ord_vars[i];
        if (m->var_sc[v] != SC_WORKGROUP) continue;
        uint32_t pt; pointee(m, m->var_ptr[v], NULL, &pt);
        uint64_t count; uint32_t t = strip_arrays(m, pt, &count);
        if (op_of(m, t) == OP_TYPE_VECTOR) AT(m, m->types[t], 3);
        uint32_t s = scalar_of(m, t); int o = op_of(m, s);
        if ((o != OP_TYPE_INT && o != OP_TYPE_FLOAT) || AT(m, m->types[s], 2) != 32 || !count) { used = 0; break; }
    }
    return shuf && ls && used;
}

static int64_t has_spec_id(M *m, uint32_t id)
{
    for (size_t i = 0; i < m->n_decos; i++) if (deco_is1(m, deco_find(m, m->ord_decos[i], DEC_SPEC_ID), id)) return 1;
    return 0;
}

static int64_t tess_modes_set(M *m, const int64_t *modes)   /* modes: 3 values, -1 = None */
{
    static const uint32_t g[3][3] = {{22, 24, 25}, {1, 2, 3}, {4, 5, 0}}; static const int gn[3] = {3, 3, 2};
    int64_t n = 0;
    for (size_t k = 0; k < m->nins; k++) {
        Ins *x = m->ins[k];
        if (OPC(x) == 16 && m->entry && AT(m, x, 1) == AT(m, m->entry, 2) && x->n == 3)
            for (int i = 0; i < 3; i++) if (in_set(x->w[2], g[i], (size_t)gn[i]) && modes[i] >= 0 && x->w[2] != (uint64_t)modes[i]) { x->w[2] = (uint32_t)modes[i]; n++; }
    }
    if (n) index_(m);
    return n;
}

static int64_t cache_raw_loads(M *m, int64_t ntables)
{
    if (!ntables) return 0;
    if (has_raw_helper(m)) FAIL(m);
    return 0;
}

static int64_t list_caps(M *m) { res_clear(m); for (size_t i = 0; i < m->ncaps; i++) res_push(m, m->caps[i]); return (int64_t)m->ncaps; }
static int64_t list_exts(M *m)   /* res per extension: its word count and words */
{
    res_clear(m);
    for (size_t i = 0; i < m->nexts; i++) { Ins *x = m->exts[i]; res_push(m, x->n); for (uint32_t j = 0; j < x->n; j++) res_push(m, x->w[j]); }
    return (int64_t)m->nexts;
}
static int64_t list_io(M *m, uint32_t sc) { IO *o; size_t n = io(m, sc, &o); res_clear(m); for (size_t i = 0; i < n; i++) { res_push(m, o[i].loc); res_push(m, o[i].var); res_push(m, o[i].type); } return (int64_t)n; }
static int64_t int_type_of(M *m, uint32_t t) { uint32_t w, s; res_clear(m); if (!int_type(m, t, &w, &s)) return 0; res_push(m, w); res_push(m, s); return 1; }

/* ================================================================================================ the interface */
enum {
    P_STRIP_UNUSED_CAPS = 1, P_FORMATLESS_STORAGE_CAPS, P_EXTENDED_FORMAT_CAPS, P_WORKGROUP_PLAIN_ARRAYS, P_PRECISE_MINMAX, P_TXQ_EXTRA_COMPONENTS, P_HMMA_USED,
    P_PROMOTE_LOCALS, P_LOOP_POOL_SEARCH, P_BUFFER_TEXTURES_SPLIT, P_NONUNIFORM_ARRAYS, P_RESOURCES, P_SET_BINDING, P_IMAGE_ATOMIC, P_IMAGE_WRITES, P_CACHE_RAW_LOADS,
    P_INIT_OUTPUTS, P_IO, P_PER_PRIMITIVE_INPUTS, P_GL_DEPTH, P_HAS_SPEC_ID, P_ALPHA_TEST, P_INT_OUTPUTS, P_CONST_WRITTEN_OUTPUTS, P_RETYPE_OUTPUTS, P_SHUFFLE_PRECHECK,
    P_INTEGER_ONLY_INPUTS, P_PRIVATE_VIEWPORT_MASK, P_CAPS, P_EXTS, P_INT_TYPE, P_SCALAR_OF, P_BLOCK_ROWS, P_FUNCTIONS_NAMED, P_SUBGROUP_USED, P_TESS_MODES_SET,
    P_INS_WORDS, P_NINS,
};

EXPORT const char *spvn_source(void) { return SPVN_SOURCE; }
EXPORT int spvn_abi(void) { return 1; }

static int parse_index(M *m, const uint8_t *data, size_t len)
{
    if (setjmp(m->jb)) return 0;
    if (parse(m, data, len)) return 0;
    index_(m); arena_reset(&m->tmp);
    return 1;
}
EXPORT M *spvn_new(const uint8_t *data, size_t len)
{
    M *m = m_alloc();
    if (m && !parse_index(m, data, len)) { m_free(m); m = NULL; }
    return m;
}
EXPORT void spvn_free(M *m) { m_free(m); }
EXPORT const uint8_t *spvn_data(M *m, size_t *len)
{
    if (setjmp(m->jb)) { *len = 0; return NULL; }
    *len = data_(m); return m->out;
}
EXPORT M *spvn_copy(M *m) { size_t n; const uint8_t *d = spvn_data(m, &n); return d ? spvn_new(d, n) : NULL; }
EXPORT const int64_t *spvn_res(M *m, size_t *n) { *n = m->nres; return m->res; }
EXPORT const char *spvn_why(M *m, int *line) { *line = m->why_line; return m->why ? m->why : ""; }

static uint32_t U32(M *m, int64_t v) { if (v < 0 || v > UINT32_MAX) FAIL(m); return (uint32_t)v; }

EXPORT int64_t spvn_call(M *m, int pass, const int64_t *a, size_t na)
{
    if (setjmp(m->jb)) { arena_reset(&m->tmp); return SPVN_FALLBACK; }
    m->why = NULL; int64_t r = 0;
#define NEED(n) do { if (na < (n)) FAIL(m); } while (0)
    switch (pass) {
    case P_STRIP_UNUSED_CAPS: r = strip_unused_caps(m); break;
    case P_FORMATLESS_STORAGE_CAPS: r = formatless_storage_caps(m); break;
    case P_EXTENDED_FORMAT_CAPS: r = extended_format_caps(m); break;
    case P_WORKGROUP_PLAIN_ARRAYS: r = workgroup_plain_arrays(m); break;
    case P_PRECISE_MINMAX: r = precise_minmax(m); break;
    case P_TXQ_EXTRA_COMPONENTS: r = txq_extra_components(m); break;
    case P_HMMA_USED: r = hmma_used(m); break;
    case P_PROMOTE_LOCALS: r = promote_locals(m); break;
    case P_LOOP_POOL_SEARCH: if (has_raw_helper(m)) FAIL(m); r = 0; break;
    case P_BUFFER_TEXTURES_SPLIT: r = buffer_textures_split(m); break;
    case P_NONUNIFORM_ARRAYS: r = nonuniform_arrays(m); break;
    case P_RESOURCES: r = resources(m); break;
    case P_SET_BINDING: NEED(3); r = set_binding(m, U32(m, a[0]), U32(m, a[1]), U32(m, a[2])); break;
    case P_IMAGE_ATOMIC: NEED(1); r = image_atomic(m, U32(m, a[0])); break;
    case P_IMAGE_WRITES: NEED(1); r = image_writes(m, U32(m, a[0])); break;
    case P_CACHE_RAW_LOADS: NEED(1); r = cache_raw_loads(m, a[0]); break;
    case P_INIT_OUTPUTS: r = init_outputs(m, a, na); break;
    case P_IO: NEED(1); r = list_io(m, U32(m, a[0])); break;
    case P_PER_PRIMITIVE_INPUTS: r = per_primitive_inputs(m, a, na); break;
    case P_GL_DEPTH: NEED(1); r = gl_depth(m, U32(m, a[0])); break;
    case P_HAS_SPEC_ID: NEED(1); r = has_spec_id(m, U32(m, a[0])); break;
    case P_ALPHA_TEST: NEED(1); r = alpha_test(m, U32(m, a[0])); break;
    case P_INT_OUTPUTS: r = int_outputs(m); break;
    case P_CONST_WRITTEN_OUTPUTS: r = const_written_outputs(m); break;
    case P_RETYPE_OUTPUTS: r = retype_outputs(m, a, na); break;
    case P_SHUFFLE_PRECHECK: r = shuffle_precheck(m); break;
    case P_INTEGER_ONLY_INPUTS: r = integer_only_inputs(m); break;
    case P_PRIVATE_VIEWPORT_MASK: r = private_viewport_mask(m); break;
    case P_CAPS: r = list_caps(m); break;
    case P_EXTS: r = list_exts(m); break;
    case P_INT_TYPE: NEED(1); r = int_type_of(m, U32(m, a[0])); break;
    case P_SCALAR_OF: NEED(1); r = scalar_of(m, U32(m, a[0])); break;
    case P_BLOCK_ROWS: NEED(1); r = block_rows(m, U32(m, a[0])); break;
    case P_FUNCTIONS_NAMED: r = functions_named(m); break;
    case P_SUBGROUP_USED: r = subgroup_used(m); break;
    case P_TESS_MODES_SET: NEED(3); r = tess_modes_set(m, a); break;
    case P_INS_WORDS: {   /* res: the words of instruction a[0] */
        NEED(1); if (a[0] < 0 || (size_t)a[0] >= m->nins) FAIL(m);
        Ins *x = m->ins[a[0]]; res_clear(m); for (uint32_t i = 0; i < x->n; i++) res_push(m, x->w[i]); r = x->n; break;
    }
    case P_NINS: r = (int64_t)m->nins; break;
    default: FAIL(m);
    }
#undef NEED
    arena_reset(&m->tmp);
    return r;
}
