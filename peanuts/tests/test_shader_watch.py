"""Smoother next start (the quick menu's Peanuts tab, dev.peanuts.shaders.ShaderWatch): its backend dev.peanuts.shaders.HostWatch, the host's numbers
(dm/gfx/vk_android/vka_pso.c vka_pso_watch, dm/peanuts/peanuts_shaderwatch.c) and learning on (vka_pso.c sw_*, dm/peanuts/peanuts_swhot.h): what the runs of a
game used, kept per game and driver, made first and before the game starts at the next start; the transform feedback capture pipelines too (vka_pso.c sw_tf_*,
dm/peanuts/peanuts_swtf.h, dm/gfx/vk_android/vka_tf.c vka_tf_learned). The JVM part is skipped without a JDK, the C part without a C
compiler (dm/host/tests/Makefile peanuts-swhot runs it too)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src/main/java'
WATCH = JAVA / 'dev/peanuts/shaders/HostWatch.java'
COUNTS = JAVA / 'dev/peanuts/shaders/HostCounts.java'
CONTRACT = JAVA / 'dev/peanuts/shaders/ShaderWatch.java'
BRIDGE = JAVA / 'dev/peanuts/Bridge.java'
GAME = JAVA / 'dev/nxnative/android/GameActivity.java'
JVM_TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/shaders/HostCountsJvmTest.java'
PSO = REPO / 'dm/gfx/vk_android/vka_pso.c'
JNI = REPO / 'dm/peanuts/peanuts_shaderwatch.c'
HOT = REPO / 'dm/peanuts/peanuts_swhot.h'
SWTF = REPO / 'dm/peanuts/peanuts_swtf.h'
TF = REPO / 'dm/gfx/vk_android/vka_tf.c'
C_TEST = REPO / 'dm/host/tests/t_peanuts_swhot.c'
C_TEST_TF = REPO / 'dm/host/tests/t_peanuts_swtf.c'
CMAKE = REPO / 'android/host/CMakeLists.txt'


def read(p): return p.read_text(encoding='utf-8')


def body(src, head):
    """The braces' body of the definition that starts with head (a declaration of it, ending in ';' first, is passed over)"""
    i = src.index(head)
    while True:
        semi, brace = src.find(';', i), src.find('{', i)
        if brace >= 0 and (semi < 0 or brace < semi): break
        i = src.index(head, i + 1)
    j = brace
    depth = 0
    for k in range(j, len(src)):
        if src[k] == '{': depth += 1
        elif src[k] == '}':
            depth -= 1
            if not depth: return src[j:k + 1]
    raise AssertionError('no body after ' + head)


class Wiring(unittest.TestCase):
    def test_one_hook_in_the_game_activity(self):
        s = read(GAME)
        self.assertEqual(s.count('dev.peanuts.shaders.HostWatch.env(this, id, env);'), 1)
        line = [l for l in s.splitlines() if 'HostWatch.env(' in l][0]
        self.assertIn('// PEANUTS', line)
        settings = s[s.index('private String settingsEnv('):]
        self.assertLess(settings.index('HostWatch.env('), settings.index('\n    }\n'))

    def test_the_backend_registers_and_learns_only_when_on(self):
        env = body(read(WATCH), 'public static void env(')
        self.assertIn('ShaderWatch.backend(INSTANCE);', env)
        self.assertIn('ShaderWatch.on(a, titleId)', env)
        self.assertIn('!dev.peanuts.build.ShaderCapture.active(a)', env)   # a preparation boot of Build is no play
        self.assertIn('env.removeIf(s -> s.startsWith("PN_SHADERWATCH="))', env)
        self.assertIn('static final String LINE = "PN_SHADERWATCH=1";', read(WATCH))
        self.assertIn('Bridge.shaderWatchLearnSafe(on)', body(read(WATCH), 'public void learning('))

    def test_the_contract_is_kept(self):
        c = read(CONTRACT)
        self.assertIn('public interface Backend', c)
        w = read(WATCH)
        self.assertIn('implements ShaderWatch.Backend', w)
        for m in ('boolean available(android.content.Context c, String titleId)', 'int learned()', 'int ready(android.content.Context c, String titleId)',
                  'void learning(android.content.Context c, String titleId, boolean on)'):
            self.assertIn(m, w)
        self.assertIn('static final int UNKNOWN = -1;', read(COUNTS))
        self.assertIn('public static final int UNKNOWN = -1;', c)

    def test_jni_names_match(self):
        b, j = read(BRIDGE), read(JNI)
        self.assertIn('public static native boolean shaderWatch(int[] out);', b)
        self.assertIn('public static native boolean shaderWatchLearn(boolean on);', b)
        self.assertIn('Java_dev_peanuts_Bridge_shaderWatch(JNIEnv *env, jclass cls, jintArray out)', j)
        self.assertIn('Java_dev_peanuts_Bridge_shaderWatchLearn(JNIEnv *env, jclass cls, jboolean on)', j)
        self.assertIn('void vka_pso_watch(int out[4]) __attribute__((weak));', j)
        self.assertIn('void vka_pso_watch_learn(int on) __attribute__((weak));', j)
        self.assertIn('${ROOT}/dm/peanuts/peanuts_shaderwatch.c', read(CMAKE))


