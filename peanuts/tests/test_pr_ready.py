"""peanuts/scripts/pr_ready.py: the mechanical rules a pull request is sent back for, checked on made-up repositories (no game data)."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'peanuts/scripts/pr_ready.py'
NOREPLY = '1+tester@users.noreply.github.com'
EM = chr(0x2014)   # spelled out: the test itself stays clean


@unittest.skipUnless(shutil.which('git'), 'needs git')
class PrReady(unittest.TestCase):
    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'core.autocrlf', 'false')   # keep the CRLF a test commits
        self.write('README.md', '# x\n\nintro\n\n<!-- TABLE_BEGIN -->\n| a |\n<!-- TABLE_END -->\n\n## Credits and license\n\nsomeone\n')
        self.write('docs/env_switches.tsv', 'name\tclass\tmeaning\nDM_OLD\tswitch\told\n')
        self.write('dm/gfx/vk_android/vka_draw.c', 'int a;\n')
        self.commit('base')
        self.git('branch', 'base')
        self.git('checkout', '-q', '-b', 'pr')

    def git(self, *a, email=NOREPLY):
        env = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_COMMITTER_NAME='t', GIT_AUTHOR_EMAIL=email, GIT_COMMITTER_EMAIL=email)
        return subprocess.run(['git', *a], cwd=self.repo, check=True, capture_output=True, text=True, env=env).stdout.strip()

    def write(self, path, text, mode='w'):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, mode, encoding='utf-8', newline='\n') as f: f.write(text)

    def commit(self, msg, email=NOREPLY):
        self.git('add', '-A'); self.git('commit', '-q', '-m', msg, email=email)

    def run_it(self, base='base', head='HEAD'):
        args = [head] if base is None else ['--base', base, head]
        r = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.repo, capture_output=True, text=True, encoding='utf-8')
        must = r.stdout.split('must fix', 1)[1].split('warnings', 1)[0].split('not checked', 1)[0] if 'must fix' in r.stdout else ''
        return r.returncode, must, r.stdout

    def test_clean_branch_passes(self):
        self.write('dm/gfx/vk_android/vka_draw.c', 'int a;\n// a comment\n')
        self.write('docs/reference/rendering.md', 'an entry\n')
        self.commit('a clean change')
        code, must, out = self.run_it()
        self.assertEqual(code, 0, out)
        self.assertIn('nothing to fix', out)
        self.assertIn('scope:', out)   # every-game code is a warning, not a failure

    def test_base_behind_stacked_and_merges(self):
        self.write('a.txt', 'other pr\n'); self.commit('another PR')
        self.git('branch', 'other')
        self.write('b.txt', 'mine\n'); self.commit('mine')
        self.git('checkout', '-q', 'base'); self.write('c.txt', 'main moved\n'); self.commit('main moved'); self.git('checkout', '-q', 'pr')
        code, must, out = self.run_it()
        self.assertEqual(code, 1, out)
        self.assertNotIn('behind', must)
        self.assertIn('1 commits behind base (merge-base', out)   # behind without a conflict: a warning
        self.assertIn('built on other', must)
        self.git('checkout', '-q', 'base'); self.write('b.txt', 'theirs\n'); self.commit('main took b.txt'); self.git('checkout', '-q', 'pr')
        self.assertIn('2 commits behind base', self.run_it()[1])
        self.git('checkout', '-q', '-b', 'pr3', 'other'); self.git('merge', '-q', '--no-ff', '--no-edit', 'base~1')   # main merged in, not rebased
        self.assertIn('merge commit', self.run_it('base~1')[1])

    def test_base_defaults_to_dev(self):
        self.assertEqual(self.run_it(None)[0], 2)   # no dev branch: name the base
        self.git('checkout', '-q', '-b', 'dev-0.1.10', 'base'); self.write('d.txt', 'dev\n'); self.commit('dev line', email='x@example.org')
        self.git('branch', 'dev')
        self.write('d2.txt', 'dev\n'); self.commit('the cycle line moved past dev')
        self.git('checkout', '-q', '-b', 'pr2'); self.write('e.txt', 'x\n'); self.commit('on the dev line')
        code, must, out = self.run_it(None)
        self.assertIn('against dev (', out)
        self.assertEqual(code, 0, out)   # the dev line's commits and its own authors are not this PR's
        self.assertNotIn('x@example.org', out)

    def test_size_cap(self):
        self.write('dm/games/0100ACD0163D0000/shader_map.tsv', 'k\n' * 200_001)
        self.write('dm/games/0100ACD0163D0000/README.md', 'note\n')
        self.commit('a huge table')
        self.assertIn('200,001 rows', self.run_it()[1])
        self.git('branch', '-f', 'base')   # already over the cap at the merge-base: a warning
        self.write('dm/games/0100ACD0163D0000/shader_map.tsv', 'k\n' * 200_002); self.commit('regenerated')
        code, must, out = self.run_it()
        self.assertEqual(code, 0, out)
        self.assertIn('as it was at the merge-base', out)

    def test_crlf_in_profiles(self):
        self.write('dm/games/0100ACD0163D0000/game.tsv', 'option\tx\t1\r\n')
        self.write('docs/note.txt', 'fine\r\n')
        self.commit('crlf')
        must = self.run_it()[1]
        self.assertIn('game.tsv: CRLF', must)
        self.assertNotIn('note.txt', must)

    def test_readiness_and_every_game_paths(self):
        self.write('dm/games/0100ACD0163D0000/readiness.tsv', 'status\tplays\n')
        self.write('dm/tools/patch_nso.py', 'x = 1\n')
        self.write('scripts/pack_android.py', 'x = 1\n')
        self.write('android/app/src/main/java/dev/nxnative/android/Prepare.java', 'class P {}\n')
        self.write('android/app/build.gradle', 'x\n')
        self.commit('profile with every-game code')
        _, must, out = self.run_it()
        self.assertIn('readiness.tsv: generated or maintainer-owned', must)
        self.assertIn('every-game code', must)
        for p in ('dm/tools/patch_nso.py', 'scripts/pack_android.py', 'Prepare.java', 'build.gradle'):
            self.assertIn(p, out)

    def test_shader_split_reader_beside_its_profile(self):
        self.write('dm/gfx/tools/shader_split.py', 'MAGIC = 1\n')
        self.write('dm/games/0100ACD0163D0000/game.tsv', 'option\tx\t1\n')
        self.write('dm/games/0100ACD0163D0000/README.md', 'note\n')
        self.commit('a reader and its profile')
        code, must, out = self.run_it()
        self.assertEqual(code, 0, out)
        self.assertIn('split: dm/gfx/tools/shader_split.py with the profile', out)

    def test_binaries_owned_files_and_text(self):
        self.write('dm/games/0100ACD0163D0000/vk_prewarm.bin', 'seed')
        (self.repo / 'blob').write_bytes(b'\0\1\2')
        self.write('peanuts/games.json', '{}\n')
        self.write('.github/ISSUE_TEMPLATE/1_game_problem.yml', 'x: 1\n')
        self.write('dm/third_party/ryujinx/New.cs', 'class N {}\n')
        self.write('README.md', '# x\n\nintro\n\n<!-- TABLE_BEGIN -->\n| a |\n| b |\n<!-- TABLE_END -->\n\n## Credits and license\n\nsomeone\n')
        self.write('dm/host/hle_x.c', f'int b; // one {EM} two\n')
        self.write('dm/host/tests/t_x.c', f'/* rejects {EM} */\n')
        self.commit('everything wrong')
        code, must, out = self.run_it()
        self.assertEqual(code, 1, out)
        for p in ('vk_prewarm.bin', 'blob', 'peanuts/games.json', 'ISSUE_TEMPLATE', 'dm/third_party/ryujinx/New.cs', 'README.md:', 'dm/host/hle_x.c:1: an em dash'):
            self.assertIn(p, must)
        self.assertNotIn('t_x.c', must)   # a test may quote what it rejects: a warning
        self.assertIn('t_x.c:1: an em dash in test', out)

    def test_readme_outside_the_owned_parts(self):
        self.write('README.md', 'a new first line\n' + (self.repo / 'README.md').read_text(encoding='utf-8'))
        self.commit('intro')
        self.assertEqual(self.run_it()[1], '')

    def test_switch_and_every_game_code_with_a_profile(self):
        self.write('docs/env_switches.tsv', 'name\tclass\tmeaning\nDM_NEW\tswitch\tnew\nDM_OLD\tswitch\told, reworded\n')
        self.write('dm/gfx/vk_android/vka_draw.c', 'int a;\nint b = 1;\n')
        self.write('dm/games/0100ACD0163D0000/game.tsv', 'option\tDM_NEW\tx\n')
        self.commit('switch and profile')
        code, must, out = self.run_it()
        self.assertEqual(code, 1, out)
        self.assertIn('new switch DM_NEW with the profile of 0100ACD0163D0000', must)
        self.assertNotIn('DM_OLD', must)   # a reworded row is no new switch
        self.assertIn('every-game code (dm/gfx/vk_android/vka_draw.c) with the profile', must)
        self.assertIn('needs a new test', out)

    def test_author_not_noreply_is_a_warning(self):
        self.write('x.txt', 'x\n'); self.commit('x', email='someone@example.org')
        code, must, out = self.run_it()
        self.assertEqual(code, 0, out)
        self.assertIn('author t <someone@example.org> on 1 commit', out)

    def test_web_flow_committer(self):
        self.write('x.txt', 'x\n'); self.git('add', '-A')
        env = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL=NOREPLY, GIT_COMMITTER_NAME='GitHub', GIT_COMMITTER_EMAIL='noreply@github.com')
        subprocess.run(['git', 'commit', '-q', '-m', 'web editor'], cwd=self.repo, check=True, env=env)
        self.assertNotIn('noreply@github.com', self.run_it()[2])

    @unittest.skipUnless(shutil.which('sh'), 'needs sh')
    def test_folds_in_check_publish(self):
        self.write('peanuts/scripts/check-publish.sh', (REPO / 'peanuts/scripts/check-publish.sh').read_text(encoding='utf-8'))
        self.commit('the gate')
        self.assertEqual(self.run_it()[0], 0)
        self.write('x.txt', 'x\n'); self.git('add', '-A')
        self.git('commit', '-q', '-m', 'x\n\nCo-Authored-By: Copilot <copilot@example.com>')
        code, must, out = self.run_it()
        self.assertEqual(code, 1, out)
        self.assertIn('check-publish.sh', must)
        self.assertIn('AI trailer', must)


if __name__ == '__main__':
    unittest.main()
