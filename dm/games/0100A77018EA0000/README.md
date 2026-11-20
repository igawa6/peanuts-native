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

A third startup reproduced the hang under a 40-second, process-scoped trace. The targeted worker (`0x102d`) was in `h_oe_PopMsg` → `pthread_cond_wait` (`dm/host/hle_oe.c:347`), waiting on an empty system-notification queue. The main thread was in `nn::os::detail::ThreadManager::SuspendThread` (`sdk+0x378134`) → SVC `0x32` → `svc_thread_impl` → `pthread_cond_timedwait` (`dm/host/hle_thread.c:1226`), waiting for that worker to acknowledge its pause.

The pause signal handler declines to stop a thread in host code. Entering the native wait records its depth, but does not acknowledge an individual pause. A host regression test should cover pausing a guest thread blocked in `oe::PopNotificationMessage` while the queue is empty: the collector needs valid guest register and stack state, and the target must not return to guest execution until resumed. The physical retest is a new presented frame, followed separately by the title, New Game and controlled battle actions.
