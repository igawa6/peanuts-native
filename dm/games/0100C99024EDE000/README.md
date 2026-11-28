# Mega Man Star Force Legacy Collection (`0100C99024EDE000`)

<!-- PEANUTS: Peanuts-only profile (Banana Native has none for this title) -->

Capcom's *Mega Man Star Force Legacy Collection* (RE Engine, Havok, Wwise, SDK `20.5.15`, NVN).

## Builds

| Version | Display | `main` | `subsdk0` (`NintendoSDK_movie`) | `sdk` |
|---|---|---|---|---|
| `v65536` | `1.0.1` | `b81e081feebc12a18fb8420b8b810c0f` | `3904a8dee6801472d56bd4921378df27` | `190030518b5d64e5d87b84bc54661af9` |

## Shaders

14,604 RE Engine SDF shaders extracted from `re_chunk_000.pak` (`shader_split.py`), with 4,426 compute thread-group / shared-memory facts (`shader_facts.tsv`) and 16,384 vertex/pixel variation pairs (`shader_pairs.tsv`).

## Rewrite census

From the `v65536` (`1.0.1`) survey:

- `main`: 40,730,178 instructions (2 undecodable), 20,271 `x18` uses, 161 `mrs tpidr_el0`.
- `sdk`: 1,674,226 instructions (2 undecodable), 15,571 `x18` uses, 124 `mrs tpidrro_el0`, 58 `svc`, 43 `mrs cntpct_el0`, 3 `msr tpidr_el0`, 2 `mrs tpidr_el0`.
- `subsdk0`: 875,249 instructions (3 undecodable), 9,784 `x18` uses.

## Memory

The NPDM declares 20 MB of system resource (`sys_resource 0x1400000`) and RE Engine reserves a 16 GB alias region (`mem_alias 0x400000000`, `mem_reserve 0x428000000`).

## Host fixes found on this game

- **`nn::fs::IsSaveDataExisting` (`dm/host/hle_fs.c`):** RE Engine checks `IsSaveDataExisting` on first boot; returning true for an empty save directory caused the game to skip creating save data and stall on "Creating new save data". Answering truthfully (first user's save exists once the directory has entries) and letting `EnsureSaveData` create the first user's directory fixes first-boot save creation.
- **`nn::hid::InitializeGesture(int, int)` (`dm/host/hle_hid.c`):** SDK `20.5.15` imports the two-argument overload (`_ZN2nn3hid17InitializeGestureEii`); without an HLE hook the SDK version calls `sm::Initialize` and aborts with Result `0xe15`.

## Frame rate

- **Turnip T30 (AYN Thor, Adreno 740, Android 13):** 60 fps across the collection launcher (3D Mega Man), Star Force 1, 2, and 3 overworld, encounter transitions, and 3D battles (GPU busy p50/p90 `2.3 / 4.5 ms` in menus, `3.7–3.9 ms` in gameplay/encounters). Audio (Wwise + Opus voice lines) plays cleanly in sync.
- **System driver (AYN Thor, Android 13):** blocked at the readiness check (stock Adreno 740 Android 13 driver lacks `VK_EXT_map_memory_placed`).
- Not run on the Mac.
