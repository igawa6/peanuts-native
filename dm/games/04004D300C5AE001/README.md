# Kirby and the Forgotten Land - Nintendo Switch 2 Edition (`04004D300C5AE001`)

Program 1 of application `01004D300C5AE000`, display version 1.1.0 (update v65536), SDK 19.3.7 (Ounce). The Switch 2 Edition + Star-Crossed World is another program beside the original game, [Kirby and the Forgotten Land](../01004D300C5AE000/README.md) (program 0), which keeps its own profile: its own main (`395fc326c2e5b1651d937074f8cfd75d`), sdk (`5e81e79381e8be36fe8bcf05ae18d6dd`) and `subsdk0` (libc), and NVN2. Status: **bring-up**: it plays on Android (the AYN Thor, 12 GB) from the save into its first areas.

## What it needs

| Setting | Where | Why |
|---|---|---|
| 39 GB block: `mem_reserve 0x9a8000000`, `mem_alias 0x980000000`, `mem_stack 0x10000000`, `app_pool 0x240000000`, `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | 16 MB of system resource (virtual address memory), no heap: the game takes its memory from three StandardAllocators over address regions of 20, 10 and 5 GB, which the 38 GB alias region holds as asked, after the 62 MB node area; the Switch 2's 9 GB pool, which the game needs ([Memory](#memory)) |
| `modules main subsdk0 sdk` | `game.tsv` | every module main imports from |
| The SDK's node area cut to 62 MB | `dm/tools/sdk_patches.py` (the SDK-wide rule for this constructor, `sdk+0x14a9c0`, which Mario Kart World's, Yoshi's and Donkey Kong Bananza's SDKs share) | `nninitStartup` initializes a StandardAllocator over 20 GB of address space; the host's cap gives it 4.06 GB, which fits the alias region only after a 62 MB node area ([Memory](#memory)) |
| No NaN fill of new allocator blocks | [`patches.py`](patches.py) (main `395fc326…`) | the game's allocators fill every new block with a signaling NaN; at boot that writes 5.5 GB, all of it resident ([Memory](#memory)) |

## Memory

- **The node area.** `nninitStartup` initializes a StandardAllocator over 20 GB of address space (`StandardAllocator::Initialize(nullptr, 20 GB)`, returning to `main+0x86cf10`). The host's cap gives it 4.06 GB, which fits the alias region only after the SDK's node area is cut to 62 MB (the AddressRegionManager constructor at `sdk+0x14a9c0`); with the 1 GB node area it aborts in `nn::diag` at `sdk+0x13c5d8`.
- **Three allocators, a 38 GB alias region.** `nnMain` (`main+0x661ba0`) takes blocks of 2.64 GB (`0xa8f2ec30`) and 2.83 GB (`0xb5400000`) from the first allocator, then initializes a second over 10 GB with a 3.17 GB limit (`main+0x661c88`) and a third over 5 GB (`main+0x661e94`). With 8.69 GB of memory or less (`0x22bf2ec30`, compared at `main+0x661bf0` and `+0x661cb8`) it spins forever (`b .` at `main+0x661e00` / `+0x661e04`), so it keeps the 9 GB pool. The 38 GB alias region holds all three regions as asked, so no allocator is capped: address space only, and the pool bounds what is mapped.
- **No NaN fill.** The game's allocators fill every block they hand out with `0x7fbfffff` (a signaling NaN) unless a no-fill byte is set, and Kirby leaves it clear. The blocks `nnMain` takes would make that 5.5 GB written in the first second, every page of it resident: on a 12 GB phone Android's low-memory killer stops the game. The cuts in [`patches.py`](patches.py) branch past the fill at its five sites (the host gives fresh memory as zeros); the game then holds 2.5 to 3.2 GB.
- **The shader-code pool.** A 2,900 MiB pool of shader code moves to its file in 64 MiB steps, dropping each step's pages as it goes ([Moving a large pool to the file doubled its RAM (Android)](../../../docs/reference/memory.md#moving-a-large-pool-to-the-file-doubled-its-ram-android)).

## Files and shaders

- Program 1 is in the update, `updates/65536/program/1/` in the console's layout (`program1/` in the flattened one). Its own RomFS holds one file (`Empty`); it reads program 0's RomFS (the update's, 9,386 files, 5.7 GB) through the program-indexed `MountRom` (`DM_ROMFS_PROGRAM_0`, `dm/host/hle_fs.c`) and add-on `01004D300C5AF001` (Star-Crossed World and the Switch 2 Edition's content, 11,326 files, 10.8 GB).
- 93,348 shaders in the map: 66,046 from the add-on (its `gfx/` models and effects, and the 36 of its NDSR pack `ndsr/lower_left.ndsr`, agl's upscaler, group `other`), the rest from program 0's RomFS and main. Its six graphics shaders draw effects (the dust cloud that drops coins at the start): without them in the library the frame is black while such an effect draws. The `shader_groups` are program 0's (`/gfx/`, `/ptcl/`, `/lyt/`, `/shader/`).
- The survey ran on a folder of program 1's ExeFS, the update's RomFS and the add-on, on Windows (the survey's own steps, no heap check); the split ran on the Mac on the same folder.

## Open

- The heap check (Mac) has not run on this profile; `shader_groups` are program 0's, unchecked for program 1.

## Found on this game

- [The SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area): this `sdk` build's constructor, under the SDK-wide rule it shares.
- [Built-in fonts and the audio device notification of SDK 21](../../../docs/reference/services.md#built-in-fonts-and-the-audio-device-notification-of-sdk-21): `nn::pl::GetBuiltInFontDataSet`, the batch form.
- [Moving a large pool to the file doubled its RAM (Android)](../../../docs/reference/memory.md#moving-a-large-pool-to-the-file-doubled-its-ram-android): its 2,900 MiB shader-code pool.
