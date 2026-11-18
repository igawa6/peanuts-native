// PEANUTS: the companion runtime's two pad hooks in hle_hid.c: dm_pad_raw (what player 1 holds, before the gate and without taking a press away from the game) and
// dm_pad_gate (the filter over every state the game reads). Built over t_hid.c, whose stand-ins for the rest of the host it uses; only its main is replaced.
#define main t_hid_main
#include "t_hid.c"
#undef main

// A gate as the runtime's is while the second screen has the controller: only B passes, the sticks are centred; off, nothing changes. It records the slot it was asked for
static _Atomic int gate_on; static int gate_slot = -1, gate_calls;
static void gate(int slot, uint64_t *buttons, int32_t axes[4]) {
    gate_slot = slot; gate_calls++;
    if (!atomic_load(&gate_on)) return;
    *buttons &= DM_BTN_B;
    axes[0] = axes[1] = axes[2] = axes[3] = 0;
}

extern int front_calls;   // t_peanuts_hid_stubs.c: the front end's join screen, counting its openings
int main(void) {
    g_mode = 1; h_hid_SetStyles(7); ids((uint32_t[]){0}, 1); dm_pad_players(1);
    dm_input_set(0, 0, 0, 0, 0); dm_pad_set(0, 0, 0, 0, 0); buttons_of(0, STYLE_FULLKEY);

    // dm_pad_raw: every source of player 1, the sticks' direction bits, the npad it is on
    uint32_t npad = 99; uint64_t b = 0; int32_t a[4] = {0};
    dm_input_set(DM_BTN_X, 0, 0, 0, 0); dm_pad_set(DM_BTN_A | DM_BTN_LEFT, -30000, 0, 0, 20000);
    dm_pad_raw(&npad, &b, a);
    CHECK(npad == 0 && (b & 0xffff) == (DM_BTN_X | DM_BTN_A | DM_BTN_LEFT) && a[0] == -30000 && a[3] == 20000, "raw: the keyboard's and the controller's buttons, the sticks, on No1 docked");
    CHECK((b >> 16 & 0xff) == (1u << 0 | 1u << 5), "raw: the sticks' directions as buttons (left stick left, right stick up)");
    dm_input_set(0, 0, 0, 0, 0); dm_pad_set(0, 0, 0, 0, 0);
    dm_pad_raw(&npad, &b, a);
    CHECK(b == 0, "raw: a press that came and went is not held (the latch is the game's)");
    CHECK((buttons_of(0, STYLE_FULLKEY) & (DM_BTN_X | DM_BTN_A)) == (DM_BTN_X | DM_BTN_A), "the game still gets that press at its next read: raw took nothing from it");
    g_mode = 0; dm_hid_opmode_changed();
    dm_pad_raw(&npad, &b, a);
    CHECK(npad == NPAD_HANDHELD, "raw: handheld mode names the Handheld npad");
    g_mode = 1; dm_hid_opmode_changed();

    // dm_pad_gate: not set, off, on
    dm_pad_set(DM_BTN_A | DM_BTN_B, 12345, 0, 0, 0);
    CHECK(buttons_of(0, STYLE_FULLKEY) == (DM_BTN_A | DM_BTN_B) && lx_of(0) == 12345 && gate_calls == 0, "no gate set: the game reads the pad as before");
    atomic_store(&dm_pad_gate, gate);
    CHECK(buttons_of(0, STYLE_FULLKEY) == (DM_BTN_A | DM_BTN_B) && lx_of(0) == 12345 && gate_calls == 2 && gate_slot == 0, "gate set but off: unchanged, asked with slot 0 for No1");
    atomic_store(&gate_on, 1);
    { NpadState s; fill(&s, 0, STYLE_FULLKEY);
      CHECK(s.buttons == DM_BTN_B && s.lx == 0 && s.ly == 0 && s.rx == 0 && s.ry == 0, "gate on: the game gets only what passes, no stick and no stick direction"); }
    dm_pad_raw(&npad, &b, a);
    CHECK((b & 0xffff) == (DM_BTN_A | DM_BTN_B) && a[0] == 12345, "raw is before the gate: the runtime still sees what is held");
    g_mode = 0; dm_hid_opmode_changed();
    buttons_of(NPAD_HANDHELD, STYLE_HANDHELD);
    CHECK(gate_slot == 8, "the Handheld npad is slot 8");
    g_mode = 1; dm_hid_opmode_changed();
    atomic_store(&gate_on, 0);
    CHECK(buttons_of(0, STYLE_FULLKEY) == (DM_BTN_A | DM_BTN_B), "gate off again: the pad is back");
    dm_pad_set(0, 0, 0, 0, 0);

    // the controller applet right after an operation mode change is answered with the connected players, without the front end's join screen; once, and only for 5 s
    { static uint8_t arg[CTRL_ARG_SIZE]; arg[0] = 1; arg[1] = 1; arg[5] = 1; int32_t info[2] = {-1, -1};
      motion_now += 10000000000ull;   // the mode changes above are long ago
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 0 && info[0] == 1 && info[1] == NPAD_NO1, "one player wanted, one controller: OK with player 1 at once, no join screen");
      dm_pad_players(3);   // a second controller holds player 2 (which this game does not read): the join screen has something to choose
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 1, "the applet with no mode change goes to the front end's join screen");
      g_mode = 0; dm_hid_opmode_changed(); motion_now += 1000000000ull; info[0] = info[1] = -1;
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 1 && info[0] == 1 && info[1] == NPAD_HANDHELD, "1 s after a mode change: OK with player 1 on the Handheld npad, no join screen");
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 2, "the next applet is the join screen again");
      g_mode = 1; dm_hid_opmode_changed(); motion_now += 6000000000ull;
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 3, "6 s after a mode change: the join screen");
      g_mode = 0; dm_hid_opmode_changed(); arg[0] = 2; arg[1] = 2; arg[5] = 0;
      CHECK(ctrl_support(info, (const int8_t *)arg) == RES_OK && front_calls == 4, "two players wanted and one connected: the join screen, mode change or not");
      g_mode = 1; dm_hid_opmode_changed(); dm_pad_players(1); }

    // the six-axis history: each entry is the sample that was current at its time, not the newest one. Samples 10 ms apart, turning at 1, 2, 3, 4 rad/s about x
    { uint32_t h = 0; SixAxisState st[8]; float flat[3] = {0, 0, -1};   // the full key style's sensor of No1
      motion_now += 10000000000ull;   // every earlier sample is stale
      for (int k = 1; k <= 4; k++) { motion_now += 10000000ull; dm_motion_set_switch(0, flat, (float[]){(float)k, 0, 0}); }
      float one;
      CHECK(h_hid_SixStates(st, 8, &h) == 8 && st[0].gyro[0] > st[2].gyro[0] && st[2].gyro[0] > st[4].gyro[0] && st[4].gyro[0] > st[6].gyro[0], "8 states read: the newest sample first, older samples behind it, 10 ms a sample");
      CHECK(st[1].gyro[0] == st[2].gyro[0] && st[3].gyro[0] == st[4].gyro[0] && st[5].gyro[0] == st[7].gyro[0], "the sample current 5 and 10 ms ago is the one before the newest, and so on; times before the oldest sample give the oldest");
      CHECK(st[0].sampling == st[1].sampling + 1 && st[1].sampling == st[7].sampling + 6, "the sampling numbers still count down by one");
      one = st[0].gyro[0]; h_hid_SixStates(st, 1, &h);
      CHECK(st[0].gyro[0] == one, "one state read: the newest sample");
      motion_now += 300000001ull;
      CHECK(h_hid_SixStates(st, 8, &h) == 8 && st[0].gyro[0] == 0 && st[5].gyro[0] == 0, "stale motion: the history is the resting state again"); }
    if (fails) printf("FAILED: %d of %d\n", fails, checks); else printf("all passed: %d\n", checks);
    return fails != 0;
}
