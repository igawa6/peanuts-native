# Settings: Advanced

**Settings > Advanced** lists the switches the game host offers, each with a plain name and a line that says when to try it. The same rows appear on a game's page, under **Advanced switches** in its Advanced section, for that game only; the page opens in place of the game page. Every row starts at **Auto**: nothing is written and the app or the game's profile decides. Pick another value only when the row's "Try this if" line matches what you see. A change applies at the next start.

<!-- TODO screenshot: Settings > Advanced with the search box and the area sections -->

## How the page is laid out

Each row has its name, a line "Try this if ...", a line "Auto: ..." that says what happens when you set nothing, and a row of chips: **Auto**, then the values. One tap sets one. A number with a few useful values is also chips; the other numbers open a small box.

- **Search** at the top finds a row by its name, its "Try this if" line or its `DM_` name.
- **Experimental** adds the rows that are not measured on every game, with a warning: they may look wrong or crash.
- **Developer** lists every other switch by area, with its `DM_` name and what it does, and the rows a Settings control already sets. These are for bug hunting; set one when a maintainer asks.
- **Reset all** undoes every row you set. **Back** returns to Settings or to the game's page.
- A line in `env.txt` or in a game's **Extra environment** wins over this page.

Rows you set are written into the log of the next run (`[adv]` lines), so a shared log shows what you changed.

## Rows you may want

### Graphics quality
- **What it does:** the graphics quality of a game with a render configuration (Fire Emblem: Fortune's Weave): Performance or Quality.
- **When to toggle:** Quality when the game looks too plain, Performance when it is too slow.
- **Default:** Auto, which is Performance.

### Render size
- **What it does:** the size the game draws at, as a share of the output its upscaler takes it to: 33, 50, 59, 67 or 100 %.
- **When to toggle:** raise it when the picture is too soft, lower it when the game is too slow.
- **Default:** Auto, which is 50 %.

### Mesh prepass workaround
- **What it does:** draws some depth-only model passes another way, a workaround for spikes and flicker on models with Qualcomm's driver.
- **When to toggle:** when the picture shows spikes or flicker on models.
- **Default:** Auto: on with Qualcomm's driver when its mesh shaders are on (`DM_VK_MESH=1`), off otherwise. Qualcomm's driver draws mesh shaders through compute by default, where this workaround is not needed.

## Rows for memory and speed

These are in the Advanced group, collapsed. None is measured on every game.

| Row | Try this if | Default |
|---|---|---|
| **Memory pressure guard** | textures reload and stutter although the phone has free memory (Off gives the older behaviour) | On |
| **Texture cache limit** | the game is killed for memory (lower it) or textures keep reloading (raise it) | measured from free memory |
| **Soft textures** | the game runs out of memory; large textures lose their finest level | the game's profile |
| **Soft texture levels** | the game still runs out of memory with Soft textures on | 1 level |
| **Frame generation floor** | generated frames look bad when the game runs slowly | 30 fps |
| **Skipped-work notice** | the notice "Part of the picture is not drawn" appears and you want it off (0) or only for larger gaps | 5 % |

Rows that a launcher control sets (**Frame rate cap**, **Instanced draws**, **Classic render passes**, **Background pipelines**, **RAM saver**, **Motion sensitivity**, **Full vegetation**, **Game language**, **GPU timing**, **GPU driver**, **Rumble**) are described on [Settings: Performance](Settings-Performance), [Settings: Game](Settings-Game) and [Settings: Controls](Settings-Controls).

## Limits

- The list grows with each release; a row you set stays until you reset it, even when a later version makes it the default.
- Wrong values in a developer row can stop a game from starting. Reset all clears them.
