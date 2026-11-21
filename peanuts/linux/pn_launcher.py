# Peanuts Native's additions to Banana Native's game window (linux/appimage/launcher.py), loaded by one line that peanuts/linux/build-appimage.sh adds to
# the staged copy (install(globals()) before its main block). Banana Native's file stays as upstream has it; what changes is done here by wrapping its
# functions and Window methods:
#   - .nsp / .xci files under ~/Games (and removable media) are games too. Their title is read with nximport_cli (in the AppImage) and the player's own
#     prod.keys: the file the player chose in the window (Choose prod.keys…, kept in ~/.config/peanutsnative/keys.path), else NX_KEYS, else
#     ~/.switch/prod.keys (Peanuts' builder's default). Nothing searches for, downloads or ships keys.
#   - Prepare of such a game runs Peanuts' builder (peanuts/scripts/peanuts_build.py in the pack tools' source): base + update + DLC files of that title
#     → a .nup in ~/Games/PeanutsNative → the pack, as the Android app's Build does (peanuts/docs/BUILDER.md). Later runs find the .nup and use it.
#   - Only Switch 1 games (title IDs 01…) this AppImage has a host or a pack for are listed (Banana Native game files in ~/Games, Switch 2 titles, are not).
import os, re, subprocess

PROBE_FILE = re.compile(r'^(?P<path>.+)  \((?P<n>\d+) titles?, [0-9.]+s\)$')   # as peanuts_build.py
PROBE_TITLE = re.compile(r'^\s+title (?P<title>[0-9A-Fa-f]{16})\s+app (?P<app>[0-9A-Fa-f]{16})\s+kind (?P<kind>\S+)\s+version (?P<version>\d+)\s+name "(?P<name>.*)"\s+publisher ".*"\s*$')
EXTS = ('.xci', '.nsp')


