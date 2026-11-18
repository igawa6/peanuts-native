# Super Mario Bros. Wonder - Nintendo Switch 2 Edition (`040015100B514001`)

Experimental. It reached a first course at 30 fps with a Turnip driver on one phone (about 9.5 minutes of play). The system driver and other phones have not been tested. The Switch 2 Edition is a separate program of the game, so your dump folder must contain it.

## At a glance

| | |
|---|---|
| Status | Experimental |
| Version | update 327680 (1.2.1), program 1 |
| Frame rate | 30 fps on one AYN Thor (Adreno 740) with Turnip; not measured elsewhere |
| Driver | Turnip tested; system driver not tested |
| Memory | The game asks for a 4.9 GB heap; the notes give no phone memory figure yet |

## Your dump

The Switch 2 Edition is program 1 of the game. Prepare needs a dump folder that holds it (`updates/<version>/program/1/`). A `.nup` holds the original program 0 only, so it cannot give you the edition yet. The game's page in the app switches between the original game and the edition, and both share one save (as on the console). The original Super Mario Bros. Wonder has its own separate profile. See [First start and Prepare](First-Start-and-Prepare).

## What to expect

- The game reads three trees: its own files, the original game's files and an add-on that holds the edition's content, so a dump missing any of them can fail to boot.
- One pipeline is built during the first frames (85.5 ms) because the prewarm had not reached it yet.
- Settings: no game-specific settings; see [Settings overview](Settings-Overview).

## Known issues

- Not tested on the system driver or on other phones.
- Some movie fixes (the "playing while online" movie after New Game stuttered because audio was starved) have been made and were not yet checked on a phone.
- Switching frame generation on and off during a run had hung the GPU in an earlier build; the notes say the fix is not yet checked on a phone.
