<!-- PEANUTS: base-game bring-up profile -->
# Dragon Quest Monsters: The Dark Prince

## Build

The base application is `0100A77018EA0000`, CNMT version `0`, display version **1.0.0**. The NPDM declares AArch64. The module build IDs are:

| Module | Build ID |
|---|---|
| `rtld` | `567cdd4c5251494c5cd70822e3ccc740` |
| `main` | `221c19c45e821e7ce155f734e979a800` |
| `subsdk0` | `d0f13015e5badf2243d60a2bc9b978dd` |
| `sdk` | `3644a6760469451cabc99f00c540acc2` |

The SDK module identifies Nintendo SDK 13.3.5.

## Shaders

`Data/globalgamemanagers` and the extensionless UnityFS shader bundles report Unity **2020.3.32f1**. For example, `Data/StreamingAssets/AB/2d/shader` contains a serialized file with two Shader objects (class 48) of type hash `a30e3dcf6c6d25153d6f64e5e41caf4e`. This is the Unity 2020.3 layout already recognized by `unityfs.py`; no new reader or title-specific decoder is required for that bundle.

The existing reader consumed all 131 Shader objects in the 2D, character and effects shader bundles to their declared ends (2, 42 and 87 objects respectively). Those three bundles contain 2,225 serialized subprogram records before deduplication; this is not a runtime coverage count.

The full base-game split found **35,020 unique shader keys**: 17,479 vertex, 17,483 pixel, 2 geometry and 56 compute. Of these, 35,009 first appeared in `Data/` and 11 in `main`. The splitter recorded 17,482 vertex/pixel pairs and found shaders in 1,990 RomFS files. Keep `shader_files.tsv` for targeted builds: a file can contribute a pair even when its individual shaders first appeared elsewhere.

## Status

**Bring-up only; startup stalls on Odin 2.** Two launches on Android 13 (Adreno 740, Turnip v27.0.0-R1, Vulkan 1.4.363) initialized the GPU and opened a 48 kHz AAudio stream. Both logged a thread-suspension timeout. On the second launch, the state dump still reported `tick 0 / stage startup` after 33 seconds: the main thread was in `SetThreadActivity` (SVC `0x32`), and the `IL2CPP Threadpool worker` it asked to pause was in a native wait, not stopped. A Vulkan screenshot request was accepted but no next frame was captured.

No title screen, New Game, player input or battle was verified. Audio callbacks are not evidence of audible game sound. The isolated save stayed empty. This profile is not a release-list entry.

In `dm/host/hle_thread.c`, SVC `0x32` accepts a thread already inside an SVC or stopped in the pause signal handler. The handler deliberately does not park a thread executing host code. A native HLE wait records its depth but does not acknowledge an individual pause. That matches the worker's observed state; the dump did not establish which native wait it entered. For a retest, check that this worker can acknowledge the pause with valid guest register and stack state, and that a later Vulkan frame actually reaches the title screen.