def install(g):
    APPDIR, CONF, GAMES, PE, PACKS = g['APPDIR'], g['CONF'], g['GAMES'], g['PE'], g['PACKS']
    glob, QMessageBox, QFileDialog, shq, SRC = g['glob'], g['QMessageBox'], g['QFileDialog'], g['shq'], g['SRC']
    Window, hosts = g['Window'], g['hosts']
    IMPORTER = os.path.join(APPDIR, 'usr/bin/nximport_cli')
    NUPDIR = os.path.join(GAMES, 'PeanutsNative')
    PROBES = os.path.join(CONF, 'probe.tsv')

    def keys_file():
        try:
            p = open(os.path.join(CONF, 'keys.path')).read().strip()
            if p: return p if os.path.isfile(p) else None
        except OSError: pass
        p = os.environ.get('NX_KEYS') or os.path.expanduser('~/.switch/prod.keys')
        return p if os.path.isfile(p) else None

    def game_files():
        out = []
        for root, depth in [(GAMES, 2)] + [(m, 3) for m in glob.glob('/run/media/*/*') + glob.glob('/media/*/*')]:
            for k in range(1, depth + 1):
                for e in EXTS: out += glob.glob(os.path.join(root, *(['*'] * k)) + e)
        return sorted(set(out))

    def probes(files, keys):
        """path → [(app, kind, version, name)], cached by path, size and time in ~/.config/peanutsnative/probe.tsv"""
        cache = {}
        try:
            for l in open(PROBES, encoding='utf-8'):
                p = l.rstrip('\n').split('\t')
                if len(p) == 7: cache.setdefault((p[0], p[1], p[2]), []).append((p[3], p[4], int(p[5]), p[6]))
        except OSError: pass
        res, todo = {}, []
        for f in files:
            try: st = os.stat(f)
            except OSError: continue
            k = (f, str(st.st_size), str(int(st.st_mtime)))
            if k in cache: res[f] = cache[k]
            else: todo.append((f, k))
        if todo and keys and os.access(IMPORTER, os.X_OK):
            try: out = subprocess.run([IMPORTER, '--keys', keys, 'probe'] + [f for f, _ in todo], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120).stdout
            except (OSError, subprocess.SubprocessError): out = ''
            cur = None
            for line in out.splitlines():
                m = PROBE_FILE.match(line)
                if m: cur = m.group('path'); res.setdefault(cur, []); continue
                m = PROBE_TITLE.match(line)
                if m and cur is not None: res[cur].append((m.group('app').upper(), m.group('kind'), int(m.group('version')), m.group('name')))
            keyof = dict(todo)
            try:
                os.makedirs(CONF, exist_ok=True)
                with open(PROBES, 'a', encoding='utf-8') as w:
                    for f, ts in res.items():
                        if f in keyof:
                            for t in ts: w.write('\t'.join(keyof[f] + (t[0], t[1], str(t[2]), t[3].replace('\t', ' '))) + '\n')
            except OSError: pass
        return res, [f for f, _ in todo if f not in res]

    # Packs: Banana Native's pack search walks all of ~/Games, so it found Banana Native's own packs (~/Games/BananaNative/packs, another shader chain:
    # they cannot run here) and listed their games. A pack counts when it is in this AppImage's packs folder or was made with its shader chain
    bn_pack_dirs = g['pack_dirs']
    def pack_dirs():
        own, chain = os.path.realpath(PACKS) + os.sep, SRC.get('chain')
        def ours(d):
            if os.path.realpath(d).startswith(own): return True
            m = re.search(r'v-([0-9a-f]+)/', g['tsv'](os.path.join(d, 'pack.tsv')).get('shaders', ''))
            return bool(chain and m and m.group(1) == chain)
        return [d for d in bn_pack_dirs() if ours(d)]
    g['pack_dirs'] = pack_dirs

    bn_games = g['games']
    def games():
        gs = bn_games()
        files = game_files(); keys = keys_file()
        found, unread = probes(files, keys)
        for f, ts in found.items():
            for app, kind, ver, name in ts:
                e = gs.setdefault(app, {})
                e.setdefault('pn_files', []).append(f)
                if kind == 'base': e.setdefault('name', name)
        hs = hosts()
        for i in list(gs):   # this AppImage's games only (a pack made by it stays listed), and Switch 1 games only (title IDs 01…; Switch 2's are 04…)
            if not i.startswith('01') or (i not in hs and not gs[i].get('pack')): del gs[i]
        for i, e in gs.items():
            if e.get('pn_files') and not e.get('nup'): e['nup'] = e['pn_files'][0]; e['pn_import'] = True
        if unread: gs['~keys'] = {'name': 'Games that need your prod.keys', 'pn_keys': unread, 'pn_have_keys': bool(keys)}
        return gs
    g['games'] = games

    bn_card = Window.card
    def card(self, i, e, runnable, chain):
        if not e.get('pn_keys'): return bn_card(self, i, e, runnable, chain)
        c = bn_card(self, i, {'name': e['name']}, False, chain)   # Banana Native's card frame, its text replaced below
        from PyQt6.QtWidgets import QLabel, QPushButton
        labels = c.findChildren(QLabel)
        n = len(e['pn_keys'])
        if len(labels) >= 3:
            labels[1].setText(('The importer could not read ' if e['pn_have_keys'] else 'To read ') + f'{n} .xci / .nsp file{"s" if n > 1 else ""}' +
                              (' with the keys file you chose' if e['pn_have_keys'] else ', Peanuts Native needs your own prod.keys'))
            labels[2].setText('<br>'.join(os.path.basename(f) for f in e['pn_keys'][:6]) + ('<br>…' if n > 6 else ''))
        b = QPushButton('Choose prod.keys…'); b.setMinimumWidth(190); b.setObjectName('primary'); b.clicked.connect(lambda: self.pn_choose_keys())
        c.layout().itemAt(1).layout().addWidget(b)
        return c
    Window.card = card

    def pn_choose_keys(self):
        f, _ = QFileDialog.getOpenFileName(self, 'Your prod.keys', os.path.expanduser('~'), 'Keys (*.keys);;All files (*)')
        if not f: return
        os.makedirs(CONF, exist_ok=True); open(os.path.join(CONF, 'keys.path'), 'w').write(f + '\n')
        try: os.remove(PROBES)
        except OSError: pass
        self.refresh()
    Window.pn_choose_keys = pn_choose_keys

    bn_prepare = Window.prepare
    def prepare(self, nup, *args, **kw):   # (Banana Native's prepare takes nup, and in later launchers a 'full' choice too: passed on as given)
        if nup.lower().endswith(EXTS):
            i = next((k for k, e in games().items() if nup in e.get('pn_files', [])), None)
            if not i: QMessageBox.warning(self, 'PeanutsNative', f'{os.path.basename(nup)}: its title could not be read.'); return
            if not keys_file(): QMessageBox.warning(self, 'PeanutsNative', 'Choose your prod.keys first.'); return
            self.pn_files = games()[i]['pn_files']
            nup = nup + f'#[{i}]'   # (Banana Native's prepare reads the title from the name)
        else: self.pn_files = None
        return bn_prepare(self, nup, *args, **kw)
    Window.prepare = prepare

    bn_run_pack = Window.run_pack
    def run_pack(self):
        if not getattr(self, 'pn_files', None): return bn_run_pack(self)
        import shutil
        shutil.rmtree(self.out + '.new', ignore_errors=True); self.bar.setRange(0, 0); self.step.setText('Reading the game files (your keys)…')
        os.makedirs(NUPDIR, exist_ok=True)
        script = (f'"{self.src}/linux/tools/linux-pack.sh" setup >/dev/null 2>&1 || true; echo "stage: importing the game into a .nup (several minutes)"; '
                  f'DM_SPLIT_DIRECT=1 DMSH_VK_ZSTD_LEVEL=6 DOTNET_ROOT="{PE}/dotnet" '
                  f'"{PE}/venv/bin/python" "{self.src}/peanuts/scripts/peanuts_build.py" ' + ' '.join(shq(f) for f in self.pn_files) +
                  f' --keys {shq(keys_file())} --importer {shq(IMPORTER)} --nup {shq(NUPDIR)} -- -j 4 -J 6 --made-by "AppImage {SRC["commit"]}" --out "{self.out}.new"'
                  + ('' if getattr(self, 'full', True) else ' --no-shaders'))   # (no Quick choice in Banana Native 0.3.43's launcher: every shader)
        open(self.log, 'w').close(); self.start(f'NX_PACK_ENV="{PE}"; export NX_PACK_ENV; ' + script, self.pack_done)
    Window.run_pack = run_pack
