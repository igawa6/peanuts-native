/* Peanuts Native: patch_nso.py's spin wait search in C (libdmsh_rwspin), which patch_nso.py loads through ctypes (spin_native) so that the search over every
 * candidate head has no Python loop. It answers one question per head: does spin_region(T, bes) find a region? patch_nso.py then builds the regions of the heads
 * that have one with its own spin_region, so the output is made by the Python as before (docs/reference/rewriter.md, "The spin wait search in C").
 *
 * What is here, each the Python function of the same name transliterated: spin_quick, spin_region (the stores loop and the region without stores) and
 * spin_region1 (the node search, prune, walk, the live registers). What a word reads as (spin_word: its kind, an unconditional b, spin_store, a load, the
 * first word of a spin_lock sequence; spin_rw: the registers read and written) is decoded here from the instruction's bits for the classes below, which give
 * capstone's values (checked against capstone over every text word of four large modules and millions of random words: dm/tools/tests/test_rwspin.py and
 * docs/reference/rewriter.md). spin_rmw and spin_lock are decided here where every word they read has one of the encodings checked the same way (rmw_c,
 * lock_c). Any other word or sequence is asked from the Python through the callback (patch_nso.py spin_native_ask), which answers with patch_nso.py's own
 * functions; the answers are kept for the process.
 *
 * Registers: a set is 128 bits, bit 0-30 x0-x30, 31 sp, 32-63 v0-v31, 64 nzcv; the Python numbers any other name it meets from 65 on (spin_native_reg).
 * Interface: rws_new(text, text_end, touched words, data blocks, SPIN_QK, limits, callback) → state; rws_heads(state, heads, back-edges) → how many heads have a
 * region (written in order), or -1 when the callback failed (the Python then searches that job itself); rws_word(w, out) and rws_seq the decisions alone (tests);
 * rws_source() is the SHA-256 of this file, passed by the build (-DRWS_SOURCE=...), which patch_nso.py checks against its RWSPIN_SHA. */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifndef RWS_SOURCE
#define RWS_SOURCE "unknown"
#endif
#define EXPORT __attribute__((visibility("default")))

typedef struct { uint64_t lo, hi; } RS;
static inline RS rs_or(RS a, RS b) { RS r = {a.lo | b.lo, a.hi | b.hi}; return r; }
static inline RS rs_and(RS a, RS b) { RS r = {a.lo & b.lo, a.hi & b.hi}; return r; }
static inline RS rs_andnot(RS a, RS b) { RS r = {a.lo & ~b.lo, a.hi & ~b.hi}; return r; }
static inline int rs_any(RS a) { return (a.lo | a.hi) != 0; }
static inline int rs_eq(RS a, RS b) { return a.lo == b.lo && a.hi == b.hi; }
static inline RS rs_bit(int b) { RS r = {0, 0}; if (b >= 0) { if (b < 64) r.lo = 1ull << b; else r.hi = 1ull << (b - 64); } return r; }
static const RS RS0 = {0, 0};
#define NZCV 64
/* a general register field: 31 is the zero register (no register) or sp */
static inline RS GX(unsigned r) { return r == 31 ? RS0 : rs_bit((int)r); }
static inline RS GS(unsigned r) { return rs_bit((int)r); }
static inline RS VR(unsigned r) { return rs_bit(32 + (int)r); }

enum { K_NONE = 0, K_BR = 1, K_PC = 2, K_OK = 3 };

/* What the search reads of one word (patch_nso.py spin_word's tuple, and spin_rw's sets) */
typedef struct {
    int8_t kind, is_b, ld, lock0;
    int8_t st, st_val, st_base;   /* spin_store: st; its value register (-1: zr, None) and base register */
    int8_t has_tgt, rw;           /* has_tgt: the branch target is a + tgt, not the bits' (from the Python); rw: r and w are known */
    int64_t tgt;
    RS r, w;
} WV;

static void wv_none(WV *v) { memset(v, 0, sizeof *v); v->st_val = -1; v->st_base = -1; }
static void wv_rw(WV *v, RS r, RS w) { v->rw = 1; v->r = r; v->w = w; }

/* ---- the decoder: a class's values, or 0 where the Python decodes the word (capstone) ---- */

/* logical immediate N:immr:imms valid (LLVM's isValidDecodeLogicalImmediate) */
static int bitmask_ok(unsigned n, unsigned imms, unsigned sf)
{
    if (!sf && n) return 0;
    unsigned v = (n << 6) | (~imms & 0x3F);
    if (!v) return 0;
    int len = 31 - __builtin_clz(v);
    if (len < 1) return 0;
    unsigned levels = (1u << len) - 1;
    if ((imms & levels) == levels) return 0;
    return 1;
}

static int dec_dp_imm(uint32_t w, WV *v)
{
    unsigned rd = w & 31, rn = (w >> 5) & 31, sf = w >> 31;
    switch ((w >> 23) & 7) {
    case 0: case 1:   /* adr, adrp */
        v->kind = K_PC; wv_rw(v, RS0, GX(rd)); return 1;
    case 2: {   /* add / sub (immediate): add, adds, sub, subs; mov (to / from sp), cmp, cmn */
        unsigned s = (w >> 29) & 1;
        v->kind = K_OK;
        if (s && rd == 31) { wv_rw(v, GS(rn), rs_bit(NZCV)); return 1; }   /* cmp, cmn: (operands, nzcv) */
        if (s) wv_rw(v, (!sf && !(w & 0x40000000)) ? rs_or(GX(rd), GS(rn)) : GS(rn), rs_or(GX(rd), rs_bit(NZCV)));   /* capstone: adds (32-bit) reads its destination */
        else wv_rw(v, GS(rn), GS(rd));
        return 1;
    }
    case 4: {   /* logical (immediate): and, orr, eor, ands; mov, tst */
        unsigned opc = (w >> 29) & 3, n = (w >> 22) & 1, imms = (w >> 10) & 63;
        if (!bitmask_ok(n, imms, sf)) return 1;   /* unallocated: no instruction */
        v->kind = K_OK;
        if (opc == 3 && rd == 31) { wv_rw(v, GX(rn), rs_bit(NZCV)); return 1; }   /* tst */
        if (opc == 3) wv_rw(v, GX(rn), rs_or(GX(rd), rs_bit(NZCV)));
        else wv_rw(v, GX(rn), GS(rd));
        return 1;
    }
    case 5: {   /* move wide: movn, movz, movk; mov */
        unsigned opc = (w >> 29) & 3, hw = (w >> 21) & 3;
        if (opc == 1 || (!sf && hw >= 2)) return 1;
        v->kind = K_OK;
        if (opc == 3) wv_rw(v, GX(rd), GX(rd));
        else wv_rw(v, RS0, GX(rd));
        return 1;
    }
    case 6: {   /* bitfield: sbfm, bfm, ubfm and their aliases */
        unsigned opc = (w >> 29) & 3, n = (w >> 22) & 1, immr = (w >> 16) & 63, imms = (w >> 10) & 63;
        if (opc == 3 || n != sf || (!sf && ((immr | imms) & 32))) return 1;
        v->kind = K_OK; wv_rw(v, rs_or(GX(rd), GX(rn)), GX(rd));
        return 1;
    }
    case 7: {   /* extract: extr, ror */
        unsigned op21 = (w >> 29) & 3, n = (w >> 22) & 1, o0 = (w >> 21) & 1, imms = (w >> 10) & 63, rm = (w >> 16) & 31;
        if (op21 || o0 || n != sf || (!sf && (imms & 32))) return 0;
        v->kind = K_OK; wv_rw(v, rs_or(GX(rn), GX(rm)), GX(rd));
        return 1;
    }
    }
    return 0;
}

