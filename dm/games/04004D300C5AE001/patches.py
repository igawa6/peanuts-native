# Kirby and the Forgotten Land - Nintendo Switch 2 Edition's own code, keyed by the NSO build ID. SDK cuts live in dm/tools/sdk_patches.py; see README.md, Memory.
CUTS = {
    # 1.1.0 (v65536) main: the game's allocators fill every block they hand out with 0x7fbfffff (a signaling NaN, so a float read before it is written shows) unless a
    # no-fill byte is set: byte 1 of the options word the allocator wrapper's constructor (main+0x86ce00) keeps at +8 (four allocate functions read it at +9), and a global
    # byte (main+0x2c10e41) for the main allocator (main+0x8638e0). Kirby passes 0x01010000, so it is 0 and the fill runs. nnMain (main+0x661ba0) takes blocks of 2.64 GB and
    # 2.83 GB from the first StandardAllocator, so the fill wrote 5.5 GB in the first second of the boot, every page of which then stays resident: on a 12 GB phone Android's
    # low-memory killer stops the game in most boots without the cuts (5.7 to 7.4 GB resident; pages sampled through /proc/<pid>/mem held nothing but the pattern). Platform workaround: each
    # fill check branches as the set byte does (cbnz → b to the same instruction after the fill). Nothing else changes: the blocks are fresh memory the host gives as zeros
    '395fc326c2e5b1651d937074f8cfd75d': [
        (0x863910, 'a8050035', '2d000014', 'main allocator: no NaN fill of a new block (cbnz w8 on the global no-fill byte → b, the same target)'),
        (0x869604, '28050035', '29000014', 'allocator (main+0x869400 kind): no NaN fill of a new block (cbnz w8 on the no-fill byte at +9 → b, the same target)'),
        (0x8698c4, '28050035', '29000014', 'allocator (main+0x869400 kind): no NaN fill of a new block (cbnz w8 on the no-fill byte at +9 → b, the same target)'),
        (0x869e24, '28050035', '29000014', 'allocator (main+0x869400 kind): no NaN fill of a new block (cbnz w8 on the no-fill byte at +9 → b, the same target)'),
        (0x86d018, '68050035', '2b000014', 'StandardAllocator wrapper (main+0x86ce00): no NaN fill of a new block (cbnz w8 on the no-fill byte at +9 → b, the same target)'),
    ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': "the allocators' NaN fill of new blocks: off", 'now': 'build', 'goal': 'game', 'purpose': 'memory',
     'checked': "the five fill sites (movn w9, #0x8040, lsl #16 in main) and their no-fill checks, 2026-11-14",
     'why': "HAL's allocator wrappers; a function-matched rule could carry it over the game's updates and to program 0 if its allocators fill as well",
     'blocked': 'one build of program 1; program 0 not yet checked for the same fill'},
]
