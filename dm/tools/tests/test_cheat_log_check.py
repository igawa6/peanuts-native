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

# PEANUTS begin: the two cheat files this test read (Ocarina of Time's and Fire Emblem Fortune's Weave's, Switch 2 profiles) are not carried; two synthetic
# profiles of the same shape (notes and section labels, chains, a master-less list) stand in, in a temporary tree that clc.REPO points at
import shutil   # noqa: E402
import tempfile   # noqa: E402

OOT = '0100000000AA0000'
FE = '0100000000BB0000'
CHEATS = {
    OOT: ('E0F3CCFAF50065AE', '[Synthetic game data; notes: README Cheats]\n[--SectionStart:Player--]\n[Infinite Rupees (999)]\n580F0000 04E842A8\n580F1000 000000D8\n'
          '780F0000 0000966C\n640F0000 00000000 000003E7\n[Infinite Health (experimental)]\n580F0000 04E842A8\n780F0000 00009694\n540E2F00 00000028\nA4EF0000\n'
          '[--SectionEnd:Player--]\n[--SectionStart:Ammo--]\n[Infinite Arrows (99)]\n580F0000 04E842A8\n780F0000 00009D4C\n640F0000 00000000 00000063\n[--SectionEnd:Ammo--]\n'),
    FE: ('DB93B0125F485060', '[Synthetic game data; notes: README Cheats]\n[Max Gold]\n580F0000 01234560\n780F0000 00000010\n640F0000 00000000 0098967F\n'
         '[All Units Level 20]\n580F0000 01234560\n780F0000 00000200\n600F0000 00000000 00000014\n'),
}


def setUpModule():
    tmp = Path(tempfile.mkdtemp(prefix='cheat_log_check_'))
    for tid, (build, text) in CHEATS.items():
        (tmp / 'dm/games' / tid / 'cheats').mkdir(parents=True)
        (tmp / 'dm/games' / tid / 'cheats' / f'{build}.txt').write_text(text, encoding='utf-8')
    global _real_repo, _tmp
    _real_repo, _tmp, clc.REPO = clc.REPO, tmp, tmp


def tearDownModule():
    clc.REPO = _real_repo
    shutil.rmtree(_tmp, ignore_errors=True)
# PEANUTS end


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
