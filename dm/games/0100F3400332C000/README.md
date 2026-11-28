<!-- PEANUTS: Xenoblade Chronicles 2 (Japan), update 3.0.1 -->
# Xenoblade Chronicles 2 (Japan)

## Current status

The base game and update were tested on a Lenovo Legion Y700 Gen 5 (TB323FU, Android 16, Adreno 840) on 2026-11-27. The base and update import as one `.nup`. Add-on content and later chapters remain untested.

## Android

- **Qualcomm system driver 842.31:** Started a new game in Simplified Chinese on Normal, played through the King Crustip fight, reached Argentum in Chapter One, and created a manual save. Typical five-second log samples in the title and field were about 30 fps. Rex's model showed streaks before drawing his weapon and stretched across the screen in Argentum. The first run also saw three rejected pre-rasterization pipeline libraries and continued using whole pipelines.
- **MrPurple Turnip T30 (PurpleVK 26.3.0-devel, driver ID 18):** Restarted and loaded the Argentum save, then walked and rotated the camera in the same area. The observed character streaks were absent and recent five-second log samples were about 30 fps. The main Build ID in the Android log matches the profile.

## Native frame rate

This profile uses the shared host's bounded time-step and integer interval operations. Its Android thread tuning is enabled only for the reviewed Y700 Gen 5 model and matching main Build ID.

The Android game settings offer Game owned and 20, 30, 40, 45, 60, 90 and 120 FPS. The profile defaults to 30 FPS. The 60 FPS mode uses the same build-checked timing plan as Xenoblade Chronicles X. It adjusts simulation and UI time to the measured frame time and scales the camera, particles, renderer animation, hair and cloth. The host limits presents to 30 FPS when the game's event flag is set. Cloth movement is capped at the original 30 FPS step, including at 20 FPS. Damping interpolates between the exact retention values at 30, 60 and 120 FPS.

On the Y700 Gen 5 with Purple Turnip T30 and frame generation off, the 30 FPS setting held 30.0 FPS once the Chapter 1 Argentum save had loaded. With Use fast cores enabled, the 60 FPS setting reached 59.8–60.1 FPS in individual five-second windows. Other windows during movement and camera turns dropped to the high 40s and 50s. The same 2.1-second stick sweep from the same save changed the quest marker distance from 63 to 60 at both settings. The title and load sequence reached 30.0 FPS during the event cap with 60 FPS selected. Later battles, dialogue and cutscenes still need a gameplay pass at 60 FPS.

The Y700 test installation uses the docked picture at 1x scale and reports the whole graphics frame to Android ADPF using `DM_VK_ADPF_SPAN=100`. A warmed stationary sample with this setting held 59.3–60.0 FPS for four consecutive five-second windows. A second route with movement and camera turns had five-second samples down to 46.3 FPS. The tests at 0.75x scale and in handheld mode did not improve performance. The ADPF override is local to the test app.

On the Y700 Gen 5 with the cloth fix installed and frame generation off, the 20 FPS smoke test reached 20.2 FPS. In the warmed port, two consecutive five-second samples at each target were 45.2–45.4 FPS at the 45 cap, 46.2–46.5 FPS at the 90 cap and 45.5–45.7 FPS at the 120 cap. The display modes followed 45 (90 Hz panel), 90 and 120, while the device exposed CPU frequency ceilings of 1.997/2.880 GHz. The high rates remain experimental targets.

### Y700 Gen 5 movement and camera performance

The first port sweep with per-frame tracing averaged 42.2 FPS over 25 seconds. Late frames spent a median 5.8 ms on the GPU, with a median 0.24 ms in NVN submit and present. The slowest frames were waiting on a game condition variable signalled by the guest main thread. Warm pipeline creation and display acquire waits were absent in the ordinary late samples.

