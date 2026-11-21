# DRAGON BALL: Sparking! ZERO (010035F022078000): 60 fps patch for base 1.0.0 (v0), keyed by NSO build ID.
CUTS = {
  # v0 1.0.0 main: bypass UEngine::bUseFixedFrameRate (bit 6 of [GEngine+0x9c4]) in UEngine::UpdateTimeAndHandleMaxTickRate
  # and in all 6 Spike Chunsoft fixed-frame-rate checks so both engine tick and battle timing take the 60.0f (1/60s) path.
  '764daf48e0d3500e369c1baffa5159d6': [
    (0x4094f30, '68003037', '1f2003d5', 'UpdateTimeAndHandleMaxTickRate: skip fixed frame rate clamp 1 (tbnz w8, #6 -> nop)'),
    (0x4094fb0, 'c8003037', '1f2003d5', 'UpdateTimeAndHandleMaxTickRate: skip fixed frame rate clamp 2 (tbnz w8, #6 -> nop)'),
    (0x4095190, 'e8003036', '07000014', 'UpdateTimeAndHandleMaxTickRate: skip fixed frame rate clamp 3 (tbz w8, #6 -> b +0x1c)'),
    (0x4095248, '48013036', '0a000014', 'UpdateTimeAndHandleMaxTickRate: skip fixed frame rate clamp 4 (tbz w8, #6 -> b +0x28)'),
    (0x4095278, 'a8003037', '1f2003d5', 'UpdateTimeAndHandleMaxTickRate: skip fixed frame rate clamp 5 (tbnz w8, #6 -> nop)'),
    (0x17dbc80, '89003037', '1f2003d5', 'Battle step 1: use 60.0f instead of FixedFrameRate (tbnz w9, #6 -> nop)'),
    (0x17dcaf0, '89003037', '1f2003d5', 'GetTargetFrameRate: return 60.0f instead of FixedFrameRate (tbnz w9, #6 -> nop)'),
    (0x17ea0b0, '89003037', '1f2003d5', 'Battle step 2: use 60.0f instead of FixedFrameRate (tbnz w9, #6 -> nop)'),
    (0x1817390, '89003037', '1f2003d5', 'Frame ratio scale: use 60.0f instead of FixedFrameRate (tbnz w9, #6 -> nop)'),
    (0x196bf38, 'a9003037', '1f2003d5', '60fps mode check 1: treat bUseFixedFrameRate as false (tbnz w9, #6 -> nop)'),
    (0x3b34398, '3f011a72', '3f001f6b', '60fps mode check 2: treat bUseFixedFrameRate as false (tst w9, #0x40 -> cmp wzr, wzr)'),
  ],
}

BINDING = [
    {
        'what': '60 fps: bypass UEngine::bUseFixedFrameRate (bit 6 of [GEngine+0x9c4]) in engine tick and battle step',
        'now': 'build',
        'goal': 'unreviewed',
        'purpose': 'frame rate',
        'checked': 'v0 main (764daf48e0d3500e369c1baffa5159d6): all 11 bit-6 checks on [GEngine+0x9c4] verified on Android (60 fps at 1.00x battle clock speed)',
        'why': 'DefaultEngine.ini enables bUseFixedFrameRate=True with FixedFrameRate=30.0; bypassing all 11 bit-6 checks routes both UpdateTimeAndHandleMaxTickRate and the 60 Hz battle step onto the native 60 fps path',
    },
]
