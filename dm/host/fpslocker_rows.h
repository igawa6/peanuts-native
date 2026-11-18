// PEANUTS begin: restricted reviewed memory-row evaluator and dynamic timing extensions.
// PEANUTS: reviewed FPSLocker Warehouse operations; no runtime YAML or
// arbitrary expression evaluation.
#ifndef DM_FPSLOCKER_ROWS_H
#define DM_FPSLOCKER_ROWS_H
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define FPW_MAX_ROWS 16
#define FPW_SPEC_SIZE 4096
struct fpw_row {
  uint64_t offset, chain[3];
  unsigned nchain, kind, copies, direct, aux;
  float a, b;
};
struct fpw_rows {
  struct fpw_row row[FPW_MAX_ROWS];
  unsigned n;
};
static inline int fpw_hex(const char *s, uint64_t *v) {
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
static inline int fpw_factors(char *s, float *a, float *b) {
  char *end;
  errno = 0;
  *a = strtof(s, &end);
  if (errno || *end != ':' || !isfinite(*a) || *a < 0)
    return 0;
  *b = strtof(end + 1, &end);
  return !errno && !*end && isfinite(*b) && *b > 0;
}
static inline int fpw_parse(const char *spec, const char *build,
                            struct fpw_rows *out) {
  struct fpw_rows result = {0};
  char buf[FPW_SPEC_SIZE];
  if (!spec || !*spec) {
    *out = result;
    return 0;
  }
  if (strlen(spec) >= sizeof buf)
    return -1;
  strcpy(buf, spec);
  char *save = NULL;
  for (char *row = strtok_r(buf, ";", &save); row;
       row = strtok_r(NULL, ";", &save)) {
    char *f[5], *p = row;
    for (unsigned i = 0; i < 5; i++) {
      f[i] = p;
      char *comma = strchr(p, ',');
      if (i < 4) {
        if (!comma)
          return -1;
        *comma = 0;
        p = comma + 1;
      } else if (comma)
        return -1;
    }
    size_t n = strlen(f[0]);
    if (n < 28 || n > 64)
      return -1;
    for (size_t i = 0; i < n; i++)
      if (!strchr("0123456789abcdef", f[0][i]))
        return -1;
    struct fpw_row r = {0};
    // Integer constants, reciprocal elapsed FPS and 32-bit state caps extend
    // the same reviewed row format; none evaluates an arbitrary expression.
    const char *kinds[] = {"zero_i32",         "target_f32",
                           "frame_ms_f32",     "frame_ms2_f32",
                           "vsync_u32",        "inverse_f32",
                           "frame_scaled_f32", "target_scaled_f32",
                           "time_ratio_f32",   "flag_cap_u8", "target_i32", "zero_u16",
                           "time_step_pair_f32", "constant_i32",
                           "elapsed_fps_f32", "flag_cap_u32_gt"};
    for (unsigned i = 0; i < sizeof kinds / sizeof *kinds; i++)
      if (!strcmp(f[1], kinds[i]))
        r.kind = i + 1;
    if (!r.kind)
      return -1;
    char *path = f[2];
    r.aux = *path == '%';
    r.direct = r.aux || *path == '@';
    if (r.direct)
      path++;
    char *slash = strchr(path, '/');
    if (slash)
      *slash = 0;
    if (!fpw_hex(path, &r.offset) || (!r.aux && !r.offset) ||
        (r.offset & (r.direct ? 3 : 7)))
      return -1;
    if (r.direct && slash)
      return -1;
    while (slash) {
      path = slash + 1;
      slash = strchr(path, '/');
      if (slash)
        *slash = 0;
      if (r.nchain == 3 || !fpw_hex(path, &r.chain[r.nchain]))
        return -1;
      r.nchain++;
    }
    r.copies = !strcmp(f[3], "1") ? 1 : !strcmp(f[3], "2") ? 2 : 0;
    if (!r.copies || (r.kind == 13 && r.copies != 1) ||
        (r.kind == 13 && r.direct && (r.offset & 7)))
      return -1;
    if ((r.kind >= 6 && r.kind <= 10) || r.kind >= 13) {
      if (!fpw_factors(f[4], &r.a, &r.b) ||
          (r.kind != 10 && r.kind != 14 && r.kind != 16 && r.a == 0))
        return -1;
      if ((r.kind == 14 || r.kind == 16) &&
          (r.a > 16777215 || truncf(r.a) != r.a))
        return -1;
      if ((r.kind == 10 || r.kind == 16) &&
          (r.b < 15 || r.b > 120 || truncf(r.b) != r.b))
        return -1;
    } else if (strcmp(f[4], "0"))
      return -1;
    if (strcmp(f[0], build))
      continue;
    if (result.n == FPW_MAX_ROWS)
      return -1;
    result.row[result.n++] = r;
  }
  *out = result;
  return (int)result.n;
}
typedef int (*fpw_read_fn)(uint64_t, void *, size_t);
typedef void (*fpw_write_fn)(uint64_t, uint32_t);
typedef void (*fpw_write16_fn)(uint64_t, uint16_t);
typedef void (*fpw_write64_fn)(uint64_t, uint64_t);
/* Five-frame time sampling and the ratio/seconds pair follow MacNativeGames'
 * Wonder runtime/game_patches_wonder.c, commit f7fda5e32a532e5101fee10c2a1fb5c207d6056c.
 *
 * MIT License
 * Copyright (c) 2026 MacNativeGames contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
struct fpw_step_filter {
  double sample[5];
  unsigned count, next;
};
// A pause or an invalid interval clears history and leaves the current pair.
static inline int fpw_step_filter_push(struct fpw_step_filter *filter,
                                       double elapsed, double *step) {
  if (!isfinite(elapsed) || elapsed < .001 || elapsed > .25) {
    filter->count = filter->next = 0;
    return 0;
  }
  filter->sample[filter->next] = elapsed;
  filter->next = (filter->next + 1) % 5;
  if (filter->count < 5)
    filter->count++;
  double total = 0;
  for (unsigned i = 0; i < filter->count; i++)
    total += filter->sample[i];
  *step = total / filter->count;
  return 1;
}
static inline int fpw_rate_ok(const struct fpw_rows *rows, int fps) {
  if (fps < 15 || fps > 120)
    return 0;
  for (unsigned i = 0; i < rows->n; i++)
    if (rows->row[i].kind == 5 && 60 % fps)
      return 0;
  return 1;
}
static inline uint64_t fpw_address(const struct fpw_row *r, uint64_t base,
                                   uint64_t aux, fpw_read_fn read) {
  uint64_t p = r->aux ? aux : base;
  if (!p || r->offset > UINT64_MAX - p)
    return 0;
  p += r->offset;
  if (!r->direct) {
    if (!read(p, &p, 8) || !p)
      return 0;
    for (unsigned k = 0; k < r->nchain; k++) {
      if (r->chain[k] > UINT64_MAX - p)
        return 0;
      p += r->chain[k];
      if (k + 1 < r->nchain && (!read(p, &p, 8) || !p))
        return 0;
    }
  }
  return p;
}
static inline int fpw_effective_cap(const struct fpw_rows *rows, uint64_t base,
                                    uint64_t aux, int fps, fpw_read_fn read) {
  for (unsigned i = 0; i < rows->n; i++) {
    const struct fpw_row *r = &rows->row[i];
    if (r->kind != 10 && r->kind != 16)
      continue;
    uint64_t a = fpw_address(r, base, aux, read);
    uint32_t v = 0;
    if (a && read(a, &v, r->kind == 16 ? 4 : 1) &&
        (r->kind == 16 ? v > (unsigned)r->a : v == (unsigned)r->a))
      fps = (int)r->b;
  }
  return fps;
}
static inline unsigned fpw_apply_context_storage(const struct fpw_rows *rows,
                                         uint64_t base, uint64_t aux, int fps,
                                         double elapsed, double pair_elapsed,
                                         fpw_read_fn read, fpw_write_fn write,
                                         fpw_write16_fn write16, fpw_write64_fn write64) {
  if (!fpw_rate_ok(rows, fps))
    return 0;
  unsigned applied = 0;
  for (unsigned i = 0; i < rows->n; i++) {
    const struct fpw_row *r = &rows->row[i];
    if (r->kind == 10 || r->kind == 16)
      continue;
    uint64_t ptr = fpw_address(r, base, aux, read);
    if (r->kind == 13) {
      uint64_t cur;
      if (!write64 || !ptr || (ptr & 7) || ptr > UINT64_MAX - 8 ||
          !isfinite(pair_elapsed) || pair_elapsed <= 0 || !read(ptr, &cur, 8))
        continue;
      float seconds = (float)(pair_elapsed > r->b ? r->b : pair_elapsed);
      float ratio = seconds * r->a;
      if (!isfinite(seconds) || !isfinite(ratio))
        continue;
      uint32_t lo, hi;
      memcpy(&lo, &ratio, 4); memcpy(&hi, &seconds, 4);
      uint64_t value = (uint64_t)lo | ((uint64_t)hi << 32);
      if (cur != value) { write64(ptr, value); applied++; }
      continue;
    }
    if (r->kind == 12) {
      uint16_t cur[2];
      if (!write16 || !ptr || (ptr & 1) || ptr > UINT64_MAX - r->copies * 2 ||
          !read(ptr, cur, r->copies * 2)) continue;
      for (unsigned c = 0; c < r->copies; c++)
        if (cur[c]) { write16(ptr + c * 2, 0); applied++; }
      continue;
    }
    uint32_t cur[2];
    if (!ptr || (ptr & 3) || ptr > UINT64_MAX - r->copies * 4 ||
        !read(ptr, cur, r->copies * 4))
      continue;
    uint32_t value = 0;
    if (r->kind == 14)
      value = (uint32_t)r->a;
    else if (r->kind == 11)
      value = (uint32_t)fps;
    else if (r->kind == 5)
      value = 60 / (unsigned)fps;
    else if (r->kind != 1) {
      float f = r->kind == 2 ? (float)fps : 1000.f / (float)fps;
      if (r->kind == 4)
        f = truncf(f * 100.f) / 100.f;
      if (r->kind == 6)
        f = (r->a / (float)fps) * r->b;
      if (r->kind == 7)
        f = r->a * (1000.f / (float)fps) * r->b;
      if (r->kind == 8)
        f = ((float)fps / r->a) * r->b;
      if (r->kind == 9) {
        double v = elapsed * r->a, lo = r->a / fps;
        f = (float)(v < lo ? lo : v > r->b ? r->b : v);
      }
      // Reciprocal of the same clamped elapsed time used by time_ratio_f32:
      // games can consume seconds, a speed ratio and FPS in separate paths.
      if (r->kind == 15) {
        if (!isfinite(elapsed) || elapsed < 0) continue;
        double lo = r->a / fps;
        double seconds = elapsed < lo ? lo : elapsed > r->b ? r->b : elapsed;
        f = (float)(r->a / seconds);
      }
      memcpy(&value, &f, 4);
    }
    for (unsigned c = 0; c < r->copies; c++)
      if (cur[c] != value) {
        write(ptr + c * 4, value);
        applied++;
      }
  }
  return applied;
}
static inline unsigned fpw_apply_context_width(const struct fpw_rows *rows,
                                         uint64_t base, uint64_t aux, int fps,
                                         double elapsed, fpw_read_fn read,
                                         fpw_write_fn write, fpw_write16_fn write16) {
  return fpw_apply_context_storage(rows, base, aux, fps, elapsed, elapsed,
                                    read, write, write16, NULL);
}
static inline unsigned fpw_apply_context(const struct fpw_rows *rows,
                                         uint64_t base, uint64_t aux, int fps,
                                         double elapsed, fpw_read_fn read,
                                         fpw_write_fn write) {
  return fpw_apply_context_width(rows, base, aux, fps, elapsed, read, write, NULL);
}
static inline unsigned fpw_apply(const struct fpw_rows *rows, uint64_t base,
                                 int fps, fpw_read_fn read,
                                 fpw_write_fn write) {
  return fpw_apply_context(rows, base, 0, fps, 0, read, write);
}
#endif

// PEANUTS end
