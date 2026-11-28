// SPDX-FileCopyrightText: Copyright 2026 Peanuts Native contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// PEANUTS: unoodle, an Oodle decoding worker around ooz's kraken.cpp (Kraken, Mermaid / Selkie, Leviathan) for the PC builder (README.md beside this file).
// It is a program of its own: it is linked with kraken.cpp (GPL-3.0-or-later, so this file is too; COPYING) and with nothing else of the repository, and the
// MIT tools talk to it over a pipe only. It speaks the pipe protocol of dm/host/hle_oodle.c's DM_OODLE_SERVE mode, so it stands in for the game's own decoder
// behind shader_split.py's oodle() and uepak.OodleHost (DM_OODLE_OOZ=<this program>):
//   request:  u64 compressed length, u64 raw length, the compressed bytes      (little endian)
//   answer:   u64 bytes decoded (the raw length, or 0 = failed), then that many bytes
// Arguments are ignored (OodleHost starts its worker as `<host> <modules>`). It fails closed: a stream that does not decode to exactly the
// raw length, or does not use exactly its compressed bytes, answers 0; a read or write past the buffers' slack hits a guard page and ends the
// process, which the caller sees as a closed pipe. LZNA and Bitknit streams are refused (their decoders are not built in).
// Environment UNOODLE_STATS=<file>: one line appended at the end of input: streams, bytes and decode time per decoder type.
// PEANUTS: built as a shared library (-DUNOODLE_LIBRARY, build.sh <output>.so: the phone's libunoodle.so) it has no main: unoodle_main() serves the same protocol on
// stdin and stdout and returns main's exit code, called by a worker process that loads it (dm/gfx/tools/ooz_split.py), never inside the app's own process
// (the guard pages end the process that runs it). unoodle_source() is the SHA-256 of the sources it was built from (build.sh; build.gradle checks it).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>

typedef unsigned char byte;
int Kraken_Decompress(const byte *src, size_t src_len, byte *dst, size_t dst_len);   // kraken.cpp

// The two decoders kraken.cpp calls from files that carry no licence notice (lzna.cpp, bitknit.cpp): not built in; a stream of theirs fails
struct LznaState; struct BitknitState;
int LZNA_DecodeQuantum(byte *, byte *, byte *, const byte *, const byte *, struct LznaState *) { return -1; }
void LZNA_InitLookup(struct LznaState *) {}
void BitknitState_Init(struct BitknitState *) {}
size_t Bitknit_Decode(const byte *, const byte *, byte *, byte *, byte *, struct BitknitState *) { return (size_t)-1; }

enum { SLACK = 64 };                           // ooz reads and writes up to 64 bytes past its buffers (its SAFE_SPACE)
static const uint64_t LIMIT = 1ull << 30;      // per stream: kraken.cpp keeps offsets in an int

// A buffer that ends, after its slack, at a page without access: going further stops the process instead of touching other memory
struct Arena { byte *base; size_t cap; };
static byte *arena_end(Arena *a, size_t n) {
  size_t page = (size_t)sysconf(_SC_PAGESIZE), need = (n + SLACK + page - 1) / page * page;
  if (need > a->cap) {
    if (a->base) munmap(a->base, a->cap + page);
    size_t cap = need < (1u << 22) ? (1u << 22) : need * 2;
    byte *p = (byte *)mmap(NULL, cap + page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == (byte *)MAP_FAILED || mprotect(p + cap, page, PROT_NONE)) { a->base = NULL; a->cap = 0; return NULL; }
    a->base = p; a->cap = cap;
  }
  return a->base + a->cap - SLACK - n;         // the n bytes, then the slack, then the guard page
}

static uint64_t g_stream;
static void fault(int) {
  char m[96]; int n = snprintf(m, sizeof m, "unoodle: stream %llu: the decoder left its buffers (damaged or unsupported data)\n", (unsigned long long)g_stream);
  if (write(2, m, n) < 0) {}
  _exit(3);
}

