<!-- PEANUTS: Android bring-up evidence and limitations. -->
# CRISIS CORE –FINAL FANTASY VII– REUNION (`01004BC0166CC000`)

Base 1.0.0 (v0), SDK 13.3.0, Unreal Engine 4.27, NVN. The profile covers `main`, `subsdk0` and `sdk` for this build only. The decoded global and game shader archives and executable yield 22,104 unique keys. The profile contains keys, pairs and compute launch metadata, not game shaders. No prewarm seed was recorded.

## Android bring-up

The UE4 replacement allocator must preserve the virtual-page alignment of over-aligned requests. Without that fix the audio SDK can run out of space inside the configuration buffer before the title screen ([allocator fix](https://github.com/igawa6/peanuts-native/pull/236), on main since 0.1.7). Its DSP work buffer is separate.

Two fresh Normal games were tested on an Odin 2 with Turnip v27 R1 on 2026-11-20 (UTC):

- [18:55 UTC report](https://github.com/igawa6/peanuts-native/pull/236#issuecomment-7048003976): runtime `3a9393e70810` used the older compatible Vulkan bank `18075425630b`. Movement, hits and HP changes worked at Sector 1 Station – Platform, but the real-time scene was severely overexposed. The opening movie and battle overlays remained legible.
- [19:44 UTC report](https://github.com/igawa6/peanuts-native/pull/238#issuecomment-7049002556): the combined profile and allocator build `7137913365fe` used its matching bank `dfebef5d68a9`, with brightness at the default of 5. The station, train, characters and damage numbers were visible during combat. Zack used a potion, defeated the platform sentries and received the call directing him up the stairs. This cleared the **first encounter**, not the mission.

The severe overexposure did not reproduce in the second run. Both the runtime build and the bank changed, so the comparison does not identify its cause.

**0.1.8 runtime, 2026-11-25.** AYN Thor (Adreno 740), the base game built on the phone by the app with a pack from the current chain (22,104 shaders), the same pack and a new Normal game for each driver, brightness 5:

- Turnip v27.0.0-R1 (`git-f28352d117`): the station is lit correctly; the first encounter clears and the game goes on to the Behemoth scene. 58.95 fps in the fight (SurfaceFlinger, 3,012 of 3,017 frames at two refreshes of the 117.84 Hz panel, max 25.5 ms).
- Turnip 27.0.0-R2 (`git-2648b2059f`): **the severe overexposure comes back.** The station and the fight are near white (94 % of the scene above 240 of 255); movement, hits and HP changes work, 58.96 fps.
- Stock Qualcomm driver (Vulkan 1.3.128): boots to the title at 59 fps, then stopped on a SIGBUS in `libhwui.so` as the station scene began (one run).

<!-- PEANUTS: #578 landing note (2026-11-26) -->
**Later finding (2026-11-26, GitHub #578):** the overexposure follows the pipeline cache, not the driver. With an empty pipeline cache both R1 and R2 wash out white on the first run (about 100 % of the scene above 240), and a warm cache is right on R2 (0 %); `DM_VK_PSO_ASYNC=0` or a program list learned from an earlier run also gives 0 %. The R1 run above was second, with the programs the R2 run had learned. It is the known case of a draw skipped while its pipeline is built leaving a result the game keeps (docs/reference/rendering.md): a 1x1 exposure value goes from about 0.00036 to 3332 and never recovers. A prewarm seed would avoid it; this profile ships none. The stock driver stops either with a SIGBUS in `libhwui.so` at the station or a SIGSEGV in `vulkan.adreno.so` creating an image view before the title. The profile has no readiness row.

<!-- PEANUTS: on the 0.1.9 release list (2026-11-27) -->
**On the release list from 0.1.9** (`peanuts/release-titles.txt`, so in the default APK), as a bring-up: only the opening was played, to the Behemoth scene. Play it with Turnip. On the first start, with an empty pipeline cache, the picture can wash out white; the next start, with the cache the first one filled, draws it right. Qualcomm's own driver crashes (above).

Original reading: The second run above used Turnip Gen8 V38, the R1 generation. Later progression remains untested; the status is **bring-up**.
