# PEANUTS (issue #120): the split waits here for the rewrite that runs beside it (PackService RewriteBeside) before it starts the game's own decoder from
# the rewritten modules (an Unreal game's Oodle, ue_ondevice.DeviceOodle; Oodle- or LZ5-compressed files, shader_split.py _oodle). PN_REWRITE_BESIDE_DONE names a
# file the app writes when that rewrite process ends: its exit code. Unset or empty: no rewrite beside, nothing to wait for. A game whose split never needs
# the game's code never waits, so the rest of its split runs beside the rewrite.
import os, time


def wait():
    """Returns once the rewritten modules are there; raises SystemExit naming the rewrite when it failed"""
    done = os.environ.get('PN_REWRITE_BESIDE_DONE', '')
    if not done:
        return
    t0, said = time.monotonic(), False
    while not os.path.exists(done):
        if not said:
            print('split: waiting for the rewrite beside it (the game\'s own decoder needs the rewritten modules)', flush=True); said = True
        time.sleep(0.5)
    try:
        code = int(open(done).read().strip() or '1')
    except (OSError, ValueError):
        code = 1
    if said:
        print(f'split: the rewrite beside it ended after {time.monotonic() - t0:.0f} s of waiting (exit {code})', flush=True)
    if code != 0:
        raise SystemExit(f'[RED] the rewrite beside the split failed (exit {code}), so the game\'s own decoder has no rewritten modules: see rewrite.log')
