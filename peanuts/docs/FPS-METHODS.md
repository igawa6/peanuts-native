# FPS methods catalogue (Peanuts / Banana Native)

Every game with FPS support uses one or more of these methods. For a new game, find the method that fits its engine and timing, copy the profile rows from the closest example, then measure. Keys are documented in `dm/games/README.md` (profile keys table). The mechanisms are explained in `docs/reference/frame-rate.md`.

Refresh the example list from the live profiles before choosing (it changes as games are added):

```sh
sh peanuts/scripts/fps-survey.sh
```

## How to pick (try in this order: cheapest and most general first)

1. **Survey the engine** (`survey.md`: engine, SDK, modules). Methods found *by name* work on any build of that engine, so check them first.
2. **Test the game's timing**: at caps of 30 and 60, does a 3-second walk land in the same place? If it does, the game steps by elapsed time (method A). If movement halves at 30, it counts frames (methods C–H).
3. **Exact build ID in FPSLocker Warehouse** → method D.
4. **Emulator FPS-mod archive or community patch for this build** → method E.
5. **Nothing published** → reverse-engineer the limiter (methods F, G, H), or settle for Game owned only (method J).

A game can combine methods: Demon Slayer 2 uses an Unreal limiter by name plus Warehouse rows, and Luigi's Mansion 3 uses display pacing plus memory rows.

## Methods

| | Method | When it fits | Profile rows | Examples (title ID) |
|---|---|---|---|---|
| A | **Display pacing, time-based game** | the game steps by elapsed time; it only asks for interval 2 | `present_interval display`, `fps_limit 60`, `fps_limit_max 120` | CTR `0100F9F00C696000`, Sandrock `0100B63016916000` |
| B | **Display pacing, marked experimental** | likely time-based but not proven in every mode | `fps_limit_present 1`, `fps_limit_max …` (default Game owned unless `fps_limit`) | Diablo III `01001B300B9BE000`, Tomodachi Life `010051F0207B2000`, Unicorn Overlord `010069401ADB8000`, Dave the Diver `010097F018538000`, Pokopia `0400A3A01EE4C000`, Yoshi's Crafted World `01006000040C2000` (UE 4.18; `fps_modes 20,30,45,60`, walk test 2026-11-22) , Star Ocean The Second Story R `010065301A2E0000` (Unity 2021.3.22f1; `fps_modes 20,30,60`, walk test 2026-11-22; Warehouse "no patch") |
| C | **Engine limiter the host writes, found by name** | Unreal 4.2x/5 (`t.MaxFPS`, `rhi/nvn.SyncInterval`), Unity integer target rate, Frostbite simulation rate, Grezzo governor (`hle_fpslock.c`) | `fps_target <n>` (+ `present_interval display` where the note says so) | MH Rise `0100B04011742000`, Link's Awakening `01006BB00C6F0000`, Echoes of Wisdom `01008CF01BAAC000`, Demon Slayer 2 `0100AD80208A8000` (UE5) |
| D | **FPSLocker Warehouse memory rows** | the exact main build is in the Warehouse | `fpslocker <build> <kind> <path> <copies> <factors>`, `fps_limit`, `fps_limit_max` | BotW `01007EF00011E000`, XCX `0100453019AA8000`, P5R `01005CA01580E000`, FE3H `010055D009F78000`, Kirby FL `01004D300C5AE000`, DQ3 `01003E601E324000`, Engage `0100A6301214E000`, Kakarot `010051C0134F8000`, Hogwarts Legacy `0100F7E00C70E000` (UE 4.27: `t.MaxFPS`/`FrameTimeBudget`/`r.VSync` pointer rows, `fps_limit_present 1`, max 60) |
| E | **Community timing plans** (code words + memory, checked before sealing) | an emulator 60 fps mod or community patch exists for this build | `fps_plan` / `fps_memory` (expected word checked), optional `fps_plan_120` for a static 120 plan | Pokémon BDSP `0100000011D90000`/`010018E011D92000`, Arceus `01001F5010DFA000`, Scarlet/Violet `0100A3D008C5C000`/`01008F6008C5E000`, Z-A `0100F43008C44000`, Wonder `010015100B514000`, Skyward Sword `01002DA013484000`, Dread `010093801237C000` |
| F | **Rewriter frame step** (`FRAME_STEP` in `patches.py`) | the game counts frames and has no published patch; RE finds the frame function | `fps_target`, `fps_hold on/off`; `fps_step_elapsed 1` plus an elapsed clock for caps above 60 | TotK `0100F2C0115B6000`, ASTRAL CHAIN `01007300020FA000` (elapsed), Age of Calamity `01002B00111A2000` (hold off; `fps_modes 30,60`), Paper Mario TTYD `0100ECD018EBE000`, Ocarina of Time `0400486024DEC000`; combined with D in BotW, with E in Dread |
| G | **sead dynamic interval, decoupled** | Nintendo sead framework that steps by 60/interval; found by code shape in any build | `fps_decouple sead` | DK Bananza `040074A01BF12000`, Splatoon Raiders `0400380020056000` |
| H | **Fixed steps per frame** | the framework runs N fixed steps per frame from a mode field | `fps_steps <build> <global> <members> <mode> <count>` | Mario Kart World `0400C3F00006E000` |
| J | **Game owned only** | the timestep is baked in and nothing above works safely | no rows; record the evidence in the note | MK8D 4.0.1 (1/60 literal at 17 sites, no Warehouse entry) |

