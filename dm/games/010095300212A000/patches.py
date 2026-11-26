# PEANUTS: Resident Evil Revelations 2's own code, keyed by the NSO build ID (a Peanuts Native profile; Banana Native has none for this title).
CUTS = {
    # v131072: SDK 1.6.0 links the IR sensor library into main (no nn::irsensor symbol in main or sdk), so the host's irsensor hooks (hle_offline.c) never
    # see it. The game's controller setup (main+0xd13464, once per controller) calls its IR camera wrapper main+0xe34a38, which, while the camera's state
    # word (+8) is 0, asks GetIrCameraHandle (main+0xe3c3b8 → +0xe3c800) for the controller's handle and sets the state to 6. GetIrCameraHandle connects to
    # the irs service itself (main+0xe3e374 → ConnectToHipcService "irs"), which the host does not serve, and then calls through the session object it never
    # got: CRASH sig=11 addr=0 at main+0xe3c828, right after "[RED] sf: the game connects to service irs itself". The wrapper's state check becomes a
    # branch past the call, so the camera stays in state 0 (never set up), as a controller without an IR camera.
    'd69bca484713baa11285cf91df0c0368': [
        (0xe34a4c, 'c8000035', '06000014', 'IR camera wrapper: cbnz w8 (already set up) → b: never ask the irs service for a camera handle'),
        # The title step main+0x820ad8 (state 1 of main+0x820a78) waits for the title GUI (+0x18) to stop playing (main+0x6d93d0).
        # The title GUI's own steps (+0x2bc, main+0x6d58f8): 3 waits for the first A, 4 and 5 are the press-any-button menu (cursor, confirm at
        # main+0x6d5bdc), 6 and on come after it was confirmed. On the host the GUI never stops playing after that, so the screen stayed black. The wait
        # is now "until the menu was confirmed" (step >= 6) instead of "until the GUI stops": title, A, menu, A, then the save confirmation.
        (0x820adc, '3de2fa97', '09bc42b9', 'title state 1: bl is-playing → ldr w9, [x0, #0x2bc] (title GUI step)'),
        (0x820ae0, '604a0037', '3f150071', 'title state 1: tbnz w0 → cmp w9, #5'),
        (0x820ae4, '680e40f9', '4d4a0054', 'title state 1: ldr x8, [x19, #0x18] → b.le (menu not confirmed yet: wait)'),
        (0x820ae8, '08914d39', '08904d39', 'title state 1: ldrb w8, [x8, #0x364] → ldrb w8, [x0, #0x364] (x0 still holds the title GUI)'),
        # After the language screen (title step main+0x820a78 states 4/5) step 6 shows message 0x3a/0xca as a type-9 dialog and
        # step 7 waits for it to close. A type-9 dialog closes itself (main+0x614d84) once a global counter (+0x1bb0, set by main+0x6c5c60) reaches its
        # ticket (1); on the host nothing ever calls main+0x6c5c60 (a probe on it never hit on an AYN Thor), so it never closes and the game stays on that message. Step 12 of the same
        # state machine already force-closes its timed dialog the same way (+0x33e/+0x3d7). main+0x614d84 is every dialog's per-frame update (vtable +0x48),
        # so the check is now "type (+0x340) is 9" in place of "the counter reached the ticket": type-9 dialogs close as shown, the others (yes/no: Raid
        # mode) never by this path, as on the host before (the counter stays 0).
        (0x614da4, 'e9c00090', '684243b9', 'dialog update: adrp x9 → ldr w8, [x19, #0x340] (dialog type)'),
        (0x614da8, '682e46b9', '1f250071', 'dialog update: ldr w8, [x19, #0x62c] → cmp w8, #9'),
        (0x614dac, '29b542f9', '01010054', 'dialog update: ldr x9 → b.ne (not type 9: leave it open)'),
        (0x614db0, '290140f9', '1f2003d5', 'dialog update: ldr x9 → nop'),
        (0x614db4, '29b15bb9', '1f2003d5', 'dialog update: ldr w9, [x9, #0x1bb0] → nop'),
        (0x614db8, '1f01096b', '1f2003d5', 'dialog update: cmp w8, w9 → nop'),
        (0x614dbc, '88000054', '1f2003d5', 'dialog update: b.hi → nop'),
    ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md)
BINDING = [
    {'what': 'Resident Evil Revelations 2: the IR camera wrapper never sets a camera up', 'now': 'build', 'goal': 'host', 'purpose': 'runtime',
     'checked': 'main d69bca484713baa11285cf91df0c0368 (v131072): the wrapper, GetIrCameraHandle and the irs service getter disassembled, 2026-11-25',
     'why': "an old SDK's IR sensor library linked into main has no symbol to hook; an irs service on the host answering as a console with no IR camera would cover every such game",
     'blocked': 'the irs service hands out a shared memory handle (nn::os::AttachSharedMemory, MapSharedMemory), which the host does not make yet'},
    {'what': 'Resident Evil Revelations 2: the title step waits for the title menu to be confirmed, not for the title GUI to stop playing', 'now': 'build', 'goal': 'unreviewed', 'purpose': 'runtime',
     'checked': 'main d69bca484713baa11285cf91df0c0368 (v131072): the title state machine main+0x820a78, the title GUI steps main+0x6d58f8 and the timeline engine main+0xa4c208 disassembled; probe runs on an AYN Thor, 2026-11-26',
     'why': 'the title GUI never stops playing on the host (its timeline keeps waiting on a variable, main+0xa4ec90 type 3); the cause in the host is not found yet, and a host fix would cover the other GUIs that wait the same way'},
    {'what': "Resident Evil Revelations 2: a type-9 dialog closes as soon as it shows instead of waiting for a ready counter nothing sets", 'now': 'build', 'goal': 'unreviewed', 'purpose': 'runtime',
     'checked': 'main d69bca484713baa11285cf91df0c0368 (v131072): the dialog update main+0x614d84 and the counter setter main+0x6c5c60 (callers main+0x785458, main+0x5c03b4, main+0x77d8a4) disassembled; a probe on main+0x6c5c60 never hit, 2026-11-26',
     'why': 'the counter is set by a manager (main+0x785320) whose state the host never brings to 3 or 4; which service it waits on is not known yet'},
]
