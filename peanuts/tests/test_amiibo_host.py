"""amiibo in the host (dm/peanuts/peanuts_amiibo.c): the nfc and nfp take-overs with the app's virtual tag, only in a game started with PN_AMIIBO=1. A
harness includes the file with stand-ins for the host's event binding. Without PN_AMIIBO=1 the loader's lookup gets nothing from it and the app's calls are
refused. With it, the lookup finds every take-over, and the harness runs the game's calls as a game makes them (search, placed while searching, read, an
area created and written, unmount and the tag lifted a second later, placed in advance and found by the next search) and checks the structures, the
results and the files written. Made-up IDs only. Also the wiring: hle_sdk16.c's own nfc and nfp block stays whole, the loader asks peanuts_amiibo_force
first under PN_AMIIBO, the Android host defines it and compiles the file, the game's start adds PN_AMIIBO=1 only through AmiiboSwitch, Bridge declares
the calls."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'dm/peanuts/peanuts_amiibo.c'
SDK16 = ROOT / 'dm/host/hle_sdk16.c'
LOADER = ROOT / 'dm/host/loader.c'

# The host's own headers are for arm64 Android and Apple only: the test compiles a copy of the file beside a stand-in hle.h with what it uses
STAND_IN = r'''
#pragma once
#include <stdint.h>
#include <stdio.h>
typedef uint32_t Result;
#define RES_OK 0u
#define LOG(...) do { fprintf(stderr, "[dm] " __VA_ARGS__); fputc('\n', stderr); } while (0)
typedef struct { const char *name; void *fn; const char *why; } hle_force_ent;
#define PLAT_TABLE(name) __attribute__((used, section(#name), aligned(8)))   /* as on arm64: no padding between the entries (x86-64 compilers align a 24-byte global to 16) */
void dm_applet_enter(const char *name);
void dm_applet_leave(void);
#define DM_APPLET(name) dm_applet_enter(name); dm_applet_leave()
void hle_bind_system_event(void *g, int autoclear);
uint64_t hle_system_event_binding(void *g);
int hle_signal_system_event_binding(uint64_t binding);
'''

HARNESS = r'''
#include "peanuts/peanuts_amiibo.c"
#include <assert.h>
#include <unistd.h>
#include <sys/stat.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s (line %d)\n", msg, __LINE__); } } while (0)
static void *bound[8]; static int nbound; static int signals[8];
void hle_bind_system_event(void *ev, int autoclear) { (void)autoclear; for (int i = 0; i < nbound; i++) if (bound[i] == ev) return; bound[nbound++] = ev; }
uint64_t hle_system_event_binding(void *ev) { for (int i = 0; i < nbound; i++) if (bound[i] == ev) return (uint64_t)(i + 1); return 0; }
int hle_signal_system_event_binding(uint64_t b) { if (b) __atomic_add_fetch(&signals[b - 1], 1, __ATOMIC_SEQ_CST); return b != 0; }
uint32_t hle_hid_connected_npad(void) { return 0x20; }
void dm_applet_enter(const char *name) { (void)name; }
void dm_applet_leave(void) {}
static int sig(int i) { return __atomic_load_n(&signals[i], __ATOMIC_SEQ_CST); }
static int wait_for(int (*cond)(void), int ms) { for (int i = 0; i < ms / 10; i++) { if (cond()) return 1; usleep(10000); } return cond(); }
static NfpDeviceHandle dev;
static int state_is_removed(void) { return h_nfp_State(&dev) == ST_REMOVED; }
static int state_is_found(void) { return h_nfp_State(&dev) == ST_FOUND; }
static char dir[512];
static int file_size(const char *name) { char p[600]; struct stat st; snprintf(p, sizeof p, "%s/%s", dir, name); return stat(p, &st) == 0 ? (int)st.st_size : -1; }
static int area_written(void) { return file_size("area-0a0b0c0d.bin") == 0xd8 && file_size("common.txt") > 0; }
static int read_file(const char *name, void *buf, int n) { char p[600]; snprintf(p, sizeof p, "%s/%s", dir, name); FILE *f = fopen(p, "rb"); if (!f) return -1; int k = (int)fread(buf, 1, n, f); fclose(f); return k; }
static char mii_hex[0x58 * 2 + 1];
static char desc[4096];
static void describe(const char *extra) {
    snprintf(desc, sizeof desc, "key=test-one\nid=0102030405060702\nuid=04a1b2c3d4e5f6\nname=Testfigure\nmii=%s\ncreated=2026-01-02\nwritten=2026-03-04\n"
             "today=2026-11-24\nwrites=5\ndir=%s\ntitle=0100000000aa0000\n%s", mii_hex, dir, extra);
}

// Without PN_AMIIBO=1: the loader finds nothing here (and gives the game hle_sdk16.c's block), the app's calls are refused
static int off(void) {
    char st[512];
    CHECK(peanuts_amiibo_force("_ZN2nn3nfp10InitializeEv") == NULL, "off: no take-over for the loader");
    CHECK(peanuts_amiibo_force("_ZN2nn3nfc12IsNfcEnabledEv") == NULL, "off: nfc neither");
    describe("");
    CHECK(nxhost_peanuts_amiibo_place(desc) == 0, "off: placing is refused");
    CHECK(nxhost_peanuts_amiibo_wait_lib(0, 10) == -1, "off: the app's waiting thread is told so and ends");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(!strncmp(st, "enabled=0\n", 10) && strstr(st, "lib=0\n"), "off: the status says so");
    printf("amiibo host (off): %d checks, %d failed\n", checks, fails);
    return fails != 0;
}

int main(int argc, char **argv) {
    (void)argc; snprintf(dir, sizeof dir, "%s", argv[2]);
    for (int i = 0; i < 0x58; i++) snprintf(mii_hex + 2 * i, 3, "%02x", (i * 7 + 3) & 0xff);
    if (!strcmp(argv[1], "off")) return off();
    void *ev_act = malloc(64), *ev_deact = malloc(64);
    char st[512];

    // the app's screen starts before the host's main has set the game's environment: its early calls are refused and decide nothing (the AYN Thor:
    // the watcher's first call came 0.25 s before PN_AMIIBO=1 was set)
    describe("");
    CHECK(nxhost_peanuts_amiibo_place(desc) == 0, "before the modules load: placing is refused");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(!strncmp(st, "enabled=0\n", 10), "before the modules load: not on yet");
    CHECK(nxhost_peanuts_amiibo_wait_lib(0, 10) == -2, "before the modules load: the app's waiting thread asks again later");
    setenv("PN_AMIIBO", "1", 1);   // main.c sets the game's environment, then the loader resolves the modules

    // the loader's lookup: every take-over of the file, nothing else
    CHECK(peanuts_amiibo_force("_ZN2nn2fs8MountRomEPKc") == NULL, "the first lookup (another service's name) decides, and goes by");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(!strncmp(st, "enabled=1\n", 10), "on from the loader's first lookup, though the app asked earlier");
    CHECK(peanuts_amiibo_force("_ZN2nn3nfp10InitializeEv") == (void *)h_nfp_Init, "on: nfp::Initialize is the stand's");
    CHECK(peanuts_amiibo_force("_ZN2nn3nfc12IsNfcEnabledEv") == (void *)h_nfc_Enabled, "on: nfc::IsNfcEnabled too");
    CHECK(peanuts_amiibo_force("_ZN2nn3nfp99NotATakeOverEv") == NULL, "an nfp name it does not take over");
    CHECK(peanuts_amiibo_force("_ZN2nn2fs8MountRomEPKc") == NULL, "another service's name goes by");
    int entries = 0, found = 0;
    for (const hle_force_ent *e = __start_pnamiibo; e < __stop_pnamiibo; e++) { entries++; if (peanuts_amiibo_force(e->name) == e->fn && e->why && e->why[0]) found++; }
    CHECK(entries == ENTRIES && found == entries, "every take-over of the table is found, with its reason");

    CHECK(h_nfc_Enabled() == 1, "NFC is on");
    CHECK(h_nfp_LibState() == 0, "the library starts uninitialized");
    h_nfp_Init();
    CHECK(h_nfp_LibState() == 1, "initialized");
    CHECK(nxhost_peanuts_amiibo_wait_lib(0, 10) == 1, "the app's wait: the library is open");
    CHECK(nxhost_peanuts_amiibo_wait_lib(1, 30) == 1, "and stays open: the wait ends at its time");
    int n = 0; CHECK(h_nfp_List(&dev, &n, 1) == RES_OK && n == 1 && dev.id == NFP_DEVICE_ID, "one device");
    CHECK(h_nfp_AttachAct(ev_act, &dev) == RES_OK && h_nfp_AttachDeact(ev_deact, &dev) == RES_OK, "events attached");
    CHECK(h_nfp_State(&dev) == ST_INIT, "initialized device");

    // the game searches: the app sees it
    CHECK(h_nfp_Start(&dev) == RES_OK && h_nfp_State(&dev) == ST_SEARCH, "searching");
    usleep(20000);
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(strstr(st, "state=1\n") && strstr(st, "search=1\n") && !strstr(st, "searching_ms=0\n"), "status: searching, the first search, for some ms");
    uint8_t guard[0x80]; memset(guard, 0x5a, sizeof guard);
    CHECK(h_nfp_TagInfo(guard + 16, &dev) == NFP_RES(97), "no tag yet: tag not found");
    int untouched = 1; for (unsigned i = 0; i < sizeof guard; i++) if (guard[i] != 0x5a) untouched = 0;
    CHECK(untouched, "no tag: nothing written");

    // a bad description is refused
    CHECK(nxhost_peanuts_amiibo_place("key=x\nid=12\n") == 0, "an incomplete description is refused");
    CHECK(h_nfp_State(&dev) == ST_SEARCH, "still searching after a refused place");

    // placed while searching: found at once, activate signalled
    describe("area=00000001:a1a2a3\n");
    CHECK(nxhost_peanuts_amiibo_place(desc) == 1, "placed while searching: found");
    CHECK(h_nfp_State(&dev) == ST_FOUND && sig(0) == 1, "tag found, activate signalled once");
    NfpTagInfo ti; memset(&ti, 0xee, sizeof ti);
    CHECK(h_nfp_TagInfo(&ti, &dev) == RES_OK, "tag info");
    static const uint8_t uid[7] = {0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6};
    CHECK(!memcmp(ti.uid, uid, 7) && ti.uid[7] == 0 && ti.uid_len == 7 && ti.protocol == 1 && ti.type == 2, "UID, its length, NFC-A, type 2");
    NfpModelInfo mi;
    CHECK(h_nfp_ModelInfo(&mi, &dev) == NFP_RES(73), "model info needs a mount");
    CHECK(h_nfp_Mount(&dev, 0, MOUNT_ROM) == RES_OK && h_nfp_State(&dev) == ST_MOUNTED, "mounted (ROM)");
    CHECK(h_nfp_ModelInfo(&mi, &dev) == RES_OK, "model info");
    CHECK(mi.character[0] == 0x01 && mi.character[1] == 0x02 && mi.variant == 0x03 && mi.type == 0x04 && mi.number == 0x0506 && mi.series == 0x07,
          "model info from the ID: character bytes as stored, the number as a value");
    NfpCommonInfo ci;
    CHECK(h_nfp_CommonInfo(&ci, &dev) == NFP_RES(73), "common info needs the RAM mount");
    CHECK(h_nfp_Unmount(&dev) == RES_OK && h_nfp_State(&dev) == ST_FOUND, "unmounted");
    CHECK(h_nfp_Mount(&dev, 0, MOUNT_ROM | MOUNT_RAM) == RES_OK, "mounted again within the lift time (keeps it on)");
    CHECK(h_nfp_CommonInfo(&ci, &dev) == RES_OK && ci.written.year == 2026 && ci.written.month == 3 && ci.written.day == 4 && ci.writes == 5 && ci.area_size == 0xd8,
          "common info: last write, count, area size");
    NfpRegisterInfo ri;
    CHECK(h_nfp_RegInfo(&ri, &dev) == RES_OK && !strcmp(ri.name, "Testfigure") && ri.created.year == 2026 && ri.created.month == 1 && ri.mii[0] == 3 && ri.mii[1] == 10,
          "register info: nickname, created, the owner's Mii");

    // areas: one from the app, one the game creates
    uint8_t buf[0x100]; uint64_t got = 0;
    CHECK(h_nfp_OpenArea(&dev, 1) == RES_OK && h_nfp_GetArea(buf, &got, &dev, sizeof buf) == RES_OK && got == 0xd8 && buf[0] == 0xa1 && buf[2] == 0xa3 && buf[3] == 0,
          "the app's area opens and reads back, the rest zero");
    CHECK(h_nfp_OpenArea(&dev, 0x0a0b0c0d) == NFP_RES(128), "no area of this game yet");
    uint8_t init[16]; for (int i = 0; i < 16; i++) init[i] = (uint8_t)(0x40 + i);
    NfpAreaCreateInfo cinfo; memset(&cinfo, 0, sizeof cinfo); cinfo.access = 0x0a0b0c0d; cinfo.data = init; cinfo.size = sizeof init;
    CHECK(h_nfp_CreateArea(&dev, &cinfo) == RES_OK, "area created");
    CHECK(wait_for(area_written, 2000), "the new area is written to the amiibo's folder");
    uint8_t file[0xd8]; char title[32] = "";
    CHECK(read_file("area-0a0b0c0d.bin", file, sizeof file) == 0xd8 && file[0] == 0x40 && file[15] == 0x4f && file[16] == 0, "its bytes");
    CHECK(read_file("area-0a0b0c0d.title", title, sizeof title - 1) == 16 && !strncmp(title, "0100000000aa0000", 16), "the game that made it");
    CHECK(h_nfp_CreateArea(&dev, &cinfo) == NFP_RES(168), "creating it twice: it exists");
    CHECK(h_nfp_OpenArea(&dev, 0x0a0b0c0d) == RES_OK, "this game's area opens");
    uint8_t big[0xd9] = {0};
    CHECK(h_nfp_SetArea(&dev, big, sizeof big) == NFP_RES(68), "too large");
    uint8_t upd[4] = {9, 8, 7, 6};
    CHECK(h_nfp_SetArea(&dev, upd, sizeof upd) == RES_OK && h_nfp_Flush(&dev) == RES_OK, "changed and flushed");
    usleep(300000);
    CHECK(read_file("area-0a0b0c0d.bin", file, sizeof file) == 0xd8 && file[0] == 9 && file[3] == 6 && file[4] == 0, "the change is in the file");
    char common[64] = ""; read_file("common.txt", common, sizeof common - 1);
    CHECK(strstr(common, "writes=7\n") && strstr(common, "written=2026-11-24\n"), "two writes counted, today's date");
    CHECK(h_nfp_CommonInfo(&ci, &dev) == RES_OK && ci.writes == 7 && ci.written.month == 11, "common info follows the writes");
    cinfo.size = 4;
    CHECK(h_nfp_RecreateArea(&dev, &cinfo) == RES_OK, "recreated");

    // the amiibo settings applet: already registered
    NfpDeviceHandle out; uint8_t registered = 0; memset(&ri, 0, sizeof ri);
    CHECK(h_nfp_Nick(&out, &registered, &ri, NULL, &ti) == RES_OK && registered == 1 && out.id == NFP_DEVICE_ID && !strcmp(ri.name, "Testfigure"), "nickname and owner: done");

    // the game is done: unmount, and the tag leaves the stand a second later
    CHECK(h_nfp_Unmount(&dev) == RES_OK, "unmounted");
    CHECK(h_nfp_State(&dev) == ST_FOUND, "still on the stand at first");
    CHECK(wait_for(state_is_removed, 2500), "lifted off on its own");
    CHECK(sig(1) == 1, "deactivate signalled once");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(strstr(st, "on=\n") && strstr(st, "reads=2\n") && strstr(st, "access=0a0b0c0d\n"), "status: nothing on the stand, two mounts, the area opened last");

    // placed in advance: the next search finds it after a moment
    describe("");
    CHECK(h_nfp_Stop(&dev) == RES_OK && h_nfp_State(&dev) == ST_INIT, "detection stopped");
    CHECK(nxhost_peanuts_amiibo_place(desc) == 2, "placed in advance: kept");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(strstr(st, "ready=test-one\n") != NULL, "status: ready");
    CHECK(h_nfp_Start(&dev) == RES_OK && h_nfp_State(&dev) == ST_SEARCH, "the next search");
    CHECK(wait_for(state_is_found, 1500), "found by it");
    CHECK(sig(0) == 2, "activate signalled again");
    CHECK(h_nfp_Mount(&dev, 0, 3) == RES_OK && h_nfp_OpenArea(&dev, 0x0a0b0c0d) == NFP_RES(128), "the app's description had no area: this game has none");
    nxhost_peanuts_amiibo_remove();
    CHECK(h_nfp_State(&dev) == ST_REMOVED && sig(1) == 2 && h_nfp_TagInfo(&ti, &dev) == NFP_RES(97), "taken off by the app");
    nxhost_peanuts_amiibo_status(st, sizeof st);
    CHECK(strstr(st, "search=2\n") != NULL, "two searches counted");
    h_nfp_Fini();
    CHECK(h_nfp_LibState() == 0, "finalized");
    CHECK(nxhost_peanuts_amiibo_wait_lib(1, 10) == 0, "the app's wait: the library is closed");
    printf("amiibo host: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
'''


def own_entries():
    return re.findall(r'PN_FORCE\("(_ZN2nn3nf[cp][^"]+)"', SRC.read_text(encoding='utf-8'))


class Wiring(unittest.TestCase):
    def test_upstream_block_stays_whole(self):
        """hle_sdk16.c is not touched: every game amiibo is off for gets its nfc and nfp block as upstream wrote it"""
        s = SDK16.read_text(encoding='utf-8')
        self.assertNotIn('PN_AMIIBO', s)
        self.assertNotIn('PEANUTS', s[s.index('// ---------- nfc: the NFC library beside nfp ----------'):s.index('// ---------- vi: vsync event ----------')])
        own = own_entries()
        theirs = set(re.findall(r'HLE_FORCE\("(_ZN2nn3nf[cp][^"]+)"', s))
        self.assertEqual(len(own), len(set(own)), 'no name twice in the stand\'s table')
        self.assertEqual(set(own), theirs, 'peanuts_amiibo.c takes over exactly the symbols the upstream block does')
        self.assertNotIn('HLE_FORCE("', SRC.read_text(encoding='utf-8'), 'none of them in hle.h\'s table, where the loader would die on the duplicate')

    def test_loader_asks_the_stand_first(self):
        s = LOADER.read_text(encoding='utf-8')
        body = s[s.index('static void *force_lookup(const char *name) {'):]
        body = body[:body.index('\n}\n')]
        hook = body[body.index('#ifdef PN_AMIIBO'):body.index('#endif')]
        self.assertIn('PEANUTS', hook)
        self.assertIn('peanuts_amiibo_force(name)', hook)
        self.assertLess(body.index('peanuts_amiibo_force'), body.index('g_fidx'), 'before the loader\'s own table')

    def test_only_a_game_turned_on_gets_it(self):
        sw = (ROOT / 'android/app/src/main/java/dev/peanuts/amiibo/AmiiboSwitch.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean(ALL, false)', sw, 'Settings › Users › amiibo is off by default')
        self.assertIn('if (titleId != null && on(c, titleId)) env.add("PN_AMIIBO=1");', sw)
        game = (ROOT / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8')
        self.assertEqual(game.count('PN_AMIIBO'), 1, 'GameActivity sets PN_AMIIBO only through AmiiboSwitch.env')
        self.assertRegex(game, r'dev\.peanuts\.amiibo\.AmiiboSwitch\.env\(this, id, env\);   // PEANUTS: ')
        settings = (ROOT / 'android/app/src/main/java/dev/nxnative/android/GameSettings.java').read_text(encoding='utf-8')
        self.assertRegex(settings, r'if \(dev\.peanuts\.amiibo\.AmiiboSwitch\.offered\(a, id\)\) options\.add\(.*"amiibo".*// PEANUTS: ')
        self.assertLess(settings.index('AmiiboSwitch.offered(a, id)) options.add('), settings.index('// ---- Controls ----'), 'the game\'s switch is with its options, not its Controls')
        sections = (ROOT / 'android/app/src/main/java/dev/peanuts/ui/SettingsSections.java').read_text(encoding='utf-8')
        users = sections[sections.index('new Section("users"'):sections.index('new Section("app"')]
        controls = sections[sections.index('new Section("controls"'):sections.index('new Section("screens"')]
        self.assertIn('new Group("amiibo", r("amiibo", ', users, 'Settings › Users › amiibo')
        self.assertNotIn('amiibo', controls, 'Controls stays the controller\'s')
        page = (ROOT / 'android/app/src/main/java/dev/peanuts/ui/SettingsPage.java').read_text(encoding='utf-8')
        self.assertIn('UsersPage.fill(a, col, wide || tablet, more.getChildCount() == 0 ? null : more)', page,
                      'Settings › Users draws its other groups (amiibo) under the users\' part, not only the table')
        self.assertIn('java.util.Arrays.asList("Users", "Miis")', page, 'the users\' part keeps drawing its own Users and Miis rows')
        users_page = (ROOT / 'android/app/src/main/java/dev/peanuts/users/UsersPage.java').read_text(encoding='utf-8')
        self.assertIn('PUi.add(col, tail, 0)', users_page, 'UsersPage puts them back at its end each time it draws anew')

    def test_host_build_and_bridge(self):
        cm = (ROOT / 'android/host/CMakeLists.txt').read_text(encoding='utf-8')
        self.assertIn('dm/peanuts/peanuts_amiibo.c', cm)
        self.assertEqual(cm.count('DM_NSP_DIRECT PN_AMIIBO'), 2, 'both host kinds (per game, generic) define PN_AMIIBO')
        bridge = (ROOT / 'android/app/src/main/java/dev/peanuts/Bridge.java').read_text(encoding='utf-8')
        for name in ('amiiboStatus', 'amiiboPlace', 'amiiboRemove', 'amiiboWaitLib'):
            self.assertIn(f'public static native', bridge)
            self.assertIn(f'Java_dev_peanuts_Bridge_{name}', SRC.read_text(encoding='utf-8'))
            self.assertRegex(bridge, rf'static native \w+ {name}\(')


class Host(unittest.TestCase):
    # ELF only: the table is a section the linker bounds with __start_ and __stop_ symbols, as on Android (Mach-O has none)
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'a C compiler for ELF (Linux) required')
    def test_virtual_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            (t / 'peanuts').mkdir(); (t / 'host').mkdir()
            shutil.copy(SRC, t / 'peanuts' / SRC.name)
            (t / 'host' / 'hle.h').write_text(STAND_IN, encoding='utf-8')
            (t / 'harness.c').write_text(HARNESS, encoding='utf-8')
            out = t / 'data'; out.mkdir()
            exe = t / 'amiibo'
            cc = subprocess.run(['cc', '-O1', '-g', '-Wall', '-Werror', '-Wno-unused-function', '-pthread', '-fsanitize=address,undefined',
                                 f'-DENTRIES={len(own_entries())}', '-o', str(exe), str(t / 'harness.c')], capture_output=True, text=True)
            self.assertEqual(cc.returncode, 0, cc.stderr[-4000:])
            env = {k: v for k, v in os.environ.items() if k != 'PN_AMIIBO'}
            for mode, line in (('off', 'amiibo host (off): '), ('on', 'amiibo host: ')):
                run = subprocess.run([str(exe), mode, str(out)], capture_output=True, text=True, timeout=60, env=env)
                self.assertEqual(run.returncode, 0, mode + ': ' + run.stdout + run.stderr[-4000:])
                self.assertIn(line, run.stdout)
                self.assertIn('0 failed', run.stdout)


if __name__ == '__main__':
    unittest.main()
