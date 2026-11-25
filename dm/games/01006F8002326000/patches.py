# PEANUTS: new file (the 60 fps variant: tmouh's "ACNH 60 FPS + SpeedFix" v1.4.5, CC BY-NC 4.0, speedfix60/NOTICE.md)
"""Animal Crossing: New Horizons' rewriter patches (dm/tools/patch_nso.py). The game's own code needs no cut: only its 60 fps variant has rows.

VARIANT_CUTS['60']: the speed fix of "ACNH 60 FPS + SpeedFix" v1.4.5 by tmouh (https://github.com/tmouh/acnh-60fps, CC BY-NC 4.0) for 3.0.3's main, the 6,810
records of its IPS as cut rows with the game's original bytes (speedfix60/main.tsv, dm/tools/ips_rows.py; speedfix60/NOTICE.md says what was converted). The game's
per-frame speeds are halved, its accelerations quartered and its frame counts doubled, so at 60 fps it plays at the speed it has at 30. game.tsv `variant 60 main 60fps`
rewrites main a second time with them into <pack>/60/ (scripts/pack_android.py rewrite_variants); the launcher's Frame rate cap of 60 starts it
(Profile.fpsVariant). The rows apply to main build ff1d1c05670db6021c85b624a710b963 only: patch_nso.py asserts each original, and a pack of another main build lists
no variant (pack_android.py variant_overlays).

The SHA-256 of the two data files is pinned here, so that a change to either changes this file, and with it the rewrite key of the game's modules: a pack made
with older rows is then rebuilt, its game data with it (speedfix60/romfs.tsv, which the pack step applies)."""
import hashlib, os

_HERE = os.path.dirname(os.path.abspath(__file__))
_MAIN = 'ff1d1c05670db6021c85b624a710b963'   # 3.0.3 (v2228224)
_SPEEDFIX_SHA256 = {
    'speedfix60/main.tsv': 'be0da9559c4981877edbd5ce3406676095231f875e2dfa61e56938ea7063b32b',
    'speedfix60/romfs.tsv': 'e4fa67842fd1c3505fd06403d9354b875c1c8fbd5926b5bf2d6645f964ecf84f',
}


def _rows(rel):
    """The cut rows (offset, original hex, new hex, reason) of a speedfix60 rows file: <offset> <original> <new>, the comment line above a group its reason"""
    path = os.path.join(_HERE, *rel.split('/'))
    with open(path, 'rb') as f: data = f.read()
    assert hashlib.sha256(data).hexdigest() == _SPEEDFIX_SHA256[rel], f'{rel}: not the file this patches.py pins (_SPEEDFIX_SHA256)'
    out, why = [], ''
    for line in data.decode('utf-8').splitlines():
        if not line.strip(): continue
        if line.startswith('#'): why = line[1:].strip(); continue
        off, ob, nb = line.split('\t')
        out.append((int(off, 16), ob, nb, 'ACNH 60 FPS + SpeedFix (tmouh): ' + why))
    return out


def _have():
    """Whether both data files are here, as pinned. An APK without this game carries every profile's patches.py but not this folder (android/app/build.gradle
    tools.zip): the variant is then simply not there, and no other game's rewrite is stopped by it. A file that is here but not as pinned is an error"""
    paths = {rel: os.path.join(_HERE, *rel.split('/')) for rel in _SPEEDFIX_SHA256}
    if not all(os.path.isfile(p) for p in paths.values()): return False
    for rel, p in paths.items():   # the game data rows are read by the pack step, not here: only their bytes are checked
        with open(p, 'rb') as f: assert hashlib.sha256(f.read()).hexdigest() == _SPEEDFIX_SHA256[rel], f'{rel}: not the file this patches.py pins (_SPEEDFIX_SHA256)'
    return True


VARIANT_CUTS = {'60': {_MAIN: _rows('speedfix60/main.tsv')}} if _have() else {}

BINDING = [
    {'what': "Animal Crossing: New Horizons' 60 fps variant (VARIANT_CUTS['60']: tmouh's ACNH 60 FPS + SpeedFix v1.4.5, speedfix60/main.tsv)", 'now': 'build', 'goal': 'build',
     'purpose': 'frame rate', 'why': "a third-party mod's patch for one main build, used as its author made it: another main build needs the author's patch for that build"},
]
