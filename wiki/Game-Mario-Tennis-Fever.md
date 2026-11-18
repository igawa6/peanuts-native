# Mario Tennis Fever (`0400D4201C6EA000`)

Experimental. On the tested phone a whole tournament (about 16 minutes) ran at 60 fps on a Turnip driver. Qualcomm's own driver is not recommended for this game: it stalls the GPU for about a quarter of a second at times and shows dark patches on the player intros. The first Prepare is long.

## At a glance

| | |
|---|---|
| Status | Experimental |
| Version | update 327680 (1.1.1) |
| Frame rate | 60 fps median on Turnip (Mainline v32) on an Odin 3 (Adreno 830), 56.6 at the lowest, over about 16.5 minutes |
| Driver | Turnip. Qualcomm V@0800.72 had kernel GPU hangs of about 250 ms and one lost device after four minutes |
| First Prepare | Long: the game has 35,045 shaders. Keep the phone charged and let it finish |

## What to expect

- **First Prepare:** the game's archive is unpacked by native code on the phone and the shader split then takes minutes (one note says about an hour on one core; not measured on your phone), and the translation of 35,045 shaders takes a while on top of that. See [First start and Prepare](First-Start-and-Prepare).
- **Match start:** one frame of 240-275 ms as a match loads, on both drivers.
- **Audio:** no underruns on either driver, but about one short skip a minute is heard (open).
- **Memory:** not measured yet on a phone, so there is no readiness requirement for it.

## Settings that matter for this game

The game has no settings of its own beyond the common ones ([Settings overview](Settings-Overview)). The one choice that matters is the **GPU driver**: pick Turnip ([GPU drivers](GPU-Drivers)).

## Known issues

- **Grass and cloud sea:** tessellated grass is not drawn (4 draws skipped at the match intro), so grass courts look flat compared with the console.
- **Qualcomm's driver:** GPU stalls and dark square patches on the player intros at match start.
- **Umbrella shadow:** the shadow of the court's umbrella is cut on both drivers (cosmetic).
- **Button icons:** drawn as "?" in earlier builds; the app now ships an icon font that fixes this on Turnip.
- Nothing past the first Adventure scenes and one Free Play match is described as checked; no other court, doubles or online modes.
