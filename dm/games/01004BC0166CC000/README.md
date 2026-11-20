<!-- PEANUTS: Android bring-up evidence and limitations. -->
# CRISIS CORE –FINAL FANTASY VII– REUNION (`01004BC0166CC000`)

Base 1.0.0 (v0), SDK 13.3.0, Unreal Engine 4.27, NVN. The profile covers `main`, `subsdk0` and `sdk` for this build only. The decoded global and game shader archives and executable yield 22,104 unique keys. The profile contains keys, pairs and compute launch metadata, not game shaders. No prewarm seed was recorded.

## Android bring-up

The UE4 replacement allocator must preserve the virtual-page alignment of over-aligned requests. Without that fix the audio SDK can run out of space inside the configuration buffer before the title screen ([allocator fix](https://github.com/igawa6/peanuts-native/pull/236)). Its DSP work buffer is separate.

With the allocator fix, the profile reached the first playable battle on an Odin 2 using Turnip v27 R1. A new Normal game reached Sector 1 Station – Platform: movement and attacks worked, damage registered against a sentry and player HP decreased. The runtime used a previously prepared shader library of compatible ABI. The real-time scene was severely overexposed; the opening movie and battle overlays remained legible. Mission completion, later progression and the system driver were not tested. The status is **bring-up**, not playable.
