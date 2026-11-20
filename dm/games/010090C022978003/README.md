<!-- PEANUTS: collection member bring-up. -->
# Kingdom Hearts II Final Mix

Program **3** of *Kingdom Hearts HD 1.5+2.5 ReMIX*, base v0 (1.0.0). Its `main.npdm` identifies `010090C022978003`, not the collection menu (`010090C022978000`) or [Kingdom Hearts Final Mix](../010090C022978001/README.md) (`010090C022978001`). The member's RomFS contains 26 files (11,020,290,317 bytes), including `kh2_first.pkg`.

Use `python scripts/pack_android.py <dump> --program 3` with program 0 (`base/exefs`, `base/romfs`) and program 3 (`base/program/3/{exefs,romfs}`) in the dump folder to create the Android pack. A program-0-only `.nup` cannot substitute. For the Android test launcher, add a folder containing only this member beside program 0, so the first supported program is unambiguous.

`main` imports `vkCreateShaderModule` and `vkCreateViSurfaceNN`. Its decompressed SPIR-V bank starts at `0x41b79c`, spans `0x31e14` bytes including padding, and has SHA-256 `cf241a21f5a3f005976ac4ad6f08e65bac776adca22d0905802109b4bd7c5677`. All 48 modules (26 vertex, 22 fragment; 46 distinct hashes) validated with `spirv-val --target-env vulkan1.0`. No NVN shader table is involved. The launcher checks the rewritten bank before enabling Android Vulkan presentation for this title. No on-device preparation or gameplay result is claimed.