static int dec_dp_reg(uint32_t w, WV *v)
{
    unsigned rd = w & 31, rn = (w >> 5) & 31, rm = (w >> 16) & 31, ra = (w >> 10) & 31, sf = w >> 31, s = (w >> 29) & 1;
    if ((w & 0x1F000000) == 0x0A000000) {   /* logical (shifted register): and, bic, orr, orn, eor, eon, ands, bics; mov, mvn, tst */
        unsigned opc = (w >> 29) & 3, n = (w >> 21) & 1;
        if (!sf && (w & 0x8000)) return 1;
        v->kind = K_OK;
        if (opc == 3 && !n && rd == 31) { wv_rw(v, rs_or(GX(rn), GX(rm)), rs_bit(NZCV)); return 1; }   /* tst */
        if (opc == 3) wv_rw(v, rs_or(GX(rn), GX(rm)), rs_or(GX(rd), rs_bit(NZCV)));
        else wv_rw(v, rs_or(GX(rn), GX(rm)), GX(rd));
        return 1;
    }
    if ((w & 0x1F200000) == 0x0B000000) {   /* add / sub (shifted register); cmp, cmn, neg, negs */
        unsigned shift = (w >> 22) & 3;
        if (shift == 3 || (!sf && (w & 0x8000))) return 1;
        v->kind = K_OK;
        if (s && rd == 31) { wv_rw(v, rs_or(GX(rn), GX(rm)), rs_bit(NZCV)); return 1; }
        if (s) wv_rw(v, rs_or(GX(rn), GX(rm)), rs_or(GX(rd), rs_bit(NZCV)));
        else wv_rw(v, rs_or(GX(rn), GX(rm)), GX(rd));
        return 1;
    }
    if ((w & 0x1F200000) == 0x0B200000) {   /* add / sub (extended register); cmp, cmn */
        unsigned opt = (w >> 22) & 3, imm3 = (w >> 10) & 7;
        if (opt || imm3 > 4) return 1;
        v->kind = K_OK;
        if (s && rd == 31) { wv_rw(v, rs_or(GS(rn), GX(rm)), rs_bit(NZCV)); return 1; }
        if (s) wv_rw(v, rs_or(GS(rn), GX(rm)), rs_or(GX(rd), rs_bit(NZCV)));
        else wv_rw(v, rs_or(GS(rn), GX(rm)), GS(rd));
        return 1;
    }
    switch ((w >> 21) & 0xFF) {
    case 0xD0:   /* add / sub with carry: adc, adcs, sbc, sbcs; ngc, ngcs */
        if ((w >> 10) & 63) return 0;
        v->kind = K_OK;
        wv_rw(v, rs_or(rs_bit(NZCV), rs_or(GX(rn), GX(rm))), s ? rs_or(GX(rd), rs_bit(NZCV)) : GX(rd));
        return 1;
    case 0xD2: {   /* conditional compare: ccmn, ccmp (register, immediate) */
        if (!s || (w & 0x400) || (w & 0x10)) return 0;
        v->kind = K_OK;
        wv_rw(v, rs_or(rs_bit(NZCV), rs_or(GX(rn), (w & 0x800) ? RS0 : GX(rm))), rs_bit(NZCV));
        return 1;
    }
    case 0xD4: {   /* conditional select: csel, csinc, csinv, csneg; cset, csetm, cinc, cinv, cneg */
        if (s || (w & 0x800)) return 0;
        v->kind = K_OK;
        wv_rw(v, rs_or(rs_bit(NZCV), rs_or(GX(rn), GX(rm))), GX(rd));
        return 1;
    }
    case 0xD6: {   /* data processing (2 source, bit 30 0; 1 source, bit 30 1) */
        unsigned op = (w >> 10) & 63;
        if (s) return 0;
        if (!(w & 0x40000000)) {
            if (op != 2 && op != 3 && (op < 8 || op > 11)) return 0;   /* udiv, sdiv, lslv, lsrv, asrv, rorv */
            v->kind = K_OK; wv_rw(v, rs_or(GX(rn), GX(rm)), GX(rd));
            return 1;
        }
        if (rm || op > 5 || (op == 3 && !sf)) return 0;   /* rbit, rev16, rev / rev32, rev, clz, cls */
        v->kind = K_OK; wv_rw(v, GX(rn), GX(rd));
        return 1;
    }
    }
    if ((w & 0x1F000000) == 0x1B000000) {   /* data processing (3 source) */
        unsigned op54 = (w >> 29) & 3, op31 = (w >> 21) & 7, o0 = (w >> 15) & 1;
        if (op54) return 0;
        if (op31 == 0) { v->kind = K_OK; wv_rw(v, rs_or(GX(rn), rs_or(GX(rm), GX(ra))), GX(rd)); return 1; }   /* madd, msub; mul, mneg */
        if (!sf) return 0;
        if (op31 == 1 || op31 == 5) { v->kind = K_OK; wv_rw(v, rs_or(GX(rn), rs_or(GX(rm), GX(ra))), GX(rd)); return 1; }   /* s / umaddl, s / umsubl; s / umull, s / umnegl */
        if ((op31 == 2 || op31 == 6) && !o0 && ra == 31) { v->kind = K_OK; wv_rw(v, rs_or(GX(rn), GX(rm)), GX(rd)); return 1; }   /* smulh, umulh */
        return 0;
    }
    return 0;
}

/* load / store register: size, V, opc → mnemonic class. 'L' a load to Rt, 'P' prfm / prfum, 'S' a store, 'X' unallocated; *lock0 SPIN_LOCK0's number */
static char ls_class(unsigned size, unsigned vv, unsigned opc, int unscaled, int *lock0, int *wreg, int *xzr_store)
{
    *lock0 = 0; *wreg = 0; *xzr_store = 0;
    if (!vv) {
        if (size == 3 && opc == 2) return 'P';
        if (opc == 3 && size >= 2) return 'X';
        if (opc == 0) { *wreg = size <= 2; *xzr_store = size == 3; return 'S'; }
        if (opc == 1 && !unscaled) *lock0 = 2;   /* ldr, ldrb, ldrh */
        return 'L';
    }
    if (size == 0) { if (opc & 1) { if (!unscaled) *lock0 = 2; return 'L'; } return 'S'; }   /* b / q */
    if (opc >= 2) return 'X';
    if (opc == 1) { if (!unscaled) *lock0 = 2; return 'L'; }
    return 'S';
}

