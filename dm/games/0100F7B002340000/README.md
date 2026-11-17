# FIFA 18 (`0100F7B002340000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Base game 1.0.0 (v0), no update tested. SDK 3.4.0, NVN. EA's FIFA engine for the Switch (EA basekit: AssetStream, the RNA renderer `fifarna`, Apt UI, EAThread). Modules `main`, `subsdk0` (NVN's GLSLC, which `main` imports but never calls: `nn::gfx::detail::GlslcDll` is linked and unused) and `sdk`. The RomFS ships `fifazf.nss`, an unstripped ELF of `main` (symbols and DWARF), which makes every crash report and every disassembly readable by name.

**Status (current): matches play; replays, celebrations, training and close-up camera views do not.** On a Retroid Pocket 6 (Adreno 740, Qualcomm's system driver 0676.53) it boots, shows the menus with their text, and plays whole matches at 60 fps at 1080p (docked mode; GPU about 10 ms a frame), with the players, crowd, shadows and rain right. Not checked: Turnip, handheld mode (720p), the Mac, a companion.

## Files and shaders

- RomFS: EA BIG4 archives (`data0` … `data7`, `datax`, one per language, with `.bh` tables beside them). Their members are chunk-compressed (`chunkzip`, `chunzstd`); `datax.big`'s are also EASF-encrypted.
- **Current:** 330 shaders (160 vertex, 160 pixel, 10 compute) from 189 NXSHADER effects (`.fxo`: an `NXSHADER` header and one whole GLSLC output each, a vertex + pixel program or a compute one) in `data/fifarna/shader.big` and `shaderboot.big` inside `datax.big` → [NXSHADER effects in EA BIG4 archives behind EASF and chunkzip](#found-on-this-game). All 330 translate and pass `spirv-val`.

## Found on this game

- **Resolved: no shader container found.** `shader_split.py` reads EA BIG4 archives (`eabig.py`, `ea_big_shaders`): the archive's table, then only members named `.big` or `.fxo`, through EASF and chunkzip. EASF is AES-128-CBC with the key as its IV; the key is not stored anywhere: `eabig.key_for` finds it in `main`'s code (the 8-byte key name compared with a 64-bit literal in `Fifa::AssetStreamCryptoKeyProvider::GetKey`, then the `adrp` / `add` / `ldp` of the key) and keeps the candidate whose decryption matches the SHA-256 of the first 32 plaintext bytes that the EASF header carries.
- **Resolved: players and crowd drew black in matches.** `sh_irradiance_prefilter.cs` (the compute shader that bakes the spherical-harmonic lighting) was missing from the map: SDK 3.4's GLSLC writes its control section 0x180 bytes longer than its +0x20 says, and the bytes after +0x20's length (float constants) passed as headerless compute code. `glslc_shaders` now prefers the output's own section header when both readings are valid and differ (188 of the 189 outputs agree).
- **Resolved: the menus had no text.** The glyph cache (`EA::Text::GlyphCache_RNA`, a 1024x1024 R8 linear texture) is filled first by a GPU copy and then by the CPU through `nvnMemoryPoolMap`; the GPU copy ended the image's tracking of CPU writes. A linear texture whose GPU writes are copied back into its storage now keeps following CPU writes (`vka_tex.c`, `wb_cpu`).
- **Resolved: close-up kits and faces red with alpha 0, orange shadows.** The runtime texture compressors (`compress_bc1.cs`, `compress_bc3.cs`, `SportsRNA::Utility::DXTBatchJob`) load the composited RGBA8 textures with `SULD.D .B32` (raw 32-bit texels, declared `r32ui`); the host gave them an `R8G8B8A8_UINT` view, so each load returned only the red byte. A shader image of an integer class over a 4-byte texture of another class is now viewed as `R32_UINT` / `R32_SINT` (`DM_VK_RAW32=0`: as before).

## Open

- **Replays, celebrations, training, the goalkeeper camera and similar close-up views: the players are not visible.** They are drawn (with the pitch's pixel shaders skipped through `DM_VK_SKIP_FS`, every player shows, textured), but the pitch, drawn after them in the next render pass on the same targets with the same less-or-equal test, covers them. In a normal match the passes come in the same order and the players show, so the players' depth in these camera modes is wrong or lost. Unknown why; a frame capture is the next step.
- Kit preview: the faces draw black (not looked into).
- `nvnCommandBufferSetSubpixelPrecisionBias` and `nvnQueueGetTotal*MemoryUsed` / `nvnQueueResetMemoryUsageCounts` are unimplemented (red lines, no visible effect).
- The memory layout is the survey's guess (`mem_used` from the Android rewrite); not measured.
