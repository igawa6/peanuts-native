# PEANUTS: new file (Ys X: Nordics' 60 fps variant: the frame step without its floor)
"""Ys X: Nordics' rewriter patches (dm/tools/patch_nso.py). The game's own code needs no cut: only its 60 fps variant has a row.

VARIANT_CUTS['60']: fdk::Framework's frame step (main+0x8678b0) takes the time since the last step and clamps it to [1 / Framework+0x34, 1 / Framework+0x30] (the
target rate, which the profile's fpslocker row sets to the cap, and the least rate, 15). At 60 fps the game's thread on the phone often starts a step a few ms
after a long one (a device probe of the engine's step: 4-7 ms after 30-35 ms while walking on the deck); the floor counts each such short step as 16.7 ms, so the game ran
3-17 % faster than real time while walking at 60, and the intro cutscene's shots were ~8 % shorter. At 30 the floor is 33.3 ms and the steps are 32.6-34.3 ms:
game time 1.001 of real (README.md "Frame rate"). The row drops the floor (the fcsel that takes the larger of the step and 1 / the target rate becomes a plain move), so the step is
the measured time, at most 1 / 15 s: game time = real time at any frame rate, as the PC version's variable frame rate. game.tsv `variant 60 main 60fps` rewrites main
a second time with it into <pack>/60/ (scripts/pack_android.py rewrite_variants); the launcher's frame limit of 60 or 40 starts it (Profile.fpsVariant), 30 runs the game's own code."""
_MAIN = 'c55f6b8aefa54324c8e3debe0912c57c'   # 1.0.3 (v196608)

VARIANT_CUTS = {'60': {_MAIN: [
    (0x8679a0, '414c201e', '0140201e', 'fdk::Framework frame step: fcsel s1, s2, s0, mi (the step at least 1 / Framework+0x34) → fmov s1, s0 (the measured step, at most 1 / Framework+0x30)'),
]}}

BINDING = [
    {'what': "Ys X: Nordics' 60 fps variant: fdk::Framework frame step without the 1 / target-rate floor (VARIANT_CUTS['60'])", 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'checked': 'main 1.0.3 disassembly (main+0x8678b0..0x8679bc) and a device probe of the engine step at 30 and 60 fps, 2026-11-26',
     'why': 'the frame step is fdk engine code; a function-matched rule could carry it to other builds of the game',
     'blocked': 'only main 1.0.3 is at hand'},
]
