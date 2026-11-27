# PEANUTS: FANTASY LIFE i 1.5.0 and 2.2.0, Switch main. The host deliberately does not load the EOS SDK.
CUTS = {
    '74bcac96835db9aeeb29bce78be410ea': [
        (0x5a9f064, 'c80000b4', '1f2003d5', 'offline: skip EOS_UI_AddNotifyDisplaySettingsUpdated; retain delegate cleanup'),
        (0x5ad9288, '1a91ff97', '1f2003d5', 'offline: do not subscribe to events on the absent online identity interface'),
        # Both getters use the existing `mov x0, xzr; ret` at main+0x5a902a8. No platform or account exists offline.
        (0x5a9f190, 'ff0301d1', '46c4ff17', 'offline: return no EOS platform handle'),
        (0x5a9f680, 'ff4301d1', '0ac3ff17', 'offline: return no Epic account ID'),
        (0x5b3bca0, '00feff34', '1f2003d5', 'avatar name: dictionary version zero is normal offline; still run the mask and region checks'),
    ],
    # PEANUTS: #800. 2.2.0: the same five sites, located by masked instruction windows (this main has no unwind entries for these functions, so nso_fnmap.py cannot map them)
    # and checked by disassembly. Both getters now branch to the `mov x0, xzr; ret` at main+0x5e0cba0, the same shape as 1.5.0's stub.
    '6e4502b822cb120486285ce14f16e490': [
        (0x5e1c984, 'c80000b4', '1f2003d5', 'offline: skip EOS_UI_AddNotifyDisplaySettingsUpdated; retain delegate cleanup'),
        (0x5e5bb50, '347eff97', '1f2003d5', 'offline: do not subscribe to events on the absent online identity interface'),
        (0x5e1cab0, 'ff0301d1', '3cc0ff17', 'offline: return no EOS platform handle'),
        (0x5e1cfa0, 'ff4301d1', '00bfff17', 'offline: return no Epic account ID'),
        (0x5ec5710, '00feff34', '1f2003d5', 'avatar name: dictionary version zero is normal offline; still run the mask and region checks'),
    ],
}

BINDING = [
    {'what': 'offline startup and avatar naming', 'now': 'build', 'goal': 'game', 'purpose': 'runtime',
     'why': 'the game assumes an EOS interface and a nonzero name dictionary version; neither is available on the offline host'},
]
