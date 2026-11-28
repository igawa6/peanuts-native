#!/usr/bin/env python3
# BananaNative's game window for the AppImage (PyQt6; AppRun falls back to zenity dialogs without it). One card per game: what it needs next, in words, and
# only the buttons that apply. A game is ready when it has a pack made for this AppImage's shader version and its game file (.nup) is found.
# AppRun starts it with NX_APPDIR, NX_SELF (the AppImage), NX_CONF (~/.config/banananative). It plays a game by starting the AppImage with the pack folder
# (and quits, so the game has the memory), and prepares one with linux/tools/linux-pack.sh from the source this AppImage was built from.
#   launcher.py                 the window
#   launcher.py --prepare <nup> prepare one game with a progress window only (for tests)
# "Add to Steam" writes a Steam shortcut with the launch options filled in (AppRun --steam, steam_shortcut.py beside this file).
import os, re, sys, glob, shutil, subprocess, signal
try: import steam_shortcut
except ImportError: steam_shortcut = None
from PyQt6.QtCore import Qt, QProcess, QTimer
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (QApplication, QWidget, QLabel, QPushButton, QVBoxLayout, QHBoxLayout, QFrame, QScrollArea, QFileDialog,
                             QMessageBox, QProgressBar, QSizePolicy, QDialog, QComboBox, QGridLayout)

APPDIR = os.environ.get('NX_APPDIR', os.path.dirname(os.path.abspath(__file__)) + '/../../..')
SELF = os.environ.get('NX_SELF', os.path.join(APPDIR, 'AppRun'))
CONF = os.environ.get('NX_CONF', os.path.expanduser('~/.config/banananative'))
HOME = os.path.expanduser('~')
GAMES = os.path.join(HOME, 'Games')
PACKS = os.path.join(GAMES, 'BananaNative', 'packs')
PE = os.path.join(os.environ.get('XDG_DATA_HOME', os.path.join(HOME, '.local/share')), 'banananative', 'packer')
ID_RE = re.compile(r'\[([0-9A-Fa-f]{16})\]')

def source():
    d = {}
    try:
        for l in open(os.path.join(APPDIR, 'usr/share/banananative/source')):
            k, _, v = l.strip().partition(' '); d[k] = v
    except OSError: pass
    return d
SRC = source()

def hosts():
    return {f[len('nxhost_'):] for f in os.listdir(os.path.join(APPDIR, 'usr/bin')) if f.startswith('nxhost_')}

def tsv(path):
    d = {}
    try:
        for l in open(path, encoding='utf-8', errors='replace'):
            p = l.rstrip('\n').split('\t')
            if len(p) >= 2 and p[0] not in d: d[p[0]] = p[1]
    except OSError: pass
    return d

def tsv_rows(path, key):
    try: return [l.rstrip('\n').split('\t') for l in open(path, encoding='utf-8', errors='replace') if l.startswith(key + '\t')]
    except OSError: return []

def pack_dirs():
    out = []
    for root in [GAMES] + glob.glob('/run/media/*/*') + glob.glob('/media/*/*'):
        if not os.path.isdir(root): continue
        base = root.count(os.sep)
        for dp, dn, fn in os.walk(root):
            if dp.count(os.sep) - base >= 5: dn[:] = []
            dn[:] = [x for x in dn if not x.startswith('.') and not x.endswith(('-work', '.new', '.old'))]
            if 'pack.tsv' in fn: out.append(dp); dn[:] = []
    try: out += [l.strip() for l in open(os.path.join(CONF, 'games.list')) if os.path.isfile(os.path.join(l.strip(), 'pack.tsv'))]
    except OSError: pass
    return out

def nup_files():
    out = []
    for root, depth in [(GAMES, 2)] + [(m, 3) for m in glob.glob('/run/media/*/*') + glob.glob('/media/*/*')]:
        for k in range(1, depth + 1): out += glob.glob(os.path.join(root, *(['*'] * k)) + '.nup')
    return out

