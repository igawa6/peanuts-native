# Hyrule Warriors: Age of Calamity (01002B00111A2000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a module's NSO build ID;
# CUTS, OBS and FRAME_STEP as described there ("Registered patches"). Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py
from nso_asm import ADRP, B
# Hyrule Warriors: Age of Calamity steps its game a fixed amount per update, from two 30 fps values. A KIDS task (main+0x166d980, on a KIDSTaskScheduler thread, once per update)
# waits until 1 / fps has passed since the last update (fps at time +0x30: 30 from a frame-rate object, main+0x166daa4; csinc at main+0x166db80) and then steps the game clock
# (system +0x3348, the step at +0x3350) by that object's step (x23 +0x34: 1/30 s times the game's timescale, a second time at +0x44, fps 30 at +0x48), not by the time that
# passed (the measured path, main+0x166dca8, runs only without the object). The battle itself steps 1 / the game's frame rate per update: a block at main+0x269e150 {fps,
# interval, fps, float fps} = {30, 2, 30, 30.0}, read by the scene update's caller (main+0x8c3610: step = 1.0 / fps) and its jobs (32 reads; the game's own writers at
# main+0x8a35e0 / main+0xfc1618, a run-time interval request at +0x1d58, never ran in a probe). The event scripts step by the KTGL device's frame rate (device +0x32d0, from the
# interval in its config: the third stub). The updates are not held by the presents (a task kept 60 a second while the host held frames 3 vsyncs), so they run at the cap and
# the host's frame step paces only the presents: at main+0x166dab4 (where both fps paths join, before the update's tasks and the wait) the task's fps and the frame-rate block
# become the cap (60, or 30 at a 30 cap), the update's vsyncs (60 / cap) are marked (+0x28) and the device frame rate becomes 59.94 / those vsyncs; at main+0x166dc60 the
# object's steps are scaled by its fps × vsyncs / 60 and the vsyncs are counted. Every step is then per update as on the Switch, so an update rate below the cap slows them all
# together. With only the task's wait at 1/60 s the game clock ran at twice real time; with the clock fixed and the block left at 30, battles still ran at twice (Link covered
# twice the ground in a 3 s stick hold; enemy waves came early). Off (target 0), the device frame rate follows the game's fps (29.97 at 30, as with interval 2) and the steps and
# the block stay the game's. x16, x17, s16–s18 and the flags are dead at both sites; the function uses no x16 / x17
def frame_step(ctx):
    here, emit, fs_site, fs_put, FS_STATE_OFF = ctx.here, ctx.emit, ctx.site, ctx.put, ctx.state
    fs_site(0x166dab4, '68734339'); fs_site(0x166dc60, 'e83640b9'); fs_site(0x8d94b0, '4900c0f2')
    # nnMain builds KTGL's device config on its stack and inits the device once (main+0x8d94c4 → main+0x17c34f0): {1920, 1080, 3, 9, 5, interval 2, 9, …}. The device keeps the
    # interval (main+0x17c4ba8 → device +0x2aa4, passed to every present at main+0x17ced78, which sets it on the window when it changes) and its frame rate, 59.94 / interval
    # (main+0x17c37c8 → device +0x32d0). Interval 1 above a 30 fps cap (the engine's own 60 fps mode), else the game's 2. x16, x17 and the flags are dead up to the init call
    start = here(); emit(ADRP(16, here(), FS_STATE_OFF))
    for w in (0xb9400210,   # ldr w16,[x16]          target fps (float bits)
              0x52a83e11,   # mov w17,#0x41f00000    30.0
              0x6b11021f,   # cmp w16,w17
              0x5400006d,   # b.le two (+3)
              0xf2c00029):  # movk x9,#0x1,lsl #32   interval 1
        emit(w)
    emit(B(here(), 0x8d94b4)); emit(0xf2c00049); emit(B(here(), 0x8d94b4))   # two: movk x9,#0x2,lsl #32 (the original)
    fs_put(0x8d94b0, [B(0x8d94b0, start)], 'KTGL device config present interval: 1 above a 30 fps cap, else the game\'s 2 (state page target fps)')
    # PEANUTS: the updates step by the time they take, not a fixed 1 / cap. The Thor holds 60 presents but not always 60 updates (56 to 59 in battle), and at a fixed
    # 1/60 s a step the game ran at 0.94 to 0.99 of real time; voice lines kept real time, so real-time cutscenes fell behind their audio. The clock stub measures the
    # ticks the update took (x0 now, x21 the last update's tick, d0 the tick frequency: the game's own wait just read them), as vsyncs capped at 2 (the Switch's 1/30 s,
    # so a long update still slows the game as on the Switch), steps the clock by it and keeps it at state +8 (+0xc the fraction of a vsync carried into +0x30's count).
    # The next update's battle (the block's float fps) and event scripts (the device frame rate) step by it; before any measured update, by the cap as before
    def stub(words, adrps, back):
        start = here()
        for i, w in enumerate(words): emit(ADRP(adrps[i][0], here(), adrps[i][1]) if i in adrps else w)
        emit(B(here(), back))
        return start
    start = stub((0,            # adrp x16, state
                  0xbd400210,   # ldr s16,[x16]          target fps (0 = off)
                  0x1e202208,   # fcmp s16,#0.0
                  0x5400026d,   # b.le off (+19)
                  0x1e380208,   # fcvtzs w8,s16          the cap
                  0xb9003268,   # str w8,[x19,#0x30]     the task's fps
                  0x52800791,   # mov w17,#60
                  0x1ac80a31,   # udiv w17,w17,w8        vsyncs per update at the cap
                  0xb9002a11,   # str w17,[x16,#0x28]    this update's vsyncs (on)
                  0xbd400a12,   # ldr s18,[x16,#8]       the last update's measured vsyncs (0 = none yet)
                  0x1e230231,   # ucvtf s17,w17
                  0x1e202248,   # fcmp s18,#0.0
                  0x1e31ce51,   # fcsel s17,s18,s17,gt   else the cap's
                  0,            # adrp x16, the frame-rate block
                  0xb9015208,   # str w8,[x16,#0x150]    the game's frame-rate block: fps
                  0xb9015611,   # str w17,[x16,#0x154]   interval
                  0xb9015a08,   # str w8,[x16,#0x158]    fps
                  0x52800788,   # mov w8,#60
                  0x1e230112,   # ucvtf s18,w8
                  0x1e311a52,   # fdiv s18,s18,s17
                  0xbd015e12,   # str s18,[x16,#0x15c]   float fps (the battle's step is 1 / it)
                  0x14000008,   # b dev (+8)
                  0xb9002a1f,   # off: str wzr,[x16,#0x28]
                  0xb9403268,   # ldr w8,[x19,#0x30]     the game's fps
                  0x34000188,   # cbz w8,step (+12)
                  0x52800791,   # mov w17,#60
                  0x1ac80a31,   # udiv w17,w17,w8        vsyncs per update at that fps
                  0x34000131,   # cbz w17,step (+9)
                  0x1e230231,   # ucvtf s17,w17
                  0,            # dev: adrp x16, the device pointer
                  0xf943ce10,   # ldr x16,[x16,#0x798]   the KTGL device
                  0xb40000b0,   # cbz x16,step (+5)
                  0,            # adrp x8, 59.94
                  0xbd4f3d10,   # ldr s16,[x8,#0xf3c]    59.94
                  0x1e311a10,   # fdiv s16,s16,s17
                  0xbd32d210,   # str s16,[x16,#0x32d0]  the device frame rate
                  0,            # step: adrp x16, state
                  0xb9402a11,   # ldr w17,[x16,#0x28]    on?
                  0x52800028,   # mov w8,#1
                  0x340000f1,   # cbz w17,flag (+7)      off: the scripts step every update
                  0xb9401a11,   # ldr w17,[x16,#0x18]    this 1/30 s tick of game time
                  0x39405608,   # ldrb w8,[x16,#0x15]    the tick the scripts last stepped in (low byte)
                  0x12001e31,   # and w17,w17,#0xff
                  0x6b11011f,   # cmp w8,w17
                  0x1a9f07e8,   # cset w8,ne             a new tick: they step
                  0x39005611,   # strb w17,[x16,#0x15]
                  0x39005208,   # flag: strb w8,[x16,#0x14]  the scripts step this update
                  0x39437368),  # ldrb w8,[x27,#0xdc]    (the original)
                 {0: (16, FS_STATE_OFF), 13: (16, 0x269e150), 29: (16, 0x2bac798), 32: (8, 0x1ef5f3c), 36: (16, FS_STATE_OFF)}, 0x166dab8)
    fs_put(0x166dab4, [B(0x166dab4, start)], 'KIDS frame task: fps and the frame-rate block\'s ints = the cap, vsyncs per update marked; the block\'s float fps and the device frame rate follow the last update\'s measured vsyncs; the mud scripts step this update when a new 1/30 s tick began (state page target fps; 0 = the game\'s 30)')
    start = stub((0xbd4036f0,   # ldr s16,[x23,#0x34]    the frame-rate object's step
                  0,            # adrp x16, state
                  0xb9402a11,   # ldr w17,[x16,#0x28]    vsyncs per update (0 = off)
                  0x34000551,   # cbz w17,off (+42)
                  0xb9404ae8,   # ldr w8,[x23,#0x48]     the object's fps
                  0x34000508,   # cbz w8,off (+40)
                  0xcb150011,   # sub x17,x0,x21         ticks the update took
                  0x9e630231,   # ucvtf d17,x17
                  0x1e601a31,   # fdiv d17,d17,d0        seconds
                  0x1e624231,   # fcvt s17,d17
                  0x52800791,   # mov w17,#60
                  0x1e230232,   # ucvtf s18,w17
                  0x1e320a31,   # fmul s17,s17,s18       vsyncs it took
                  0x1e201012,   # fmov s18,#2.0
                  0x1e327a31,   # fminnm s17,s17,s18     at most 2
                  0xbd000a11,   # str s17,[x16,#8]       for the next update's battle and events
                  0xbd400e12,   # ldr s18,[x16,#0xc]     the fraction of a vsync carried
                  0x1e312a52,   # fadd s18,s18,s17
                  0x1e390251,   # fcvtzu w17,s18
                  0x1e230220,   # ucvtf s0,w17
                  0x1e203a52,   # fsub s18,s18,s0
                  0xbd000e12,   # str s18,[x16,#0xc]
                  0xf9401a08,   # ldr x8,[x16,#0x30]
                  0x8b314108,   # add x8,x8,w17,uxtw
                  0xf9001a08,   # str x8,[x16,#0x30]     vsyncs stepped
                  0xbd401e12,   # ldr s18,[x16,#0x1c]    the fraction of a 1/30 s tick carried
                  0x1e2c1000,   # fmov s0,#0.5
                  0x1f004a32,   # fmadd s18,s17,s0,s18   + the update's vsyncs / 2
                  0x1e390251,   # fcvtzu w17,s18
                  0x1e230220,   # ucvtf s0,w17
                  0x1e203a52,   # fsub s18,s18,s0
                  0xbd001e12,   # str s18,[x16,#0x1c]
                  0xb9401a08,   # ldr w8,[x16,#0x18]
                  0x0b110108,   # add w8,w8,w17
                  0xb9001a08,   # str w8,[x16,#0x18]     1/30 s ticks of game time (the mud interpreter's resumes, below)
                  0xb9404ae8,   # ldr w8,[x23,#0x48]
                  0x1e230112,   # ucvtf s18,w8
                  0x1e320a31,   # fmul s17,s17,s18
                  0x52800788,   # mov w8,#60
                  0x1e230112,   # ucvtf s18,w8
                  0x1e321a31,   # fdiv s17,s17,s18       its fps x vsyncs / 60
                  0xbd4046e0,   # ldr s0,[x23,#0x44]
                  0x1e310a10,   # fmul s16,s16,s17
                  0x1e310800,   # fmul s0,s0,s17
                  0x14000002,   # b out (+2)
                  0xbd4046e0,   # off: ldr s0,[x23,#0x44]
                  0x1e260208,   # out: fmov w8,s16
                  0xb9001a68),  # str w8,[x19,#0x18]     the update's step
                 {1: (16, FS_STATE_OFF)}, 0x166dc6c)
    fs_put(0x166dc60, [B(0x166dc60, start)], 'KIDS frame task: the frame-rate object\'s steps × its fps × the vsyncs the update took (measured, at most 2) / 60 (state page target fps; 0 = as the game sets them)')
    # PEANUTS: real-time cutscenes ran at twice their speed at the 60 cap: the scene timeline advances its frame natively once a call of the scene's timeline update
    # (main+0x857d7c, add w10,w10,#1, when its mud UpdateTimelineList kept the frame in range), once an update, 30 a second on the Switch. The clock stub counts 1/30 s
    # ticks of game time (state +0x18, +0x1c the fraction), the frame task decides once an update whether the frame steps (a new tick began: state +0x14, +0x15 the
    # tick's low byte), and the advance adds that flag. The scripts and the rest of the scene update still run every update: holding the scene update at its entry
    # (main+0x857510), its script call (main+0x857d20) or the KIDS timeline list task (main+0x1672d60) on the other updates left grey frames or characters missing
    # every other frame (Thor, 2026-11-26); running both scripts every update with only the frame held back was right. Off (target 0) the frame steps every update
    fs_site(0x857d7c, '4a050011')
    start = stub((0,            # adrp x16, state
                  0x39405211,   # ldrb w17,[x16,#0x14]   the frame steps this update? (1 or 0)
                  0x0b11014a),  # add w10,w10,w17        the next frame, as the original's add w10,w10,#1 on those updates
                 {0: (16, FS_STATE_OFF)}, 0x857d80)
    fs_put(0x857d7c, [B(0x857d7c, start)], 'scene timeline frame advance: once a 1/30 s tick of game time (state +0x14; target 0 = every update)')
    # The same advance counts down the frames the timeline has left (its +4, main+0x857da0, sub w10,w10,#1); below 2 the timeline stops advancing. Left once an
    # update with the frame held, it ran out halfway: a cutscene froze on a frame until skipped (Thor, 2026-11-26)
    fs_site(0x857da0, '4a050051')
    start = stub((0,            # adrp x16, state
                  0x39405211,   # ldrb w17,[x16,#0x14]   the frame steps this update? (1 or 0)
                  0x4b11014a),  # sub w10,w10,w17        the frames left, as the original's sub w10,w10,#1 on those updates
                 {0: (16, FS_STATE_OFF)}, 0x857da4)
    fs_put(0x857da0, [B(0x857da0, start)], 'scene timeline frames left: counted once a 1/30 s tick of game time (state +0x14; target 0 = every update)')
    # PEANUTS: the map's and the HUD's blinking icons (the player arrow, points of interest, a new mission's mark) ran at twice their speed at 60: their layout
    # animations' frames are set each update from an interface frame counter (main+0xd4b9d0 reads it: its +0x1b4, 20, 22, 24 ... on the Thor), and the update
    # that owns it (main+0xf87894, after its parts' updates with the update's time in v8) adds 2 a call, the Switch's two 1/60 s frames of a 1/30 s update. It
    # adds the update's 1/60 s frames (time × 60), the fraction carried in state +0x40: 2 at 1/30 s as on the Switch, at every cap and at target 0. Found with
    # a probe on the panes' alpha (main+0x18ce46c: 2 of the map's 175 panes changing) and write watches on one's alpha (written by main+0x18d0ca0) and on its
    # animation (frame set by main+0xd4c864 and main+0xd51a44). x16, x17, s16 and s17 are dead after the calls; the flags are set again by the cmp after it
    fs_site(0xf87894, '08090011')
    start = stub((0,            # adrp x16, state
                  0xbd404210,   # ldr s16,[x16,#0x40]    the fraction of a frame carried
                  0x52a84e11,   # mov w17,#0x42700000    60.0
                  0x1e270231,   # fmov s17,w17
                  0x1f114110,   # fmadd s16,s8,s17,s16   the update's 1/60 s frames and the carry
                  0x1e390211,   # fcvtzu w17,s16         whole frames
                  0x1e230231,   # ucvtf s17,w17
                  0x1e313a10,   # fsub s16,s16,s17
                  0xbd004210,   # str s16,[x16,#0x40]
                  0x0b110108),  # add w8,w8,w17          the original's add w8,w8,#2 at 1/30 s
                 {0: (16, FS_STATE_OFF)}, 0xf87898)
    fs_put(0xf87894, [B(0xf87894, start)], 'interface frame counter: the update\'s 1/60 s frames, not 2 a call (state +0x40 the fraction)')

FRAME_STEP = {'c3cf52bf2b05d7310884971b50bcccb8': frame_step}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': '60 fps frame step: the KIDS frame task and frame-rate block', 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': "the game's own frame code; only the host's frame-step state page is shared. Fire Emblem: Three Houses and Taiko Risshiden V DX run on KTGL too, with other frame code",
     'blocked': 'frame-step stubs have no function-matched form yet'},
]
