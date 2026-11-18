# Getting higher FPS

A ladder of changes for a game that runs too slowly, cheapest first: each rung says what it costs. Work down it one step at a time and watch the frame rate in the [overlay](Settings-Performance#overlay) after each change. Not every game has every row; a row appears only when the game's profile has it.

Honest limits: the numbers below were measured on a few phones (mainly a Snapdragon 8 Elite Gen 5 class phone) and a few games. Where a rung has no measurement for a game it says "not measured yet". Results on your phone can differ; the fastest choice also depends on whether the CPU or the GPU is the limit.

## Before you start

- **Look at the Recommended card.** If the game's Performance tab has **Recommended for this phone**, choose **Recommended** first. It is made from phone measurements and keyed on what your GPU driver reports ([Settings overview](Settings-Overview#recommended-for-this-phone)).
- **The first minutes are slower.** The GPU driver builds its pipelines the first time it sees each shader. The first start can sit on the loading screen for minutes, and the first minutes of play can run at 1 to 20 fps with black frames while it catches up (reported for Ocarina of Time). Each start is faster; once the cache is full it runs faster. A hot phone makes this slower, and changing the GPU driver starts the cache again for that driver. Do not tune a game during this period.
- **Cool phone.** A warm phone clocks down. See [Heat and battery](Heat-and-Battery).

## The ladder

| Step | Change | What it costs | When it helps |
|---|---|---|---|
| 1 | **Frame rate cap** to 30 (or the game's lower choices) | the game looks less smooth | the game swings between rates; a steady 30 beats 25 to 40 |
| 2 | **Resolution** one step down (or the Performance preset) | a softer picture | the GPU is the limit |
| 3 | **Upscaler**: a cheaper one | sharpness | the game has a neural upscaler that the app replaces |
| 4 | **Dynamic resolution** (Donkey Kong Bananza) or a game's Graphics quality | sharpness in heavy scenes | heavy scenes drop frames |
| 5 | **Game options**: Full vegetation off, Hidden layers off (experimental) | less foliage or a few layers | the game has the option |
| 6 | **GPU driver**: try the other | a new shader cache to build, and it can behave differently | the game runs better on one driver |
| 7 | **Frame generation** | needs a `Lossless.dll` you own; costs GPU; the picture is one game frame behind | you want more displayed frames from a game that already holds its rate |
| 8 | **Advanced** rows | see below | testers |

Each step's "what to expect" is on [Settings: Performance](Settings-Performance).

### 1. Frame rate cap
No quality cost. A cap makes the game stop trying for more than you ask. For a game that cannot hold 60 it can give a steadier 30. A game that follows its frame rate runs at a normal speed when its rate drops (**Full game speed**, on by default). Do not use 40 or 45: they are not offered because games that step their logic by their frame time run at the wrong speed there.

### 2. Resolution
Measured in Ocarina of Time's Kokiri Forest on an Adreno 840 with Qualcomm's driver (Quality, below 45 °C at start): the GPU took 42.7 ms a frame (22 fps); **Performance** took 39.0 ms (24.4 fps); **Max performance** 37.2 ms (25.6 fps); **Balanced** changed nothing. The gain from the scene size was small in that spot because most of the GPU time went to per-draw and per-query cost, not to the pixels. Other games and drivers: **not measured yet** here.

### 3. Upscaler
Where it applies (Donkey Kong Bananza and games with the DLSS stand-in), the choices from fastest to slowest are Nearest neighbor and Bilinear, then SGSR 1 and FSR 1, then Native (the game's own network, "sharpest, slowest"). On Qualcomm's driver FSR 1 can come out too bright in some games. For Ocarina of Time on Adreno 840 (Kokiri Forest), **Native** took 48.7 ms a frame against 42.7 ms for the default, and **Bilinear** changed nothing.

### 4. Dynamic resolution
Donkey Kong Bananza only. The default range is 40 % to 100 %. A lower floor lets heavy scenes render smaller. The cost is a softer picture in those scenes. Its effect in frames per second is **not measured yet** here.

### 5. Game options
Ocarina of Time, Kokiri Forest, same test as step 2: **Full vegetation off** took 41.0 ms against 42.7 ms. **Hidden layers off** is experimental; its gain is **not measured yet**. The game's own switches (**Max resolution**, **Texture pool ×2**) cost GPU time and memory and are off. **Unlocked frame rate** does not raise fps ("Does not raise fps"). If walls vanish on Death Mountain Trail with Qualcomm's driver, **Keep cave walls (Qualcomm)** fixes it and costs GPU time on some phones.

### 6. GPU driver
The phone's own Qualcomm driver (**System**) or a custom driver such as **Turnip** ([GPU drivers](GPU-Drivers), how to install). What the project has seen:

| Game | System | Turnip |
|---|---|---|
| Donkey Kong Bananza (`040074A01BF12000`) | about 30 fps | about 30 fps |
| Pokémon Pokopia (`0400A3A01EE4C000`) | about 60 fps | about 60 fps |
| Mario Kart World (`0400C3F00006E000`) | not compared yet | not compared yet |
| Ocarina of Time (`0400486024DEC000`, experimental) | recommended on Qualcomm drivers from version 0x80320000 on; 1.5 to 2 times faster than Turnip in Kokiri Forest on an Adreno 840 | slower there (18 fps, GPU 53 ms, on one build); can still be the better choice where System crashes |
| Mario Tennis Fever (`0400D4201C6EA000`) | the GPU stalls for about a quarter of a second at times; dark patches in the player intros | the way to go: about 60 fps through a whole tournament (about 16 minutes, one phone) |
| Splatoon Raiders (`0400380020056000`, experimental) | a white scene was seen | try Turnip if you see it |

Source for the first three rows and the table's last rows: [Game guides](Games), `docs/guides/gpu-drivers.md`, `docs/guides/experimental-games.md`. If a driver's default does not hold, pick the other and say which you used when you report a problem ([Troubleshooting](Troubleshooting)).

A note for Ocarina of Time: on an older Qualcomm driver (an AYN Thor with an Adreno 740) the system driver crashed during a burst of pipeline compiles, so the recommendation applies only from driver version 0x80320000; older drivers get no driver recommendation and Turnip worked there.

### 7. Frame generation
Frame generation adds frames; it does not make the game itself faster. It needs the GPU to do extra work, so it helps most when the game already holds its own rate. **Motion estimation resolution** (25 to 100 %) is the speed dial; the default is 50 %. A phone's cost for it is **not measured yet** here. Mario Kart World's note records runs with it on.

### 8. Advanced rows
- **Background pipelines** (on): fewer freezes on a cold cache. In Donkey Kong Bananza's heaviest area with an empty cache the worst frame went from 8.1 s to 3.7 s (one measurement).
- **RAM saver**: for phones short of RAM; writes to storage.
- **Use fast cores**: not measured yet; off by default.
- **Instanced draws** and **Classic render passes**: Qualcomm-driver speed-ups, off by default. With either on, a tester lost the GPU on Adreno 830 with Turnip in 1 to 2 of 5 starts (open). Qualcomm's own driver, Ocarina of Time, Kokiri Forest: **no change** from Classic render passes, Instanced draws or Background pipelines.
- **GPU timing**: Per segment lets a game's dynamic resolution see the real GPU time.

## When it still does not run well

A game that is "GPU heavy" at 20 fps on one phone may simply be too much for it. [Games](Games) lists the state of each game, and the readiness check (Settings > Help) tells whether the phone can run your games at all ([Troubleshooting](Troubleshooting)). Open an issue with the log and the driver you used.

## Sources

`dm/games/0400486024DEC000/README.md` (Recommended settings on Qualcomm phones), `dm/games/0400486024DEC000/game.tsv` (recommend rows), `docs/guides/gpu-drivers.md`, `docs/guides/experimental-games.md`, `dm/games/040074A01BF12000/README.md`, `android/README.md`, `GameSettings.java`, `Prefs.java`.
