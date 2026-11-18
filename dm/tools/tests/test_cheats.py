"""The profiles' cheat files (game.tsv cheats rows; dm/games/README.md `cheats`): every one is named after a main build of its profile (game.py Game.cheats) and is
a file a Switch takes too (Atmosphère's parser, docs/reference/cheats.md "The file": only [Name] / {Name} headers and 8-hex-digit words, names under 64 characters,
at most 0x7F cheats and 0x100 words each), and decodes into opcodes the host runs. Ocarina of Time's: its pointer chain, the record offsets of the game-data rows
(the game note's "Cheats") and the values each cheat writes."""
import re
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/games'))
import game  # noqa: E402

# opcode → its length in words, from the first word (docs/reference/cheats.md: the VM), for the opcodes these files may use
def op_len(w, words):
    op = w >> 28
    t = (w >> 24) & 0xF
    if op == 0 or op == 1: return 3 if t != 8 else 4
    if op in (2, 3, 8): return 1 if op != 3 or (w >> 20) & 0xF == 1 else 2
    if op == 4: return 3
    if op == 5: return 2
    if op == 6: return 3
    if op == 7: return 2
    if op == 9: return 1 if (w >> 8) & 0xF == 0 else (3 if t == 8 else 2)
    if op == 0xA: o = (w >> 8) & 0xF; return 2 if o in (2, 4, 5) else 1
    if op == 0xC and t == 0:   # C0TcSo..: register conditional, its operand o: memory + a / reg + a, a static value (two words for T = 8), or none
        o = (w >> 8) & 0xF
        if o in (0, 2): return 2
        if o == 4: return 3 if (w >> 20) & 0xF == 8 else 2
        if o in (1, 3, 5): return 1
    if op == 0xC and t in (1, 2, 3): return 1   # save / restore registers, static registers
    if op == 0xC and t == 4: return 3
    raise ValueError(f'opcode {w:08X} is not one these files use')


def parse(text):
    """[(kind, name, [words])], kind '[' or '{'; ValueError on anything Atmosphère's parser refuses"""
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if not s: continue
        m = re.fullmatch(r'([\[{])([^\]}]+)([\]}])(.*)', s)
        if m:
            if (m.group(1) == '[') != (m.group(3) == ']'): raise ValueError(f'line {n}: unbalanced header')
            if len(m.group(2).encode()) >= 0x40: raise ValueError(f'line {n}: name of 64 bytes or more')
            out.append((m.group(1), m.group(2), [])); s = m.group(4).strip()
            if not s: continue
        if not out: raise ValueError(f'line {n}: words before any header')
        for tok in s.split():
            if not re.fullmatch(r'[0-9A-Fa-f]{8}', tok): raise ValueError(f'line {n}: {tok!r} is not a code word (no prose: a Switch refuses the file)')
            out[-1][2].append(int(tok, 16))
    return out


class Files(unittest.TestCase):
    def test_every_profile(self):
        n = 0
        for g in game.all_games():
            for p in g.cheats():
                n += 1
                entries = parse(p.read_text(encoding='utf-8'))
                cheats = [e for e in entries if e[2]]
                self.assertLessEqual(len(cheats), 0x7F, p)
                self.assertLessEqual(sum(e[0] == '{' for e in cheats), 1, f'{p}: one master at most')
                names = [e[1] for e in cheats]
                self.assertEqual(len(names), len(set(names)), f'{p}: duplicate names')
                for kind, name, w in cheats:
                    self.assertLessEqual(len(w), 0x100, name)
                    i = 0
                    while i < len(w): i += op_len(w[i], w[i:])
                    self.assertEqual(i, len(w), f'{name}: the last instruction is cut short')
                # a Switch turns every cheat of a new file on (dmnt_cheats_enabled_by_default) and runs them as one program of at most 0x400 words
                self.assertLessEqual(sum(len(e[2]) for e in cheats), 0x400, f'{p}: all cheats on are over the 0x400 words of one program')
        self.assertGreaterEqual(n, 2)

    def test_bad_names_refused(self):
        g = game.load('0400486024DEC000')
        g.rows.append(('cheats', ['cheats/1111111111111111.txt']))
        with self.assertRaisesRegex(ValueError, 'none of the profile'): g.cheats()
        g.rows[-1] = ('cheats', ['../game.tsv'])
        with self.assertRaisesRegex(ValueError, 'profile folder|build ID'): g.cheats()


