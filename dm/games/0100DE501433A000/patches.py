# Tetris Effect: Connected (0100DE501433A000): the rewriter's patches of this game's own builds, keyed by the NSO build ID (dm/tools/patch_nso.py, "Registered patches").
# Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py.
# PEANUTS: a Peanuts Native profile (Banana Native has none for this title).
CUTS = {
  # 2.0.2.2 (v1310720) main. The host never loads the Epic Online Services SDK (nro_collect.py ONLINE_SDKS; hle_ro.c refuses it), so Redpoint's EOS online subsystem
  # module ("RedpointEOS") has no platform. The function at main+0xb64b18 takes that module from the module manager (main+0xe93aa0, main+0xe94a9c), asks it for an
  # object through its vtable slot 0x58, builds a delegate (vtable main+0x65b5298, callback main+0xb64c94) and registers it through the object's slot 0x178 without
  # checking the object: with no EOS platform it is null, and `ldr x8, [x20]` at main+0xb64bec faults (SIGSEGV, address 0, about 5 s after nnMain on an AYN Thor).
  # The four instructions before that load are rewritten in place: adrp + add of the callback become one adr, the delegate's stores move up one slot, and the slot
  # freed holds cbz x20 to main+0xb64bfc, the function's own path after the registration (the delegate holder's clean-up). With no object nothing is registered.
  '8838095f4f659f63cb45a00e62dfb887': [
    (0xb64bdc, '08000090', 'c8050010', 'adrp x8 + add x8, #0xc94 (the delegate callback main+0xb64c94) → adr x8, #0xb64c94'),
    (0xb64be0, '08513291', 'e1030091', 'mov x1, sp, one slot earlier'),
    (0xb64be4, 'e1030091', 'a87e02a9', 'stp x8, xzr, [x21, #0x20] (the delegate callback), one slot earlier'),
    (0xb64be8, 'a87e02a9', 'b40000b4', 'RedpointEOS object null (no EOS platform): cbz x20 → main+0xb64bfc, past its delegate registration'),
  ],
}
BINDING = [
    {'what': "RedpointEOS without an EOS platform: a null object's delegate registration skipped", 'now': 'build', 'goal': 'engine', 'purpose': 'runtime',
     'why': "Redpoint's EOS online subsystem plugin for Unreal, which any game shipping it has: its registration function could be found by the plugin's L\"RedpointEOS\" name and a fingerprint"},
]
