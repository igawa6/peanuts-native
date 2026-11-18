# Experimental games

Experimental games start, but they are little tested and have known problems; some are not playable yet. Reports and help are welcome.
Back to the [guides](README.md).

## The Legend of Zelda: Ocarina of Time (`0400486024DEC000`)

- **Driver:** the phone's own Qualcomm driver (System) or Turnip ([GPU drivers](gpu-drivers.md)). On Qualcomm's driver the game page recommends
  the system driver, the Performance preset and Full vegetation off: tap **Recommended** at the top of its ⋯ page.
- **Version:** only version 2.0 is tested. Other versions get a warning, and you can still try them.
- **State:** plays. The foliage is drawn on every driver.
- **Known problems:** Kokiri Forest runs at about 26-30 fps on Qualcomm's driver (indoors about 50); Castle Town on Turnip is slow on its first visits.
- **Slow first starts:** the GPU driver builds its pipelines the first time it sees each shader. The first start can sit on the loading
  screen for minutes, and the first minutes of play (especially right after the main menu) can run at 1-20 fps with black frames while
  it catches up. Let it run, or restart the game: each start is faster, and once the cache is full it runs at 30-60 fps. A hot phone
  makes this slower. Changing the GPU driver starts the cache again for that driver.
- Details for contributors: [its game note](../../dm/games/0400486024DEC000/README.md).

## Mario Tennis Fever (`0400D4201C6EA000`)

- **State:** plays. On the author's phone it ran a whole tournament (about 16 minutes) at 60 fps on a Turnip driver.
- **First Prepare:** long. The game has 35,045 shaders; the archive unpack runs as native code on the phone, so the shader split takes minutes, but the translation still takes a while. Keep the phone charged and let it finish.
- **Known problems:** on Qualcomm's own driver the GPU stalls for about a quarter of a second at times and the player intros show dark patches. A Turnip driver is the way to go ([GPU drivers](gpu-drivers.md)).
- Details for contributors: [its game note](../../dm/games/0400D4201C6EA000/README.md).

## Super Mario Bros. Wonder - Nintendo Switch 2 Edition (`040015100B514001`)

- **State:** new. It reached a first course at 30 fps on one phone with a Turnip driver (about 9.5 minutes of play). The system driver and other phones are not tested.
- **Your dump:** the Switch 2 Edition is program 1 of the game. Prepare needs a dump folder that holds it (`updates/<version>/program/1/`); a `.nup` holds the original program 0 only, so it cannot give you the edition yet. The game's page switches between the two editions; both share one save.
- Details for contributors: [its game note](../../dm/games/040015100B514001/README.md).

## Splatoon Raiders (`0400380020056000`)

- **State:** not playable yet. It boots and runs, but at a very low frame rate. It ships so that people can test it and help.
- **Known problems:** the frame rate; little tested beyond that. A white scene was seen on the system driver.
- Details for contributors: [its game note](../../dm/games/0400380020056000/README.md).

## How to help

- **Play and report.** Open an issue ([Troubleshooting](troubleshooting.md#opening-an-issue)) with the log (**Share
  log**), your phone model and the GPU driver you used, and where in the game it happened.
- **Reference screenshots.** A screenshot from the console of the same place, next to one from the phone, shows what
  is wrong in the picture.
- **Try the other driver** when something looks wrong, and say whether it changes.
- **Code.** Pull requests are welcome. Each game's note says what is known and what is open; the
  [games table](../../dm/games/README.md#games) lists them all, and [docs/agents](../agents/README.md) explains how to
  debug a game. Issues marked
  [help wanted](https://git.eden-emu.dev/moceure/BananaNative/issues?state=open&labels=help+wanted) are a
  good start.
