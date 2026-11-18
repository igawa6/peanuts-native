#!/usr/bin/env python3
# PEANUTS: the Unreal Engine shader decode on the phone. On the Mac the NSP script makes <version dir>/ue_shaders before the split (build_ipa_from_nsp.py decode_ue_shaders: uepak.py takes
# the shader code archives out of the paks, and the game's own Oodle decodes their shaders in the Mac host, DM_OODLE_JOB). The on-device packer has no NSP script, so shader_split.py stopped
# with "no decoded shader code archives" for every Unreal game (Octopath Traveler 0, DRAGON QUEST III HD-2D Remake). This does the same work from the split, when PackService sets
# DM_UE_DECODE (the output folder):
#   - the archives come out of the paks with uepak.job (its AES through _nxaes off the Mac, dm/peanuts/pack_worker/nxaes.c); LZ4 shaders (Unreal 4.26 and later) are decoded there already;
#   - Oodle shaders (Octopath Traveler 0) go to the game's own OodleLZ_Decompress in the title's Android host, libnxhost_<title ID>.so, in its tool mode (DM_OODLE_SERVE, hle_oodle.c:
#     served before the SDK starts, no boot): a process of its own, nxpack_worker under the linker (DM_UE_HOST, as the split's pool workers) loading the library (DM_UE_HOST_LIB) and calling
#     nxhost_main with the rewritten modules (DM_UE_MODULES, the pack folder), requests over its stdin / stdout (uepak.OodleHost's protocol). The results are the job output uepak.finish reads.
# A folder whose stamp matches the paks is reused, as on the Mac.
import os, shlex, struct, subprocess, sys, tempfile, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '../../tools')); sys.path.insert(0, HERE)
import uepak
from romfs import os as ros

HOST_CODE = ("import ctypes, sys\n"
             "h = ctypes.CDLL(sys.argv[1])\n"
             "a = (ctypes.c_char_p * 3)(b'nxhost', sys.argv[2].encode(), None)\n"
             "sys.exit(h.nxhost_main(2, a))\n")


class DeviceOodle(uepak.OodleHost):
    """uepak.OodleHost with the Android host: the worker command (DM_UE_HOST) runs HOST_CODE, which loads the title's host library and calls nxhost_main(modules)"""
    def __init__(self, cmd, lib, modules):
        self.err = tempfile.TemporaryFile()
        self.p = subprocess.Popen(shlex.split(cmd) + ['-I', '-c', HOST_CODE, lib, modules], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.err,
                                  env=dict(os.environ, DM_OODLE_SERVE='1'))
        self.n = 0


class OozWorker(uepak.OodleHost):
    """uepak.OodleHost with the PC builder's worker from the open-source ooz decoder (DM_OODLE_OOZ, dm/third_party/ooz); a failure names it, not the game's decoder"""
    def __call__(self, comp, n):
        try: return super().__call__(comp, n)
        except ValueError as e: raise ValueError(str(e).replace('the game\'s Oodle in the Mac host', 'the ooz worker (DM_OODLE_OOZ; not the game\'s own decoder)')) from None


def stamp(romfs):
    """The paks and containers under the RomFS with their sizes (the NSP script's ue_stamp)"""
    out = []
    for dp, _, fs in ros.walk(romfs):
        for f in fs:
            if f.endswith(('.pak', '.utoc')):
                p = os.path.join(dp, f); out.append(f'{os.path.relpath(p, romfs)}\t{ros.stat(p).st_size}\n')
    return ''.join(sorted(out))


def rmtree(p):
    """<p> and everything in it. A file already gone is no error: macOS on an exFAT or FAT drive removes a file's AppleDouble ._<name> with it, so the walk's
    listing of that ._ entry is stale by its turn (a Mac build of FINAL FANTASY RESONANCE onto such a drive stopped here)"""
    if os.path.isdir(p):
        for dp, ds, fs in os.walk(p, topdown=False):
            for f in fs:
                try: os.remove(os.path.join(dp, f))
                except FileNotFoundError: pass
            for d in ds: os.rmdir(os.path.join(dp, d))
        os.rmdir(p)


