// PEANUTS: exact-build, expected-byte-checked Warehouse code plans, applied
// only before code is sealed.
#ifndef DM_FPSLOCKER_PLAN_H
#define DM_FPSLOCKER_PLAN_H
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define FPW_PLAN_ROWS 128
#define FPW_PLAN_TEXT 32768
#define FPW_AUX_PAGE 0x4000u
struct fpw_insn {
  char where, op;
  uint64_t off, arg;
  uint32_t expected, word;
  unsigned reg;
};
struct fpw_plan {
  struct fpw_insn row[FPW_PLAN_ROWS];
  unsigned n, clock, aux;
};
static inline int fpw_plan_hex(const char *s, uint64_t *v) {
  if (strncmp(s, "0x", 2) || !s[2])
    return 0;
  for (const char *p = s + 2; *p; p++)
    if (!strchr("0123456789abcdefABCDEF", *p))
      return 0;
  errno = 0;
  char *end;
  *v = strtoull(s, &end, 16);
  return !errno && !*end;
}
static inline int fpw_plan_word(const char *s, uint32_t *word) {
  if (strlen(s) != 8)
    return 0;
  uint32_t v = 0;
  for (unsigned i = 0; i < 4; i++) {
    char b[5] = {'0', 'x', s[i * 2], s[i * 2 + 1], 0};
    uint64_t k;
    if (!fpw_plan_hex(b, &k))
      return 0;
    v |= (uint32_t)k << (8 * i);
  }
  *word = v;
  return 1;
}
static inline int fpw_plan_parse(const char *spec, const char *build,
                                 struct fpw_plan *out) {
  struct fpw_plan plan = {0};
  char buf[FPW_PLAN_TEXT];
  if (!spec || !*spec) {
    *out = plan;
    return 0;
  }
  if (strlen(spec) >= sizeof buf)
    return -1;
  strcpy(buf, spec);
  char *save = NULL;
  for (char *line = strtok_r(buf, ";", &save); line;
       line = strtok_r(NULL, ";", &save)) {
    char *f[4], *p = line;
    for (unsigned i = 0; i < 4; i++) {
      f[i] = p;
      char *comma = strchr(p, ',');
      if (i < 3) {
        if (!comma)
          return -1;
        *comma = 0;
        p = comma + 1;
      } else if (comma)
        return -1;
    }
    if (strlen(f[0]) != 32)
      return -1;
    for (unsigned i = 0; i < 32; i++)
      if (!strchr("0123456789abcdef", f[0][i]))
        return -1;
    int match = !strcmp(f[0], build);
    if (!strcmp(f[1], "clock")) {
      if (strcmp(f[2], "-") || strcmp(f[3], "1"))
        return -1;
      if (match)
        plan.clock = 1;
      continue;
    }
    struct fpw_insn r = {0};
    r.where = f[1][0];
    if (!strchr("mrcdo", r.where) || !fpw_plan_hex(f[1] + 1, &r.off) ||
        (r.off & 3))
      return -1;
    if (r.where == 'c' || r.where == 'd') {
      if (strcmp(f[2], "-") || r.off > FPW_AUX_PAGE - 4)
        return -1;
    } else if (!fpw_plan_word(f[2], &r.expected))
      return -1;
    if (fpw_plan_word(f[3], &r.word))
      r.op = 'w';
    else {
      r.op = f[3][0];
      if (r.op == 'p' || r.op == 'q') {
        char *end;
        unsigned long reg = strtoul(f[3] + 1, &end, 10);
        if (end == f[3] + 1 || *end != ':' || reg > 31 ||
            !fpw_plan_hex(end + 1, &r.arg))
          return -1;
        r.reg = (unsigned)reg;
      } else if (!strchr("bltu", r.op) || !fpw_plan_hex(f[3] + 1, &r.arg))
        return -1;
    }
    if ((r.where == 'r' || r.where == 'o' || r.where == 'd') && r.op != 'w')
      return -1;
    if (match) {
      if (plan.n == FPW_PLAN_ROWS)
        return -1;
      plan.row[plan.n++] = r;
      if (r.where == 'c' || r.where == 'd' || r.op != 'w')
        plan.aux = 1;
    }
  }
  *out = plan;
  return (int)plan.n;
}
// All bounds/expected words/relocations are validated before any word is
// changed.
static inline int fpw_plan_apply(const struct fpw_plan *plan, uint8_t *image,
                                 uint64_t original_size, uint64_t code_off,
                                 uint64_t data_off, int owned) {
  uint32_t words[FPW_PLAN_ROWS];
  uint64_t offsets[FPW_PLAN_ROWS];
  unsigned n = 0;
  for (unsigned i = 0; i < plan->n; i++) {
    const struct fpw_insn *r = &plan->row[i];
    if (owned ? r->where != 'r' && r->where != 'o' : r->where == 'o')
      continue;
    uint64_t off = r->where == 'c'   ? code_off + r->off
                   : r->where == 'd' ? data_off + r->off
                                     : r->off;
    if (r->where != 'c' && r->where != 'd' &&
        (off > original_size || original_size - off < 4))
      return -1;
    uint32_t old;
    memcpy(&old, image + off, 4);
    if (r->where != 'c' && r->where != 'd' && old != r->expected &&
        !((r->where == 'r' || r->where == 'o') && old == r->word))
      return -2;
    if ((r->op == 'p' && r->arg >= FPW_AUX_PAGE) ||
        (strchr("bl", r->op) && r->arg >= FPW_AUX_PAGE) ||
        (strchr("qtu", r->op) && r->arg >= original_size))
      return -3;
    uint32_t word = r->word;
    uint64_t pc = (uint64_t)(uintptr_t)image + off;
    if (r->op == 'p' || r->op == 'q') {
      uint64_t target =
          (uint64_t)(uintptr_t)image + (r->op == 'p' ? data_off : 0) + r->arg;
      int64_t delta = (int64_t)(target & ~0xfffull) - (int64_t)(pc & ~0xfffull);
      int64_t pages = delta / 4096;
      if (pages < -(1 << 20) || pages >= (1 << 20))
        return -3;
      uint32_t imm = (uint32_t)pages & 0x1fffff;
      word = 0x90000000u | ((imm & 3) << 29) | ((imm >> 2) << 5) | r->reg;
    } else if (r->op != 'w') {
      uint64_t target = (uint64_t)(uintptr_t)image +
                        (r->op == 'b' || r->op == 'l' ? code_off : 0) + r->arg;
      int64_t delta = (int64_t)target - (int64_t)pc;
      if ((delta & 3) || delta < -(1ll << 27) || delta >= (1ll << 27))
        return -3;
      word = (r->op == 'l' || r->op == 'u' ? 0x94000000u : 0x14000000u) |
             ((uint32_t)(delta / 4) & 0x3ffffff);
    }
    offsets[n] = off;
    words[n++] = word;
  }
  for (unsigned i = 0; i < n; i++)
    memcpy(image + offsets[i], &words[i], 4);
  return (int)n;
}
#endif