static int dec_ldst(uint32_t w, WV *v)
{
    unsigned rt = w & 31, rn = (w >> 5) & 31, rm = (w >> 16) & 31, rt2 = (w >> 10) & 31;
    unsigned size = w >> 30, vv = (w >> 26) & 1, opc = (w >> 22) & 3;
    int lock0, wreg, xzr;
    if ((w & 0x3B000000) == 0x39000000) {   /* unsigned immediate */
        char c = ls_class(size, vv, opc, 0, &lock0, &wreg, &xzr);
        if (c == 'X') return 1;
        if (c == 'S') { if (!vv && (wreg || (xzr && rt == 31))) { v->st = 1; v->st_val = rt == 31 ? -1 : (int8_t)rt; v->st_base = (int8_t)rn; } return 1; }
        v->kind = K_OK; v->lock0 = (int8_t)lock0;
        if (c == 'P') { wv_rw(v, GS(rn), RS0); return 1; }
        v->ld = 1; wv_rw(v, GS(rn), vv ? VR(rt) : GX(rt));
        return 1;
    }
    if ((w & 0x3B200000) == 0x38000000) {   /* unscaled (00), post-index (01), unprivileged (10), pre-index (11) */
        unsigned k = (w >> 10) & 3;
        char c = ls_class(size, vv, opc, k == 0 || k == 2, &lock0, &wreg, &xzr);
        if (c == 'X') return 1;
        if (k == 2 && (vv || c == 'P')) return 1;   /* unallocated */
        if (c == 'P' && k != 0) return 1;           /* (prfm has no pre / post-index form) */
        if (c == 'S') { if (k == 0 && !vv && (wreg || (xzr && rt == 31))) { v->st = 1; v->st_val = rt == 31 ? -1 : (int8_t)rt; v->st_base = (int8_t)rn; } return 1; }
        if (k == 2) return 0;   /* ldtr*: left to capstone */
        if ((k == 1 || k == 3) && !vv && rt == rn && rn != 31) return 0;   /* writeback to the loaded register: capstone's own rule */
        v->kind = K_OK; v->lock0 = (int8_t)lock0;
        if (c == 'P') { wv_rw(v, GS(rn), RS0); return 1; }
        v->ld = 1; wv_rw(v, GS(rn), rs_or(vv ? VR(rt) : GX(rt), k ? GS(rn) : RS0));
        return 1;
    }
    if ((w & 0x3B200C00) == 0x38200800) {   /* register offset */
        unsigned option = (w >> 13) & 7;
        char c = ls_class(size, vv, opc, 0, &lock0, &wreg, &xzr);
        if (c == 'X') return 1;
        if (c == 'S') return 1;   /* a store with an index: no spin_store */
        if (!(option & 2)) return 0;
        v->kind = K_OK; v->lock0 = (int8_t)lock0;
        if (c == 'P') { wv_rw(v, rs_or(GS(rn), GX(rm)), RS0); return 1; }
        v->ld = 1; wv_rw(v, rs_or(GS(rn), GX(rm)), vv ? VR(rt) : GX(rt));
        return 1;
    }
    if ((w & 0x3A000000) == 0x28000000) {   /* load / store pair (no-allocate, post-index, offset, pre-index) */
        unsigned l = (w >> 22) & 1, k = (w >> 23) & 3, o = w >> 30;
        if (!l) return (o == 1 && !vv) ? 0 : 1;   /* stores: no spin_store (stgp: left to capstone) */
        if (o == 3) return 1;
        if (!vv && o == 1 && k == 0) return 1;   /* (ldpsw has no no-allocate form) */
        if (rt == rt2) return 0;
        if (k != 2 && k != 0 && !vv && (rn == rt || rn == rt2) && rn != 31) return 0;
        v->kind = K_OK; v->ld = 1;
        wv_rw(v, GS(rn), rs_or((k & 1) ? GS(rn) : RS0, vv ? rs_or(VR(rt), VR(rt2)) : rs_or(GX(rt), GX(rt2))));
        return 1;
    }
    if ((w & 0x3F000000) == 0x08000000) {   /* load / store exclusive, ordered */
        unsigned o2 = (w >> 23) & 1, l = (w >> 22) & 1, o1 = (w >> 21) & 1, o0 = (w >> 15) & 1;
        if (o1) return (o2 || size < 2) ? 0 : (l ? 0 : 1);   /* cas* / casp: capstone; stxp / stlxp: no values; ldxp: capstone */
        if (!l) {
            if (!o2) return 1;   /* stxr, stlxr: nothing */
            if (rm != 31 || rt2 != 31) return 0;
            if (o0 && size >= 2) v->lock0 = 1;   /* stlr */
            return 1;   /* stlr*, stllr*: no values */
        }
        if (rm != 31 || rt2 != 31) return 0;
        if (o2 && !o0) return 0;   /* ldlar: capstone */
        v->kind = K_OK; v->ld = 1;
        if (!o2 && size >= 2) v->lock0 = 1;   /* ldxr, ldaxr */
        wv_rw(v, GS(rn), GX(rt));
        return 1;
    }
    if ((w & 0x3B000000) == 0x18000000) {   /* load register (literal): no memory operand (spin_kind None) */
        unsigned o = w >> 30;
        if (vv && o == 3) return 1;
        if (!vv && o == 3) return 1;          /* prfm (literal) */
        v->ld = 0;                             /* (spin_word's load flag counts only with a kind) */
        if (o != 2 || vv) v->lock0 = 2;        /* ldr; ldrsw has none */
        return 1;
    }
    return 0;
}

static int dec_branch_sys(uint32_t w, WV *v)
{
    unsigned t = w >> 24;
    if ((w & 0x7C000000) == 0x14000000) {   /* b, bl */
        if (w >> 31) return 1;   /* bl: SPIN_BAD */
        v->kind = K_BR; v->is_b = 1; wv_rw(v, RS0, RS0); return 1;
    }
    if ((w & 0xFF000010) == 0x54000000) { v->kind = K_BR; wv_rw(v, rs_bit(NZCV), RS0); return 1; }   /* b.cond */
    if ((w & 0x7E000000) == 0x34000000 || (w & 0x7E000000) == 0x36000000) { v->kind = K_BR; wv_rw(v, GX(w & 31), RS0); return 1; }   /* cbz / cbnz, tbz / tbnz */
    if (t == 0xD6 || t == 0xD7) return 1;   /* br, blr, ret, eret, drps and their pointer authentication forms: SPIN_BAD */
    if (t == 0xD4) return ((w >> 21) & 7) == 3 ? 0 : 1;   /* svc, hvc, smc, brk, hlt, dcps: SPIN_BAD (tcancel: capstone) */
    if ((w & 0xFFF00000) == 0xD5300000 || (w & 0xFFF00000) == 0xD5100000) return 1;   /* mrs, msr (register): SPIN_BAD */
    if ((w & 0xFFFFF01F) == 0xD503201F) {   /* hints */
        unsigned h = (w >> 5) & 0x7F;
        if (h == 0 || h == 1) { v->kind = K_OK; wv_rw(v, RS0, RS0); return 1; }   /* nop, yield */
        if (h >= 2 && h <= 5) return 1;   /* wfe, wfi, sev, sevl: SPIN_BAD */
        return 0;
    }
    if ((w & 0xFFFFF01F) == 0xD503301F) {   /* barriers */
        unsigned op2 = (w >> 5) & 7, crm = (w >> 8) & 15;
        if (op2 == 2) return 1;   /* clrex: SPIN_BAD */
        if (op2 == 5 || op2 == 6 || (op2 == 4 && crm != 0 && crm != 4)) { v->kind = K_OK; wv_rw(v, RS0, RS0); return 1; }   /* dmb, isb, dsb */
        return 0;
    }
    return 0;
}