class Ocarina(unittest.TestCase):
    MGR, ARRAY, STRIDE, VALUE = 0x4E842A8, 0xD8, 0x28, 0x1C
    ROWS = {'Infinite Rupees (999)': (962, 999), 'Infinite Arrows (99)': (1006, 99), 'Infinite Bombs (99)': (1007, 99), 'Infinite Bombchus (99)': (1011, 99),
            'Infinite Deku Nuts (99)': (1019, 99), 'Infinite Deku Seeds (99)': (1020, 99), 'Infinite Deku Sticks (99)': (1021, 99),
            'Infinite Magic Beans (99)': (1032, 99), 'Infinite Business Scrub Seeds (99)': (1057, 99), 'Gold Skulltula Tokens (100)': (1065, 100)}
    COPIES = {'Infinite Health (experimental)': (963, 964), 'Infinite Magic (experimental)': (965, 967)}   # value row, the row it is set to

    @classmethod
    def setUpClass(cls):
        g = game.load('0400486024DEC000')
        (cls.path,) = g.cheats()
        cls.main = next(b['main'] for b in g.builds().values())
        cls.entries = parse(cls.path.read_text(encoding='utf-8'))

    def off(self, row): return row * self.STRIDE + self.VALUE

    def test_build(self):
        self.assertEqual(self.path.name, self.main[:16].upper() + '.txt')

    def test_labels_credit_and_sections(self):
        labels = [e[1] for e in self.entries if not e[2]]
        self.assertTrue(any('NX2navi-Trainer' in l and 'MIT' in l for l in labels), labels)
        self.assertIn('--SectionStart:Ammo--', labels)

    def test_values(self):
        cheats = {e[1]: e[2] for e in self.entries if e[2]}
        self.assertEqual(set(cheats), set(self.ROWS) | set(self.COPIES))
        chain = [0x580F0000, self.MGR, 0x580F1000, self.ARRAY]   # RF = [main + manager slot]; RF = [RF + 0xd8]
        for name, (row, value) in self.ROWS.items():
            self.assertEqual(cheats[name], chain + [0x780F0000, self.off(row), 0x640F0000, 0, value], name)   # RF += offset; u32 [RF] = value
            self.assertTrue(0 <= value <= 999, name)
        for name, (row, src) in self.COPIES.items():   # RF += offset; RE = u32 [RF + (source - row)]; u32 [RF] = RE
            self.assertEqual(cheats[name], chain + [0x780F0000, self.off(row), 0x540E2F00, self.off(src) - self.off(row), 0xA4EF0000], name)
        self.assertTrue(all('(experimental)' in n for n in self.COPIES))


