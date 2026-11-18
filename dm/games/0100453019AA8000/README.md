<!-- PEANUTS: Switch 1 Xenoblade X profile adapted from nxnative. -->
# Xenoblade Chronicles X: Definitive Edition (`0100453019AA8000`)

## Current status

Experimental Android profile for update 1.0.2 (`v131072`), SDK 19.3.2,
Monolith's engine and NVN. The Android pack and signed test APK build on Linux.
On Android (2026-11-17), the title screen renders at about 30 fps on both the
system Qualcomm driver and Turnip 26.2.0-R5. Turnip accepts New Game input and
runs the opening cinematic at a median 30.1 fps; its title screenshot clearly
shows version 1.0.2. The host opens real stereo 48 kHz audio output and its
callbacks continue; audible output was not independently checked.

The initial launcher scan produced an Android input-focus ANR before the first
game launch. The subsequent Turnip run did not add another ANR. The initial host logged
completion-effects fence warnings and two 1x1 texture-format view fallbacks.
Both are resolved in the updated host: the fence warning compared a freed
allocation's address with a later allocation, while integer unbound-image
placeholders used normalized storage. The general fixes and their tests are
linked under "Found on this game". Post-fix title tests on the system driver
and Turnip show neither warning at about 30 fps. The system-driver test also
passes with format learning disabled, proving saved format pairs are not
needed. This diagnostic setting was removed after testing. Opening-cinematic
colour/lighting correctness has not been compared against a console reference.
No gameplay, avatar completion, combat, saving or Continue validation yet.
The test app was force-stopped after both runs.

This is the Switch 1 release, not Switch 2 program `0400453019AA8001`.
It is not yet listed as a supported playable game.

## Builds and source