/* the word's values from its bits → 1, or 0 where the Python decodes it */
static int dec(uint32_t w, WV *v)
{
    wv_none(v);
    unsigned op0 = (w >> 25) & 15;
    int ok;
    if ((op0 & 0xE) == 0x8) ok = dec_dp_imm(w, v);                 /* 100x */
    else if ((op0 & 0x7) == 0x5) ok = dec_dp_reg(w, v);            /* x101 */
    else if ((op0 & 0x5) == 0x4) ok = dec_ldst(w, v);              /* x1x0 */
    else if ((op0 & 0xE) == 0xA) ok = dec_branch_sys(w, v);        /* 101x */
    else ok = 0;
    if (!ok) wv_none(v);
    return ok;
}

/* ---- the state of one process: the text, the touched words, the data blocks, the answers of the Python ---- */
typedef int (*ask_fn)(int mode, int64_t a, int64_t *out);
enum { ASK_WORD = 0, ASK_RW = 1, ASK_RMW = 2, ASK_LOCK = 3 };

typedef struct { int8_t found, kind, base; int nins, pre, count; int64_t k; RS uses, defs; } Seq;   /* a spin_rmw or spin_lock result */
typedef struct { int64_t key; union { WV wv; Seq seq; } u; } Ent;

typedef struct {
    uint8_t *text; int64_t text_end, len;
    uint8_t *touched;   /* a bit per word of [0, len) */
    int64_t *db; int ndb;
    uint8_t qk[1 << 13];
    int paths, steps, stores;
    ask_fn ask;
    Ent *tab; size_t cap, n;
    int failed, native_seq;
    uint64_t asked[4];
} St;

static inline uint32_t word_at(St *s, int64_t a) { uint32_t w = 0; if (a >= 0 && a + 4 <= s->len) memcpy(&w, s->text + a, 4); return w; }
static inline int byte_at(St *s, int64_t a) { return a >= 0 && a < s->len ? s->text[a] : 0; }
static inline int touched(St *s, int64_t a) { return a >= 0 && !(a & 3) && a < s->len && (s->touched[a >> 5] >> ((a >> 2) & 7) & 1); }

static Ent *tab_find(St *s, int64_t key, int add)
{
    if (add && (s->n + 1) * 2 > s->cap) {
        size_t nc = s->cap ? s->cap * 2 : 1 << 14;
        Ent *nt = calloc(nc, sizeof(Ent));
        if (!nt) return NULL;
        for (size_t i = 0; i < nc; i++) nt[i].key = -1;
        for (size_t i = 0; i < s->cap; i++) if (s->tab[i].key >= 0) {
            size_t h = (size_t)(s->tab[i].key * 0x9E3779B97F4A7C15ull) & (nc - 1);
            while (nt[h].key >= 0) h = (h + 1) & (nc - 1);
            nt[h] = s->tab[i];
        }
        free(s->tab); s->tab = nt; s->cap = nc;
    }
    if (!s->cap) return NULL;
    size_t h = (size_t)(key * 0x9E3779B97F4A7C15ull) & (s->cap - 1);
    while (s->tab[h].key >= 0) { if (s->tab[h].key == key) return &s->tab[h]; h = (h + 1) & (s->cap - 1); }
    if (!add) return NULL;
    s->tab[h].key = key; s->n++;
    return &s->tab[h];
}

static RS rs_from(const int64_t *p) { RS r = {(uint64_t)p[0], (uint64_t)p[1]}; return r; }

/* spin_word's values of the word at a (and spin_rw's when need_rw): the decoder's, or the Python's */
static int word_values(St *s, int64_t a, int need_rw, WV *out)
{
    if (dec(word_at(s, a), out) && (!need_rw || out->rw)) return 1;
    int64_t key = a << 2 | ASK_WORD;
    Ent *e = tab_find(s, key, 0);
    if (!e) {
        int64_t o[16] = {0};
        s->asked[ASK_WORD]++;
        if (s->ask(ASK_WORD, a, o)) { s->failed = 1; return 0; }
        if (!(e = tab_find(s, key, 1))) { s->failed = 1; return 0; }
        WV *v = &e->u.wv; wv_none(v);
        v->kind = (int8_t)o[0]; v->has_tgt = (int8_t)o[1]; v->tgt = o[2]; v->is_b = (int8_t)o[3];
        v->st = (int8_t)o[4]; v->st_val = (int8_t)o[5]; v->st_base = (int8_t)o[6]; v->ld = (int8_t)o[7]; v->lock0 = (int8_t)o[8];
    }
    if (need_rw && !e->u.wv.rw) {
        int64_t o[16] = {0};
        s->asked[ASK_RW]++;
        if (s->ask(ASK_RW, a, o)) { s->failed = 1; return 0; }
        e = tab_find(s, key, 0);   /* (the table did not grow in between) */
        e->u.wv.rw = 1; e->u.wv.r = rs_from(o); e->u.wv.w = rs_from(o + 2);
    }
    *out = e->u.wv;
    return 1;
}

/* spin_bt: the target of the direct branch word w at a from its bits */
static int64_t spin_bt(uint32_t w, int64_t a)
{
    unsigned t = w >> 24; int64_t d;
    if ((t & 0x7C) == 0x14) { d = w & 0x3FFFFFF; d -= (d & 0x2000000) << 1; }
    else if ((t & 0x7E) == 0x36) { d = (w >> 5) & 0x3FFF; d -= (d & 0x2000) << 1; }
    else { d = (w >> 5) & 0x7FFFF; d -= (d & 0x40000) << 1; }
    return a + 4 * d;
}

/* ---- spin_rmw and spin_lock, where every word they read is one of the encodings below (any other shape is asked from the Python) ----
 * A register's name as capstone prints it, as a number: the field, 32 for sp / wsp and 33 for xzr / wzr when the field is 31, plus 64 for an x name */
