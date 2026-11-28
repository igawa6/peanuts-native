<!-- PEANUTS: source-only profile, hangs at startup -->
# Dragon Quest Monsters: The Dark Prince

Unity **2020.3.32f1**, Nintendo SDK 13.3.5, 64-bit (the NPDM declares AArch64), rendering with **NVN**. Base game only, display version 1.0.0.

## Status

**Source only. The game hangs at startup and has never reached its title screen**, on the Odin 2 and on the Thor. The profile has no readiness row and is not in `nxGames`, so no APK lists it. It waits on a host fix: pausing a guest thread that is blocked in a native wait (see below).

What ran:

- **Odin 2, on the main of 2026-11-21 with this profile (Turnip v27.0.0-R1, Vulkan 1.4.363).** Two launches initialized the GPU and opened a 48 kHz AAudio stream. Both logged a thread-suspension timeout. On the second, the state dump still reported `tick 0 / stage startup` after 33 seconds: the main thread was in `SetThreadActivity` (SVC `0x32`), and the `IL2CPP Threadpool worker` it asked to pause was in a native wait, not stopped. A Vulkan screenshot request was accepted, but no next frame came.
- **Odin 2, a third launch under a 40-second process-scoped trace.** The worker the main thread pauses (`0x102d`) was in `h_oe_PopMsg` → `pthread_cond_wait` (`dm/host/hle_oe.c:347`), waiting on an empty system-notification queue. The main thread was in `nn::os::detail::ThreadManager::SuspendThread` (`sdk+0x378134`) → SVC `0x32` → `pthread_cond_timedwait` (`dm/host/hle_thread.c:1328` on `dev`), waiting for that worker to acknowledge the pause.
- **AYN Thor, same tree (Turnip v27.0.0-R1).** A Home and return delivered focus notification 15, and the same process went on from startup to its first Vulkan frame (1920×1080, all black). In the foreground it stalled again in `SetThreadActivity` on worker `0x102d`. Switching Docked → Handheld in the in-game panel delivered operation-mode notification 31; the game reached frame 6, then the same pause stalled again. Docked again delivered another notification but no menu. These notifications wake the worker for a moment; they are not a way to play.
- **AYN Thor, 0.1.8 runtime, 2026-11-25 (Turnip v27.0.0-R1).** The base game prepared on the phone by the app (35,020 shaders), new save: a black screen and no frame in 3.5 minutes, and `[RED] thread 0x102d still not suspended after more than 1 second (stuck in host code?)` in the log.

The pause handshake in SVC `0x32` is the same on `dev` as in the traced tree: a thread counts as paused when it is inside an SVC or its pause signal found it in game code, and the signal handler declines a thread in host code. A thread in a native wait is neither, so the pause waits for it forever. `dm_guest_stop_all` already counts a native wait as stopped; the pause of one thread does not.

No title screen, New Game, player input or battle was seen. Audio callbacks are not evidence of game sound. The saves stayed empty.

## Tested on

| Phone | Chip / GPU | Android | Driver | Runtime | Result |
|---|---|---|---|---|---|
| AYN Odin 2 | Snapdragon 8 Gen 2 / Adreno 740 | 13 | Turnip v27.0.0-R1 | main of 2026-11-21 with this profile | three launches, no frame |
| AYN Thor | Snapdragon 8 Gen 2 / Adreno 740 | 13 | Turnip v27.0.0-R1 | main of 2026-11-21 with this profile | black first frame after Home, frame 6 after a mode change, then the same stall |
| AYN Thor | Snapdragon 8 Gen 2 / Adreno 740 | 13 | Turnip v27.0.0-R1 | 0.1.8 | no frame in 3.5 minutes |

## Builds

The base application is `0100A77018EA0000`, CNMT version `0`, display version **1.0.0**. The module build IDs are:

| Module | Build ID |
|---|---|
| `rtld` | `567cdd4c5251494c5cd70822e3ccc740` |
| `main` | `221c19c45e821e7ce155f734e979a800` |
| `subsdk0` | `d0f13015e5badf2243d60a2bc9b978dd` |
| `sdk` | `3644a6760469451cabc99f00c540acc2` |

The SDK module identifies Nintendo SDK 13.3.5. The memory keys in `game.tsv` are the survey's guess, not measured.

## Shaders

`Data/globalgamemanagers` and the extensionless UnityFS shader bundles report Unity 2020.3.32f1. For example, `Data/StreamingAssets/AB/2d/shader` holds a serialized file with two Shader objects (class 48) of type hash `a30e3dcf6c6d25153d6f64e5e41caf4e`, the Unity 2020.3 layout `unityfs.py` already reads; no new reader or title-specific decoder is needed.

The reader consumed all 131 Shader objects in the 2D, character and effects shader bundles to their declared ends (2, 42 and 87 objects). Those three bundles hold 2,225 serialized subprogram records before deduplication; this is not a runtime coverage count.

The full base-game split found **35,020 unique shader keys**: 17,479 vertex, 17,483 pixel, 2 geometry and 56 compute. Of these, 35,009 first appeared in `Data/` and 11 in `main`. The split recorded 17,482 vertex/pixel pairs and found shaders in 1,990 RomFS files, listed in `shader_files.tsv`.

## Open

- **The host fix.** Pausing a guest thread blocked in a native wait (here `oe::PopNotificationMessage` on an empty queue): the pause must hand the collector a valid guest context (registers and stack), consume or invent no notification, and keep the thread out of game code until it is resumed. It is a change for every game and comes as its own pull request with a host test; this profile will name it with "Depends on: #<n>" and stays a draft until it lands.
- **No run on `dev`.** Every run above is on the main of 2026-11-21 or on 0.1.8. None on Turnip 27.0.0-R2 or on Qualcomm's driver.
- **Nothing past startup.** No title screen, New Game or play; so no frame-rate method, no frame times or game speed, no memory peak, and no `.keys` files.
- **Fast Prepare not checked against `dev`.** The tables and `shader_files.tsv` come from the full split of 2026-11-21. A full split with `dev`'s `shader_split.py` and `DM_SPLIT_FILES`, then a targeted split (`DM_SPLIT_TARGETED=1`) into a separate folder with the four tables compared, is still to do, once the game plays.
- **Only the base 1.0.0** is in the profile; no update was looked at.