Following [Taiko Risshiden V DX](../0100346017304000/README.md#frame-rate), the profile now zeros the engine's wait threshold while the active sync divisor is one, so Android's existing limiter owns the pacing at 60. A near-zero threshold prototype averaged 49.5 FPS on the same route. The 30 FPS mode and game-flagged events still use the previous engine threshold. Shorter job polling underperformed in a diagnostic run and was dropped.

On the Y700 Gen 5 (TB323FU), **Use fast cores** assigns the guest main thread and Vulkan replay one large core each when two large cores are available. The encoder and other guest workers keep their existing placement. In paired traced runs with the same reported CPU caps, the original thread placement averaged 42.3 FPS and separate cores averaged 51.1 FPS. Frame time p90 fell from 26.6 to 22.0 ms and GPU time remained about 5.8 ms at the median. During the session the device exposed CPU frequency ceilings of 1.997/2.880 GHz, below its earlier 3.513/4.512 GHz ceilings.

Before the handoff notification change, the test APK measured 59.6 FPS at rest and 47.9–52.8 FPS in the port movement and camera windows with per-frame tracing off. The logs confirmed frame generation was off and 30 FPS took over during startup with 60 FPS selected. The separate 30 FPS run measured 29.8–30.1 FPS during the short movement check after the initial port load.

#### Main-thread handoff

CPU sampling during the port route attributed about 41% of the sampled main-thread cycles to stacks containing `pthread_cond_signal`. An off-CPU sample in the port found the same-core handoff polling in stacks containing `nanosleep`. The guest scheduler must still wait for the higher-priority peer it made runnable. On the Y700 Gen 5, the reviewed profile now enables `DM_MAIN_HANDOFF_NOTIFY`, so the active peer directly notifies the blocked main thread when it leaves its handoff or exits. The waiter rechecks the original runnable and priority predicates under the same lock. A timed recheck covers state changes outside those notifications.

The native 60 FPS comparison used the same save, scripted route and 1.997/2.880 GHz CPU ceilings throughout, with Purple Turnip T30 and frame generation off. The order was polling, notification, notification, polling. Counting frames over complete one-second report intervals inside the approximately 25-second route gave:

| Main-thread handoff | First route average | Reverse-order route average |
| --- | ---: | ---: |
| 50 µs polling | 41.54 FPS | 42.43 FPS |
| Peer notification | 58.96 FPS | 59.96 FPS |

The notification runs had one-second samples of 53.7–61.0 and 59.0–61.0 FPS. The timing and synchronization tests also passed with notifications enabled, including stop/resume during a handoff. The installed APK with the profile default enabled repeated the same route at 60.00 FPS over 25.1 seconds, with all one-second windows at 59.0–61.0 FPS.

The final APK was retested at the port with notifications enabled and frame generation off. The 45 cap produced about 45 FPS and the 90 cap about 90 FPS. The 120 cap used the 120 Hz display mode and produced roughly 88–91 FPS before the reported CPU ceilings fell to 0.998/1.747 GHz. Subsequent samples were about 74–78 FPS. Returning to the 60 cap restored about 60 FPS at the lower ceilings.

The patch sites and animation corrections were cross-checked against [xc2-recomp's 3.0.1 timing patches](https://github.com/AksharDP/xc2-recomp/blob/fbebec796097f730eb723e81030259179cbab4ef/recomp/patches-v301.txt). All 13 original timing-site fingerprints matched the Japanese 3.0.1 module. Peanuts validates the Build ID and the original words it replaces before it installs the plan. See [the xc2-recomp MIT license](LICENSE.xc2-recomp).

## Build and memory

Base title ID `0100F3400332C000`, update title ID `0100F3400332C800`, update 3.0.1 (`v1048576`). The module Build IDs are in [game.tsv](game.tsv).

The survey found NVN in SDK 3.5.0, with `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The NPDM declares no system resource. The game uses the same memory profile and Monolith archive family as [the other regional release](../0100E95004038000/README.md). The SDK and movie module Build IDs match that release; the `main` Build ID is different.

## Shaders

The base and update split produced 12,738 keys: 4,089 vertex, 8,633 pixel, three geometry, and 13 compute. Six keys came from `main`. The split also found 9,512 vertex/pixel variation pairs. The tables were generated without add-on content.