def decode(romfs, main_nso, out):
    """<out> holds the decoded shader code archives of the RomFS's paks (made now, or kept from an earlier run with the same paks); → out"""
    st = stamp(romfs)
    sf = os.path.join(out, 'stamp')
    if os.path.isfile(sf) and open(sf).read() == st:
        print(f'ue: reusing the decoded shader archives in {out}', flush=True); return out
    t0 = time.time()
    work, part = out + '.job', out + '.partial'
    rmtree(work); rmtree(part)
    cmd, lib, mods = os.environ.get('DM_UE_HOST', ''), os.environ.get('DM_UE_HOST_LIB', ''), os.environ.get('DM_UE_MODULES', '')
    # PEANUTS: DM_OODLE_OOZ=<helper> (opt-in, for a PC that has neither the Mac host nor the phone's library): a worker built from the open-source ooz decoder answers the same
    # requests (uepak.OodleHost's protocol; it ignores its arguments) instead of the game's own decoder. Its output counts only once the split's tables are the profile's
    ooz = os.environ.get('DM_OODLE_OOZ', '')
    Host = DeviceOodle
    if ooz: cmd, lib, mods, Host = ooz, 'the ooz worker ' + ooz, '-', lambda c, l, m: OozWorker(c, m)
    dec = None
    try:
        if uepak.io_shader_tocs(romfs):
            # PEANUTS fix (AUDIT #7): an IoStore title's archives may need the game's own Oodle before uepak.job can even list them (unlike the loose-pak case below,
            # which only raises once it knows n > 0); without this check DeviceOodle ran Popen on an empty command and failed with a confusing OSError/FileNotFoundError
            if not cmd or not lib or not mods: raise ValueError('Oodle-compressed shaders and no host to decode them (DM_UE_HOST, DM_UE_HOST_LIB, DM_UE_MODULES)')
            dec = Host(cmd, lib, mods)   # PEANUTS: DM_OODLE_OOZ, above
        listing, n = uepak.job(romfs, main_nso, work, decode=dec)
        if not listing: raise ValueError(f'the RomFS has Unreal paks, but none under {romfs} holds a shader code archive')
        print('ue: ' + '; '.join(f'{x["name"]} {x["shaders"]:,} shaders' + (f' ({x["compression"]})' if x.get('compression') else '') for x in listing)
              + (f'; decoding {n:,} with ' + (lib if ooz else f'the game\'s Oodle in {lib}') if n else ''), flush=True)
        if n:
            if not cmd or not lib or not mods: raise ValueError('Oodle-compressed shaders and no host to decode them (DM_UE_HOST, DM_UE_HOST_LIB, DM_UE_MODULES)')
            dec = dec or Host(cmd, lib, mods)   # PEANUTS: DM_OODLE_OOZ, above
            with open(os.path.join(work, 'oodle.job'), 'rb') as f, open(os.path.join(work, 'oodle.job.out.partial'), 'wb') as o:
                magic, count = struct.unpack('<II', f.read(8))
                if magic != uepak.JOB_MAGIC or count != n: raise ValueError('the Oodle job file does not match its listing')
                o.write(struct.pack('<II', uepak.OUT_MAGIC, n))
                for k in range(n):
                    size, raw = struct.unpack('<II', f.read(8))
                    d = dec(f.read(size), raw)
                    o.write(struct.pack('<I', len(d))); o.write(d)
                    if k % 2000 == 0: print(f'ue: decoded {k:,} of {n:,}', flush=True)
            os.replace(os.path.join(work, 'oodle.job.out.partial'), os.path.join(work, 'oodle.job.out'))
    finally:
        if dec: dec.close()
    uepak.finish(work, part)
    open(os.path.join(part, 'stamp'), 'w').write(st)
    rmtree(out); os.replace(part, out); rmtree(work)
    print(f'ue: {len(listing)} archives decoded into {out} in {time.time() - t0:.1f} s', flush=True)
    return out
