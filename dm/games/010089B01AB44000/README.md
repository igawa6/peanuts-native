# SPY×ANYA: Operation Memories (`010089B01AB44000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.1 (v65536) over the base game, SDK 14.3.1. Unity 2020.3.43f1 IL2CPP on NVN, with CRIWARE and FMOD. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The profile is still the provisional one from `scripts/build_ipa_from_nsp.py`, except for its memory layout. On Android (AYN Thor, Adreno 740, Turnip) a new save goes through the license screens, the title menu and the classroom tutorial's first steps (moving, the camera, dashing) at 30 fps, with no red line; from the dash tutorial on, text loses letters it shows for the first time ([Text](#text)).

## What it needs

| Setting | Where | Why |
|---|---|---|
| `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | the NPDM's 16 MB of system resource: virtual address memory |
| 16 GB alias region: `mem_alias 0x400000000`, `mem_reserve 0x428000000` (front, stack and heap regions as in the 5 GB block) | `game.tsv` | with the 4.375 GB alias region Unity got no address region while loading a new game; see [Memory](#memory) |

## Memory

- `nninitStartup` gives application pool − used memory to StandardAllocator: one 3.12 GB address region at alias +0.06 GB (`[mem] address region 3.12 GB at alias +0.06 GB, returning to main+0x4d4350`). Unity's own allocator then reserves its blocks as address regions after it.
- **Resolved: a write at NULL + 0x7fffe0 or NULL + 0xffffe0 when a new game starts loading** (`main+0x9cb5b8`). The function at `main+0x9cb524` grows a Unity heap by one block (`[x19+0xf0]`, 8 MB or 16 MB): it reserves the block through the allocator object at `[x19+0x100]` (vtable slot 0), commits it (slot 2) and takes its base (slot 4). The base came back null (x0 = 0, x21 = 0), and the code writes the block's header at base + size − 0x20 without checking. The alias region was full: its high-water mark was +4.27 GB of 4.375 GB, with 1.25 GB mapped. With a 16 GB alias region the same route maps up to +5.26 GB (1.75 GB mapped) by the first tutorial and goes on. On Android the larger region is only address space. The 5 GB block was an iPhone limit ([Sizing the pool and alias region for virtual address memory](../../../docs/reference/memory.md#sizing-the-pool-and-alias-region-for-virtual-address-memory)).

## Text

- Unity's dynamic font atlas is an R8 texture that the game makes again over the same memory when it adds glyphs: `nvnTextureInitialize`, one whole `WriteTexels`, then `nvnTextureFinalize` of the old object, all in one frame.
- **Open: letters missing from text shown for the first time.** On a new save, from the end of the dash tutorial on, the controls panel reads `B  as` and `A  al  amine` for Dash and Talk/Examine and the objective `Talk to Becky` reads `a to ec`; letters shown before (Move Anya, Rotate Camera) are whole. No red line.

## Open

- On the system driver (Qualcomm's own) an earlier run (2026-11-17) drew the characters as dark silhouettes with only their eyes; on Turnip they are right. Not looked into, and not run on the system driver in the 2026-11-21 round.
- The tutorial was not played past `Return home`.

## Found on this game

- [Sizing the pool and alias region for virtual address memory](../../../docs/reference/memory.md#sizing-the-pool-and-alias-region-for-virtual-address-memory): the hardware pool's StandardAllocator region left Unity 1.2 GB of a 4.375 GB alias region; a 16 GB alias region on Android.
