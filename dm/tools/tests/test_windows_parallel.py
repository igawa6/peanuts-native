"""PEANUTS(upstream-candidate): the pieces that put the rewriter and the shader build on every thread where there is no fork (Windows), without game files:
nso_code.count_undecodable against the word-by-word count it replaced, data_blocks with its pieces counted elsewhere, and split_pool.PipePool, whose
workers receive their pipes as handles on Windows. Each runs on any system."""
import os, random, struct, sys, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import nso_code, split_pool
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM


def square(x): return x * x


class Undecodable(unittest.TestCase):
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

    def word_by_word(self, img, s, e):   # the count before: one capstone call a word
        return sum(1 for a in range(s, e, 4) if next(self.md.disasm_lite(bytes(img[a:a + 4]), a), None) is None)

    def test_same_count_as_word_by_word(self):
        rnd = random.Random(5)
        code = [0xD503201F, 0xAA0103E0, 0xD65F03C0, 0x91000421, 0xB9400020]   # nop, mov, ret, add, ldr
        for data_share in (0.0, 0.1, 0.5, 0.9, 1.0):
            words = [rnd.getrandbits(32) if rnd.random() < data_share else rnd.choice(code) for _ in range(40000)]   # > 64 KB: several runs of count_undecodable
            img = bytearray(struct.pack(f'<{len(words)}I', *words))
            for s, e in ((0, len(img)), (4, len(img) - 4), (0x1000, 0x1010), (8, 8)):
                self.assertEqual(nso_code.count_undecodable(img, s, e, self.md), self.word_by_word(img, s, e), (data_share, s, e))

    def test_pieces_counted_elsewhere(self):
        rnd = random.Random(9)
        words = [rnd.getrandbits(32) if 0x40000 <= i * 4 < 0x900000 else 0xD503201F for i in range(0xA00000 // 4)]   # data in the middle, longer than a 4 MB piece
        img = bytearray(struct.pack(f'<{len(words)}I', *words))
        seen, fde = [], nso_code.fde_ranges
        nso_code.fde_ranges = lambda img: []   # no unwind entries: the whole text is one gap
        self.addCleanup(setattr, nso_code, 'fde_ranges', fde)
        def mapper(pieces):
            seen.extend(pieces)
            return [nso_code.count_undecodable(img, s, e, self.md) for s, e in pieces]
        self.assertEqual(nso_code.data_blocks(img, len(img), self.md, mapper), nso_code.data_blocks(img, len(img), self.md))
        self.assertTrue(seen and all(e - s <= 0x400000 for s, e in seen))


class Pool(unittest.TestCase):
    def test_pipe_pool_answers_in_order(self):
        with split_pool.PipePool([sys.executable, '-I'], 3, __file__) as pool:
            self.assertEqual(list(pool.map(square, range(50))), [x * x for x in range(50)])

    @unittest.skipUnless(os.name == 'nt', 'Windows')
    def test_windows_takes_no_pass_fds(self):
        with self.assertRaises(ValueError): split_pool.PipePool([sys.executable, '-I'], 1, __file__, pass_fds=(0,))


if __name__ == '__main__': unittest.main()
