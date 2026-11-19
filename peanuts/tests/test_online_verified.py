#!/usr/bin/env python3
"""The game builds online play is verified on are one list in two places: the host's g_verified (dm/peanuts/net/pn_net.c), which keeps every other
build offline, and the launcher's Nextendo.VERIFIED (android/app/src/main/java/dev/peanuts/online/Nextendo.java), which hands PN_ONLINE and the account
to a verified build's runs only. A build added to one and not the other would get the account without online play, or the other way round."""
import os, re, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))


def entries(path, start, end):
    s = open(os.path.join(ROOT, path), encoding='utf-8').read()
    body = s[s.index(start):]
    body = body[:body.index(end)]
    return re.findall(r'\{\s*"([0-9a-f]{32})"\s*,\s*"([^"]+)"\s*\}', body)


class Verified(unittest.TestCase):
    def test_host_and_launcher_agree(self):
        host = entries('dm/peanuts/net/pn_net.c', 'g_verified[] = {', '};')
        launcher = entries('android/app/src/main/java/dev/peanuts/online/Nextendo.java', 'VERIFIED = {', '};')
        self.assertTrue(host, 'no verified build in pn_net.c')
        self.assertEqual(host, launcher)

    def test_each_is_a_build_of_its_game_profile(self):
        builds = set()
        games = os.path.join(ROOT, 'dm/games')
        for g in os.listdir(games):
            t = os.path.join(games, g, 'game.tsv')
            if os.path.isfile(t):
                builds |= set(re.findall(r'^build\t.*\bmain=([0-9a-f]{32})', open(t, encoding='utf-8').read(), re.M))
        for main, what in entries('dm/peanuts/net/pn_net.c', 'g_verified[] = {', '};'):
            self.assertIn(main, builds, f'{what}: main {main} is no build row of any profile')


if __name__ == '__main__':
    unittest.main()