class OffMeansOff(unittest.TestCase):
    """Learning off: nothing of Smoother next start is read, written, ordered or waited for; the list and the prewarm are upstream's"""

    def setUp(self): self.s = read(PSO)

    def test_the_switch_is_the_environment(self):
        on = body(self.s, 'static int sw_on(void)')
        self.assertIn('getenv("PN_SHADERWATCH")', on)
        self.assertIn('!strcmp(e, "1")', on)

    def test_every_step_asks_the_switch(self):
        for head in ('static void sw_load(void)', 'static void sw_save(void)', 'static uint32_t sw_order('):
            b = body(self.s, head)
            first = b.split('\n')[1] if '\n' in b else b   # the first statement (a one-line body is its own)
            self.assertIn('sw_on()', first, head + ' must return first when learning is off')
        learn = body(self.s, 'void vka_pso_watch_learn(int on)')
        self.assertLess(learn.index('sw_load_now()'), learn.index('atomic_store(&g_sw_on, on ? 1 : 0);'))   # the runs before's file first: no save merges without it
        self.assertIn('if (!on) atomic_store(&g_sw_dirty, 0);', learn)
        self.assertIn('if (h && sw_on()) sw_run_add(h, 0);', body(self.s, 'static void sw_used(gval *g)'))
        self.assertIn('if (sw_on()) sw_run_add(h, 1);', body(self.s, 'static void pw_note('))
        self.assertIn('t_sw_h = !bad && sw_on() ? vka_pw_hash(&r, kb) : 0;', self.s)
        start = body(self.s, 'static void pw_start(int wait)')
        self.assertIn('sw_on() && g_sw_oldn', start)          # a driver that does not prewarm makes nothing without it
        self.assertIn('if (sw_on()) g_pw_limit = g_pw_load_n;', start)
        launch = body(self.s, 'void vka_pso_prepare_launch(void)')
        self.assertIn('int sw = sw_on();', launch)
        self.assertIn('(seed || sw)', launch)
        self.assertIn('(!V.qcom_old || enabled || sw)', launch)

    def test_an_explicit_prewarm_off_wins(self):
        start = body(self.s, 'static void pw_start(int wait)')
        self.assertIn('nt <= 0 && !getenv("DM_VK_PREWARM")', start)
        self.assertIn('(!enabled || atoi(enabled) > 0)', body(self.s, 'void vka_pso_prepare_launch(void)'))

    def test_no_cost_on_the_frame_path(self):
        # the lookups test one field of an entry they hold already; the record's hash is made by the prewarm thread, never by a draw
        g = body(self.s, 'static gval *gfx_obtain(const gkey_full *fk')
        self.assertIn('if (!prewarm && g->swh) sw_used(g);', g)
        self.assertNotIn('vka_pw_hash', g)
        c = body(self.s, 'static VkPipeline cs_obtain(vka_shader *cs')
        self.assertIn('if (!prewarm && g->swh) sw_used(g);', c)
        self.assertNotIn('vka_pw_hash', c)

    def test_the_switch_has_its_row(self):
        # docs/env_switches.tsv: the fork's switch, kept while vka_pso.c reads it (env_switches.py fork_rows); env-only until the Peanuts tab is open
        rows = [l.split('\t') for l in read(REPO / 'docs/env_switches.tsv').split('\n')]
        row = [r for r in rows if r[0] == 'PN_SHADERWATCH']
        self.assertEqual(len(row), 1)
        self.assertEqual(row[0][1], 'switch')
        self.assertIn('DM_VK_PREWARM_MEM_MB', row[0][2])
        self.assertIn('Extra environment', row[0][2])

    def test_the_memory_floor_never_waits(self):
        # on a driver that does not prewarm every thread stops below the floor, before the wait for the others (which would wait for a thread that stopped)
        m = body(self.s, 'static void *pw_main(void *a)')
        floor = m.index('if (g_sw_only && mem_mb) {')
        wait = m.index('if (me > 0 && mem_mb && !g_sw_only) {')
        self.assertLess(floor, wait)
        self.assertIn('break;', m[floor:wait])

    def test_the_start_counts_only_what_it_waits_for(self):
        self.assertIn('for (;; sw_step(&sw_cur)) {', self.s)
        self.assertIn('if (atomic_load(&g_pw_counting)) atomic_fetch_add(&g_pw_processed, 1);', body(self.s, 'static void sw_step(uint32_t *cur)'))
        self.assertIn('atomic_store(&g_pw_counting, 0); atomic_store(&g_sw_wait, 0);', body(self.s, 'static void sw_wait_hot(void)'))

    def test_bounded(self):
        h = read(HOT)
        self.assertIn('#define PN_SW_MAX 4096', h)
        self.assertIn('#define PN_SW_AGE_MAX 8', h)
        self.assertIn('if (cap > PN_SW_MAX) cap = PN_SW_MAX;', body(self.s, 'static void sw_save(void)'))
        self.assertIn('if (h.n > PN_SW_MAX) return', h)
        tf = read(SWTF)
        self.assertIn('#define PN_SWTF_MAX 64', tf)
        self.assertIn('if (h.n > PN_SWTF_MAX) return', tf)
        self.assertIn('n = pn_swtf_keep(g_sw_tfr, g_sw_tfrn, g_sw_tfl, g_sw_tfln, keep, kn, e, PN_SWTF_MAX);', self.s)

    def test_per_driver_file(self):
        p = body(self.s, 'static void sw_path(void)')
        self.assertIn('vka_driver_tag()', p)
        self.assertIn('"%s.%s.hot", g_pw_path, tag ? tag : "system"', p)

    def test_capture_pipelines_only_when_learning(self):
        # the list never takes a capture variant's pipeline: with learning off pw_note returns at it as before, with learning on sw_tf_note keeps it
        self.assertIn('if (!i && kind == VKA_PW_GFX && vka_tf_is_variant(sh[0])) { sw_tf_note(sh, kb, klen); return; }', body(self.s, 'static void pw_note('))
        note = body(self.s, 'static void sw_tf_note(vka_shader *const sh[5], const void *kb, uint32_t klen)')
        self.assertIn('if (!sw_on()', note.splitlines()[1])
        self.assertIn('if (!pn_swtf_ok(&e)) return;', note)
        start = body(self.s, 'static void pw_start(int wait)')
        self.assertIn('g_sw_tf_n = sw_on() && V.xfb ? (uint32_t)g_sw_tfln : 0;', start)
        self.assertIn('g_sw_hot_n += g_sw_tf_n;', start)   # made first and waited for, as the runs' other pipelines
        self.assertIn('if (g_sw_tf_n && !atomic_load(&g_pp_skip))', body(self.s, 'static void *pw_main(void *a)'))
        self.assertIn('else sw_tf_save(', body(self.s, 'static void sw_save(void)'))   # written only after the .hot file, with the entries it keeps
        self.assertIn('"%s.%s.tf", g_pw_path, tag ? tag : "system"', body(self.s, 'static void sw_path(void)'))

    def test_a_capture_made_ahead_is_checked_at_its_first_draw(self):
        t = read(TF)
        self.assertIn('if (g_var[i].learned && !g_var[i].checked && !learned_holds(i)) continue;', body(t, 'static vka_shader *variant('))
        self.assertIn('int ok = pn_swtf_holds(st, &lay, &g_var[i].L);', body(t, 'static int learned_holds(int i)'))   # t_peanuts_swtf.c: layouts that differ
        self.assertIn('return st == 1 && run && made && !memcmp(run, made, sizeof *made); }', read(SWTF))
        self.assertIn('if (ok) g_var[i].checked = 1; else g_var[i].dead = 1;', body(t, 'static int learned_holds(int i)'))
        learned = body(t, 'vka_shader *vka_tf_learned(vka_shader *s, const vka_tf_layout *lay)')
        self.assertIn('if (!V.xfb || !s || !s->e) return NULL;', learned.splitlines()[1])
        self.assertEqual(t.count('.learned = 1;'), 1)   # only vka_tf_learned makes one
        self.assertIn('!g_var[i].dead', body(t, 'void vka_tf_prep('))

    def test_marked(self):
        # the functions sit in one PEANUTS block; every other line that uses them carries a PEANUTS marker or sits in a PEANUTS span
        a = self.s.index('// PEANUTS begin: Smoother next start, learning on')
        b = self.s.index('// PEANUTS end', a)
        block = self.s[a:b]
        for f in ('static int sw_on(void) {', 'static void sw_save(void) {', 'static uint32_t sw_order(', 'void vka_pso_watch_learn(int on) {'):
            self.assertIn(f, block)
        lines = self.s.splitlines()
        first = self.s[:a].count('\n')
        last = first + block.count('\n')
        span = False
        for n, line in enumerate(lines):
            if '// PEANUTS begin' in line: span = True
            if '// PEANUTS end' in line: span = False
            if first <= n <= last or line.lstrip().startswith('//'): continue
            if re.search(r'\bsw_(on|load|save|path|order|step|used|run_add|wait_hot|tf_\w+)\(|g_sw_wait|g_sw_hot|g_sw_dirty|g_sw_tf|t_sw_h', line):
                self.assertTrue(span or 'PEANUTS' in line, line.strip()[:120])


class Voice(unittest.TestCase):
    def test_no_em_dash(self):
        for p in (WATCH, COUNTS, JNI, HOT, SWTF, C_TEST, C_TEST_TF, JVM_TEST):
            self.assertNotIn('—', read(p), p.name)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(COUNTS), str(JVM_TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.shaders.HostCountsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('ok', r.stdout)


CC = shutil.which('cc') or shutil.which('gcc') or shutil.which('clang')


@unittest.skipUnless(CC, 'no C compiler')
class C(unittest.TestCase):
    def test_swhot(self):
        for src, more in ((C_TEST, []), (C_TEST_TF, ['-Wno-unused-function', str(REPO / 'dm/gfx/vk_android/vka_tf_spv.c')])):   # (the layout reader: this run's layouts)
            with tempfile.TemporaryDirectory() as d:
                exe = str(Path(d) / src.stem)
                r = subprocess.run([CC, '-std=gnu11', '-O1', '-Wall', '-Wextra', '-Werror', '-o', exe, str(src)] + more, capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                r = subprocess.run([exe], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                self.assertIn('ok', r.stdout)


if __name__ == '__main__':
    unittest.main()