static inline int nm(unsigned r, int x, int sp) { return (r == 31 ? (sp ? 32 : 33) : (int)r) | (x ? 64 : 0); }
static inline int nrm(unsigned r, int sp) { return r == 31 ? (sp ? 31 : -1) : (int)r; }   /* spin_norm's bit (-1: None) */
#define IS_LDXR(w) (((w) & 0xBFFF7C00u) == 0x885F7C00u)    /* ldxr / ldaxr w / x (Rs, Rt2 all ones) */
#define IS_STXR(w) (((w) & 0xBFE00000u) == 0x88000000u)    /* stxr / stlxr w / x (capstone reads one whatever its Rt2 field holds) */
#define CANON_STXR(w) (((w) & 0x7C00u) == 0x7C00u)        /* its Rt2 field all ones: a shape checked against capstone; another is asked */
#define IS_STLR(w) (((w) & 0xBFFFFC00u) == 0x889FFC00u)    /* stlr w / x */
#define IS_CBZ(w) (((w) & 0x7F000000u) == 0x34000000u)
#define IS_CBNZ(w) (((w) & 0x7F000000u) == 0x35000000u)
#define IS_CLREX(w) (((w) & 0xFFFFF0FFu) == 0xD503305Fu)
#define IS_B(w) (((w) & 0xFC000000u) == 0x14000000u)
#define IS_BCOND(w, c) (((w) & 0xFF00001Fu) == (0x54000000u | (c)))
#define IS_CMPI(w) (((w) & 0x7F80001Fu) == 0x7100001Fu)   /* cmp w / x, #imm (subs, Rd zr): its second operand an immediate */
/* → 1 decided (q filled), 0 asked from the Python */
static int rmw_c(St *s, int64_t a, Seq *q)
{
    uint32_t w0 = word_at(s, a), w1 = word_at(s, a + 4), w2 = word_at(s, a + 8), w3 = word_at(s, a + 12);
    memset(q, 0, sizeof *q);
    if (!IS_LDXR(w0)) return (w0 & 0x7FFF7C00u) == 0x085F7C00u;   /* ldxrb / ldaxrb / ldxrh / ldaxrh: no; any other shape: asked */
    unsigned op = w1 & 0x7FC00000u;
    if (op != 0x11000000u && op != 0x51000000u) return 1;    /* add / sub (immediate, no lsl #12): any other second word is none of spin_rmw's */
    unsigned d1 = w1 & 31, n1 = (w1 >> 5) & 31, imm = (w1 >> 10) & 0xFFF; int x1 = w1 >> 31;
    if (op == 0x11000000u && !imm && (d1 == 31 || n1 == 31)) return 1;   /* mov (to / from sp) */
    if (!IS_STXR(w2) || !IS_CBNZ(w3)) return 1;
    unsigned t0 = w0 & 31, rn = (w0 >> 5) & 31; int x0 = (w0 >> 30) & 1;
    unsigned t2 = w2 & 31, s2 = (w2 >> 16) & 31, n2 = (w2 >> 5) & 31; int x2 = (w2 >> 30) & 1;
    if (n2 != rn) return 1;
    if (nm(n1, x1, 1) != nm(t0, x0, 0) || nm(t2, x2, 0) != nm(d1, x1, 1) || nm(w3 & 31, w3 >> 31, 0) != nm(s2, 0, 0) || spin_bt(w3, a + 12) != a) return 1;
    if (!CANON_STXR(w2)) return 0;
    q->found = 1; q->base = (int8_t)nrm(rn, 1); q->k = op == 0x11000000u ? (int64_t)imm : -(int64_t)imm;
    q->defs = rs_or(rs_bit(nrm(t0, 0)), rs_or(rs_bit(nrm(d1, 1)), rs_bit(nrm(s2, 0))));
    q->nins = 4; q->count = 4; q->uses = rs_bit(q->base);
    return 1;
}

static int lock_c(St *s, int64_t a, Seq *q)
{
    uint32_t w0 = word_at(s, a);
    memset(q, 0, sizeof *q);
    if (a < 8) return 0;
    if (IS_LDXR(w0)) {
        unsigned t0 = w0 & 31, rn = (w0 >> 5) & 31; int x0 = (w0 >> 30) & 1;
        if (x0) return 1;   /* an x register: none of spin_lock's */
        uint32_t w1 = word_at(s, a + 4), w2 = word_at(s, a + 8), w3 = word_at(s, a + 12), w4 = word_at(s, a + 16), w5 = word_at(s, a + 20);
        if (IS_CBZ(w1) && nm(w1 & 31, w1 >> 31, 0) == nm(t0, 0, 0) && spin_bt(w1, a + 4) == a + 16 && IS_CLREX(w2) && IS_B(w3) && spin_bt(w3, a + 12) == a
            && IS_STXR(w4) && ((w4 >> 5) & 31) == rn && (w4 & 31) != 31 && IS_CBNZ(w5) && nm(w5 & 31, w5 >> 31, 0) == nm((w4 >> 16) & 31, 0, 0) && spin_bt(w5, a + 20) == a) {
            if (!CANON_STXR(w4)) return 0;
            q->found = 1; q->kind = 1; q->base = (int8_t)nrm(rn, 1); q->nins = 6; q->pre = 0; q->count = 6;
            q->uses = rs_or(rs_bit(q->base), rs_bit(nrm(w4 & 31, 0)));
            q->defs = rs_or(rs_bit(nrm(t0, 0)), rs_bit(nrm((w4 >> 16) & 31, 0)));
            return 1;
        }
        uint32_t p = word_at(s, a - 4);
        if (IS_CLREX(p) && IS_CMPI(w1) && nm((w1 >> 5) & 31, w1 >> 31, 1) == nm(t0, 0, 0) && IS_BCOND(w2, 1) && spin_bt(w2, a + 8) == a - 4 && IS_STXR(w3)
            && (w3 & 31) == 31 && !((w3 >> 30) & 1) && ((w3 >> 5) & 31) == rn && IS_CBNZ(w4) && nm(w4 & 31, w4 >> 31, 0) == nm((w3 >> 16) & 31, 0, 0) && spin_bt(w4, a + 16) == a)
            return 0;   /* the release: the Python's (it needs the word after these to decode) */
        return 1;
    }
    if (IS_STLR(w0)) {
        if ((w0 & 31) != 31) return 1;
        q->found = 1; q->kind = 2; q->base = (int8_t)nrm((w0 >> 5) & 31, 1); q->nins = 1; q->pre = 0; q->count = 1; q->uses = rs_bit(q->base);
        return 1;
    }
    uint32_t c = w0 & 0xFFC00000u;
    if (c == 0xF9400000u || ((w0 & 0x3F400000u) == 0x3D400000u)) return 1;   /* ldr x / ldr b, h, s, d, q (unsigned offset): not a w register */
    if (c != 0xB9400000u && c != 0x39400000u && c != 0x79400000u) return 0;    /* ldr w / ldrb / ldrh (unsigned offset); any other shape: asked */
    uint32_t w1 = word_at(s, a + 4), w2 = word_at(s, a + 8);
    unsigned t0 = w0 & 31;
    if (IS_CMPI(w1) && nm((w1 >> 5) & 31, w1 >> 31, 1) == nm(t0, 0, 0) && (IS_BCOND(w2, 0) || IS_BCOND(w2, 1)) && spin_bt(w2, a + 8) == a) {
        q->found = 1; q->kind = 3; q->base = (int8_t)nrm((w0 >> 5) & 31, 1); q->nins = 3; q->pre = 0; q->count = 3; q->uses = rs_bit(q->base);
        q->defs = rs_or(rs_bit(nrm(t0, 0)), rs_bit(NZCV));
    }
    return 1;
}

/* spin_rmw (mode ASK_RMW) or spin_lock (ASK_LOCK) at a, from the Python */
static int seq_at(St *s, int64_t a, int mode, Seq *out)
{
    int64_t key = a << 2 | mode;
    if (s->native_seq && (mode == ASK_RMW ? rmw_c(s, a, out) : lock_c(s, a, out))) return 1;
    Ent *e = tab_find(s, key, 0);
    if (!e) {
        int64_t o[16] = {0};
        s->asked[mode]++;
        if (s->ask(mode, a, o)) { s->failed = 1; return 0; }
        if (!(e = tab_find(s, key, 1))) { s->failed = 1; return 0; }
        Seq *q = &e->u.seq; memset(q, 0, sizeof *q);
        q->found = (int8_t)o[0];
        if (mode == ASK_RMW) { q->base = (int8_t)o[1]; q->k = o[2]; q->defs = rs_from(o + 3); q->nins = 4; q->count = 4; q->uses = rs_bit(q->base); }
        else { q->kind = (int8_t)o[1]; q->base = (int8_t)o[2]; q->nins = (int)o[3]; q->pre = (int)o[4]; q->count = (int)o[5]; q->uses = rs_from(o + 6); q->defs = rs_from(o + 8); }
    }
    *out = e->u.seq;
    return 1;
}