static bool get(void *p, size_t n) { return fread(p, 1, n, stdin) == n; }
static double now() { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

// The decoder type of a stream's first block header: byte 0 = 0x8C / 0xCC (stored whole), byte 1 = the type; -1 for anything else
static int stream_type(const byte *s, size_t n) {
  if (n < 2 || (s[0] & 0xF) != 0xC || ((s[0] >> 4) & 3) != 0) return -1;
  return s[1] & 0x7F;
}
static const char *type_name(int t) {
  switch (t) { case 5: return "lzna"; case 6: return "kraken"; case 10: return "mermaid"; case 11: return "bitknit"; case 12: return "leviathan"; }
  return "other";
}

static int serve() {   // PEANUTS: main's body, shared with unoodle_main (#120)
  signal(SIGSEGV, fault); signal(SIGBUS, fault);
  Arena in = {NULL, 0}, out = {NULL, 0};
  struct { uint64_t ok, bad, cbytes, rbytes; double s; } st[16]; memset(st, 0, sizeof st);
  uint64_t h[2];
  while (get(h, sizeof h)) {
    uint64_t cn = h[0], rn = h[1], got = 0;
    g_stream++;
    if (cn > LIMIT || rn > LIMIT) { fprintf(stderr, "unoodle: stream %llu: %llu -> %llu bytes is over the limit\n", (unsigned long long)g_stream, (unsigned long long)cn, (unsigned long long)rn); return 2; }
    byte *src = arena_end(&in, cn), *dst = arena_end(&out, rn);
    if (!src || !dst) { fprintf(stderr, "unoodle: no memory\n"); return 2; }
    if (!get(src, cn)) { fprintf(stderr, "unoodle: stream %llu: input ended inside it\n", (unsigned long long)g_stream); return 2; }
    memset(src + cn, 0, SLACK);
    int t = stream_type(src, cn), k = t >= 0 && t < 15 ? t : 15;
    double t0 = now();
    if (rn && cn && Kraken_Decompress(src, cn, dst, rn) == (int64_t)rn) got = rn;
    st[k].s += now() - t0;
    if (got) { st[k].ok++; st[k].cbytes += cn; st[k].rbytes += rn; }
    else {
      st[k].bad++;
      fprintf(stderr, "unoodle: stream %llu failed: %llu -> %llu bytes, decoder type %d (%s), first bytes %02x %02x %02x %02x\n", (unsigned long long)g_stream,
              (unsigned long long)cn, (unsigned long long)rn, t, type_name(t), cn > 0 ? src[0] : 0, cn > 1 ? src[1] : 0, cn > 2 ? src[2] : 0, cn > 3 ? src[3] : 0);
    }
    if (fwrite(&got, 8, 1, stdout) != 1 || (got && fwrite(dst, 1, got, stdout) != got) || fflush(stdout)) return 2;
  }
  const char *sf = getenv("UNOODLE_STATS");
  if (sf && g_stream) {
    FILE *f = fopen(sf, "a");
    if (f) {
      char line[2048]; int n = snprintf(line, sizeof line, "pid=%d streams=%llu", (int)getpid(), (unsigned long long)g_stream);
      for (int k = 0; k < 16; k++)
        if (st[k].ok || st[k].bad)
          n += snprintf(line + n, sizeof line - n, " | type=%d(%s) ok=%llu failed=%llu in=%llu out=%llu seconds=%.6f", k == 15 ? -1 : k, type_name(k == 15 ? -1 : k),
                        (unsigned long long)st[k].ok, (unsigned long long)st[k].bad, (unsigned long long)st[k].cbytes, (unsigned long long)st[k].rbytes, st[k].s);
      fprintf(f, "%s\n", line); fclose(f);
    }
  }
  return 0;
}

#ifndef UNOODLE_SOURCE   // PEANUTS: the phone's libunoodle.so and the source SHA-256 (#120)
#define UNOODLE_SOURCE "unknown"
#endif
#ifdef UNOODLE_LIBRARY
extern "C" __attribute__((visibility("default"))) int unoodle_main(void) { return serve(); }
extern "C" __attribute__((visibility("default"))) const char *unoodle_source(void) { return "unoodle_source=" UNOODLE_SOURCE; }
#else
__attribute__((used)) static const char source[] = "unoodle_source=" UNOODLE_SOURCE;
int main() { return serve(); }
#endif