The starter profile and allocator adaptation come from
[nxnative commit b32d0bf3](https://github.com/seedds/nxnative/commit/b32d0bf3b5fa7ddc8e0d9d006f3bad82ac82bd5c).
Its upstream gameplay evidence covers Mac update 1.0.1, not this Android port.
The ARH2 and mixed Maxwell/NVN2 HCPS readers and synthetic tests were compared
with nxnative commit `aa9e0ff71d9315b5ada964ab3d8e0ecb6f9b9481`.

Local update 1.0.2 modules:

| Module | NSO build ID (first 16 bytes) |
|---|---|
| main | `2a720c7ce5c84905c1c6847211f670fb` |
| subsdk0 (movie) | `99f9fc3860de320166ad07a6442a64f4` |
| sdk | `cff74470477c5fc65bb2538fd4a72441` |

## Experimental 30 / 60 / 120 fps

The launcher offers Game owned, 30, 60 and experimental 120 fps. The profile
keeps 30 as its default. Game owned requires a restart and leaves the original
game instructions and interval settings intact. The high-rate plan is restricted
to the registered 1.0.2 main; another build cannot receive these addresses.

Source research included [Fl4sh9174's mods](https://github.com/Fl4sh9174/Switch-Emulator-Ultrawide-FPS-Mods).
Its XCX archive's main dynamic 20–120 fps and static 60 fps patches target 1.0.1
(`3f2425864cf22684`), so those main offsets were not carried into this build.
Its SDK 120 fps patch does match this release's SDK, but changes a native SDK
queue-buffer instruction; Peanuts supplies NVN presentation itself, so that
instruction is not included in this main-module plan. The timing corrections
instead follow [FPSLocker Warehouse's exact 1.0.2 configuration](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/SaltySD/plugins/FPSLocker/patches/0100453019AA8000/2A720C7CE5C84905.yaml).

The existing Peanuts FPS framework supplies the launcher choices, reviewed
memory rows and loader relocation plan. The plan checks all 38 original code
words before installing its 96 code/data words, allocates separate code and data
pages, and seals code RX and data RW. Inserted-call sites belong to functions
that save LR, checked through this binary's unwind table and disassembly.
Helpers preserve their scratch registers, including the camera's temporary SIMD
register. The corrected paths cover camera rotation, gameplay/cutscene step,
UI rate, combat, guns, field actions, FN sites, long jumps, QTEs, fog and overdrive.
These are community timing corrections, not original Horizon/NVN semantics.

The Switch mod's raw-counter routine is replaced with the host's monotonic
elapsed time measured between presents. Speed ratio, elapsed FPS and seconds
share its clamped interval (at least one target frame and at most 1/15 s).
Both engine interval words are reasserted as 1. A 32-bit movie-state value above
2 caps the host at 30 and sets the dynamic-resolution target to 1/30 s.
No new unrewritten system-register instructions enter guest code.

The engine also has an independent wait loop at `main+0x13a9a50`: its context's
period method (`main+0x13baa30`) returns the literal 1/60 s. Updating NVN's tick
alone left even a light settings screen at 60 fps on a 120 Hz panel. The plan
redirects only that loop's two period calls (`+0x13a9ae8`, `+0x13a9b60`) to the
selected target period; other consumers retain the original method. The loop's
interval is already 1 and its 0.997 margin is unchanged. This extra native-host
adaptation is needed beyond the FPSLocker config.

Device checks on 2026-11-17: Turnip at a 120 cap reached about 71 fps on the
3D title screen (73 in its screenshot), 119.0 fps in the light settings screen,
and 30.2 fps in the opening movie. The panel actually ran at 120 Hz for this
check; its original 60 Hz minimum and maximum settings were restored afterwards.
The 120 choice remains experimental: it unlocks the ceiling but does not make
a GPU-heavy scene render at 120. Camera, combat, simulation speed and saving
still need gameplay validation; the game remains experimental.

Final 60-cap checks reached 45.6 fps on the system driver and 59.9 fps on
Turnip at the title screen; Turnip's movie held 29.8 fps. Game owned installed
zero plan words and its movie ran at 30.1 fps. Both driver rounds were stopped,
and the test app was left with Turnip and a 60 cap selected.

Open: the selected-plan Turnip movie transitions logged three live-pool memory
return warnings (`guest memory ... is given back under live pool memory`).
The short Game owned movie comparison did not log them. No crash was observed;
the cause and any rendering consequence are not established. These are separate
from the two resolved fence/placeholder warnings and are not suppressed.

Checks: portable production-code tests exercise consistent ratio/seconds/FPS
at 30/60/120, slow frames, startup, exact-build gates and 32-bit movie states.
The production loader helper accepts the actual Android pack, leaves Game owned
unchanged, and rejects a changed instruction without any partial writes. A
standalone ARM64 check on the device executes the actual relocated camera, combat,
overdrive and wait-period helpers at 30/60/120 and with slow frame samples.
The native FPS host test cannot compile on this x86 Linux host because its
platform headers require Apple or Android ARM64; the Android host/APK builds.

## Memory

The upstream profile's 5 GB reservation, 4.375 GB alias region and physical
application pool `0xCD500000` are retained. The NPDM declares 32 MB of system
resource. These settings have not been measured during Android gameplay.

The game directly reserves 60 GB, then a 4 GB plus 128 KB first arena.
The upstream platform-limit workaround reduces the outer region to 4 GB and
the first arena to 512 MB plus 128 KB with six coordinated instruction cuts.
The existing atomic function matcher finds all six on update 1.0.2 at
`main+0xb9c070`, `+0xb9c080`, `+0xb9c104`, `+0xb9c10c`, `+0xb9c11c` and
`+0x13e3fb0`; original-byte checks pass during the Android rewrite.
No 1.0.1 fixed address is applied to the new build. Later-area occupancy and
Android memory pressure remain open.

## Files and shaders

`sts.arh` indexes `sts.ard` with hashed ARH2 members. Each entry's disk size
includes its XBC1 wrapper; offsets accumulate aligned disk sizes. HCPS
containers can mix Maxwell and NVN2 programs, so each control's own version
selects its lengths and stage decoder. See the shared reference below.

Shader tables are generated from update 1.0.2, rather than relabeling the
upstream 1.0.1 tables. The split finds 22,838 keys (7,643 vertex, 15,190 pixel,
two geometry and three compute), including six in `main`, and 18,029 variation
pairs. The complete key set is identical to the upstream 1.0.1 reference.
All 22,838 shaders translate successfully to 23,505 Vulkan modules, including
integer-input variants. Every module passes `spirv-val --target-env vulkan1.3`;
there are zero undecoded binding slots and zero unrecognized binding kinds.
The compressed library is 67.3 MB (251.1 MB of SPIR-V before compression),
chain `20aaebbe3106`, Vulkan chain `b09844b02acf`, inputs `f8767773986c`.

On NFS, probing a missing result file for every shader can take minutes before
translation starts. A local staging dump and its work folder avoid these
metadata round trips; the finished library and stamp can then be copied back
beside the original game. No compiler or chain changes are needed.

## Checks

- The production placeholder function passes the strict-format-list regression
  across numeric classes and image shapes; the original source fails on its
  first integer view. The native format-list unit test also passes.
- Fifteen synthetic Monolith parser tests pass, including mixed Maxwell/NVN2
  programs, ARH2 alignment, truncated indexes and out-of-bounds members.
- All three modules rewrite for Android with no red diagnostics in their
  rewriter logs. Main applies all six function-matched allocator cuts; the SDK
  applies two shared cuts.
- The Linux Peanuts host tests, 176 Peanuts Python tests, 25 Android profile
  tests (three skipped), 16 patch-registry tests, 11 documentation tests and
  five tile-budget tests pass. Runtime-profile generation covers all 152
  profiles with the existing schema and layout.
- The normal Peanuts PC builder completes from a `.nup` containing the base,
  update and DLC: 157.5 MiB pack, zero red diagnostics. The importer validates
  the package structure and all 117 file extents (26.74 GiB); this check is not
  a full payload checksum. The imported update's three modules match the
  surveyed modules byte for byte.
- A signed test APK builds on Linux with the ARM64 host and this profile. Its app ID
  is `dev.nxnative.android.peanuts.xcxtest`; it contains the PC-prepared pack
  launcher, with the on-device pack engine left out (`nxPackEngine=fake`).
- Full graphics/tool suites were attempted but are not green on this Linux
  host: Apple compiler tools and Mach-O executables are unavailable, and the
  full native host suite also hits ARM NEON / PIE link errors. These broad
  suites are not reported as passing.

## Found on this game

- [Monolith archive and shader containers](../../../docs/reference/shaders.md#monolith-arh1-xbc1-and-hcpsslct-shader-containers): ARH2 and mixed Maxwell/NVN2 HCPS programs.
- [Reused completion-array addresses](../../../docs/reference/rendering.md#reused-completion-array-addresses-reported-as-duplicate-effects-android): allocator address reuse caused a false duplicate-effects diagnostic.
- [Integer unbound-image placeholders](../../../docs/reference/rendering.md#integer-unbound-image-placeholders-using-normalized-views-android): cold-start integer descriptors fell back to normalized views.

- [Reviewed dynamic timing plans](../../../docs/reference/frame-rate.md#reviewed-dynamic-timing-plans-on-android): exact-build timing fixes and 30/60/120 fps controls.

## Open

- Test the opening and saved-game reload on Android with system and Turnip
  drivers; check rendering, audio, input, shared fonts and memory pressure.
- Validate camera, combat and simulation speed with the experimental 60/120 fps timing plan.
