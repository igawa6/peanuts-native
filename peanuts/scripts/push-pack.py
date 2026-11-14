#!/usr/bin/env python3
# Copies a pack made on this computer to the Android device over adb, beside the game folder, where the app finds it by itself (android/app PackFind:
# <dir>/<titleID>-work/android-pack). With --game, also the game: a .nup file as it is, into <dest>/; a dump folder as what the phone needs of it
# (android/TESTING.md section 3): base/exefs, the update's folder and dlc/, into <dest>/<its name>/; base/romfs stays here (it is large). Each folder goes to <name>.tmp first and replaces its name in one shell step, so the app never sees half a copy.
#   push-pack.py <work>/android-pack
#   push-pack.py <work>/android-pack --game "<dump>/DonkeyKongBananza" --version 458752 --dest /sdcard/SW2 --dry-run
#   push-pack.py <work>/android-pack --game "<folder>/Game [<titleID>].nup" --dest "/sdcard/switch 2/Game"
# Needs adb on PATH. --dry-run prints the adb commands instead of running them.
import argparse, posixpath, re, shlex, subprocess, sys
from pathlib import Path


def die(msg): sys.exit(f'[RED] {msg}')


def pack_title(pack):
    """The title ID of the pack: its pack.tsv's title_id row; else the <titleID>-work folder it is in"""
    for line in (pack / 'pack.tsv').read_text(encoding='utf-8', errors='replace').splitlines():
        key, _, val = line.partition('\t')
        if key == 'title_id' and re.fullmatch(r'[0-9A-Fa-f]{16}', val.strip()): return val.strip().upper()
    m = re.fullmatch(r'([0-9A-Fa-f]{16})-work', pack.resolve().parent.name)
    if m: return m.group(1).upper()
    die(f'{pack}: pack.tsv has no title_id row and the folder is not in an <titleID>-work folder: the title ID is unknown')


def devices():
    """The serials of the devices adb lists as 'device' (not offline, not unauthorized)"""
    try: r = subprocess.run(['adb', 'devices'], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as e: die(f'adb devices failed ({e}); is adb on PATH?')
    return [l.split('\t')[0] for l in r.stdout.splitlines()[1:] if l.strip().endswith('\tdevice')]


def adb(serial, args, dry):
    cmd = ['adb', '-s', serial, *args]
    print('+ ' + shlex.join(cmd), flush=True)
    if dry: return
    try: subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError as e: die(f'{shlex.join(cmd)} failed with exit code {e.returncode}')


def push_dir(serial, local, remote, dry):
    """local folder -> the device path remote (its parent made first); the copy goes to remote.tmp, then replaces remote in one shell call"""
    tmp = remote + '.tmp'
    adb(serial, ['shell', shlex.join(['mkdir', '-p', posixpath.dirname(remote)])], dry)
    adb(serial, ['shell', shlex.join(['rm', '-rf', tmp])], dry)
    adb(serial, ['push', str(local), tmp], dry)
    adb(serial, ['shell', shlex.join(['find', tmp, '-name', '._*', '-type', 'f', '-delete'])], dry)   # macOS's AppleDouble files of an exFAT/FAT drive: not the pack's
    adb(serial, ['shell', shlex.join(['rm', '-rf', remote]) + ' && ' + shlex.join(['mv', tmp, remote])], dry)


def main():
    ap = argparse.ArgumentParser(description='Copy a pack made here to the Android device beside the game folder (the app finds it there).')
    ap.add_argument('pack', help='the android-pack folder (it holds pack.tsv)')
    ap.add_argument('--dest', default='/sdcard/SW2', help='the folder on the device that holds the game folder or the .nup (default /sdcard/SW2)')
    ap.add_argument('--serial', help='the adb serial; omitted: the one device adb lists')
    ap.add_argument('--game', help='also the game: a .nup file (to <dest>/), or the dump\'s game folder (base/exefs, the update and dlc/ to <dest>/<its name>/)')
    ap.add_argument('--version', help='the update to copy with --game (its updates/<V> folder; default the highest numeric one)')
    ap.add_argument('--dry-run', action='store_true', help='print the adb commands instead of running them')
    a = ap.parse_args()

    pack = Path(a.pack).expanduser()
    if not (pack / 'pack.tsv').is_file(): die(f'{pack} has no pack.tsv: give the android-pack folder')
    title = pack_title(pack)
    dest = a.dest.rstrip('/') or '/'
    if a.serial: serial = a.serial
    else:
        try: devs = devices()
        except SystemExit:
            if not a.dry_run: raise
            devs = []
        if len(devs) == 1: serial = devs[0]
        elif a.dry_run and not devs: serial = '<serial>'; print('(no device found: --dry-run shows <serial> for it)')
        elif not devs: die('no device in adb devices: connect the phone with USB debugging on, or pass --serial')
        else: die('several devices in adb devices: pass --serial, one of ' + ', '.join(devs))
    print(f'title {title}, device {serial}, to {dest}')

    work = f'{dest}/{title}-work'
    push_dir(serial, pack, f'{work}/android-pack', a.dry_run)
    adb(serial, ['shell', shlex.join(['ls', f'{work}/android-pack/pack.tsv'])], a.dry_run)
    print(f'pushed: {work}/android-pack (the app reads it there)')

    if a.game and Path(a.game).suffix.lower() == '.nup':   # a .nup: the file itself, beside the pack's <titleID>-work (PackFind looks in the .nup's folder)
        nup = Path(a.game).expanduser().resolve()
        if not nup.is_file(): die(f'{nup} is not a file')
        remote = f'{dest}/{nup.name}'
        adb(serial, ['shell', shlex.join(['rm', '-f', remote + '.tmp'])], a.dry_run)
        adb(serial, ['push', str(nup), remote + '.tmp'], a.dry_run)
        adb(serial, ['shell', shlex.join(['mv', '-f', remote + '.tmp', remote])], a.dry_run)
        print(f'pushed the game to {remote}')
        png = nup.with_suffix('.png')   # the icon peanuts_build.py wrote beside the .nup: the app shows it from beside the .nup on the device
        if png.is_file() and png.stat().st_size > 0:
            adb(serial, ['push', str(png), remote[:-4] + '.png'], a.dry_run)
            print(f'pushed the icon to {remote[:-4]}.png')
    elif a.game:
        game = Path(a.game).expanduser().resolve()
        if not (game / 'base' / 'exefs').is_dir(): die(f'{game} has no base/exefs: give the game folder the dump made')
        gdev = f'{dest}/{game.name}'
        push_dir(serial, game / 'base' / 'exefs', f'{gdev}/base/exefs', a.dry_run)
        versions = sorted((d.name for d in (game / 'updates').glob('*') if d.is_dir() and d.name.isdigit()), key=int) if (game / 'updates').is_dir() else []
        ver = a.version or (versions[-1] if versions else None)
        if ver:
            if not (game / 'updates' / ver).is_dir(): die(f'{game}/updates/{ver} is not there (updates: {", ".join(versions) or "none"})')
            push_dir(serial, game / 'updates' / ver, f'{gdev}/updates/{ver}', a.dry_run)
        else: print('no update in updates/: none copied')
        if (game / 'dlc').is_dir(): push_dir(serial, game / 'dlc', f'{gdev}/dlc', a.dry_run)
        else: print('no dlc/ folder: none copied')
        print(f'pushed the game parts to {gdev}/ (base/romfs stays on this computer)')


if __name__ == '__main__':
    try: main()
    except KeyboardInterrupt: sys.exit('[RED] stopped')
