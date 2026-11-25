# Stray (010075101EF84000): disable DefaultEngine.ini bUseFixedFrameRate=True so fps_target 60 unlocks 60 fps.
CUTS = {
  # v0 1.0.0 main: DefaultEngine.ini sets bUseFixedFrameRate=True and FixedFrameRate=30.0, which forces UEngine::UpdateTimeAndHandleMaxTickRate
  # onto its fixed-timestep path and ignores t.MaxFPS. Renaming the INI lookup key (bUseFixedFrameRate -> xUseFixedFrameRate) leaves
  # bUseFixedFrameRate at its false default so hle_fpslock.c's t.MaxFPS=60 and rhi.SyncInterval=1 govern frame pacing.
  '0dbe0a904fd985953f07063934ebe6a8': [
    (0x41c5bcc, '00625573', '00785573', 'rodata ASCII bUseFixedFrameRate -> xUseFixedFrameRate'),
  ],
}

BINDING = [
    {
        'what': 'rename bUseFixedFrameRate INI lookup key to xUseFixedFrameRate',
        'now': 'build',
        'goal': 'unreviewed',
        'purpose': 'frame rate',
        'why': 'DefaultEngine.ini sets bUseFixedFrameRate=True and FixedFrameRate=30.0, overriding t.MaxFPS; renaming the INI key keeps bUseFixedFrameRate false so hle_fpslock.c unlocks 60 fps',
    },
]
