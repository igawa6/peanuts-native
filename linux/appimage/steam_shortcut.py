#!/usr/bin/env python3
# Adds BananaNative games to Steam as non-Steam games (Steam's shortcuts.vdf), with the launch options they need, so nobody has to type them:
#   PATH=/usr/local/bin:/usr/bin:/bin %command% "<pack folder>"   (Steam starts non-Steam games with no PATH; the AppImage runtime needs fusermount)
#   steam_shortcut.py [--close-steam] --exe <AppImage> [--icon <png>] <name>=<pack folder> ...
# One shortcut per game, named "<name> (BananaNative)": an existing one of that name is updated (its artwork, play time and appid are kept).
# Every Steam user on this system (~/.local/share/Steam/userdata/<id>/config). Steam must not be running: it keeps its own copy and writes it back.
# --close-steam: close Steam first (steam -shutdown) and start it again afterwards. Exit: 0 done, 2 no Steam user found, 3 Steam is running,
# 4 Game Mode (Steam can't be closed from inside it), 1 other error. shortcuts.vdf.bak-banananative keeps the file as it was before each write.
import os, sys, time, zlib, struct, shutil, subprocess

SUFFIX = ' (BananaNative)'
PATHVAR = 'PATH=/usr/local/bin:/usr/bin:/bin'

# ---- binary VDF: a map is a list of (type, key, value); types 0 map, 1 string, 2 int32, 3 float32, 7 uint64, end 8
def parse(b):
    pos = 0
    def cstr():
        nonlocal pos
        e = b.index(b'\x00', pos); s = b[pos:e]; pos = e + 1; return s
    def mapping():
        nonlocal pos
        out = []
        while True:
            t = b[pos]; pos += 1
            if t == 8: return out
            k = cstr()
            if t == 0: out.append((0, k, mapping()))
            elif t == 1: out.append((1, k, cstr()))
            elif t in (2, 3): out.append((t, k, b[pos:pos + 4])); pos += 4
            elif t == 7: out.append((7, k, b[pos:pos + 8])); pos += 8
            else: raise ValueError(f'unknown VDF type {t} at {pos - 1}')
    root = mapping()
    if pos != len(b): raise ValueError('trailing bytes in shortcuts.vdf')
    return root

def dump(m):
    out = bytearray()
    for t, k, v in m:
        out += bytes([t]) + k + b'\x00'
        out += dump(v) if t == 0 else (v + b'\x00' if t == 1 else v)
    return bytes(out + b'\x08')

def get(m, key):
    key = key.encode() if isinstance(key, str) else key
    for t, k, v in m:
        if k.lower() == key.lower(): return v
    return None

def put(m, t, key, v):
    key = key.encode() if isinstance(key, str) else key
    for n, (tt, k, _) in enumerate(m):
        if k.lower() == key.lower(): m[n] = (t, k, v); return
    m.append((t, key, v))

# ---- Steam
def steam_running():
    for p in os.listdir('/proc'):
        if not p.isdigit(): continue
        try: c = open(f'/proc/{p}/comm').read().strip()
        except OSError: continue
        if c in ('steam', 'steamwebhelper'): return True
    return False

def user_configs():
    roots = {os.path.realpath(os.path.expanduser(r)) for r in ('~/.local/share/Steam', '~/.steam/steam', '~/.steam/root') if os.path.isdir(os.path.expanduser(r))}
    out = []
    for r in sorted(roots):
        u = os.path.join(r, 'userdata')
        if not os.path.isdir(u): continue
        for d in sorted(os.listdir(u)):
            c = os.path.join(u, d, 'config')
            if d.isdigit() and d != '0' and os.path.isdir(c) and c not in out: out.append(c)
    return out

def shortcut_names():
    """AppNames of the non-Steam games already in Steam (for the game window's 'In Steam' label)"""
    names = set()
    for c in user_configs():
        try: root = parse(open(os.path.join(c, 'shortcuts.vdf'), 'rb').read())
        except (OSError, ValueError): continue
        for _, _, e in (get(root, 'shortcuts') or []):
            n = get(e, 'AppName')
            if n: names.add(n.decode('utf-8', 'replace'))
    return names