def games():
    """title ID → {name, pack, chain, nup}: from the packs found (the newest per game) and the game files found"""
    g = {}
    for d in pack_dirs():
        t = tsv(os.path.join(d, 'pack.tsv')); i = t.get('title_id', '').upper()
        if not i: continue
        m = re.search(r'v-([0-9a-f]+)/', t.get('shaders', ''))
        e = {'name': t.get('name', i), 'pack': d, 'chain': m.group(1) if m else None, 'mtime': os.path.getmtime(os.path.join(d, 'pack.tsv'))}
        if i not in g or e['mtime'] > g[i].get('mtime', 0): g.setdefault(i, {}).update(e)
    for f in nup_files():
        m = ID_RE.search(os.path.basename(f))
        if not m: continue
        i = m.group(1).upper(); e = g.setdefault(i, {})
        e.setdefault('nup', f); e.setdefault('name', re.sub(r'\s*\[.*$', '', os.path.basename(f)))
    for i, e in g.items():
        try:
            f = open(os.path.join(CONF, i + '.nup')).read().strip()
            if os.path.isfile(f): e['nup'] = f
        except OSError: pass
    return g

def yes(parent, text):   # NX_LAUNCHER_YES=1: every question answered yes (unattended tests)
    return os.environ.get('NX_LAUNCHER_YES') == '1' or QMessageBox.question(parent, 'BananaNative', text) == QMessageBox.StandardButton.Yes

def tilde(p): return '~' + p[len(HOME):] if p and p.startswith(HOME + '/') else p

# ---- options (the settings dialog). The same keys and meanings as AppRun's settings.env text; AppRun reads <ID>.env first, then settings.env, and the
# first line of a key wins (loadenv/setdef), so here too a key's value is its first line that is not a comment.
# (key, label, help, [(value, text)], default text, the game.tsv key it needs or None)
OPTIONS = [
    ('Picture and speed', None),
    ('DM_OPMODE', 'Mode', 'How the game thinks it is played. Docked is heavier.', [('handheld', 'Handheld'), ('docked', 'Docked (TV)')], 'Handheld', None),
    ('DM_GRAPHICS_SCALE', 'Resolution', 'The size the game draws at.', [('2', 'Handheld size (720p)'), ('1', 'Full size')], 'Handheld size (720p)', None),
    ('NX_FPS_CAP', 'Frame rate cap', 'The most frames a second the game may run at.',
     [('0', 'The game decides'), ('20', '20 fps (Donkey Kong Bananza)'), ('30', '30 fps'), ('40', '40 fps'), ('45', '45 fps'), ('60', '60 fps')], 'The game decides', None),
    ('NX_SCENE', '3D resolution', "The size the game draws its 3D scene at, before it is scaled to the screen. Larger is sharper and heavier.",
     [], "The game's own", None),
    # (NX_HOLD60, Hold 60 fps, is left out on purpose: on the Odin 2 Pro Mario Kart World runs 35-40 fps in free roam, so one 1/60 s step a frame
    # played it at about 60 % speed, slow motion; the game's own drop to 30 keeps real speed. settings.env still takes it)
    ('NX_DRS', 'Dynamic resolution', 'The lowest size the game may draw at when busy.',
     [(str(p), f'{p} %' + (' (always full size)' if p == 100 else '')) for p in range(30, 101, 10)], "The game's own range", 'drs_ctl'),
    ('Frame generation (Lossless Scaling)', None),
    ('NX_FRAMEGEN', 'Frame generation', "Made-up frames between the game's frames. Costs GPU time. Needs Lossless.dll (Steam's Lossless Scaling).",
     [('0', 'Off'), ('1', 'On')], 'Off', None),
    ('NX_FRAMEGEN_MULT', 'Frames per game frame', '', [('2', '2x'), ('3', '3x'), ('4', '4x')], '2x', None),
    ('NX_FRAMEGEN_FLOW', 'Motion quality', 'Lower is faster, less exact.', [('25', '25 %'), ('50', '50 %'), ('75', '75 %'), ('100', '100 %')], '75 %', None),
    ('Overlay and window', None),
    ('NX_OVERLAY', 'Performance overlay', 'fps, frame time, temperatures and memory in a corner (MangoHud).', [('0', 'Off'), ('1', 'On')], 'Off', None),
    ('NX_WINDOW', 'Window', '', [('1280x720', 'Window 1280x720'), ('1920x1080', 'Window 1920x1080')], 'Full screen', None),
    ('Memory and device', None),
    ('DM_MEM_FILEBACK', 'RAM saver', "Keeps part of the game's data in a file on the storage instead of in memory.", [('1', 'On'), ('0', 'Off')], 'On', None),
    ('DM_MEM_FILEBACK_KINDS', 'RAM saver moves', '', [('shader,texture', 'Shaders and textures (most saving)'), ('shader', 'Shaders only (fewer writes)')],
     'Shaders and textures', None),
    ('NX_GOVERNOR', 'CPU speed policy', 'Needs the governor helper (see the README).',
     [('schedutil', 'Balanced (schedutil)'), ('performance', 'Always fastest (hotter)'), ('0', "The system's own")], 'Balanced (schedutil)', None),
    ('NX_GAMESCOPE_WSI', "Gamescope's Vulkan layer", 'Game Mode only. It caused GPU faults on the Odin 2.', [('0', 'Off'), ('1', 'On')], 'Off', None),
]
KEY_LINE = re.compile(r'^\s*(#?)\s*([A-Z][A-Z0-9_]*)\s*=(.*)$')