class FortunesWeave(unittest.TestCase):
    """Fire Emblem Fortune's Weave's file (its README "Cheats"): main build db93b012…'s singletons, the unit loop over the pool's current block and its
    force filter, the Unit / PersonData offsets and the values each cheat writes. The programs are spelled out from these constants, opcode by opcode"""
    POOL, BLOCK, UNITS, STRIDE = 0x220A350, 0x38, 250, 0x8B0         # [main+POOL] UnitPool*, [pool+0x38] Unit[250]
    DESC, FORCE_ID = 0x1B8, 0x10                                       # [Unit+0x1B8] force descriptor, its u8 id at +0x10
    GUD, GOLD, GOLD_MAX = 0x220AB10, 0x48, 9999999                     # [main+GUD]+0x48 s32 gold, the game's own clamp 0x98967F
    PD, GAINED, CAP, NSTATS = 0x188, 0x1D4, 0x232, 9                   # Unit+0x188 PersonData*; s16 gained at Unit+0x1D4+2k, cap at PD+0x232+2k
    LEVEL, EXP, LV_TABLE, LV_MAX = 0x1F2, 0x1F8, 0x2219A18, 99          # u8 level, u32 total EXP; the level EXP table (vector) and the game's 99
    WEXP, WRANK, WMASK, WX_TABLE, WX_MAX, NSLOTS = 0x202, 0x21C, 0x262, 0x2219A58, 12, 13   # u16 EXP / u8 rank per slot, PD's u16 slot mask

    @classmethod
    def setUpClass(cls):
        g = game.load('04007EF01F8AE000')
        (cls.path,) = g.cheats()
        cls.main = next(b['main'] for b in g.builds().values())
        cls.entries = parse(cls.path.read_text(encoding='utf-8'))
        cls.cheats = {e[1]: e[2] for e in cls.entries if e[2]}

    def party(self, setup, body):
        """RF = the pool's current block; [setup]; loop 250: R1 = descriptor, R2 = its id (0xFF when unreadable), R7 = player (id 0 or 4..7); if R7: [body]; RF += stride"""
        end = 0x20000000
        return ([0x580F0000, self.POOL, 0x580F1000, self.BLOCK, 0xC086F400, 0, 0] + setup +
                [0x300E0000, self.UNITS, 0xC1010030, 0x58012F00, self.DESC, 0xC1070030,
                 0xC0861400, 0, 0, 0x40020000, 0, 0xFF, 0x51022100, self.FORCE_ID,
                 0xC0152400, 0, 0x40070000, 0, 1, end,
                 0xC0122400, 4, 0xC0142400, 7, 0x40070000, 0, 1, end, end,
                 end, 0xC0457400, 1] + body + [end, 0x780F0000, self.STRIDE, 0x310E0000] + ([end] if setup else []) + [end])

    def table(self, begin, cap):
        """R4 = begin, R5 = min((end - begin) / 4, cap); if R5: R6 = u32 table[R5 - 1] (the block stays open)"""
        return [0x58040000, begin, 0x58050000, begin + 8, 0x98155040, 0x78054000, 2, 0xC0815400, 0, cap, 0x40050000, 0, cap, 0x20000000,
                0xC0865400, 0, 0, 0x98185100, 0, 1, 0x78083000, 2, 0x98088040, 0x54062800, 0]

    def test_build(self):
        self.assertEqual(self.path.name, self.main[:16].upper() + '.txt')
        self.assertEqual(self.path.name, 'DB93B0125F485060.txt')

    def test_names_and_status(self):
        self.assertEqual(set(self.cheats), {'Gold 9,999,999', 'Party: all stats at cap', 'Party: max level and EXP (experimental)',
                                            'Party: max weapon/class EXP (experimental)'})
        labels = [e[1] for e in self.entries if not e[2]]
        self.assertIn('--SectionStart:Party--', labels)
        for want in ('Gold: confirmed by code', 'Stats: confirmed by code'): self.assertIn(want, labels)
        self.assertEqual(sum(l.startswith(('Level: unverified (open:', 'Weapon EXP: unverified (open:')) for l in labels), 2, labels)

    def test_gold(self):
        self.assertEqual(self.cheats['Gold 9,999,999'], [0x580F0000, self.GUD, 0xC086F400, 0, 0, 0x780F0000, self.GOLD, 0x640F0000, 0, self.GOLD_MAX, 0x20000000])
        self.assertEqual(self.GOLD_MAX, 0x98967F)

    def test_stats(self):
        body = [0x58032F00, self.PD, 0xC0863400, 0, 0]
        for k in range(self.NSTATS): body += [0x52062300, self.CAP + 2 * k, 0xA26F0200, self.GAINED + 2 * k]   # s16 gained[k] = PD cap[k]
        self.assertEqual(self.cheats['Party: all stats at cap'], self.party([], body + [0x20000000]))
        self.assertLess(self.GAINED + 2 * self.NSTATS, 0x1EC)   # stops before current HP

    def test_level(self):
        self.assertEqual(self.cheats['Party: max level and EXP (experimental)'],
                         self.party(self.table(self.LV_TABLE, self.LV_MAX), [0xA15F0200, self.LEVEL, 0xA46F0200, self.EXP]))

    def test_weapon_exp(self):
        body = [0x58032F00, self.PD, 0xC0863400, 0, 0, 0xC1090030, 0x52092300, self.WMASK,   # R9 = PD's slot mask
                0x980AF100, 0, self.WEXP, 0x980BF100, 0, self.WRANK, 0x300D0000, self.NSLOTS,    # RA = &EXP[0], RB = &rank[0]; 13 slots
                0x945C9100, 1, 0xC046C400, 0, 0xA26A0000, 0xA15B0000, 0x20000000,               # if mask bit: u16 EXP = R6, u8 rank = R5
                0x780A0000, 2, 0x780B0000, 1, 0x78094000, 1, 0x310D0000, 0x20000000]
        self.assertEqual(self.cheats['Party: max weapon/class EXP (experimental)'], self.party(self.table(self.WX_TABLE, self.WX_MAX), body))
        self.assertEqual(self.WEXP + 2 * (self.NSLOTS - 1), 0x21A)
        self.assertEqual(self.WRANK, self.WEXP + 2 * self.NSLOTS)


if __name__ == '__main__':
    unittest.main()
