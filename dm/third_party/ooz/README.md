# ooz: an open-source Oodle decoder, and unoodle, the worker around it

<!-- PEANUTS: this folder is Peanuts Native's; upstream Banana Native has none of it. -->

Some games keep their shaders in Oodle-compressed files (Pokémon Legends: Arceus's GFPAK archives, the Trinity packs of Pokémon Scarlet, Violet and Legends: Z-A,
the shader libraries of some Unreal games). The Mac's NSP script decodes them with the game's own decoder. The PC builder has none, so it
(`peanuts/scripts/peanuts_build.py`, [BUILDER.md](../../../peanuts/docs/BUILDER.md)) builds `unoodle` from this folder and gives it to the shader split as
`DM_OODLE_OOZ`.

The phone decoded them with the game's own decoder too, which runs from the rewritten modules, so the split waited there for the rewrite beside it (FINAL
FANTASY RESONANCE on an AYN Thor: 264 s of its 291 s). Since 2026-11-28 the APK carries the same worker as a library, `libunoodle.so` (`build.sh <output>.so`,
made by `android/ondevice/build.sh <work dir> zbic`), and the split decodes with it first: see [On the phone](#on-the-phone).

## Licence: GPL-3.0-or-later, kept as a program of its own

The repository's own files are MIT ([LICENSE](../../../LICENSE)). `kraken.cpp` is GPL-3.0-or-later by the notice in its header, so everything compiled with it is
too: `unoodle.cpp` and the two patches say so, and the licence text is [COPYING](COPYING) in this folder.

`unoodle` is a stand-alone executable. It is linked with `kraken.cpp` and the C library and with nothing else of this repository, and no MIT file includes or
links any file of this folder. The MIT tools (`dm/gfx/tools/shader_split.py`, `dm/gfx/tools/uepak.py`, `ue_ondevice.py`) start it as a separate process and talk
to it over a pipe, with the protocol they already used for the game's own decoder (`DM_OODLE_SERVE`). They stay MIT. Whoever passes a built `unoodle` on must offer
its source under the GPL: this folder at the same commit.

`libunoodle.so`, the phone's form, is the same code with `unoodle_main()` in place of `main()`. It is loaded only into a worker process the split starts for it
(`nxpack_worker`, the APK's Python, under `/system/bin/linker64`), which talks to the split over the same pipe; nothing of the app links it. The APK is already a
GPL-3.0-or-later work as a whole (its translator and the host's mesh emulation, [android/NOTICE.md](../../../android/NOTICE.md)), so carrying a GPL-3.0-or-later
library changes nothing for it; the APK's notice lists it, and its source is this folder at the APK's commit.

Where it is listed: here, in [dm/third_party/README.md](../README.md), in the builder's guide and in [android/NOTICE.md](../../../android/NOTICE.md).

### The open question upstream

powzix/ooz has **no licence file**. Its issues #10 and #12 ask which licence applies and have no answer. The only grant is the notice at the top of `kraken.cpp`
(quoted below); `stdafx.h`, `lzna.cpp`, `bitknit.cpp` and the README carry no notice at all. The code is also an independent re-implementation of a proprietary
format. The repository owner accepted `kraken.cpp` on the strength of its header notice; the files without a notice that hold decoder code are left out.

## What is upstream

From <https://github.com/powzix/ooz> at commit `05038060aa68f9187ae9923b2388ca8db40e58d1` (the head of `master`, committed 2019-02-13), fetched as raw files:

| File | Bytes | SHA-256 | Licence notice in the file |
|---|---|---|---|
| `kraken.cpp` | 137,434 | `20c48a30db54efa7681f8c9c674f2a16f081373872d033885a6fdf244b9cff01` | GPL-3.0-or-later, quoted below |
| `stdafx.h` | 732 | `92b798615ee1ac90ed53a2685fc62dfbe7133d5ba69e567733d8081d420cf701` | none: a Visual Studio precompiled-header stub (eight includes, a warning pragma and nine integer typedefs) |

Both are here byte for byte; `build.sh` checks the two hashes before it compiles. The notice of `kraken.cpp`, its lines 1 to 17:

> === Kraken Decompressor for Windows ===
> Copyright (C) 2016, Powzix
>
> This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software
> Foundation, either version 3 of the License, or (at your option) any later version.
>
> This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
> PARTICULAR PURPOSE. See the GNU General Public License for more details.
>
> You should have received a copy of the GNU General Public License along with this program. If not, see <http://www.gnu.org/licenses/>.

The upstream README describes the project as "Open source Kraken / Mermaid / Selkie / Leviathan / LZNA / Bitknit decompressor" and warns:
"(Warning! not fuzz safe, so please trust the input)". See [Safety](#safety).

### Left out on purpose

- `lzna.cpp` and `bitknit.cpp` (the LZNA and Bitknit decoders): no licence notice. `kraken.cpp` calls four of their functions (`LZNA_DecodeQuantum`,
  `LZNA_InitLookup`, `BitknitState_Init`, `Bitknit_Decode`); `unoodle.cpp` defines the four as stubs that fail, so a compressed LZNA (decoder type 5) or Bitknit
  (type 11) stream is refused. No game met so far uses either.
- `targetver.h`, `stdafx.cpp`, the Visual Studio project, `testdata/`, and the upstream `README.md` (its two relevant lines are quoted above).

## What was added and changed

Nothing in `kraken.cpp` or `stdafx.h` is edited in place. `build.sh` copies them, applies the two patches to the copy (on arm64 a third, with `ooz_neon.h`) and compiles it.

| File | What it is |
|---|---|
| `ooz-port.patch` | The port to gcc and clang on x86-64, 25 changed lines. `stdafx.h`: the Windows headers go behind `_MSC_VER`; elsewhere `<x86intrin.h>` and the MSVC names `kraken.cpp` uses (`__int64`, `__forceinline`, `_BitScanReverse`, `_BitScanForward`, `_byteswap_ushort` / `ulong` / `uint64`, `_rotl`). `kraken.cpp`: two lines that put the command line tool at its end (`main`, `LoadLibrary`, `QueryPerformanceCounter`) behind `_MSC_VER`. No decoder code changes |
| `ooz-guards.patch` | One added check, 5 lines: at the end of a Mermaid chunk the literals and the 16-bit offsets must be used up exactly. Without it a Mermaid stream asked for one byte more or less than its true size still "decoded" in 254 of 303 tries, because the final literals are copied without a count; with it all 303 are refused. Kraken has this check already |
| `ooz-arm64.patch` | arm64 only, 4 added lines: `stdafx.h` includes `ooz_neon.h` in place of `<x86intrin.h>` on `__aarch64__`. No decoder code changes |
| `ooz_neon.h` | arm64 only, the 21 SSE2 intrinsics `kraken.cpp` calls (`_mm_loadu_si128`, `_mm_unpacklo_epi8`, `_mm_movemask_epi8`, ...) and MSVC's `_rotl`, written with AArch64 NEON or plain C, each with SSE2's exact result. GPL-3.0-or-later, as the program it is compiled into |
| `unoodle.cpp` | The worker, 108 lines: the pipe protocol, the stubs for the two decoders left out, and the fail-closed checks under [Safety](#safety) |
| `build.sh` | `build.sh <output program> [compiler]`: checks the hashes, copies, patches, compiles with `-O2 -fno-strict-aliasing -fwrapv` and, on x86-64, `-msse4.1`. The decoder reads and writes through casted pointers throughout and was written for MSVC; those two flags give gcc and clang MSVC's reading of that code. The program holds no path of the build machine: built twice in different folders with the same compiler it is the same file |
| `COPYING` | The GNU General Public License, version 3 |

The compiler flags and the patches are the ones of the validated builds below; change none of them without running the checks again.

## Where it builds

Linux and macOS, x86-64 or arm64, with g++ or clang++, and the `patch` program. The decoder uses x86 SSE2 intrinsics (58 calls, 21 distinct): on x86-64
they come from `<x86intrin.h>`, on arm64 from `ooz_neon.h` (AArch64 NEON). Any other processor makes `build.sh` stop with exit code 3 and a message. An Intel
Mac should build it as Linux does (not tried). Windows is not supported: the worker's guard pages use `mmap` and its pipes would need binary mode. The builder
uses the worker on Linux and macOS and keeps its refusal on Windows.

## On the phone

`PackService` sets `DM_OODLE_OOZ_LIB=libunoodle.so` when the APK has the library (`findLibrary("unoodle")`) and the worker program is there. `shader_split.py`'s
`oodle()` (Trinity packs, GFPAK entries, Unreal Engine 3 chunks, legacy and IoStore paks read inline, New Pokémon Snap's DRPF) and `ue_ondevice.py`'s `decode()`
(Unreal shader code archives) then start `DM_UE_HOST`'s command with a few lines of Python that load the library and call `unoodle_main()`
(`dm/gfx/tools/ooz_split.py`): no file of the app's storage is executed, and the guard pages below end that worker, not the app. That decoder needs no rewritten
modules, so the split no longer waits for the rewrite beside it (`rewrite_wait.wait()` is called only where the game's own decoder starts).

The rule of [Safety](#safety) holds on the phone in the split itself: ooz's output is written only when the split's tables are the profile's (`shader_split.py`
`main`, `ooz_check`: `profile_differs`, byte for byte but for the keys of `shader_optional.tsv` and the other listed versions). When they are not, or the split
stops while ooz was in use (a stream it refuses, a worker that does not start, a red line), the split runs again with the game's own decoder, which waits for the
rewrite as before, and `PN_REUSE_DIR/ooz.refused` keeps that for the title's later splits of the same files (until the app, the profile's map or `main`
changes). The targeted scan does not go on to a full scan with ooz: a targeted scan whose tables differ goes straight to the game's decoder. ooz's kept scan
results are kept apart from the game decoder's (`DM_SPLIT_DECODER`, in `ScanCache`'s and `split_reuse.py`'s keys; `decoder ooz` in the decoded Unreal archives'
stamp), so the rerun never takes a result ooz decoded. A profile without `shader_map.tsv` is never split with ooz. `DM_OODLE_OOZ_LIB=` (empty) in
`ondevice/env.txt` forces the game's own decoder; an APK without the library decodes as before.

The library carries the SHA-256 of this folder's sources in `build.sh`'s order (`unoodle_source=`), and the APK build refuses a stage library of other sources
(`nxPackUnoodleCheck`). `-fno-strict-overflow` is in the flags (PR #889: clang miscopies Leviathan matches without it).

## Safety

Upstream says the decoder is not fuzz safe. Its inputs here are the user's own game files, and the worker fails closed:

- The answer is "failed" unless the decoder returns exactly the raw length asked for and used exactly the compressed bytes given.
- The input and output buffers end, after the 64 bytes of slack ooz reads and writes past its buffers, at a page without access. A read or write further out
  stops the process (the caller sees a closed pipe and stops the build) instead of touching other memory.
- Streams over 1 GiB are refused (ooz keeps offsets in an `int`), and so is input that ends inside a request.

What the worker cannot notice: Oodle streams carry no checksum here, so a stream with one flipped bit can decode to other bytes of the right length (263 of 303
tries). The check for that is outside the worker. Every shader's key in a profile's `shader_map.tsv` is a hash of the shader's bytes, so
`scripts/pack_android.py` takes a split made with this decoder only when its keys are exactly the profile's (`ooz_split_checked`; a game without a profile map is
not split with it at all), and `vk_android_build.py` hashes every record against its key again before it translates it.

## Checked

2026-11-15, x86-64 Linux, g++ 16.2, the worker built with both patches. Each game: a full shader split with the worker, then every key compared with the profile's
map and every record of the package hashed again.

| Game | Oodle streams | Failures | Shader keys matched / missing / extra | Also |
|---|---|---|---|---|
| Pokémon Legends: Arceus 1.1.1 | 102,452, all Kraken | 0 | 12,851 / 0 / 0 | 1.69 GB decoded to 7.33 GB; the bytes outside the shaders have no reference to compare with |
| FINAL FANTASY RESONANCE 1.0.1 | 5,919, all decoder type 10 (Mermaid or Selkie) | 0 | 73,496 / 0 / 0 | the split package (8 files, 678 MB) is byte for byte the one a phone made with the game's own decoder |
| Octopath Traveler 0 1.0.5 | 10,596, all type 10 | 0 | 10,324 / 0 / 0 | every stream identical to an earlier job's output; not yet split from its game files on a PC |

2026-11-17, arm64 macOS (Apple Silicon, Apple clang), the worker built with the three patches and `ooz_neon.h`, through the PC builder from FINAL FANTASY
RESONANCE 1.0.1's `.nsp` files: the split's keys were all 73,496 of the profile's and no other (`ooz_split_checked`), and the pack played on an AYN Thor from
a save in Chapter 1. Before that, `peanuts/tests/test_unoodle.py` compared every intrinsic of `ooz_neon.h` with SSE2's definition on 20,000 random registers.

2026-11-28, x86-64 Linux, g++ 16.2 and the library form (`build.sh <output>.so`, run through `ooz_split.py`'s worker code), OCTOPATH TRAVELER 0 1.0.5 from its
two `.nsp` files: the 10,596 Mermaid streams of a recorded Unreal job decode byte for byte to the game's own decoder's output, with the program and with the
library; the targeted split with each (and `DM_UE_DECODE`) has the profile's four tables, byte for byte in their rows, and the same package. A stand-in decoder
that changes one byte in every 50th stream sends the split back to the game's decoder, whose tables are the profile's again. The arm64 Android library is built
with NDK 28.2 clang, not run here (no arm64 machine or emulator); it awaits a phone.

Not checked: Leviathan and Selkie streams as such; any other game on arm64 (Arceus and Octopath Traveler 0 were checked on x86-64 only); clang beyond one job
on x86-64; any other system. ooz issue #6 (a tANS-coded Mermaid chunk whose lookup table does not fit the scratch space) did not occur in these 16,515 type 10
streams; it is a clean refusal when it does, and no fix for it is included.

`peanuts/tests/test_unoodle.py` builds the worker and checks the protocol, three hand-written stream forms (ooz has no compressor) and the fail-closed paths
(on arm64 also each intrinsic of `ooz_neon.h` against SSE2's definition, as those streams reach none of them),
with no game data.
