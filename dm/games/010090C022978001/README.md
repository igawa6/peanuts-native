<!-- PEANUTS: new profile, collection member (program 1), depends on the guest Vulkan presentation path -->
# Kingdom Hearts Final Mix (`010090C022978001`)

Program 1 of *Kingdom Hearts HD 1.5+2.5 ReMIX* (`010090C022978000`), base v0 (1.0.0). The member's `main.npdm` identifies `010090C022978001`. The collection menu is program 0 and has another `main` build. Kingdom Hearts II Final Mix (`010090C022978003`) is program 3 and has a profile of its own. The SDK modules of the three programs match.

The game draws with Vulkan, not NVN: `main` imports `vkCreateShaderModule` and `vkCreateViSurfaceNN`, and its shaders are SPIR-V modules embedded in `main`. The profile needs the guest Vulkan presentation path (#996): with it, `scripts/pack_android.py` verifies the `guest_spirv` row instead of translating NVN shaders, and the launcher routes the game's Vulkan imports to the Android loader. Without it the packer looks for NVN shaders and finds none.

## Status

**Reached player control once, on a tree before 0.1.7; not run on `dev` yet.** The run was on commit `b2427d87` of the earlier combined pull request (#226), before this profile was split from the presentation path and rebased. On an AYN Thor with Qualcomm's system driver, a new game (New Game, Final Mix, Camera Auto, Rumble On, a new save) reached Dive to the Heart. As Sora the player moved and jumped onto the pedestals, chose the Dream Sword, gave up the Dream Shield, and fought both opening groups of Shadows; the Attack command, hit response and prize tutorial drew. After the battle control returned on the next stained-glass platform, and Sora walked to the transparent door. The run stopped normally. No campaign save, reload or later area was played.

No frame times, game speed or memory peak were measured, and the run was not on Turnip. The profile has no frame rate rows: the game keeps its own pacing, which was not measured. The log of that run was not attached.

Packing, on the PC at the same commit: `python scripts/pack_android.py <dump> --program 1` wrote a full folder pack of 70.5 MiB without `--no-shaders`, placeholders or external shader files, and the rewritten bank matched the `guest_spirv` hash. `spirv-val --target-env vulkan1.0` accepted all 48 embedded modules (26 vertex, 22 fragment; 46 distinct).

## Tested on

| | |
|---|---|
| Device | AYN Thor, Snapdragon QCS8550 / Adreno 740, Android 13 (RAM not recorded for this run) |
| Driver | Qualcomm system driver, Vulkan 1.3.128 |
| App | test app built from `b2427d87` (before 0.1.7) |
| Game | 1.0.0 (v0), program 1 |
| Played | New game to Dive to the Heart: movement, jumps, the weapon choice, two Shadow fights, the next platform |

## Builds

| Module | Build ID |
|---|---|
| `main` | `badf2faf580b712464365c4d529d92c9` |
| `subsdk1` | `99e21f98a397434b534c1459fc38a2b8` |
| `sdk` | `bc204aae7319bab274894f1cc3f5da92` |

The IDs were read from the member's own v0 ExeFS. The SPIR-V bank in `main`'s decompressed memory image (`guest_spirv`):

| Offset | Size (with padding) | SHA-256 |
|---|---|---|
| `0x30c838` | `0x31ce8` | `8c7e503ad269efa6bd85b18eb19ed9d580fc9bdf8fd7e372df80f9150f52ebf4` |

The pack needs a dump folder with program 0 (`base/exefs`, `base/romfs`) and program 1 (`base/program/1/{exefs,romfs}`). The member's RomFS holds 17 files (7,586,936,221 bytes), `kh1_first.pkg` among them. A `.nup` of program 0 alone holds neither the member's code nor its RomFS. For the test app, add a folder that holds only this member beside program 0, so the first supported program is unambiguous.

`mem_used` is the rounded image charge of `main`, `subsdk1` and `sdk` (70 MiB). The other memory rows were not measured on a device.

## Open

- A run on `dev` with the presentation path (#996): a pack from `dev`'s packer, a test app from the same tree, into gameplay, with the log attached.
- A run on Turnip 27.0.0-R2, and the system driver run again on the current tree, each with its log.
- `vk_pipelines.keys`: the guest's pipelines go to the driver directly (`vka_guest.c`), not through the NVN replay's pipeline cache (`vka_pso.c`), so the host is expected to write no keys file for this game. To check after the run.
- Frame times and game speed, the memory peak, 10 minutes of play (the release list).
- Prepare in the app and import from a `.nup`: not supported. Only the desktop folder pack above was used.
- A campaign save and its reload.
- No `shader_files.tsv`: the game's shaders are in `main`, not in RomFS files, so there is no file for a targeted split.
