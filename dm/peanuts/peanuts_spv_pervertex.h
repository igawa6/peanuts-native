// PEANUTS: remove unused trailing ClipDistance/CullDistance members of the translator's gl_PerVertex blocks.
// Vulkan counts declared distances, not just those accessed: Yoshi's VS declares 8 + 1, its GS 8 + 1 on each side.
// Work on unpacked words only. Never shrink a live array or renumber a member. Noncanonical/escaping blocks,
// implicit NV passthrough and transform feedback retain their original interface.
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static inline size_t pn_spv_pervertex(uint32_t *w, size_t bytes) {
    enum { MemberName = 6, ExtInst = 12, EntryPoint = 15, Capability = 17, TypeInt = 21, TypeFloat = 22, TypeArray = 28,
           TypeStruct = 30, TypePointer = 32, Constant = 43, SpecConstant = 50, Function = 54, Variable = 59,
           AccessChain = 65, InBoundsAccessChain = 66, MemberDecorate = 72, GroupDecorate = 74, GroupMemberDecorate = 75,
           VectorShuffle = 79, CompositeExtract = 81, CompositeInsert = 82, MemberDecorateString = 5633 };
    // Tags 1..4: 32-bit constants 0..3 (a constant used as a struct index must already be an integer).
    // Other tags identify a block and the only routes by which this rewrite allows it to be referenced.
    enum { Block = 8, Array = 10, Ptr = 12, ArrayPtr = 14, Var = 16, ArrayVar = 18 };
    size_t n = bytes / sizeof *w, i = 5, first_type, first_fn = n;
    if ((bytes & 3) || n < 5 || w[0] != 0x07230203 || !w[3] || w[3] > 0x3fffff) return bytes;
    uint32_t id[2] = {0}, count = 0, bound = w[3];
    uint8_t distances[2] = {0}, keep[2] = {2, 2};
    // One per-vertex interface in each direction. Identify it by decorations, never debug names or numeric IDs.
    for (; i < n;) {
        const uint32_t *x = w + i; uint32_t op = x[0] & 0xffff, len = x[0] >> 16;
        if (!len || len > n - i) return bytes;
        if (op >= 19 && op <= 39) break; // types, constants and globals follow the annotations
        if (op == EntryPoint && len >= 3 && x[1] > 3) return bytes; // vertex, tessellation, geometry only
        if ((op == Capability && len == 2 && (x[1] == 53 || x[1] == 5251)) || op == GroupDecorate || op == GroupMemberDecorate) return bytes;
        if (op == MemberDecorate && len == 5 && x[3] == 11 && ((x[2] == 2 && x[4] == 3) || (x[2] == 3 && x[4] == 4))) {
            uint32_t b = 0; while (b < count && id[b] != x[1]) ++b;
            if (b == count) { if (count == 2 || !x[1] || x[1] >= bound) return bytes; id[count++] = x[1]; }
            distances[b] |= (uint8_t)(1u << (x[2] - 2));
        }
        i += len;
    }
    if (!count || distances[0] != 3 || (count == 2 && distances[1] != 3)) return bytes;
    first_type = i;
    uint8_t *tag = calloc(bound, 1);
    if (!tag) return bytes;
    for (uint32_t b = 0; b < count; ++b) tag[id[b]] = (uint8_t)(Block + b);
#define PN_TAG(word) ((word) < bound ? tag[(word)] : 0)
    for (; i < n;) {
        const uint32_t *x = w + i; uint32_t op = x[0] & 0xffff, len = x[0] >> 16;
        if (!len || len > n - i) goto done;
        uint32_t lo = 1, hi = len;
        if (op == TypeStruct && len >= 2 && (PN_TAG(x[1]) & ~1) == Block) {
            if (len != 6) goto done; // distances must be the final two members
            lo = 2; // no nesting another tracked block among the members
        } else if (op == TypeArray && len == 4 && (PN_TAG(x[2]) & ~1) == Block) {
            if (x[1] >= bound) goto done;
            tag[x[1]] = (uint8_t)(Array + (tag[x[2]] & 1)); lo = 3;
        } else if (op == TypePointer && len == 4 && ((PN_TAG(x[3]) & ~1) == Block || (PN_TAG(x[3]) & ~1) == Array)) {
            if (x[1] >= bound || (x[2] != 1 && x[2] != 3)) goto done;
            tag[x[1]] = (uint8_t)(tag[x[3]] + Ptr - Block); hi = 0;
        } else if (op == Variable && len >= 4 && ((PN_TAG(x[1]) & ~1) == Ptr || (PN_TAG(x[1]) & ~1) == ArrayPtr)) {
            if (len != 4 || x[2] >= bound) goto done; // no whole-block initializer
            tag[x[2]] = (uint8_t)(tag[x[1]] + Var - Ptr); hi = 0;
        } else if ((op == AccessChain || op == InBoundsAccessChain) && len >= 4 && ((PN_TAG(x[3]) & ~1) == Var || (PN_TAG(x[3]) & ~1) == ArrayVar)) {
            uint32_t t = tag[x[3]], k = 4 + ((t & ~1u) == ArrayVar);
            if (len <= k) goto done; // e.g. &gl_in[vertex], before selecting the member
            uint32_t m = PN_TAG(x[k]); if (!m || m > 4) goto done;
            if (m > keep[t & 1]) keep[t & 1] = (uint8_t)m;
            hi = 0; // remaining indices can only address inside this member
        } else if (op == Constant || op == SpecConstant) {
            if (op == Constant && len == 4 && x[3] < 4 && x[2] < bound) tag[x[2]] = (uint8_t)(1 + x[3]);
            hi = len < 3 ? len : 3; // the value consists of literals, not IDs
        } else if (op == TypeInt || op == TypeFloat) hi = len < 2 ? len : 2;
        else if (op == VectorShuffle || op == CompositeInsert) hi = len < 5 ? len : 5;
        else if (op == CompositeExtract) hi = len < 4 ? len : 4;
        else if (op == Function && first_fn == n) first_fn = i;
        // Any other use of the type, pointer or variable (whole load/store, by-value construction, call,
        // copy, pointer arithmetic, ...) is not proven safe. An unrecognized literal can only veto the rewrite.
        for (uint32_t k = lo; k < hi; ++k) if (!(op == ExtInst && k == 4) && PN_TAG(x[k]) >= Block) goto done;
        i += len;
    }
#undef PN_TAG
    if (first_fn == n || (keep[0] == 4 && (count == 1 || keep[1] == 4))) goto done;
    // Compact unmodified spans in place. Only the struct declaration and its member metadata change. All
    // references to retained members (including per-vertex array accesses) and the whole function body stay verbatim.
    size_t dst = 0, start = 0;
    for (i = 5; i < first_fn;) {
        uint32_t *x = w + i; uint32_t op = x[0] & 0xffff, len = x[0] >> 16, take = len;
        if (len >= 2 && (op == TypeStruct || op == MemberName || op == MemberDecorate || op == MemberDecorateString)) {
            for (uint32_t b = 0; b < count; ++b) if (x[1] == id[b] && keep[b] < 4) {
                if (op == TypeStruct && i >= first_type) { take = keep[b] + 2; x[0] = take << 16 | TypeStruct; }
                else if (len >= 3 && x[2] >= keep[b]) take = 0;
            }
        }
        if (take < len) {
            size_t span = i + take - start;
            if (dst != start) memmove(w + dst, w + start, span * sizeof *w);
            dst += span; start = i + len;
        }
        i += len;
    }
    if (dst != start) { memmove(w + dst, w + start, (n - start) * sizeof *w); bytes = (dst + n - start) * sizeof *w; }
done:
    free(tag);
    return bytes;
}
