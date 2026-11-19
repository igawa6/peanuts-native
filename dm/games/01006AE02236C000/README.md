<!-- PEANUTS: exact-build host bring-up -->
# Double Dragon Revive

## Status

Host profile for the Switch release (`01006AE02236C000`). The base and patch imported together. The NACP display versions are `1.0.0` (v0) and `N1.10` (v65536); the latter is the default pack. Android boot, a New Game, player input, and rendering on either driver have not been verified.

## Code and memory

Both builds use SDK 18.3.1, NintendoSDK movie (`subsdk0`), Unreal Engine 5.4.3 and NVN. The five NROs in the RomFS are EOSSDK, KITT, and its data, support and WebKit modules. The main build IDs are `e4425ccd2d3252723e5ea90894722c78` (base) and `dbd5c9ec799e65a9ffedc1c1a94c8657` (patch). `game.tsv` identifies each required module in load order.

The NPDM requests 20 MiB system resource. The Android partition follows the existing Unreal profile for [Captain Tsubasa 2](../01009720213B0000/README.md), which ships the same SDK and movie modules. This is an initial partition, not a measured gameplay high-water mark.

## Shaders

The engine's global and ProjectDD material shader libraries are stored in the unencrypted `ProjectDD-Switch.utoc` / `.ucas` IoStore container. `ue_ondevice.decode` with the PC Oodle worker decoded 2,686 global records in each build and 15,917 / 16,011 material records respectively. The decoded global library is byte-identical between builds; the material library changed. Of the 18,497 distinct IoStore shader keys in the union, 28 occur only in the base and 114 only in the patch. `shader_optional.tsv` records those measured differences, not a blanket allowance for missing shaders.

Two KITT NROs, byte-identical between the builds, contribute 13 more keys. The full split has 18,510 keys: 1,620 compute, 4 geometry, 15,194 pixel and 1,692 vertex. Both versions contribute to this map; the update alone is not a substitute for the base material library.

The desktop Android pack for v65536 translated all 18,510 keys to 37,712 Vulkan 1.3 SPIR-V modules, with no unknown binding kinds or undecoded binding slots. All 18,482 keys required by v65536 are present in the packed library; the other 28 belong only to the base.

`hle_oodle.c` found one `OodleLZ_Decompress` function in each main: `main+0x153ca30` on the base and `main+0x16efce0` on the update. On Odin, the update's `DM_OODLE_SERVE` worker decoded one real IoStore shader group (282 to 2,592 bytes, zero failures). The complete 2,600-byte reply matched the independent host decode: SHA-256 `1284343b43c1b56c2d21a462118721023c6285ef6ebe45699e9016750f7163b5`. This tests the decoder, not gameplay.

## Found on this game

Both executables select a 62 GiB region before calling a helper that also has a caller outside startup. [Unreal engine address-region detection](../../../docs/reference/memory.md#unreals-engine-address-region-and-its-options) gates the startup size store at `main+0xc940b8` / `main+0xe2bafc` without changing that shared helper. The existing FMallocBinned3 detector recognizes the constructor in both builds. These are static matches, not evidence of a successful Android allocation.
