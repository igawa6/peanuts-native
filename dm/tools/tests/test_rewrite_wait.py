"""PEANUTS (issue #120): dm/gfx/tools/rewrite_wait.py, where the split waits for the rewrite beside it before the game's own decoder needs the rewritten
modules: no wait without PN_REWRITE_BESIDE_DONE, a wait until the app writes the rewrite's exit code there, and a stop naming the rewrite when it failed."""
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[3] / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import rewrite_wait  # noqa: E402


class Wait(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='rewrite_wait_'))
        self.done = self.tmp / 'rewrite-beside.done'
        self.old = os.environ.get('PN_REWRITE_BESIDE_DONE')

    def tearDown(self):
        if self.old is None: os.environ.pop('PN_REWRITE_BESIDE_DONE', None)
        else: os.environ['PN_REWRITE_BESIDE_DONE'] = self.old

    def test_no_rewrite_beside_no_wait(self):
        os.environ['PN_REWRITE_BESIDE_DONE'] = ''
        t0 = time.monotonic(); rewrite_wait.wait(); self.assertLess(time.monotonic() - t0, 0.2)

    def test_waits_for_the_exit_code(self):
        os.environ['PN_REWRITE_BESIDE_DONE'] = str(self.done)
        threading.Timer(0.8, lambda: self.done.write_text('0\n')).start()
        t0 = time.monotonic(); rewrite_wait.wait(); self.assertGreater(time.monotonic() - t0, 0.6)

    def test_a_failed_rewrite_stops_the_split(self):
        os.environ['PN_REWRITE_BESIDE_DONE'] = str(self.done)
        self.done.write_text('1\n')
        with self.assertRaises(SystemExit) as e: rewrite_wait.wait()
        self.assertIn('rewrite beside the split failed', str(e.exception.code))

    def test_both_decoders_wait(self):
        self.assertIn('rewrite_wait.wait()', (TOOLS / 'ue_ondevice.py').read_text(encoding='utf-8'))
        self.assertIn('rewrite_wait.wait()', (TOOLS / 'shader_split.py').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
