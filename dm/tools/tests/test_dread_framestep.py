# PEANUTS: new file (Metroid Dread 60 fps cutscenes: dm/games/010093801237C000/patches.py)
"""Metroid Dread's frame step stub (the cutscene predicate main+0x119a320 behind the state page's target): recorded through a context like docs_patches.py's, decoded with capstone and
walked instruction by instruction for a target of 60, 30 and 0 (no game files: the site's original bytes are given to the context). Also the profile: fps_target 60 and the build ID."""
import importlib.util
from pathlib import Path
import struct
import sys
import types
import unittest

import capstone

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
import game  # noqa: E402

GAME = '010093801237C000'
BID = '646761f643afebb379edd5e6a5151af2'
STATE = 0x72a20000   # a stand-in for FS_STATE_OFF (the real one is the page after the key page)
SITE_ORIG = bytes.fromhex('ff0305d1')   # sub sp, sp, #0x140: the predicate's first instruction


def load_patches():
    spec = importlib.util.spec_from_file_location('dread_patches', REPO / 'dm/games' / GAME / 'patches.py')
    assert spec is not None and spec.loader is not None   # PEANUTS(upstream-candidate): required fixture loader.
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


def record(fn, area=0x219a324):
    words, puts, sites = [], [], []
    ctx = types.SimpleNamespace(here=lambda: area + 4 * len(words), emit=words.append, state=STATE,
                                site=lambda off, orig: sites.append((off, bytes.fromhex(orig))), put=lambda off, ws, why: puts.append((off, list(ws), why)))
    fn(ctx)
    return area, words, puts, sites


def run(area, words, puts, target_bits):
    """walks the predicate's entry: returns ('ret', w0) or ('body', sp adjust) once control reaches main+0x119a324"""
    cs = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    code = {0x119a320: puts[0][1][0]}
    for i, w in enumerate(words): code[area + 4 * i] = w
    pc, regs, nz, sp = 0x119a320, {}, None, 0
    for _ in range(40):
        ins = next(cs.disasm(struct.pack('<I', code[pc]), pc))
        m, o = ins.mnemonic, ins.op_str
        if m == 'b': pc = int(o.lstrip('#'), 0)
        elif m == 'adrp': regs[o.split(',')[0]] = int(o.split('#')[1], 0); pc += 4
        elif m == 'ldr':
            assert o == 'w17, [x16]' and regs['x16'] == STATE
            regs['w17'] = target_bits; pc += 4
        elif m == 'mov':
            d, v = [s.strip() for s in o.split(',')]
            regs[d] = 0 if v == 'wzr' else int(v.lstrip('#'), 0); pc += 4
        elif m == 'cmp': nz = regs['w17'] == regs['w16']; pc += 4
        elif m == 'b.ne': pc = int(o.lstrip('#'), 0) if not nz else pc + 4
        elif m == 'ret': return ('ret', regs.get('w0'))
        elif m == 'sub': sp = -int(o.split('#')[1], 0); pc += 4
        else: raise AssertionError(f'unexpected {m} {o} at {pc:#x}')
        if pc == 0x119a324: return ('body', sp)
    raise AssertionError('did not terminate')


def f32(x): return struct.unpack('<I', struct.pack('<f', x))[0]


class DreadFrameStep(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_patches()
        cls.area, cls.words, cls.puts, cls.sites = record(cls.m.FRAME_STEP[BID])

    def test_registered_for_the_2_1_0_main(self):
        g = game.load(GAME)
        self.assertIn(BID, [row['main'] for row in g.builds().values()])
        self.assertEqual(list(self.m.FRAME_STEP), [BID])
        self.assertEqual(self.sites, [(0x119a320, SITE_ORIG)])

    def test_the_site_is_one_branch_to_the_stub(self):
        (off, ws, _), = self.puts
        self.assertEqual((off, len(ws)), (0x119a320, 1))
        d = next(capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM).disasm(struct.pack('<I', ws[0]), off))
        self.assertEqual((d.mnemonic, int(d.op_str.lstrip('#'), 0)), ('b', self.area))

    def test_target_60_returns_false_without_touching_the_stack(self):
        self.assertEqual(run(self.area, self.words, self.puts, f32(60.0)), ('ret', 0))

    def test_target_30_and_0_run_the_original_predicate(self):
        for t in (30.0, 0.0):
            self.assertEqual(run(self.area, self.words, self.puts, f32(t)), ('body', -0x140), t)

    def test_original_instruction_is_replayed_and_rejoins_after_it(self):
        self.assertEqual(self.words[-2].to_bytes(4, 'little'), SITE_ORIG)
        last = next(capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM).disasm(struct.pack('<I', self.words[-1]), self.area + 4 * (len(self.words) - 1)))
        self.assertEqual((last.mnemonic, int(last.op_str.lstrip('#'), 0)), ('b', 0x119a324))

    def test_profile_targets_60(self):
        self.assertEqual(game.load(GAME).get('fps_target'), '60')


if __name__ == '__main__':
    unittest.main()
