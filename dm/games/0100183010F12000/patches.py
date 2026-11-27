# JUMP FORCE - Deluxe Edition (0100183010F12000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a
# module's NSO build ID; CUTS as described there ("Registered patches"). Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py
CUTS = {
  # JUMP FORCE 1.08 (v458752) main. PEANUTS, a workaround: the game's online client ("CSprdJ5Client", set up from main+0x28421a8 in main+0x27b4420) is the object behind the global
  # main+0x787f748; startup code right after it (main+0x28423b4 -> the constructor main+0x278ea10) reads that global and calls through its +0x11b0 without a null check. On the
  # tablet the client was never made: main+0x27b4420 stops at the first negative result, from the init of its online components (main+0x27b66b0 runs each one's slot 0x10, list at
  # +0x4c0) or from main+0x2848800 in main+0x2848710, which then makes the client (0x6a08 bytes) and stores it in the global; the first run crashed with SIGSEGV at main+0x278eab0
  # (x0 = 0) before the first frame. Which component fails is open (decided at run time; the Mac's DM_PROBE would show it). Online play is not used, so the two bail-outs go and the
  # client is made whatever the components answered
  '1667568420912c733119f8ed92a52794': [
    (0x27b4678, '0806f837', '1f2003d5', "online client setup: tbnz w8, #31, main+0x27b4738 (the components' init failed: no client) -> nop"),
    (0x2848740, '8802f837', '1f2003d5', 'online client creation: tbnz w8, #31, main+0x2848790 (its init step failed: no client) -> nop'),
  ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md)
BINDING = [
    {'what': 'online client made although its components report an error', 'now': 'build', 'goal': 'host', 'purpose': 'runtime',
     'why': "the game's startup uses the online client without a null check; which online component's init fails on the host is not known yet",
     'blocked': 'needs the failing component (a probe on the Mac, or its host service found) so the host can answer as a console does'},
]
