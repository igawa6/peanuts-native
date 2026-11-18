"""The NSP script's heap check keys (dm/games/profile_guess.py heap_signals) from a host log's SetHeapSize refusal: the heap region the profile then has holds the request."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'games'))
from profile_guess import heap_signals  # noqa: E402


def heap_region(g):   # hle_svc.c mem_init_once: block - 256 MB - alias - stack
    return int(g['mem_reserve'], 0) - 0x10000000 - int(g['mem_alias'], 0) - int(g['mem_stack'], 0)


class HeapSignals(unittest.TestCase):
    def apply(self, g, log):
        return {**g, **{k: v[0] for k, v in heap_signals(log, g).items()}}

    def test_heap_beside_the_alias_region(self):   # the request fits in the block: the alias region gives up the rest (Tears of the Kingdom)
        g = {'mem_reserve': '0x140000000', 'mem_alias': '0xb8000000', 'mem_stack': '0x10000000', 'app_pool': '0xCD500000'}
        out = self.apply(g, '[dm] SetHeapSize OOM: req 0x86000000 > cap 0x68000000\n')
        self.assertEqual(out['mem_reserve'], '0x140000000')
        self.assertGreaterEqual(heap_region(out), 0x86000000)

    def test_heap_larger_than_the_block(self):   # Super Mario Bros. Wonder's program 1: a 4.94 GB heap beside a 4.375 GB alias region, the block grows
        g = {'mem_reserve': '0x140000000', 'mem_alias': '0x118000000', 'mem_stack': '0x10000000', 'app_pool': '0x240000000', 'sys_resource': '0x1000000'}
        out = self.apply(g, '[dm] SetHeapSize OOM: req 0x13be00000 > cap 0x8000000\n')
        self.assertEqual(out['mem_alias'], '0x118000000')
        self.assertGreaterEqual(heap_region(out), 0x13be00000)
        self.assertEqual(out['mem_reserve'], '0x278000000')


if __name__ == '__main__':
    unittest.main()