/* spin_quick */
static int spin_quick(St *s, int64_t T, int64_t hi, uint8_t *seen, int64_t *todo)
{
    int64_t nw = (hi - T) / 4 + 1; int nt = 0;
    memset(seen, 0, (size_t)nw);
    todo[nt++] = T;
    while (nt) {
        int64_t a = todo[--nt];
        if (a < T || a > hi || ((a - T) & 3)) continue;   /* (a word of the region: T and hi are word offsets) */
        if (seen[(a - T) >> 2]) continue;
        seen[(a - T) >> 2] = 1;
        uint32_t w = word_at(s, a); int c = s->qk[(w >> 21) << 2 | ((w >> 10) & 3)];
        int64_t succ[2]; int ns;
        if (c == 1) { int64_t d = (int64_t)((w & 0x3FFFFFF) ^ 0x2000000) - 0x2000000; succ[0] = a + d * 4; ns = 1; }
        else if (c == 2) { int64_t d = (int64_t)(((w >> 5) & 0x7FFFF) ^ 0x40000) - 0x40000; succ[0] = a + d * 4; succ[1] = a + 4; ns = 2; }
        else if (c == 3) { int64_t d = (int64_t)(((w >> 5) & 0x3FFF) ^ 0x2000) - 0x2000; succ[0] = a + d * 4; succ[1] = a + 4; ns = 2; }
        else if (c == 4) continue;
        else { succ[0] = a + 4; ns = 1; }
        for (int i = 0; i < ns; i++) if (succ[i] == T) return 1;
        for (int i = 0; i < ns; i++) todo[nt++] = succ[i];
    }
    return 0;
}

/* ---- spin_region1 ---- */
enum { N_BR = 1, N_PC, N_OK, N_STC, N_RMW, N_ACQ, N_REL, N_TST };
typedef struct {
    int8_t kind, ld, base, nsucc, has_ud;
    int nins, pre;
    int64_t k, succ[2];
    RS uses, defs;
} Node;

typedef struct {   /* one search's arrays, sized for its words */
    int64_t T, hi, nw;
    Node *nodes; uint8_t *isnode, *bad, *inR, *R2, *back, *valid, *badback, *onpath;
    int64_t *todo; int ntodo;
    int64_t *inner; int ninner, capinner;
    int *pred, *predn, *predo;   /* predecessors: predo[i] .. predo[i] + predn[i] in pred */
    int *stk;
    RS *live;
    int cnt0, cnt1;
} Srch;

typedef struct { int8_t lock, base; int ver; int64_t val; } DEnt;

static inline int idx_of(Srch *x, int64_t a) { return (a >= x->T && a <= x->hi && !((a - x->T) & 3)) ? (int)((a - x->T) >> 2) : -1; }

static int in_inner(Srch *x, int64_t a) { for (int i = 0; i < x->ninner; i++) if (x->inner[i] == a) return 1; return 0; }
static int add_inner(Srch *x, int64_t a)
{
    if (in_inner(x, a)) return 1;
    if (x->ninner == x->capinner) {
        int nc = x->capinner ? x->capinner * 2 : 16; int64_t *ni = realloc(x->inner, sizeof(int64_t) * (size_t)nc);
        if (!ni) return 0;
        x->inner = ni; x->capinner = nc;
    }
    x->inner[x->ninner++] = a; return 1;
}

/* prune(ends) into keep: ends and every node that reaches one of them without passing T */
static void prune(Srch *x, const uint8_t *ends, uint8_t *keep)
{
    int n = 0;
    for (int i = 0; i < x->nw; i++) { keep[i] = ends[i]; if (ends[i]) x->stk[n++] = i; }
    while (n) {
        int i = x->stk[--n];
        for (int j = 0; j < x->predn[i]; j++) { int p = x->pred[x->predo[i] + j]; if (!keep[p]) { keep[p] = 1; x->stk[n++] = p; } }
    }
}

#define WALK_NO 1
static int walk(St *s, Srch *x, int i, int *ver, DEnt *delta, int nd)
{
    if (++x->cnt1 > s->steps) return WALK_NO;
    Node *n = &x->nodes[i];
    DEnt dl[nd + 1]; int nn = nd;
    if (nd) memcpy(dl, delta, sizeof(DEnt) * (size_t)nd);
    if (n->kind == N_RMW || n->kind == N_ACQ || n->kind == N_REL) {
        int8_t lock = n->kind != N_RMW; int v = ver[(int)n->base];
        int j;
        for (j = 0; j < nn; j++) if (dl[j].lock == lock && dl[j].base == n->base && dl[j].ver == v) break;
        if (j == nn) { dl[nn].lock = lock; dl[nn].base = n->base; dl[nn].ver = v; dl[nn].val = 0; nn++; }
        dl[j].val += n->k;
        if (lock && !(0 <= dl[j].val && dl[j].val <= 1)) return WALK_NO;
    }
    int vb[128]; int *vv = ver;
    if (rs_any(n->defs)) {
        memcpy(vb, ver, sizeof vb); vv = vb;
        for (int r = 0; r < 128; r++) if (r < 64 ? (n->defs.lo >> r & 1) : (n->defs.hi >> (r - 64) & 1)) vb[r]++;
    }
    for (int k = 0; k < n->nsucc; k++) {
        int64_t sa = n->succ[k];
        if (sa == x->T) {
            if (++x->cnt0 > s->paths) return WALK_NO;
            for (int j = 0; j < nn; j++) if (dl[j].val) { x->badback[i] = 1; break; }
        } else {
            int si = idx_of(x, sa);
            if (si >= 0 && x->inR[si]) {
                if (x->onpath[si]) return WALK_NO;
                x->onpath[si] = 1;
                int r = walk(s, x, si, vv, dl, nn);
                x->onpath[si] = 0;
                if (r) return r;
            }
        }
    }
    return 0;
}

enum { R1_NONE = 0, R1_DICT = 1, R1_VARY = 2, R1_FAIL = -1 };

