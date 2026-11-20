<!-- PEANUTS: collection member bring-up. -->
# Kingdom Hearts Final Mix

Program **1** of *Kingdom Hearts HD 1.5+2.5 ReMIX*, base v0 (1.0.0). The member's `main.npdm` identifies `010090C022978001`; the collection menu is program 0 (`010090C022978000`), and [Kingdom Hearts II Final Mix](../010090C022978003/README.md) is program 3. They have different `main` builds even though their SDK modules match. This profile is for code preparation, not an Android playability listing.

With a dump folder containing both program 0 and program 1, run `python scripts/pack_android.py <dump> --program 1 --code-only`. The member's RomFS contains 17 files (7,586,936,221 bytes), including `kh1_first.pkg`. A program-0-only `.nup` cannot supply either the member's code or its RomFS.

`main` imports `vkCreateShaderModule` and `vkCreateViSurfaceNN`. In its decompressed memory image the SPIR-V bank starts at `0x30c838`, spans `0x31ce8` bytes including padding, and has SHA-256 `8c7e503ad269efa6bd85b18eb19ed9d580fc9bdf8fd7e372df80f9150f52ebf4`. All 48 embedded modules (26 vertex, 22 fragment; 46 distinct hashes) validated with `spirv-val --target-env vulkan1.0`. These are not NVN shader-map entries. The Android host needs a guest Vulkan presentation path for the VI surface; a successful code rewrite or shader validation does not establish a boot, rendered frame, or player control.