def env_read(path):
    """key → value of the first line that is not a comment (AppRun's loadenv: the text after a '#' is dropped, spaces trimmed)"""
    d = {}
    try:
        for l in open(path, encoding='utf-8', errors='replace'):
            m = KEY_LINE.match(l.split('#', 1)[0] if not l.lstrip().startswith('#') else l)
            if m and not m.group(1) and m.group(2) not in d: d[m.group(2)] = m.group(3).strip()
    except OSError: pass
    return d

def env_write(path, values, header):
    """values: key → value, or None for the default. Edits the file in place: the first line of a key (commented or not) becomes KEY=value, or is
    commented out for the default; any later active line of that key is commented out; a key with no line is appended. Other lines are kept as they are"""
    try: lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    except OSError: lines = header.splitlines()
    seen = set()
    for n, l in enumerate(lines):
        m = KEY_LINE.match(l)
        if not m or m.group(2) not in values: continue
        k, v = m.group(2), values[m.group(2)]
        if k in seen:
            if not m.group(1): lines[n] = '#' + l.lstrip()
            continue
        seen.add(k)
        if v is None: lines[n] = l if m.group(1) else '#' + l.lstrip()
        else: lines[n] = f'{k}={v}'
    lines += [f'{k}={v}' for k, v in values.items() if k not in seen and v is not None]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + '.tmp'; open(tmp, 'w', encoding='utf-8').write('\n'.join(lines) + '\n'); os.replace(tmp, path)

GAME_ENV_HEADER = """# BananaNative settings for one game (the title ID in this file's name). They win over settings.env.
# Written by the game window's Options; lines KEY=VALUE as in settings.env. A line starting with '#' is switched off."""