/* spin_region1(T, bes, ban, stores) → R1_*; for R1_VARY the vary set is or-ed into ban */
static int region1(St *s, Srch *x, uint8_t *ban, int stores)
{
    int64_t T = x->T, hi = x->hi; int nw = (int)x->nw;
    memset(x->isnode, 0, (size_t)nw); memset(x->bad, 0, (size_t)nw); memset(x->back, 0, (size_t)nw);
    x->ninner = 0; x->ntodo = 0;
    x->todo[x->ntodo++] = T;
    while (x->ntodo) {
        int64_t a = x->todo[--x->ntodo];
        int i = idx_of(x, a);   /* (a word of the region: T, or a successor in (T, hi]) */
        if (i < 0) return R1_FAIL;   /* (a target not on a word: the Python decides) */
        if (x->isnode[i] || x->bad[i]) continue;
        if (ban[i]) { x->bad[i] = 1; continue; }
        uint32_t w = word_at(s, a);
        Seq rmw = {0}, lk = {0};
        if (a + 12 <= hi && (w & 0x3FE00000) == 0x08400000 && (byte_at(s, a + 15) & 0x7F) == 0x35) { if (!seq_at(s, a, ASK_RMW, &rmw)) return R1_FAIL; }
        WV wd;
        if (!word_values(s, a, 0, &wd)) return R1_FAIL;
        if (!rmw.found && (wd.lock0 == 1 || (wd.lock0 == 2 && byte_at(s, a + 11) == 0x54 && (byte_at(s, a + 4) & 0x1F) == 0x1F))) { if (!seq_at(s, a, ASK_LOCK, &lk)) return R1_FAIL; }
        if (lk.found && lk.kind == 3 && a == T) lk.found = 0;   /* the region's own test loop (kind 3: 'tst') */
        Node n; memset(&n, 0, sizeof n); n.base = -1;
        if (rmw.found) { n.kind = N_RMW; n.base = rmw.base; n.k = rmw.k; n.nins = 4; n.uses = rmw.uses; n.defs = rmw.defs; n.has_ud = 1; n.succ[0] = a + 16; n.nsucc = 1; }
        else if (lk.found) {
            n.kind = lk.kind == 1 ? N_ACQ : lk.kind == 2 ? N_REL : N_TST; n.base = lk.base; n.k = lk.kind == 1 ? 1 : lk.kind == 2 ? -1 : 0;
            n.nins = lk.nins; n.pre = lk.pre; n.uses = lk.uses; n.defs = lk.defs; n.has_ud = 1; n.succ[0] = a + 4 * lk.count; n.nsucc = 1;
        } else if (stores && wd.st) {
            n.kind = N_STC; n.nins = 1; n.uses = rs_or(rs_bit(wd.st_base), wd.st_val >= 0 ? rs_bit(wd.st_val) : RS0); n.has_ud = 1; n.succ[0] = a + 4; n.nsucc = 1;
        } else {
            if (wd.kind == K_NONE) { x->bad[i] = 1; continue; }
            n.nins = 1; n.ld = wd.ld;
            if (wd.kind == K_BR) {
                int64_t t = wd.has_tgt ? a + wd.tgt : spin_bt(w, a);
                n.kind = N_BR; n.succ[0] = t; n.nsucc = 1;
                if (!wd.is_b) { n.succ[1] = a + 4; n.nsucc = 2; }
            } else { n.kind = wd.kind == K_PC ? N_PC : N_OK; n.succ[0] = a + 4; n.nsucc = 1; }
        }
        int t_ = 0;
        for (int k = 0; k < n.nins; k++) if (touched(s, a + 4 * (k - n.pre))) { t_ = 1; break; }
        if (t_) { x->bad[i] = 1; continue; }
        x->nodes[i] = n; x->isnode[i] = 1;
        if (n.kind == N_RMW) { if (!add_inner(x, a + 4) || !add_inner(x, a + 8) || !add_inner(x, a + 12)) return R1_FAIL; }
        if (n.kind == N_ACQ || n.kind == N_REL || n.kind == N_TST)
            for (int k = 0; k < n.nins; k++) { int64_t b = a + 4 * (k - n.pre); if (b != a && !add_inner(x, b)) return R1_FAIL; }
        for (int k = 0; k < n.nsucc; k++) {
            int64_t sa = n.succ[k];
            if (sa == T) x->back[i] = 1;
            else if (T < sa && sa <= hi) x->todo[x->ntodo++] = sa;
        }
    }
    int anyback = 0;
    for (int i = 0; i < nw; i++) if (x->back[i]) { anyback = 1; break; }
    if (!anyback) return R1_NONE;
    for (int i = 0; i < nw; i++) if (x->isnode[i]) {
        if (in_inner(x, T + 4 * (int64_t)i)) return R1_NONE;
        for (int k = 0; k < x->nodes[i].nsucc; k++) if (in_inner(x, x->nodes[i].succ[k])) return R1_NONE;
    }
    /* predecessors (edges into T left out) */
    memset(x->predn, 0, sizeof(int) * (size_t)nw);
    for (int i = 0; i < nw; i++) if (x->isnode[i])
        for (int k = 0; k < x->nodes[i].nsucc; k++) { int si = idx_of(x, x->nodes[i].succ[k]); if (si >= 0 && x->nodes[i].succ[k] != T) x->predn[si]++; }
    int o = 0;
    for (int i = 0; i < nw; i++) { x->predo[i] = o; o += x->predn[i]; x->predn[i] = 0; }
    for (int i = 0; i < nw; i++) if (x->isnode[i])
        for (int k = 0; k < x->nodes[i].nsucc; k++) { int si = idx_of(x, x->nodes[i].succ[k]); if (si >= 0 && x->nodes[i].succ[k] != T) x->pred[x->predo[si] + x->predn[si]++] = i; }
    prune(x, x->back, x->inR);
    for (int i = 0; i < nw; i++) if (x->inR[i] && !x->nodes[i].has_ud) {
        WV v;
        if (!word_values(s, T + 4 * (int64_t)i, 1, &v)) return R1_FAIL;
        x->nodes[i].uses = v.r; x->nodes[i].defs = v.w; x->nodes[i].has_ud = 1;
    }
    /* walk: every path from T to a back-edge */
    memset(x->badback, 0, (size_t)nw); memset(x->onpath, 0, (size_t)nw);
    x->cnt0 = x->cnt1 = 0;
    int ver[128]; memset(ver, 0, sizeof ver);
    x->onpath[0] = 1;
    if (walk(s, x, 0, ver, NULL, 0)) return R1_NONE;
    int anyvalid = 0;
    for (int i = 0; i < nw; i++) { x->valid[i] = x->back[i] && !x->badback[i]; anyvalid |= x->valid[i]; }
    if (!anyvalid) return R1_NONE;
    prune(x, x->valid, x->R2);
    /* the live registers: the least fixed point, as the Python's loop */
    for (int i = 0; i < nw; i++) x->live[i] = RS0;
    for (int more = 1; more;) {
        more = 0;
        for (int i = nw - 1; i >= 0; i--) if (x->R2[i]) {
            Node *n = &x->nodes[i]; RS out = RS0;
            for (int k = 0; k < n->nsucc; k++) {
                int64_t sa = n->succ[k];
                if (sa == T) { if (x->valid[i]) out = rs_or(out, x->live[0]); }
                else { int si = idx_of(x, sa); if (si >= 0 && x->R2[si]) out = rs_or(out, x->live[si]); }
            }
            RS nw_ = rs_or(n->uses, rs_andnot(out, n->defs));
            if (!rs_eq(nw_, x->live[i])) { x->live[i] = nw_; more = 1; }
        }
    }
    RS defs = RS0; int nstc = 0, polled = 0;
    for (int i = 0; i < nw; i++) if (x->R2[i]) {
        Node *n = &x->nodes[i];
        defs = rs_or(defs, n->defs);
        if (n->kind == N_STC) nstc++;
        if (n->kind == N_RMW || n->kind == N_ACQ || n->kind == N_REL || n->kind == N_TST || n->ld) polled = 1;
    }
    if (nstc && !polled) return R1_NONE;
    int vary = 0;
    for (int i = 0; i < nw; i++) if (x->R2[i] && x->nodes[i].kind == N_STC && rs_any(rs_and(x->nodes[i].uses, defs))) { ban[i] = 1; vary = 1; }
    if (vary) return R1_VARY;
    if (nstc > s->stores) return R1_NONE;
    if (rs_any(rs_and(x->live[0], defs))) return R1_NONE;
    return R1_DICT;
}