## Traps already met

- A frame limit on a frame-counting game without a frame step runs it **slow** (frame-rate.md, "A frame limit on a game with a frame step ran it slow").
- A present cap does nothing without a frame step or an engine limit the host writes (frame-rate.md, "A present cap applies only…").
- Movies hold 30 fps under a frame step. That is expected, not a bug.
- `fps_limit_min 30` is for when timing fixes don't hold up below 30 rendered fps.
- Warehouse and community rows are **exact build only**. A different build ID needs new rows or a by-name method.
- On the Thor, the panel stayed at 60 Hz, so 90 and 120 could not be shown. Report them as unverified.
- A rewriter frame step (method F) takes `DM_FPS_TARGET` 0, 30 or 60 only (`loader.c`: `[RED] DM_FPS_TARGET=20: must be 0, 30 or 60`). A profile with `fps_target` but no `fps_limit` gets the launcher's `FPS_CAPS` {own, 60, 30, 20, 15}, and 20 / 15 silently run the game's own rate. Add `fps_limit 60` + `fps_modes 30,60` (Link's Awakening, Age of Calamity 2026-11-21). Paper Mario TTYD (`0100ECD018EBE000`) still has the gap.
- Measuring a method-F game's speed without a walk test: read the stub's vsync counter on the frame-step state page (`main base` from the log + `# FPS_STATE_OFF` in the pack's `main.fixups.tsv`; u64 at +0x30, vsyncs per update at +0x28) with the live control line `memdump <addr> 64 <file>` twice, 20 s apart: speed = (delta / 60) / wall seconds. Hyrule Warriors: Age of Calamity measured 0.99 at 60 and 1.00 at 30 this way.

- A method-C Unreal limiter by name is not guaranteed on UE 4.18: Yoshi's Crafted World 1.0.1 has `t.MaxFPS` and `nvn.SyncInterval` in `main` (UTF-32), but `ue_cvar.py` reports both "not registered" because Good-Feel's registration shape differs from DRAGON QUEST XI S. Check the rewrite log's `Unreal console variables` line before planning on `fps_target`; a time-based UE game can still take method B.

- Method C fails on an Unreal build whose `TCHAR` is 8-bit: Hogwarts Legacy 1.0.5 (UE 4.27.2) keeps its console variable names in ASCII, `ue_cvar.py` finds none (no `UE_CVAR` rows in `main.fixups.tsv`), so `fps_target` does nothing. Its exact build was in the Warehouse, so method D's pointer rows (`target_f32`, `frame_ms_f32`, `zero_i32`, each `<ptr>/0x0` with 2 copies) did the job (2026-11-22).

## Learning loop (do this after every game)

After M6, add what you learned to this file:
1. Add the title to the Examples cell of the method it used.
2. If the method was **new** (a new engine limiter found by name, a new `fpslocker` kind, a new framework pattern), add a row: when it fits, the rows, the example, and a link to the commit and the game note. If the mechanism holds for every game of that engine, say so; that is what makes it reusable.
3. If a method **failed** on a game where it looked like it should fit, add a line to *Traps already met*.
4. Engine-wide code goes in the core (`hle_fpslock.c`, the rewriter's by-name finders), not in one game's `patches.py`, so the next game on that engine needs only a profile row. Check this with the owner before you change shared code.

- A Unity 2021.3 game can boot-crash or hang from the profile guess, not the timing: Star Ocean The Second Story R with the survey's alias (0x118000000) ran out of alias address space ~35 s in (black screen, or `CRASH addr=0x3fffe0`). Test FPS only after the memory keys hold; `DM_ALIAS_SIZE=<bytes>` tries a bigger alias without a rebuild (Sandrock's 0x1d8000000 fixed it).
