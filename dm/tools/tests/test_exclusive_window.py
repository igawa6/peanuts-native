"""Exercise exclusive-window branches through the real Android rewriter."""
# PEANUTS(upstream-candidate): synthetic only; no game dumps required.
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_nso import make_nso  # noqa: E402
from test_nso_match import image, RET  # noqa: E402

TOOLS = Path(__file__).resolve().parents[1]
START = 0x100
WORDS = (
    0x085ffe47,  # ldaxrb w7, [x18]
    0x34000067,  # cbz w7, store
    0xd5033f5f,  # clrex
    0x14000004,  # b occupied
    0x0806fe45,  # store: stlxrb w6, w5, [x18]
    0x35ffff66,  # cbnz w6, start
    RET,         # stored
    RET,         # occupied
)


class ExclusiveWindow(unittest.TestCase):
    def rewrite(self, tmp, words):
        raw = image({START: words})
        src = Path(tmp) / 'main'; src.write_bytes(make_nso((raw[:0x1000], raw[0x1000:0x3000], raw[0x3000:])))
        return subprocess.run([sys.executable, str(TOOLS / 'patch_nso.py'), str(src), tmp, 'main', '--target', 'android'],
                              env=dict(os.environ, DM_GAME='010015100B514000', DM_REWRITE_J='1'), capture_output=True, text=True)

    def test_checks_byte_then_stores_or_exits_after_clrex(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self.rewrite(tmp, WORDS)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            patched = (Path(tmp) / 'main.patched.bin').read_bytes()
        md = Cs(CS_ARCH_ARM64, CS_MODE_ARM); md.detail = True
        def instruction(at): return next(md.disasm(patched[at:at + 4], at))
        entry = instruction(START)
        self.assertEqual(entry.mnemonic, 'b')
        stub = entry.operands[0].imm
        ins = list(md.disasm(patched[stub:stub + 100], stub))
        load = next(i for i, x in enumerate(ins) if x.mnemonic == 'ldaxrb')
        ld, guard, clear, skip, store, retry = ins[load:load + 6]
        self.assertEqual((guard.mnemonic, clear.mnemonic, skip.mnemonic, store.mnemonic, retry.mnemonic),
                         ('cbz', 'clrex', 'b', 'stlxrb', 'cbnz'))
        self.assertEqual(guard.operands[-1].imm, store.address)
        self.assertEqual(retry.operands[-1].imm, ld.address)
        self.assertEqual(ld.operands[-1].mem.base, store.operands[-1].mem.base)
        self.assertNotEqual(ld.reg_name(ld.operands[-1].mem.base), 'x18')
        self.assertEqual(instruction(retry.address + 12).operands[0].imm, START + 24)
        exit_stub = skip.operands[0].imm
        self.assertEqual(instruction(exit_stub).mnemonic, 'str')
        self.assertEqual(instruction(exit_stub + 8).operands[0].imm, START + 28)

    def test_outside_branch_still_cannot_enter_the_store(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self.rewrite(tmp, (*WORDS, 0x17fffffc))  # outside b to store
        self.assertNotEqual(run.returncode, 0)
        self.assertIn('branch into window 120', run.stderr)

    def test_cannot_skip_clrex_on_the_way_to_the_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self.rewrite(tmp, (WORDS[0], 0x34000047, *WORDS[2:]))  # cbz w7 directly to b
        self.assertNotEqual(run.returncode, 0)
        self.assertIn('0x10c', run.stderr)


if __name__ == '__main__': unittest.main()
