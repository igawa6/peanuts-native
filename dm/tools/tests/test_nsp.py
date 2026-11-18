"""dm/tools/nsp.py and scripts/nsp_extract.py: the plaintext NSP NSP Box writes (PFS0 of unsigned NCAs), against NSPs built here by a small writer written from the
layout in nsp.py's header: a game snapshot, a game with a version, a DLC, the folder dump they extract to (read by pack_dump.scan), and what is refused (an encrypted NCA,
a patch, a damaged NCA, a stray file, an existing folder). No game files needed."""
import hashlib
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
import nsp
import nsp_extract   # pyright: ignore[reportMissingImports]  # PEANUTS(upstream-candidate): scripts/ is added to sys.path above.
import nup
import pack_dump   # pyright: ignore[reportMissingImports]  # PEANUTS(upstream-candidate): scripts/ is added to sys.path above.
import romfs as romfs_tool

TID = 0x0400AAAA00002000
DLC = TID + 0x1001
NONE = 0xFFFFFFFF


def align(n, a): return (n + a - 1) // a * a


def npdm(tid):
    d = bytearray(0x100)
    d[:4] = b'META'
    struct.pack_into('<II', d, 0x70, 0x80, 0x20)
    d[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', d, 0x90, tid)
    return bytes(d)


def pfs0(entries):
    names = b''.join(n.encode() + b'\0' for n, _ in entries)
    names += bytes(align(0x10 + 0x18 * len(entries) + len(names), 0x10) - (0x10 + 0x18 * len(entries) + len(names)))
    hdr, body, name_off = bytearray(struct.pack('<4sIII', b'PFS0', len(entries), len(names), 0)), b'', 0
    for n, d in entries:
        hdr += struct.pack('<QQII', len(body), len(d), name_off, 0)
        name_off += len(n.encode()) + 1
        body += d + bytes(align(len(d), 0x10) - len(d))
    return bytes(hdr) + names + body


def romfs(tree):
    """tree: {path: bytes}; the console's tables (no hash buckets: one bucket, everything chained, which nsp.py ignores)"""
    dirs = {'': {'dirs': [], 'files': []}}
    for p in sorted(tree):
        parts = p.split('/')
        for i in range(len(parts) - 1):
            d = '/'.join(parts[:i + 1])
            if d not in dirs:
                dirs[d] = {'dirs': [], 'files': []}
                dirs['/'.join(parts[:i])]['dirs'].append(d)
        dirs['/'.join(parts[:-1])]['files'].append(p)
    order = ['']
    def walk(d):
        for c in sorted(dirs[d]['dirs']): order.append(c); walk(c)
    walk('')
    doff, off = {}, 0
    for d in order:
        doff[d] = off; off += 0x18 + align(len(d.rsplit('/', 1)[-1].encode()) if d else 0, 4)
    dt = bytearray(off)
    foff, fo, data, fpos = {}, 0, b'', {}
    for d in order:
        for f in dirs[d]['files']:
            foff[f] = fo; fo += 0x20 + align(len(f.rsplit('/', 1)[-1].encode()), 4)
    ft = bytearray(fo)
    for d in order:
        n = d.rsplit('/', 1)[-1].encode() if d else b''
        kids = sorted(dirs[d]['dirs']); files = dirs[d]['files']
        sib = NONE
        parent = d.rsplit('/', 1)[0] if '/' in d else ''
        sibs = sorted(dirs[parent]['dirs']) if d else []
        if d and sibs.index(d) + 1 < len(sibs): sib = doff[sibs[sibs.index(d) + 1]]
        struct.pack_into('<6I', dt, doff[d], doff[parent] if d else 0, sib, doff[kids[0]] if kids else NONE, foff[files[0]] if files else NONE, NONE, len(n))
        dt[doff[d] + 0x18:doff[d] + 0x18 + len(n)] = n
        for i, f in enumerate(files):
            n = f.rsplit('/', 1)[-1].encode()
            data += bytes(align(len(data), 0x10) - len(data)); fpos[f] = len(data)
            struct.pack_into('<IIQQII', ft, foff[f], doff[d], foff[files[i + 1]] if i + 1 < len(files) else NONE, len(data), len(tree[f]), NONE, len(n))
            ft[foff[f] + 0x20:foff[f] + 0x20 + len(n)] = n
            data += tree[f]
    dhb, fhb = struct.pack('<I', NONE), struct.pack('<I', NONE)
    dhpos = 0x50; dtpos = dhpos + len(dhb); fhpos = dtpos + len(dt); ftpos = fhpos + len(fhb); datap = align(ftpos + len(ft), 0x10)
    return struct.pack('<10Q', 0x50, dhpos, len(dhb), dtpos, len(dt), fhpos, len(fhb), ftpos, len(ft), datap) + dhb + bytes(dt) + fhb + bytes(ft) + bytes(datap - ftpos - len(ft)) + data


def section(kind, payload):
    """(FS header, body) of a PFS0 (kind 1) or RomFS (kind 0) section: the payload follows a fake hash area at 0x200"""
    fh = bytearray(0x200)
    if kind == 1:
        struct.pack_into('<HBBB', fh, 0, 2, 1, 2, 1)
        struct.pack_into('<IIQQQQ', fh, 0x28, 0x10000, 2, 0, 32, 0x200, len(payload))
    else:
        struct.pack_into('<HBBB', fh, 0, 2, 0, 3, 1)
        struct.pack_into('<4sIII', fh, 8, b'IVFC', 0x20000, 32, 7)
        for i in range(6): struct.pack_into('<QQII', fh, 0x18 + 24 * i, 0 if i < 5 else 0x200, 0x200 if i < 5 else len(payload), 14, 0)
    return bytes(fh), bytes(0x200) + payload + bytes(align(len(payload), 0x200) - len(payload))


def nca(secs, tid, ctype):
    hdr = bytearray(0xC00)
    hdr[0x200:0x204] = b'NCA3'
    hdr[0x204:0x206] = bytes([0, ctype])
    struct.pack_into('<Q', hdr, 0x210, tid)
    body, off = b'', 0xC00
    for i, (fh, b) in enumerate(secs):
        struct.pack_into('<IIII', hdr, 0x240 + 16 * i, off // 512, (off + len(b)) // 512, 0, 0)
        hdr[0x400 + 0x200 * i:0x600 + 0x200 * i] = fh
        body += b; off += len(b)
    struct.pack_into('<Q', hdr, 0x208, off)
    return bytes(hdr) + body


def cnmt(tid, version, typ, records, extended=b''):
    h = bytearray(0x20)
    struct.pack_into('<QIBBHHH', h, 0, tid, version, typ, 0, len(extended), len(records), 0)
    body = bytes(h) + extended + b''.join(d + d[:16] + len(n).to_bytes(5, 'little') + bytes([0, t, 0]) for d, n, t in records)
    return body + hashlib.sha256(body).digest()


def build(kind, tid, version, game=None, extra=None, patch=False, wrong_id=False):
    """An NSP: kind 'game' (Program + Control NCAs) or 'dlc' (a Data NCA); returns its bytes"""
    ents, recs = [], []
    def add(blob, t):
        d = hashlib.sha256(blob).digest()
        ents.append((d[:16].hex() + '.nca', blob)); recs.append((d, blob, t))
    if kind == 'game':
        ex = {'main': b'NSO0' + bytes(60), 'main.npdm': npdm(tid)}
        add(nca([section(1, pfs0(sorted(ex.items()))), section(0, romfs(game))], tid, 0), 1)
        add(nca([section(0, romfs({'control.nacp': bytes(0x4000), 'icon_AmericanEnglish.dat': b'\xff\xd8jpg'}))], tid, 2), 3)
        typ, ext = 0x80, struct.pack('<QII', tid + 0x800, 0, 0)
    else:
        add(nca([section(0, romfs(game))], tid + (1 if wrong_id else 0), 4), 2)
        typ, ext = 0x82, struct.pack('<QI', TID, 0) + bytes(4)
    c = cnmt(tid, version, 0x81 if patch else typ, [(d, b, t) for d, b, t in recs], ext)
    name = f'{"Application" if kind == "game" else "AddOnContent"}_{tid:016x}.cnmt'
    meta = nca([section(1, pfs0([(name, c)]))], tid, 1)
    ents.append((hashlib.sha256(meta).digest()[:16].hex() + '.cnmt.nca', meta))
    ents += extra or []
    return pfs0(ents)


GAME0 = {'a.bin': b'hello' * 1000, 'sub/b.bin': b'', 'sub/deep/c.bin': bytes(range(256)), 'z.txt': b'z'}
GAME1 = dict(GAME0, **{'new.bin': b'new' * 40})
DLCT = {'dlc.bin': b'dlc data' * 500, 'x/y.bin': b'1'}


class Base(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory()
        self.dir = Path(self.t.name)
    def tearDown(self): self.t.cleanup()
    def write(self, name, data):
        p = self.dir / name
        p.write_bytes(data)
        return p


class Read(Base):
    def test_game(self):
        with nsp.open_nsp(self.write('g.nsp', build('game', TID, 0, GAME0)), verify=True) as n:
            (p,) = n.packages
            self.assertEqual((p.kind, p.id, p.version), (nsp.APPLICATION, TID, 0))
            self.assertEqual(sorted(p.files), sorted(['exefs/main', 'exefs/main.npdm', 'control/control.nacp', 'control/icon_AmericanEnglish.dat'] + ['romfs/' + k for k in GAME0]))
            for k, v in GAME0.items():
                off, size = p.files['romfs/' + k]
                self.assertEqual(bytes(n.read(off, size)), v)

    def test_dlc(self):
        with nsp.open_nsp(self.write('d.nsp', build('dlc', DLC, 0, DLCT)), verify=True) as n:
            (p,) = n.packages
            self.assertEqual((p.kind, p.id), (nsp.ADDON, DLC))
            self.assertEqual(sorted(p.files), ['romfs/dlc.bin', 'romfs/x/y.bin'])


class Refused(Base):
    def refused(self, data, text, verify=True):
        with self.assertRaisesRegex(nsp.Error, text): nsp.open_nsp(self.write('x.nsp', data), verify=verify).close()

    def test_encrypted_nca(self):
        raw = bytearray(build('game', TID, 0, GAME0))
        at = bytes(raw).find(b'NCA3')
        raw[at:at + 4] = b'\x01\x02\x03\x04'
        self.refused(bytes(raw), 'encrypted|plain|name', verify=False)

    def test_patch_record(self): self.refused(build('game', TID, 0, GAME0, patch=True), 'Patch|record')
    def test_damaged_nca(self):
        raw = bytearray(build('game', TID, 0, GAME0))
        raw[raw.index(b'hello' * 10)] ^= 1
        self.refused(bytes(raw), 'does not match')
    def test_damaged_passes_without_verify(self):
        raw = bytearray(build('game', TID, 0, GAME0))
        raw[raw.index(b'hello' * 10)] ^= 1
        nsp.open_nsp(self.write('x.nsp', bytes(raw)), verify=False).close()
    def test_stray_file(self): self.refused(build('game', TID, 0, GAME0, extra=[('a' * 32 + '.nca', bytes(0xC00))]), 'no record names|encrypted|plain|name')
    def test_ticket(self): self.refused(build('game', TID, 0, GAME0, extra=[('x.tik', b'1')]), 'not NCAs')
    def test_wrong_program_id(self): self.refused(build('dlc', DLC, 0, DLCT, wrong_id=True), 'program ID')
    def test_not_pfs0(self): self.refused(bytes(0x400), 'PFS0')
    def test_truncated(self): self.refused(build('game', TID, 0, GAME0)[:200], 'outside|cut off|missing')


class Extract(Base):
    def run_extract(self, *nsps, out=None, **kw):
        out = out or self.dir / 'dump'
        for p in nsps: nsp_extract.extract(p, out, say=lambda *a: None, **kw)
        return out

    def test_versioned_game_and_dlc_make_a_dump(self):
        out = self.run_extract(self.write('g.nsp', build('game', TID, 131072, GAME1)), self.write('d.nsp', build('dlc', DLC, 0, DLCT)))
        v = out / 'updates/131072'
        for k, val in GAME1.items(): self.assertEqual((v / 'romfs' / k).read_bytes(), val)
        self.assertEqual((v / 'exefs/main.npdm').read_bytes(), npdm(TID))
        self.assertTrue((v / 'control/control.nacp').is_file() and any((v / 'meta').glob('*.cnmt')))
        self.assertEqual((out / f'dlc/{DLC:016X}/0/romfs/x/y.bin').read_bytes(), b'1')
        app, vers, dlc = pack_dump.scan(out)   # the tools read it as any flattened dump
        self.assertEqual(app, TID)
        self.assertEqual(list(vers), [131072])
        self.assertEqual(list(dlc), [DLC])

    def test_version_zero_is_base(self):
        out = self.run_extract(self.write('g.nsp', build('game', TID, 0, GAME0)))
        self.assertEqual((out / 'base/romfs/a.bin').read_bytes(), GAME0['a.bin'])
        self.assertEqual(pack_dump.scan(out)[0], TID)

    def test_existing_folder_kept(self):
        p = self.write('g.nsp', build('game', TID, 0, GAME0))
        out = self.run_extract(p)
        (out / 'base/romfs/a.bin').write_bytes(b'mine')
        with self.assertRaisesRegex(nsp_extract.Error, 'exists already'): self.run_extract(p, out=out)
        self.assertEqual((out / 'base/romfs/a.bin').read_bytes(), b'mine')

    def test_damaged_leaves_nothing(self):
        raw = bytearray(build('game', TID, 0, GAME0))
        raw[raw.index(b'hello' * 10)] ^= 1
        with self.assertRaises(nsp.Error): self.run_extract(self.write('g.nsp', bytes(raw)))
        self.assertFalse(any((self.dir / 'dump').rglob('*')))

    def test_cli(self):
        p = self.write('g.nsp', build('game', TID, 0, GAME0))
        self.assertEqual(nsp_extract.main([str(p), '--out', str(self.dir / 'o')]), 0)
        self.assertTrue((self.dir / 'o/base/exefs/main').is_file())
        self.assertEqual(nsp_extract.main([str(p), '--out', str(self.dir / 'o')]), 1)


class InPlace(Base):
    """An NSP read where it is, as a NUP file (nup.py's synthetic TOC): paths `<file>.nsp:update/<version>/romfs/...`, romfs.py, pack_dump.scan, the descriptor"""
    def setUp(self):
        super().setUp()
        self.g = self.write('game.nsp', build('game', TID, 131072, GAME1))
        self.d = self.write('dlc.nsp', build('dlc', DLC, 0, DLCT))

    def test_packages_and_files(self):
        n = nup.open_nup(self.g)
        self.assertEqual([(p.selector, p.title_id, p.version) for p in n.packages], [('update/131072', TID, 131072)])
        for k, v in GAME1.items():
            self.assertEqual(n.read(n.node(f'update/131072/romfs/{k}')), v)
        self.assertEqual(n.read(n.node('update/131072/exefs/main.npdm')), npdm(TID))
        self.assertEqual(sorted(x for x, _ in n.children(n.node('update/131072/exefs'))), ['main', 'main.npdm'])
        self.assertEqual(nup.default_romfs(self.g), f'{self.g}:update/131072/romfs')

    def test_version_zero_is_base(self):
        g0 = self.write('g0.nsp', build('game', TID, 0, GAME0))
        self.assertEqual([(p.selector, p.flags) for p in nup.open_nup(g0).packages], [('base', 0)])

    def test_romfs_view(self):
        p = romfs_tool.Path(f'{self.g}:update/131072/romfs')
        self.assertEqual((p / 'sub/deep/c.bin').read_bytes(), GAME1['sub/deep/c.bin'])
        self.assertEqual(sorted(c.name for c in p.iterdir()), sorted({k.split('/')[0] for k in GAME1}))

    def test_scan_with_dlc_beside(self):
        app, vers, dlc = pack_dump.scan(self.g)
        self.assertEqual(app, TID)
        self.assertEqual(list(vers), [131072])
        self.assertEqual(list(dlc), [DLC])
        self.assertEqual((dlc[DLC] / 'x/y.bin').read_bytes(), b'1')

    def test_other_version_beside_is_found(self):
        self.write('other.nsp', build('game', TID, 196608, GAME0))
        app, vers, dlc = pack_dump.scan(self.g)
        self.assertEqual(sorted(vers), [131072, 196608])
        self.assertTrue(str(vers[196608]).endswith('other.nsp:update/196608'))

    def test_descriptor(self):
        import os
        fd = os.open(self.g, os.O_RDONLY)
        try:
            with mock.patch.dict(os.environ, {'DM_NUP_FD': f'{fd}:{self.dir / "stand-in.nsp"}'}):
                n = nup.open_nup(self.dir / 'stand-in.nsp')
                self.assertEqual(n.read(n.node('update/131072/romfs/a.bin')), GAME1['a.bin'])
        finally: os.close(fd)

    def test_damaged_header_refused(self):
        raw = bytearray(build('game', TID, 131072, GAME1))
        raw[raw.index(b'NCA3')] = 0x58   # not an NCA3 magic
        with self.assertRaises(nup.Error): nup.open_nup(self.write('bad.nsp', bytes(raw)))


@unittest.skipUnless(shutil.which('clang') and shutil.which('make'), 'no C compiler')
class Host(Base):
    """The C host's reader (dm/host/nsp_toc.h through nup_image.h) mounts an NSP's RomFS as the folder nsp_extract.py unpacks it to: dm/host/tests t_nup_dump reads every
    file through hle_fs.c and compares it with the folder's (a second reader of the NSP, written separately)"""
    def test_every_file_equal(self):
        tests = REPO / 'dm/host/tests'
        subprocess.run(['make', '-C', str(tests), 'GAME=040074A01BF12000', 'build/t_nup_dump'], check=True, capture_output=True, text=True)
        big = dict(GAME1, **{'big.bin': bytes(range(251)) * 9000, 'sub/more/e.bin': b'e' * 33})
        g = self.write('game.nsp', build('game', TID, 131072, big))
        out = self.dir / 'dump'
        nsp_extract.extract(g, out, say=lambda *a: None)
        r = subprocess.run([str(tests / 'build/t_nup_dump'), f'{g}:update/131072', str(out / 'updates/131072/romfs')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('every file matches', r.stdout)
        d = self.write('dlc.nsp', build('dlc', DLC, 0, DLCT))
        nsp_extract.extract(d, out, say=lambda *a: None)
        r = subprocess.run([str(tests / 'build/t_nup_dump'), f'{d}:addon/{DLC:016x}', str(out / f'dlc/{DLC:016X}/0/romfs')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        r = subprocess.run([str(tests / 'build/t_nup_dump'), f'{d}:aoc/{DLC & 0xFFF}', str(out / f'dlc/{DLC:016X}/0/romfs')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Launcher(Base):
    """The launcher's reader (Nup with Nsp, android/app/src/jvmtest NspJvmTest) reads each package of an NSP as nsp_extract.py unpacks it"""
    STUB = """package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
    public static final String NX_DMSH_VK_CHAIN = "6607c8d19abc";
    public static final String NX_DMSH_VK_ABI = "01b52a48538b";
    public static final String NX_DMSH_VK_ABI_COMPAT = "";
}
"""
    def test_packages(self):
        src = REPO / 'android/app/src/main/java/dev/nxnative/android'
        (self.dir / 'BuildConfig.java').write_text(self.STUB)
        subprocess.run(['javac', '-d', str(self.dir / 'classes')] + [str(src / f'{c}.java') for c in ('Nup', 'Nsp', 'Zstd', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Profile', 'ShaderLibs', 'PickedPath', 'CopyPlan', 'PackFind')]
                       + [str(REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/NspJvmTest.java'), str(self.dir / 'BuildConfig.java')], check=True, capture_output=True, text=True)
        out = self.dir / 'dump'
        big = dict(GAME1, **{'big.bin': bytes(range(251)) * 9000, 'sub/more/e.bin': b'e' * 33})
        g = self.write('game.nsp', build('game', TID, 131072, big))
        d = self.write('dlc.nsp', build('dlc', DLC, 0, DLCT))
        g0 = self.write('base.nsp', build('game', TID, 0, GAME0))
        for f in (g, d, g0): nsp_extract.extract(f, out, say=lambda *a: None) if f != g0 else nsp_extract.extract(f, self.dir / 'dump0', say=lambda *a: None)
        for nspf, sel, folder, pid in ((g, 'update/131072', out / 'updates/131072', f'{TID:016X}'), (g0, 'base', self.dir / 'dump0/base', f'{TID:016X}'),
                                       (d, f'addon/{DLC:016x}', out / f'dlc/{DLC:016X}/0', '-')):
            r = subprocess.run(['java', '-cp', str(self.dir / 'classes'), 'dev.nxnative.android.NspJvmTest', str(nspf), sel, str(folder), pid], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)
        beside = self.dir / 'beside'
        beside.mkdir()
        (beside / 'newer.nsp').write_bytes(build('game', TID, 196608, GAME1))
        (beside / 'older.nsp').write_bytes(build('game', TID, 131072, GAME0))
        (beside / 'any name.nsp').write_bytes(build('dlc', DLC, 0, DLCT))
        bad = self.dir / 'bad'
        bad.mkdir()
        raw = bytearray(build('game', TID, 0, GAME0))
        at = bytes(raw).find(b'NCA3')
        raw[at:at + 4] = b'\x01\x02\x03\x04'
        (bad / 'encrypted.nsp').write_bytes(bytes(raw))
        (bad / 'cut.nsp').write_bytes(build('game', TID, 0, GAME0)[:400])
        (bad / 'junk.nsp').write_bytes(b'not an nsp' * 40)
        (bad / 'nometa.nsp').write_bytes(pfs0([('readme.txt', b'hello' * 40)]))
        (bad / 'dlc.nsp').write_bytes(build('dlc', DLC, 0, DLCT))
        r = subprocess.run(['java', '-cp', str(self.dir / 'classes'), 'dev.nxnative.android.NspJvmTest', 'refused'] + [str(bad / f) for f in ('encrypted.nsp', 'cut.nsp', 'junk.nsp', 'nometa.nsp', 'dlc.nsp')],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        r = subprocess.run(['java', '-cp', str(self.dir / 'classes'), 'dev.nxnative.android.NspJvmTest', 'beside', str(beside), 'newer.nsp', 'older.nsp', 'any name.nsp'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
