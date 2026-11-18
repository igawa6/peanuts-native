"""scripts/cheat_log_check.py: a run's log against a profile's cheat file (synthetic logs)."""
import importlib.util
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('cheat_log_check', REPO / 'scripts/cheat_log_check.py')
# PEANUTS(upstream-candidate): this fixture requires a loadable script before executing it.
assert spec is not None and spec.loader is not None
clc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clc)

OOT = '0400486024DEC000'
FE = '04007EF01F8AE000'


def log_for(title_id, skip=(), extra=()):
    f = clc.profile_cheat_file(title_id)
    names = clc.cheat_names(f.read_text(encoding='utf-8'))
    lines = [f'[dm] cheats: /x/{f.name}: build {f.stem.lower()}0000000000000000: {len(names)} cheats (0 master, {len(names)} on, 0 broken, {len(names)} runnable)']
    for i, n in enumerate(names):
        if n not in skip: lines.append(f'[dm] cheat "{n}": write 4 bytes of 0x3e7 at guest address 0x{0x7100000000 + i * 0x10:x} (x)')
    return '\n'.join(lines + list(extra)) + '\n'


class CheatLogCheck(unittest.TestCase):
    def test_names_skip_labels_and_sections(self):
        for tid in (OOT, FE):
            names = clc.cheat_names(clc.profile_cheat_file(tid).read_text(encoding='utf-8'))
            self.assertTrue(names)
            self.assertFalse([n for n in names if n.startswith('--') or 'notes:' in n], names)

    def test_clean_log_passes(self):
        for tid in (OOT, FE):
            out, ok = clc.check(tid, log_for(tid))
            self.assertTrue(ok, out)

    def test_missing_write_fails(self):
        names = clc.cheat_names(clc.profile_cheat_file(OOT).read_text(encoding='utf-8'))
        out, ok = clc.check(OOT, log_for(OOT, skip=[names[0]]))
        self.assertFalse(ok)
        self.assertTrue(any(l.startswith('FAIL') and names[0] in l for l in out), out)

    def test_red_cheat_line_fails(self):
        out, ok = clc.check(OOT, log_for(OOT, extra=['[dm] [RED] cheat "x": line 3 is wrong, turning it off']))
        self.assertFalse(ok)

    def test_no_loaded_line_fails(self):
        out, ok = clc.check(OOT, 'nothing here\n')
        self.assertFalse(ok)

    def test_min_addrs_and_only(self):
        names = clc.cheat_names(clc.profile_cheat_file(FE).read_text(encoding='utf-8'))
        out, ok = clc.check(FE, log_for(FE), only=[names[0]], min_addrs=2)
        self.assertFalse(ok)
        out, ok = clc.check(FE, log_for(FE), only=['no such cheat'])
        self.assertFalse(ok)


if __name__ == '__main__':
    unittest.main()
