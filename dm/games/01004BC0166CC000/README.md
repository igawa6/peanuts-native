<!-- PEANUTS: Android bring-up evidence and limitations. -->
# CRISIS CORE –FINAL FANTASY VII– REUNION (`01004BC0166CC000`)

Base 1.0.0 (v0), SDK 13.3.0, Unreal Engine 4.27, NVN. The profile covers `main`, `subsdk0` and `sdk` for this build only. The decoded global and game shader archives and executable yield 22,104 unique keys. The profile contains keys, pairs and compute launch metadata, not game shaders. No prewarm seed was recorded.

## Android bring-up

The UE4 replacement allocator must preserve the virtual-page alignment of over-aligned requests. Without that fix the audio SDK can run out of space inside the configuration buffer before the title screen ([allocator fix](https://github.com/igawa6/peanuts-native/pull/236), on main since 0.1.7). Its DSP work buffer is separate.

Two fresh Normal games were tested on an Odin 2 with Turnip v27 R1 on 2026-11-20 (UTC):

- [18:55 UTC report](https://github.com/igawa6/peanuts-native/pull/236#issuecomment-7048003976): runtime `3a9393e70810` used the older compatible Vulkan bank `18075425630b`. Movement, hits and HP changes worked at Sector 1 Station – Platform, but the real-time scene was severely overexposed. The opening movie and battle overlays remained legible.
- [19:44 UTC report](https://github.com/igawa6/peanuts-native/pull/238#issuecomment-7049002556): the combined profile and allocator build `7137913365fe` used its matching bank `dfebef5d68a9`, with brightness at the default of 5. The station, train, characters and damage numbers were visible during combat. Zack used a potion, defeated the platform sentries and received the call directing him up the stairs. This cleared the **first encounter**, not the mission.

The severe overexposure did not reproduce in the second run. Both the runtime build and the bank changed, so the comparison does not identify its cause. Later progression and the system driver remain untested; the status is **bring-up**.