def add(vdf, exe, icon, games):
    """games: [(name, pack)] → lines saying what was done. Writes vdf (made when missing)"""
    try: root = parse(open(vdf, 'rb').read())
    except FileNotFoundError: root = [(0, b'shortcuts', [])]
    sc = get(root, 'shortcuts')
    if sc is None: sc = []; root.append((0, b'shortcuts', sc))
    exe_q = f'"{exe}"'.encode(); start_q = f'"{os.path.dirname(exe)}"'.encode(); said = []
    for name, pack in games:
        label = (name + SUFFIX).encode(); opts = f'{PATHVAR} %command% "{pack}"'.encode()
        e = next((v for _, _, v in sc if get(v, 'AppName') == label), None)
        if e is None:
            e = []; sc.append((0, str(max([int(k) for _, k, _ in sc if k.isdigit()], default=-1) + 1).encode(), e))
            appid = (zlib.crc32(exe_q + label) | 0x80000000) & 0xffffffff
            for t, k, v in ((2, b'appid', struct.pack('<I', appid)), (1, b'AppName', label), (1, b'Exe', exe_q), (1, b'StartDir', start_q),
                            (1, b'icon', icon.encode()), (1, b'ShortcutPath', b''), (1, b'LaunchOptions', opts), (2, b'IsHidden', bytes(4)),
                            (2, b'AllowDesktopConfig', struct.pack('<I', 1)), (2, b'AllowOverlay', struct.pack('<I', 1)), (2, b'OpenVR', bytes(4)),
                            (2, b'Devkit', bytes(4)), (1, b'DevkitGameID', b''), (2, b'DevkitOverrideAppID', bytes(4)), (2, b'LastPlayTime', bytes(4)),
                            (1, b'FlatpakAppID', b''), (1, b'sortas', b''), (0, b'tags', [])):
                e.append((t, k, v))
            said.append(f'added: {label.decode()}')
        else:
            old = (get(e, 'Exe'), get(e, 'StartDir'), get(e, 'LaunchOptions'))
            put(e, 1, b'Exe', exe_q); put(e, 1, b'StartDir', start_q); put(e, 1, b'LaunchOptions', opts)
            if not get(e, 'icon'): put(e, 1, b'icon', icon.encode())
            said.append(f'{"updated" if old != (exe_q, start_q, opts) else "already there"}: {label.decode()}')
    data = dump(root)
    if parse(data) != root: raise ValueError('shortcuts.vdf did not read back the same')   # (never write a file Steam could not read)
    if os.path.exists(vdf): shutil.copy2(vdf, vdf + '.bak-banananative')
    tmp = vdf + '.tmp-banananative'; open(tmp, 'wb').write(data); os.replace(tmp, vdf)
    return said

def main(argv):
    close = '--close-steam' in argv; argv = [a for a in argv if a != '--close-steam']
    exe = icon = None; games = []; it = iter(argv)
    for a in it:
        if a == '--exe': exe = os.path.abspath(next(it))
        elif a == '--icon': icon = next(it)
        elif '=' in a: n, _, p = a.partition('='); games.append((n, os.path.abspath(p)))
        else: print(f'unknown argument: {a}', file=sys.stderr); return 1
    if not exe or not games: print(__doc__ or 'usage: steam_shortcut.py [--close-steam] --exe <AppImage> [--icon <png>] <name>=<pack> ...', file=sys.stderr); return 1
    confs = user_configs()
    if not confs: print('No Steam user found: open Steam once and sign in, then try again.'); return 2
    closed = False
    if steam_running():
        if os.environ.get('SteamGameId') or os.environ.get('GAMESCOPE_WAYLAND_DISPLAY'):
            print('Steam is running in Game Mode: switch to Desktop Mode and try again there.'); return 4
        if not close: print('Steam is running: it must be closed first.'); return 3
        subprocess.run(['steam', '-shutdown'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            if not steam_running(): break
            time.sleep(1)
        if steam_running(): print('Steam did not close: close it yourself and try again.'); return 3
        closed = True; time.sleep(2)
    for c in confs:
        for l in add(os.path.join(c, 'shortcuts.vdf'), exe, icon or '', games): print(l)
    if closed:   # start it again, without the AppImage's variables
        env = {k: v for k, v in os.environ.items() if k not in ('APPDIR', 'APPIMAGE', 'ARGV0', 'OWD', 'LD_LIBRARY_PATH', 'PYTHONPATH', 'PYTHONHOME')}
        subprocess.Popen(['steam'], env=env, start_new_session=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print('Steam started again.')
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