/* spin_region(T, bes) is not None → 1, 0; -1 when the Python failed to answer */
static int region(St *s, int64_t T, const int64_t *bes, int nb)
{
    int64_t hi = bes[0];
    for (int i = 1; i < nb; i++) if (bes[i] > hi) hi = bes[i];
    for (int i = 0; i < s->ndb; i++) if (s->db[2 * i] < hi + 4 && T < s->db[2 * i + 1]) return 0;
    if (hi < T || ((hi - T) & 3)) return 0;   /* (the scan's back-edges: words at most SPIN_WIN after T) */
    int64_t nw = (hi - T) / 4 + 1;
    Srch x; memset(&x, 0, sizeof x);
    x.T = T; x.hi = hi; x.nw = nw;
    size_t z = (size_t)nw;
    uint8_t *b = calloc(z, 11);
    x.nodes = calloc(z, sizeof(Node)); x.todo = malloc(sizeof(int64_t) * (2 * z + 4)); x.pred = malloc(sizeof(int) * 2 * z); x.predn = malloc(sizeof(int) * z);
    x.predo = malloc(sizeof(int) * z); x.stk = malloc(sizeof(int) * (2 * z + 2)); x.live = malloc(sizeof(RS) * z);
    int res = 0;
    if (!b || !x.nodes || !x.todo || !x.pred || !x.predn || !x.predo || !x.stk || !x.live) { res = -1; goto out; }
    x.isnode = b; x.bad = b + z; x.inR = b + 2 * z; x.R2 = b + 3 * z; x.back = b + 4 * z; x.valid = b + 5 * z; x.badback = b + 6 * z; x.onpath = b + 7 * z;
    uint8_t *ban = b + 8 * z, *seen = b + 9 * z;
    if (!spin_quick(s, T, hi, seen, x.todo)) goto out;
    /* the region without stores first: when it has one, spin_region returns a region either way */
    int old = region1(s, &x, ban, 0);
    if (old == R1_FAIL) { res = -1; goto out; }
    if (old == R1_DICT) { res = 1; goto out; }
    int g = R1_NONE;
    for (int it = 0; it < 8; it++) {
        g = region1(s, &x, ban, 1);
        if (g == R1_FAIL) { res = -1; goto out; }
        if (g != R1_VARY) break;
    }
    res = g == R1_DICT;
out:
    free(b); free(x.nodes); free(x.todo); free(x.pred); free(x.predn); free(x.predo); free(x.stk); free(x.live); free(x.inner);
    return res;
}

/* ---- the interface ---- */
EXPORT const char *rws_source(void) { return RWS_SOURCE; }

EXPORT void *rws_new(const uint8_t *text, int64_t len, int64_t text_end, const int64_t *touched_words, int64_t ntouched, const int64_t *db, int ndb,
                     const uint8_t *qk, int paths, int steps, int stores, ask_fn ask)
{
    St *s = calloc(1, sizeof(St));
    if (!s) return NULL;
    s->len = len; s->text_end = text_end;
    s->text = malloc((size_t)len + 64); s->touched = calloc((size_t)(len >> 5) + 2, 1); s->db = malloc(sizeof(int64_t) * (size_t)(2 * ndb + 1));
    if (!s->text || !s->touched || !s->db) { free(s->text); free(s->touched); free(s->db); free(s); return NULL; }
    memcpy(s->text, text, (size_t)len); memset(s->text + len, 0, 64);
    for (int64_t i = 0; i < ntouched; i++) { int64_t a = touched_words[i]; if (a >= 0 && !(a & 3) && a < len) s->touched[a >> 5] |= (uint8_t)(1u << ((a >> 2) & 7)); }
    memcpy(s->db, db, sizeof(int64_t) * (size_t)(2 * ndb)); s->ndb = ndb;
    memcpy(s->qk, qk, sizeof s->qk);
    s->paths = paths; s->steps = steps; s->stores = stores; s->ask = ask; s->native_seq = 1;
    return s;
}

EXPORT void rws_free(void *p)
{
    St *s = p;
    if (!s) return;
    free(s->text); free(s->touched); free(s->db); free(s->tab); free(s);
}

/* heads[i] with its back-edges bes[off[i] .. off[i + 1]): the heads that have a region, in order, into out → their number, or -1 */
EXPORT int64_t rws_heads(void *p, const int64_t *heads, int64_t n, const int64_t *bes, const int64_t *off, int64_t *out)
{
    St *s = p; int64_t k = 0;
    s->failed = 0;
    for (int64_t i = 0; i < n; i++) {
        int r = region(s, heads[i], bes + off[i], (int)(off[i + 1] - off[i]));
        if (r < 0 || s->failed) return -1;
        if (r) out[k++] = heads[i];
    }
    return k;
}

/* how many times the Python was asked, by mode (0 words, 1 registers, 2 spin_rmw, 3 spin_lock) */
EXPORT void rws_asked(void *p, int64_t *out) { St *s = p; for (int i = 0; i < 4; i++) out[i] = (int64_t)s->asked[i]; }

/* the decoder alone (tests): 1 and out = kind, is_b, ld, lock0, st, st_val, st_base, rw, r.lo, r.hi, w.lo, w.hi; 0 where the Python decodes the word */
EXPORT int rws_word(uint32_t w, int64_t *out)
{
    WV v;
    if (!dec(w, &v)) return 0;
    out[0] = v.kind; out[1] = v.is_b; out[2] = v.ld; out[3] = v.lock0; out[4] = v.st; out[5] = v.st_val; out[6] = v.st_base; out[7] = v.rw;
    out[8] = (int64_t)v.r.lo; out[9] = (int64_t)v.r.hi; out[10] = (int64_t)v.w.lo; out[11] = (int64_t)v.w.hi;
    return 1;
}

/* spin_rmw (mode 2) or spin_lock (3) at a as this file decides it alone (tests): 1 and out = found, kind, base, nins, pre, count, k, uses (2), defs (2);
 * 0 where the Python answers */
EXPORT int rws_seq(void *p, int mode, int64_t a, int64_t *out)
{
    St *s = p; Seq q;
    if (!(mode == ASK_RMW ? rmw_c(s, a, &q) : lock_c(s, a, &q))) return 0;
    out[0] = q.found; out[1] = q.kind; out[2] = q.base; out[3] = q.nins; out[4] = q.pre; out[5] = q.count; out[6] = q.k;
    out[7] = (int64_t)q.uses.lo; out[8] = (int64_t)q.uses.hi; out[9] = (int64_t)q.defs.lo; out[10] = (int64_t)q.defs.hi;
    return 1;
}
