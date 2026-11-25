// PEANUTS: code cheats on Android, the declarations dm/host/hle_cheats.c's hooks call (the bodies: peanuts_cheats.inc, included at the end of that file).
// hle_cheats.c writes a cheat's word into main's code through a writable alias of the image (the Mac's mach_vm_remap); Android has no such alias (plat_mem_alias,
// android/PORTING.md: the loader maps modules as private anonymous memory). Here a pass collects its code writes instead, compared with what main holds, and writes the
// ones that change something in one stop of the game's threads (hle_thread.c dm_guest_stop_all, the stop peanuts_patches.cpp and peanuts_helper.cpp write under).
// Included once, after hle_cheats.c's cheat_t and g_cheat; every function is static to that file.
#pragma once

#ifndef PN_CODE   // set from outside only by the test of the other platforms' path (dm/host/tests: make peanuts-cheats-alias, PN_CODE=0 with a test alias of main)
#if PLAT_ANDROID
#define PN_CODE 1   // code writes into main go through peanuts_cheats.inc (no alias of main on this platform)
#else
#define PN_CODE 0
#endif
#endif

static void pn_code_write(cheat_t *c, uint64_t addr, uint64_t len, uint64_t val);   // a cheat's write into main's text: compared, then queued for the pass's stop
static void pn_code_read(uint64_t addr, uint64_t len, uint64_t *v);                 // a read of main sees what the pass has queued there
static void pn_pass_begin(void);   // a pass starts: cheats that stopped running give their words back
static void pn_pass_end(void);     // a pass ends: the queued words written in one stop of the game's threads
static void pn_load_before(void);   // a load replaces the cheats: their keys noted, so the words' owners follow them (pn_load_after)
