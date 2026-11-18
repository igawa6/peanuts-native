"""Automatic defaults by driver (dm/gfx/vk_android/vka_auto.h): every VKA_ENV / VKA_ENVF default in dm/gfx/vk_android that reads the driver (driver_id, qcom_old) has a rule
there, so the log prints its "[auto]" line; every rule's switch is one the host reads; and its docs/advanced_settings.tsv row says in its `default` text which driver it
depends on (the player-facing side of the same rule)."""
from pathlib import Path
import re
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import advanced_switches as A

VKA = REPO / 'dm/gfx/vk_android'
DRIVER = re.compile(r'\b(driver_id|qcom_old|driverID)\b')
DRIVER_WORDS = {'VKA_DRV_QCOM': "Qualcomm", 'VKA_DRV_TURNIP': 'Turnip'}


def env_calls(text):
    """(key, default expression) of each VKA_ENV / VKA_ENVF call, the default up to the call's closing parenthesis"""
    for m in re.finditer(r'VKA_ENVF?\(\s*"(DM_VK_[A-Z0-9_]+)"\s*,', text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {'(': 1, ')': -1}.get(text[i], 0)
            i += 1
        yield m.group(1), text[m.end():i - 1]


def rules():
    """{key: driver word} of the rules in vka_auto.h"""
    src = (VKA / 'vka_auto.h').read_text()
    body = src[src.index('static inline int vka_auto_rules'):src.index('#undef VKA_AUTO_ADD')]
    out = {}
    for m in re.finditer(r'VKA_AUTO_ADD\((.*?),\s*"(DM_VK_[A-Z0-9_]+)"', body):
        cond = m.group(1)
        word = 'Turnip' if re.search(r'\bt\b', cond) else "Qualcomm" if re.search(r'\bq\b|qcom_old', cond) else None
        out[m.group(2)] = word
    return out


class AutoDefaults(unittest.TestCase):
    def test_every_driver_default_has_a_rule(self):
        r = rules()
        self.assertGreater(len(r), 5)
        missing = []
        for p in sorted(VKA.glob('*.[ch]')):
            if p.name in ('vka.h', 'vka_auto.h'): continue
            for key, dflt in env_calls(p.read_text()):
                if DRIVER.search(dflt) and key not in r: missing.append(f'{p.name}: {key}')
        self.assertEqual(missing, [], 'a default by driver without a vka_auto.h rule (no [auto] line in the log)')

    def test_rules_name_their_driver_and_read_switches(self):
        src = '\n'.join(p.read_text() for p in VKA.glob('*.c'))
        rows = {x['name']: x for x in A.load()}
        bad = []
        for key, word in rules().items():
            if word is None: bad.append(f'{key}: rule keys on no driver')
            if f'"{key}"' not in src: bad.append(f'{key}: no vk_android source reads it')
            row = rows.get(key)
            if not row: bad.append(f'{key}: no docs/advanced_settings.tsv row'); continue
            if word and word.lower() not in row['default'].lower(): bad.append(f'{key}: advanced_settings.tsv default text does not name {word} ({row["default"]!r})')
        self.assertEqual(bad, [])

    def test_env_calls_parser(self):
        t = 'x = VKA_ENV("DM_VK_A", f(V.driver_id == 1)) + VKA_ENVF("DM_VK_B", 2.0);'
        self.assertEqual(list(env_calls(t)), [('DM_VK_A', ' f(V.driver_id == 1)'), ('DM_VK_B', ' 2.0')])


if __name__ == '__main__':
    unittest.main()
