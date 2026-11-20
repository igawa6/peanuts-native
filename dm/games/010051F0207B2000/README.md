# Tomodachi Life: Living the Dream (`010051F0207B2000`)
<!-- PEANUTS: Android game profile and validation notes. -->

Update 1.0.4 (v262144), SDK 20, NVN. `main` and `sdk` are the only modules. The PC shader build produced 14,469 Vulkan shaders.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_used 0x6200000` (98 MiB) | [`game.tsv`](game.tsv) | The rewritten modules occupy 98 MiB. At the default reported usage of 64 MiB, the SDK requests a `0xc9400000` heap that the remaining pool cannot fit. Boot fails after the rejected `SetHeapSize`. |

## Android status

The AYN Thor with Turnip Gen8 V39 reached first-run setup at 30 fps without a heap override. Display-paced caps were offered as an experiment (20 to 120 fps, Game owned by default) until GitHub issue #182: the game steps its logic once a frame, so at 60 it ran at double speed (an Odin 3 owner). The profile now keeps the game's own 30 fps timing only; a higher cap needs a reviewed frame-step plan. <!-- PEANUTS -->

Android bundles 361 Vulkan pipeline keys recorded on Thor. With Turnip Gen8 V39 and an empty cache, “Preparing shaders” built 289 pipelines in 4.9 seconds. The next launch reused the cache and skipped the progress screen. [Cold startup](../../../docs/reference/rendering.md#a-draw-skipped-for-its-pipeline-left-a-result-the-game-kept-android)

## Contributor findings

In [PR #86](https://github.com/igawa6/peanuts-native/pull/86), intermission88 tested the island at 30 fps on an AYN Thor with Turnip 27.0.0 R2. One of three boots crashed when the SDK read from address `0x24` at `sdk+0x37cdfc`. The cause of the null pointer is open.

[nx2android](https://github.com/shirai91/nx2android/commit/7c9422aeba615c7a849dc314b387b14d708995d9) traced missing Mii heads and faces to a conditional depth clear in the Mii renderer.

On the AYN Thor with Turnip Gen8 V39, both settings reached the Mii creator's face-balance screen at 30 fps. Enabling `render_cond` removed the host's ignored-condition warning, with the same visible face previews. The profile leaves the setting off pending a comparison of the in-game Mii models.

**`render_cond on` since GitHub issues #196 and #182** <!-- PEANUTS -->: with the conditions ignored, the host ran every conditional clear and draw. In the Mii creator the Mii model stayed empty (an AYN Thor Max with Turnip T30 and Kimchi R8), and in a town save the ground and much of the scene came out black, the Miis' bodies white and the lamp posts dark. With `render_cond on`, on the AYN Thor with Turnip v26.2.0 R5, the creator draws the Mii's head and every face in its grid, and the town from the issue #182 save draws its ground, lit buildings and lamp lights.
