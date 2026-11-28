#!/usr/bin/env python3
# PEANUTS (issue #120): the shader split's choice between the open-source ooz decoder and the game's own Oodle decoder, and the rule that keeps ooz honest.
#
# The game's own decoder runs from the rewritten modules, so on the phone the split waited for the rewrite beside it (rewrite_wait.py) as soon as it met an
# Oodle stream (FINAL FANTASY RESONANCE on an AYN Thor: 264 s of its 291 s). unoodle (dm/third_party/ooz, GPL-3.0-or-later, a program of its own behind a pipe)
# needs no game code, so it can decode at once. It is not the game's code, and an Oodle stream carries no checksum, so its output counts only when the split's
# tables are the profile's (every key is the hash of its shader's bytes; shader_split.py main → ooz_check). When they are not, or the split stops while it was
# in use, the split runs again with the game's own decoder (which then waits for the rewrite, as before), and remembers that for the title.
#
# Where the worker comes from (argv):
#   DM_OODLE_OOZ=<program>      the PC builder's unoodle executable (peanuts_build.py), started as it is;
#   DM_OODLE_OOZ_LIB=<library>  the phone's libunoodle.so in the APK (PackService): loaded by ctypes in a worker process of DM_UE_HOST's command
#                               (nxpack_worker under /system/bin/linker64, as the game's decoder is: no file of the app's storage is executed), whose
#                               unoodle_main() serves the same protocol on that process's stdin and stdout. Empty (env.txt DM_OODLE_OOZ_LIB=): the game's own.
# The split itself sets, for the processes it starts:
#   DM_SPLIT_DECODER=ooz        while the ooz worker may decode: the split's kept scan results (ScanCache, split_reuse.py) are then kept apart from the game
#                               decoder's, so the rerun with the game's decoder never takes a result ooz decoded;
#   DM_SPLIT_DECODER_OFF=1      the game's own decoder from here on (the rerun; a profile without shader_map.tsv; a remembered refusal);
#   DM_OODLE_OOZ_USED=<files>   the marker files (LIST_SEP between them) a process touches when it starts the ooz worker: whether ooz decoded anything,
#                               also in a pool worker process, a resumed split (beside DM_SPLIT_RESUME) or a result kept from an earlier build (PN_REUSE_DIR).
import hashlib
import os
import shlex
import tempfile

LIST_SEP = '\x1f'
CODE = ("import ctypes, sys\n"
        "sys.exit(ctypes.CDLL(sys.argv[1]).unoodle_main())\n")
_RUN = None   # this split's own marker file (begin)


def argv():
    """The ooz worker's command, or None when the game's own decoder is to be used"""
    if os.environ.get('DM_SPLIT_DECODER_OFF') == '1': return None
    if ooz := os.environ.get('DM_OODLE_OOZ', ''): return [ooz]
    lib, host = os.environ.get('DM_OODLE_OOZ_LIB', ''), os.environ.get('DM_UE_HOST', '')
    if lib and host: return shlex.split(host) + ['-I', '-c', CODE, lib]
    return None


def game_decoder():
    """Whether the game's own decoder can be run to fall back to: the Mac host (DM_OODLE_HOST, DM_OODLE_MODULES) or the phone's host library (DM_UE_HOST,
    DM_UE_HOST_LIB, DM_UE_MODULES). The PC builder has neither: there the ooz split's tables are written as before and pack_android.py's ooz_split_checked
    refuses them when they are not the profile's"""
    e = os.environ.get
    return bool(e('DM_OODLE_HOST') and e('DM_OODLE_MODULES')) or bool(e('DM_UE_HOST') and e('DM_UE_HOST_LIB') and e('DM_UE_MODULES'))


def fallback():
    """Whether a split whose ooz output is not the profile's runs again with the game's own decoder (used: ooz decoded some of it; a game decoder to run)"""
    return used() and game_decoder()


def name():
    """The worker as messages name it"""
    return os.environ.get('DM_OODLE_OOZ') or os.environ.get('DM_OODLE_OOZ_LIB', '')


def mark():
    """Called by whatever starts the ooz worker or takes its output (shader_split.oodle_ask, ue_ondevice.decode)"""
    for p in filter(None, os.environ.get('DM_OODLE_OOZ_USED', '').split(LIST_SEP)):
        try: open(p, 'a').close()
        except OSError: pass


def used():
    return any(os.path.exists(p) for p in filter(None, os.environ.get('DM_OODLE_OOZ_USED', '').split(LIST_SEP)))


def _refused_file():
    d = os.environ.get('PN_REUSE_DIR', '')
    return os.path.join(d, 'ooz.refused') if d and os.environ.get('PN_REUSE') != '0' else None


def _identity(profile_map, mains):
    """What a refusal is remembered for: the worker, the app's commit, the profile's map and the game's code"""
    h = hashlib.sha256(repr((argv() and argv()[-1], os.environ.get('DM_PACKER_COMMIT'))).encode())
    for p in [profile_map] + list(mains):
        try:
            with open(p, 'rb') as f: h.update(hashlib.sha256(f.read()).digest())
        except OSError: h.update(b'-')
    return h.hexdigest()


def begin(profile_map, mains, resume=None):
    """Sets up the split's environment; → True when the ooz worker may decode. A profile without shader_map.tsv has nothing to check ooz against: game decoder"""
    global _RUN
    clear()
    if argv() is None: return False
    if not os.path.isfile(profile_map):
        print(f'split: the profile has no shader_map.tsv to check the ooz decoder against: Oodle streams go to the game\'s own decoder', flush=True)
        os.environ['DM_SPLIT_DECODER_OFF'] = '1'; return False
    rf = _refused_file()
    try:
        if rf and open(rf).read().strip() == _identity(profile_map, mains):
            print(f'split: an earlier split of these game files with the ooz decoder did not give the profile\'s tables ({rf}): the game\'s own decoder', flush=True)
            os.environ['DM_SPLIT_DECODER_OFF'] = '1'; return False
    except OSError: pass
    fd, _RUN = tempfile.mkstemp(prefix='ooz-used-'); os.close(fd); os.unlink(_RUN)
    marks = [_RUN] + ([resume.rstrip('/') + '.ooz-used'] if resume else []) + ([os.path.join(os.environ['PN_REUSE_DIR'], 'ooz.used')] if _refused_file() else [])
    os.environ['DM_SPLIT_DECODER'] = 'ooz'
    os.environ['DM_OODLE_OOZ_USED'] = LIST_SEP.join(marks)
    return True


def clear():
    """The variables the split sets go: the phone runs every tool in the app's one process, whose environment outlives a split"""
    for k in ('DM_SPLIT_DECODER', 'DM_SPLIT_DECODER_OFF', 'DM_OODLE_OOZ_USED'): os.environ.pop(k, None)


def end():
    """At the split's end: its own marker and variables go"""
    if _RUN and os.path.exists(_RUN):
        try: os.unlink(_RUN)
        except OSError: pass
    clear()


def off(why, profile_map, mains):
    """The ooz worker's output is not taken: the game's own decoder from here on, and for this title's later splits of the same files"""
    print(f'split: the ooz decoder\'s output is not taken ({why}); the split runs again with the game\'s own decoder', flush=True)
    rf = _refused_file()
    if rf:
        try:
            os.makedirs(os.path.dirname(rf), exist_ok=True)
            with open(rf, 'w') as f: f.write(_identity(profile_map, mains) + '\n')
        except OSError: pass
    end()
    os.environ['DM_SPLIT_DECODER_OFF'] = '1'
