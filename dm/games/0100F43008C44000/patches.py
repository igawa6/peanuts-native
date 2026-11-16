# PEANUTS: a Peanuts Native file (Banana Native has no patches.py for this title); whole file Peanuts' own
# Pokémon Legends: Z-A (0100F43008C44000): the rewriter's patches of this game's own builds, keyed by the NSO build ID (dm/tools/patch_nso.py, "Registered patches").
# Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py.
CUTS = {
  # 2.0.0 (v262144) main: the boot scene's switch to the title races the title menu's start. When boot is done, the scene's poll at main+0x909970 (under the lock at this
  # +0x130: is the current scene at +0x110 the one asked for, and is its ready flag (scene +0x20 → +0x23a, main+0x1189650) set) lets the game switch to the title scene, which
  # then calls the boot-finished callback the title menu registers in the object at main+0x6109cc0 (+0xe0) when it starts. When the switch comes first, the call goes to an
  # empty slot and the game crashes at the title screen: about half of the boots on PC emulators (Eden), and possible wherever the threads are not Horizon's (core
  # affinity and priorities decide the order on the Switch). The poll now also answers "not ready" while that slot is 0, so the switch waits for the menu: the order every
  # clean boot has. The function is the same 28 words: the identity call (main+0x118962c is a bare ret) and the second load of +0x110 are dropped to make room for the check.
  # Taken from an IPS mod for this build (exefs 8A1C86C437394B698397A85F45CC776190DBA0F7.ips: one record at file offset 0x909a70, the text offset + the NSO's 0x100-byte header)
  '8a1c86c437394b698397a85f45cc7761': [
    (0x909970,
     'fd7bbda9f50b00f9f44f02a9fd03009113c00491f40300aaf50301aae00313aa8844a194808a40f9400100b424ff2194080040f9a90240f91f0109eba1000054'
     '808a40f927ff2194f403002a02000014f4031f2ae00313aa8244a19480020012f44f42a9f50b40f9fd7bc3a8c0035fd6',
     'fd7bbda9f50b00f9f44f02a9fd030091f40300aa13c00491350040f9e00313aa8844a194808a40f9f4031f2ac00000b4080040f91f0115eb6100005429ff2194'
     'f403002ae00313aa8644a1948002001208c0029008d146f91f0100f100109f1af44f42a9f50b40f9fd7bc3a8c0035fd6',
     'boot scene poll main+0x909970: also not ready while the title menu has not registered its callback (adrp x8, main+0x6109000; ldr x8, [x8, #0xda0]; cmp x8, #0; csel w0, w0, wzr, ne)'),
  ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': 'boot scene poll waits for the title menu callback', 'now': 'build', 'goal': 'game', 'purpose': 'race',
     'why': "the game's own scene poll; a function-matched rule could carry it to 2.0.2 and the base game, whose builds have not been checked for the race",
     'checked': 'the IPS bytes against main 2.0.0 (v262144) text, disassembled: the same function plus the slot check'},
]