class OptionsDialog(QDialog):
    """Every game (game=None: settings.env) or one game (<ID>.env, whose defaults are the settings for every game)"""
    def __init__(self, parent, game=None, name=None, pack=None):
        super().__init__(parent); self.game = game
        self.path = os.path.join(CONF, (game or 'settings') + '.env')
        if not game and not os.path.exists(self.path):
            try: subprocess.run([SELF, '--write-settings'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError: pass   # (then Save writes only the chosen lines)
        self.setWindowTitle(f'Options: {name}' if game else 'Settings for every game'); self.resize(860, 720)
        cur, common = env_read(self.path), env_read(os.path.join(CONF, 'settings.env'))
        kinds = tsv(os.path.join(pack, 'game.tsv')) if pack else None
        v = QVBoxLayout(self); v.setContentsMargins(20, 16, 20, 16)
        intro = QLabel(f'Options for <b>{name}</b> only. “Same as every game” follows Settings.' if game else
                       'Settings for every game. A game can change them under its own <b>Options</b>.')
        intro.setWordWrap(True); v.addWidget(intro)
        area = QScrollArea(); area.setWidgetResizable(True); area.setFrameShape(QFrame.Shape.NoFrame); v.addWidget(area, 1)
        box = QWidget(); g = QGridLayout(box); g.setColumnStretch(0, 3); g.setColumnStretch(1, 2); g.setHorizontalSpacing(16); g.setVerticalSpacing(6); r = 0
        self.boxes = {}
        for o in OPTIONS:
            if o[1] is None:
                h = QLabel(o[0]); h.setObjectName('gname'); g.addWidget(h, r, 0, 1, 2); r += 1; continue
            key, label, help_, choices, deftext, needs = o
            if needs and kinds is not None and needs not in kinds: continue
            if key == 'NX_SCENE':
                if not kinds: continue
                choices = [(r[1], r[4] if len(r) > 4 else r[1]) for r in tsv_rows(os.path.join(pack, 'game.tsv'), 'scene_arg')]
                if not choices: continue
            if key == 'NX_FPS_CAP' and game and game != '040074A01BF12000': choices = [c for c in choices if c[0] != '20']
            cb = QComboBox(); cb.setMinimumWidth(300)
            if game:
                inherit = dict(choices).get(common.get(key), common.get(key)) if key in common else deftext
                cb.addItem(f'Same as every game ({inherit})', None)
            else: cb.addItem(f'Default ({deftext})', None)
            for val, text in choices: cb.addItem(text, val)
            val = cur.get(key)
            if val is not None:
                k = cb.findData(val)
                if k < 0: cb.addItem(f'{val} (set in the file)', val); k = cb.count() - 1
                cb.setCurrentIndex(k)
            lab = QLabel(f'<b>{label}</b>' + (f'<br><span style="color:#5b6473">{help_}</span>' if help_ else '')); lab.setWordWrap(True)
            g.addWidget(lab, r, 0); g.addWidget(cb, r, 1, Qt.AlignmentFlag.AlignTop); r += 1
            self.boxes[key] = cb
        g.setRowStretch(r, 1); area.setWidget(box)
        foot = QHBoxLayout()
        f = QPushButton('Edit the file…'); f.setObjectName('flat'); f.setToolTip(tilde(self.path)); f.clicked.connect(self.edit_file); foot.addWidget(f)
        foot.addStretch(1)
        c = QPushButton('Cancel'); c.clicked.connect(self.reject); foot.addWidget(c)
        s = QPushButton('Save'); s.setObjectName('primary'); s.clicked.connect(self.save); foot.addWidget(s)
        v.addLayout(foot)

    def save(self):
        try: env_write(self.path, {k: cb.currentData() for k, cb in self.boxes.items()}, GAME_ENV_HEADER if self.game else '')
        except OSError as e: QMessageBox.warning(self, 'BananaNative', f'Could not save {tilde(self.path)}: {e}'); return
        self.accept()

    def edit_file(self):
        if not os.path.exists(self.path): env_write(self.path, {}, GAME_ENV_HEADER)
        subprocess.Popen(['xdg-open', self.path], start_new_session=True); self.reject()

STYLE = """
QWidget#root { background: #eef0f3; }
QFrame#card { background: white; border: 1px solid #d5d9df; border-radius: 12px; }
QFrame#help { background: #fff8e1; border: 1px solid #f1d58a; border-radius: 12px; }
QLabel#title { font-size: 20px; font-weight: 600; color: #1d2330; }
QLabel#gname { font-size: 18px; font-weight: 600; color: #1d2330; }
QLabel#detail { color: #5b6473; }
QLabel#ok { color: #1f7a3a; font-weight: 600; }
QLabel#todo { color: #9a5b00; font-weight: 600; }
QLabel#bad { color: #a12828; font-weight: 600; }
QPushButton { padding: 10px 18px; border-radius: 8px; border: 1px solid #c4cad3; background: #f7f8fa; font-size: 15px; }
QPushButton:hover { background: #eceff3; }
QPushButton#primary { background: #2f6fde; color: white; border: none; font-weight: 600; }
QPushButton#primary:hover { background: #255fc2; }
QPushButton#flat { border: none; background: transparent; color: #2f6fde; padding: 6px 8px; }
QProgressBar { border: 1px solid #c4cad3; border-radius: 6px; text-align: center; height: 22px; }
QProgressBar::chunk { background: #2f6fde; border-radius: 6px; }
"""

class Window(QWidget):
    def __init__(self):
        super().__init__(); self.setObjectName('root'); self.setWindowTitle('BananaNative'); self.resize(1100, 760)
        self.proc = None; self.busy = None
        v = QVBoxLayout(self); v.setContentsMargins(24, 18, 24, 18); v.setSpacing(14)
        top = QHBoxLayout(); t = QLabel('BananaNative'); t.setObjectName('title'); top.addWidget(t); top.addStretch(1)
        for text, fn in (('Add a game file…', self.add_nup), ('Settings', self.settings), ('Open the Games folder', self.open_games)):
            b = QPushButton(text); b.clicked.connect(fn); top.addWidget(b)
        v.addLayout(top)
        help_ = QFrame(); help_.setObjectName('help'); h = QVBoxLayout(help_); h.setContentsMargins(16, 12, 16, 12)
        hl = QLabel('<b>How it works</b> &nbsp;1. Put the game file (<i>.nup</i>) in <i>~/Games</i>. &nbsp;2. <b>Prepare</b> it once (15-40 minutes; the '
                    'first time also downloads the tools). &nbsp;3. <b>Play</b>.<br>Preparing again is only needed when a game says so (after a BananaNative '
                    'update that changed the shaders). Saves are kept either way.')
        hl.setWordWrap(True); h.addWidget(hl); v.addWidget(help_)
        self.area = QScrollArea(); self.area.setWidgetResizable(True); self.area.setFrameShape(QFrame.Shape.NoFrame)
        v.addWidget(self.area, 1)
        foot = QLabel(f"This AppImage: {SRC.get('version', '?')} · games it can run: {len(hosts())} · packs in {tilde(PACKS)}")
        foot.setObjectName('detail'); v.addWidget(foot)
        self.refresh()

    def refresh(self):
        box = QWidget(); box.setObjectName('root'); col = QVBoxLayout(box); col.setSpacing(12); col.setContentsMargins(0, 0, 0, 0)
        hs, chain, g = hosts(), SRC.get('chain'), games()
        self.in_steam = in_steam()
        for i in sorted(g, key=lambda k: (k not in hs, g[k].get('name', k).lower())):
            col.addWidget(self.card(i, g[i], i in hs, chain))
        if not g:
            e = QLabel('No games found. Put a game file (.nup) in ~/Games, then press “Add a game file…” or reopen this window.'); e.setObjectName('detail')
            col.addWidget(e)
        col.addStretch(1); self.area.setWidget(box)

    def card(self, i, e, runnable, chain):
        c = QFrame(); c.setObjectName('card'); row = QHBoxLayout(c); row.setContentsMargins(18, 14, 18, 14); row.setSpacing(16)
        left = QVBoxLayout(); left.setSpacing(4)
        n = QLabel(e.get('name', i)); n.setObjectName('gname'); left.addWidget(n)
        st = QLabel(); left.addWidget(st)
        det = QLabel(); det.setObjectName('detail'); det.setWordWrap(True); left.addWidget(det)
        row.addLayout(left, 1)
        btns = QVBoxLayout(); btns.setSpacing(6); row.addLayout(btns)
        pack, nup = e.get('pack'), e.get('nup')
        lines = [f'Title ID {i}', 'Pack: ' + (tilde(pack) if pack else 'none yet'), 'Game file: ' + (tilde(nup) if nup else 'not found')]
        det.setText('<br>'.join(lines))
        def button(text, fn, kind=None):
            b = QPushButton(text); b.clicked.connect(fn); b.setMinimumWidth(190)
            if kind: b.setObjectName(kind)
            btns.addWidget(b); return b
        if self.busy == i:
            st.setText('Preparing…'); st.setObjectName('todo'); self.bar = QProgressBar(); self.bar.setRange(0, 100); left.addWidget(self.bar)
            self.step = QLabel('Starting'); self.step.setObjectName('detail'); left.addWidget(self.step)
            button('Stop preparing', self.stop)
        elif not runnable:
            st.setText('This BananaNative cannot run this game yet'); st.setObjectName('bad')
            det.setText(det.text() + '<br>A newer AppImage that includes it is needed.')
        elif not nup:
            st.setText('Game file not found'); st.setObjectName('bad')
            button('Choose the game file…', lambda: self.choose_nup(i))
        elif not pack:
            st.setText('Needs preparing (once)'); st.setObjectName('todo')
            button('Prepare', lambda: self.prepare(nup), 'primary')
        elif chain and e.get('chain') and e['chain'] != chain:
            st.setText('Needs preparing again: BananaNative was updated'); st.setObjectName('todo')
            button('Prepare again', lambda: self.prepare(nup), 'primary')
        else:
            st.setText('Ready to play'); st.setObjectName('ok')
            button('Play', lambda: self.play(pack), 'primary')
            b = button('Prepare again', lambda: self.prepare(nup), 'flat'); b.setToolTip('Not needed: only when the game says so')
            name = e.get('name', i)
            if name + STEAM_SUFFIX in self.in_steam:
                det.setText(det.text() + '<br>In Steam: “' + name + STEAM_SUFFIX + '” (Game Mode: Library, Non-Steam)')
            elif steam_shortcut:
                b = button('Add to Steam', lambda: self.add_steam(i, name), 'flat')
                b.setToolTip('A Steam shortcut for Game Mode, with the launch options it needs already filled in')
        if runnable and self.busy != i:
            b = button('Options', lambda: self.options(i, e), 'flat')
            if os.path.exists(os.path.join(CONF, i + '.env')) and env_read(os.path.join(CONF, i + '.env')): b.setText('Options (changed)')
        if self.busy and self.busy != i:
            for k in range(btns.count()): btns.itemAt(k).widget().setEnabled(False)
        return c

    # ---- actions
    def play(self, pack):
        subprocess.Popen([SELF, pack], start_new_session=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        QTimer.singleShot(400, QApplication.quit)

    def choose_nup(self, i):
        f, _ = QFileDialog.getOpenFileName(self, 'The game file', GAMES, 'Game file (*.nup)')
        if f: os.makedirs(CONF, exist_ok=True); open(os.path.join(CONF, i + '.nup'), 'w').write(f + '\n'); self.refresh()

    def add_nup(self):
        f, _ = QFileDialog.getOpenFileName(self, 'A game file to prepare', GAMES, 'Game file (*.nup)')
        if not f: return
        m = ID_RE.search(os.path.basename(f))
        if not m: QMessageBox.warning(self, 'BananaNative', 'Its name has no [title ID] (16 hexadecimal digits in square brackets).'); return
        i = m.group(1).upper(); os.makedirs(CONF, exist_ok=True); open(os.path.join(CONF, i + '.nup'), 'w').write(f + '\n')
        self.refresh()
        if i in hosts() and yes(self, 'Prepare it now? (15-40 minutes)'): self.prepare(f)

    def settings(self): OptionsDialog(self).exec()

    def add_steam(self, i, name, close=False):
        r = subprocess.run([SELF, '--steam'] + (['--close-steam'] if close else []) + [i], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        out = r.stdout.strip()
        if r.returncode == 3 and not close:
            if yes(self, f'Steam has to close for a moment to add {name} (it starts again by itself). Close Steam now?'): return self.add_steam(i, name, True)
            return
        if r.returncode == 0:
            QMessageBox.information(self, 'BananaNative', f'{name} is in Steam now: in Game Mode, Library, Non-Steam.\n\n{out}')
        else:
            QMessageBox.warning(self, 'BananaNative', f'{name} was not added to Steam.\n\n{out}')
        self.refresh()

    def options(self, i, e): OptionsDialog(self, i, e.get('name', i), e.get('pack')).exec()

    def open_games(self): subprocess.Popen(['xdg-open', GAMES], start_new_session=True)

    # ---- preparing
    def prepare(self, nup):
        i = ID_RE.search(os.path.basename(nup)).group(1).upper()
        if subprocess.run(['pgrep', '-f', '[u]sr/bin/nxhost_'], stdout=subprocess.DEVNULL).returncode == 0:
            QMessageBox.warning(self, 'BananaNative', 'A game is running. Close it first: preparing needs the memory.'); return
        for tool in ('spirv-val', 'curl'):
            if not shutil.which(tool): QMessageBox.warning(self, 'BananaNative', f'{tool} is missing (spirv-val: the spirv-tools package).'); return
        if 'commit' not in SRC: QMessageBox.warning(self, 'BananaNative', 'This AppImage does not say which source it was built from: it cannot prepare.'); return
        self.src = os.path.join(PE, 'src-' + SRC['commit'][:12])
        need_tools = not (os.access(os.path.join(self.src, 'linux/tools/linux-pack.sh'), os.X_OK) and os.access(os.path.join(PE, 'venv/bin/python'), os.X_OK)
                          and os.access(os.path.join(PE, 'dotnet/dotnet'), os.X_OK))
        if need_tools and not yes(self, 'Preparing needs the pack tools of this BananaNative version, once:\n• its source (about 130 MB)\n'
                                               '• Python packages and the .NET 10 runtime (first time only)\n\nAbout 10 minutes. Download them now?'):
            return
        self.busy, self.nup, self.out = i, nup, os.path.join(PACKS, i)
        self.log = os.path.join(PE, f'prepare-{i}.log'); os.makedirs(PE, exist_ok=True); os.makedirs(PACKS, exist_ok=True)
        self.refresh()
        if need_tools: self.step.setText('Downloading the pack tools (about 10 minutes, once)…'); self.bar.setRange(0, 0); self.run_tools()
        else: self.run_pack()

    def start(self, script, done):
        self.proc = QProcess(self); self.proc.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.logf = open(self.log, 'a'); self.proc.readyReadStandardOutput.connect(self.output)
        self.proc.finished.connect(lambda code, st: done(code == 0 and st == QProcess.ExitStatus.NormalExit))
        inh = ['kde-inhibit', '--power', '--screenSaver'] if shutil.which('kde-inhibit') else (['systemd-inhibit', '--what=idle:sleep', '--why=BananaNative'] if shutil.which('systemd-inhibit') else [])
        args = inh + ['sh', '-c', script]; self.proc.start(args[0], args[1:])

    def run_tools(self):
        u, c, part = SRC.get('url'), SRC['commit'], self.src + '.part'
        script = (f'set -e; rm -rf "{part}"; mkdir -p "{part}"; curl -fsSL --retry 3 "{u}/archive/{c}.tar.gz" | tar -xz -C "{part}" --strip-components=1; '
                  f'NX_PACK_ENV="{PE}" "{part}/linux/tools/linux-pack.sh" setup; rm -rf "{self.src}"; mv "{part}" "{self.src}"; '
                  f'for o in "{PE}"/src-*; do [ "$o" = "{self.src}" ] || rm -rf "$o"; done')
        open(self.log, 'w').close(); self.start(script, self.tools_done)

    def tools_done(self, ok):
        self.logf.close()
        if not ok: return self.failed('Getting the pack tools failed.')
        self.run_pack()

    def run_pack(self):
        shutil.rmtree(self.out + '.new', ignore_errors=True); self.bar.setRange(0, 100); self.bar.setValue(1); self.step.setText('Reading the game file…')
        script = (f'NX_PACK_ENV="{PE}" exec "{self.src}/linux/tools/linux-pack.sh" "$0" -j 4 -J 6 --made-by "AppImage {SRC["commit"]}" --out "{self.out}.new"')
        open(self.log, 'w').close(); self.start(script.replace('"$0"', shq(self.nup)), self.pack_done)

    def output(self):
        for l in bytes(self.proc.readAllStandardOutput()).decode('utf-8', 'replace').splitlines():
            self.logf.write(l + '\n')
            m = re.match(r'progress (\d+) (\d+)', l)
            if m and int(m.group(2)):
                a, b = int(m.group(1)), int(m.group(2)); self.bar.setRange(0, 100); self.bar.setValue(5 + 90 * a // b); self.step.setText(f'Shaders: {a} of {b}')
            elif 'rewriting' in l: self.bar.setValue(2); self.step.setText('Rewriting the game code…')
            elif l.startswith('stage: '): self.step.setText(l[7:])
            elif 'linux-pack' in l or 'translator' in l or 'ready:' in l: self.step.setText(l[:100])
        self.logf.flush()

    def pack_done(self, ok):
        self.logf.close()
        if not ok or not os.path.isfile(os.path.join(self.out + '.new', 'pack.tsv')):
            shutil.rmtree(self.out + '.new', ignore_errors=True); return self.failed('Preparing failed.')
        shutil.rmtree(self.out + '.old', ignore_errors=True)
        if os.path.isdir(self.out): os.rename(self.out, self.out + '.old')
        os.rename(self.out + '.new', self.out); shutil.rmtree(self.out + '.old', ignore_errors=True)
        subprocess.run([SELF, '--install'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        name = tsv(os.path.join(self.out, 'pack.tsv')).get('name', self.busy)
        self.busy = None; self.proc = None; self.refresh()
        print(f'prepared: {name}', flush=True)
        if os.environ.get('NX_LAUNCHER_YES') != '1':
            if steam_shortcut and name + STEAM_SUFFIX not in in_steam() and \
               QMessageBox.question(self, 'BananaNative', f'{name} is ready to play.\n\nAdd it to Steam too, to play it from Game Mode?') == QMessageBox.StandardButton.Yes:
                self.add_steam(self.out and os.path.basename(self.out), name)
            else: QMessageBox.information(self, 'BananaNative', f'{name} is ready to play.')

    def failed(self, what):
        tail = [l for l in open(self.log, errors='replace').read().splitlines() if not l.startswith('progress')][-8:]
        self.busy = None; self.proc = None; self.refresh()
        print('failed: ' + what, flush=True)
        if os.environ.get('NX_LAUNCHER_YES') != '1': QMessageBox.warning(self, 'BananaNative', what + f'\n\nThe last lines of its log ({tilde(self.log)}):\n\n' + '\n'.join(tail))

    def stop(self):
        if self.proc and QMessageBox.question(self, 'BananaNative', 'Stop preparing? The game keeps its current pack.') == QMessageBox.StandardButton.Yes:
            self.proc.finished.disconnect(); pid = self.proc.processId()
            try: os.killpg(os.getpgid(pid), signal.SIGTERM)
            except OSError: self.proc.kill()
            subprocess.run(['pkill', '-TERM', '-f', '[s]cripts/pack_android.py'])
            shutil.rmtree(self.out + '.new', ignore_errors=True); self.busy = None; self.proc = None; self.refresh()

    def closeEvent(self, ev):
        if self.proc and QMessageBox.question(self, 'BananaNative', 'A game is being prepared. Close anyway? Preparing stops.') != QMessageBox.StandardButton.Yes:
            ev.ignore(); return
        if self.proc: self.busy = 'x'; self.stop_now()
        ev.accept()

    def stop_now(self):
        try: self.proc.finished.disconnect()
        except TypeError: pass
        subprocess.run(['pkill', '-TERM', '-f', '[s]cripts/pack_android.py']); self.proc.kill()

STEAM_SUFFIX = steam_shortcut.SUFFIX if steam_shortcut else ' (BananaNative)'

def in_steam():
    try: return steam_shortcut.shortcut_names() if steam_shortcut else set()
    except Exception: return set()

def shq(s): return "'" + s.replace("'", "'\\''") + "'"

if __name__ == '__main__':
    app = QApplication(sys.argv); app.setStyleSheet(STYLE); f = app.font(); f.setPointSize(max(f.pointSize(), 11)); app.setFont(f)
    w = Window(); w.show()
    if len(sys.argv) == 3 and sys.argv[1] == '--prepare':
        QTimer.singleShot(300, lambda: w.prepare(sys.argv[2]))
    sys.exit(app.exec())
