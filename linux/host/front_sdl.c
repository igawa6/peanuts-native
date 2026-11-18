// The window, gamepads and touch on Linux, through SDL3, in the place of the Android launcher (android/app): a thread of its own makes the window and forwards SDL's events
// to the functions the launcher calls (dm/host/android/input_android.h). The Vulkan replay makes its surface from the window through nx_linux_vk_surface.
#define VK_USE_PLATFORM_XCB_KHR
#include <xcb/xcb.h>
#include <vulkan/vulkan.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <android/native_window.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "android/input_android.h"

static ANativeWindow g_anw;
static SDL_Window *g_win;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cv = PTHREAD_COND_INITIALIZER;
static int g_ready;   // 1 window made, -1 none

#define PADS 8
static struct { SDL_JoystickID id; SDL_Gamepad *pad; int player, motion, has_acc; float acc[3]; } g_pad[PADS];
static int pad_slot(SDL_JoystickID id) { for (int i = 0; i < PADS; i++) if (g_pad[i].pad && g_pad[i].id == id) return i; return -1; }
// A pad's own motion sensors (SDL3: accelerometer m/s², gyroscope rad/s, as Android's InputDevice sensors) go to nxhost_input_motion, the path the Android
// launcher feeds from a DualSense (pad_motion.h: rest offset, DM_MOTION_AXES). Off when the host says controller motion is off (DM_MOTION=0, DM_PAD_MOTION=0)
static void pad_motion_on(int i) {
    SDL_Gamepad *p = g_pad[i].pad;
    if (!SDL_GamepadHasSensor(p, SDL_SENSOR_GYRO) || !SDL_GamepadHasSensor(p, SDL_SENSOR_ACCEL)) return;
    if (!SDL_SetGamepadSensorEnabled(p, SDL_SENSOR_GYRO, true) || !SDL_SetGamepadSensorEnabled(p, SDL_SENSOR_ACCEL, true)) return;
    g_pad[i].motion = 1; g_pad[i].has_acc = 0;
    fprintf(stderr, "[front] gamepad %d: motion sensors on (gyro %.0f Hz)\n", (int)g_pad[i].id, SDL_GetGamepadSensorDataRate(p, SDL_SENSOR_GYRO));
}
static void pad_sensor(SDL_JoystickID id, int sensor, const float d[3]) {
    int i = pad_slot(id); if (i < 0 || !g_pad[i].motion) return;
    if (sensor == SDL_SENSOR_ACCEL) { for (int k = 0; k < 3; k++) g_pad[i].acc[k] = d[k]; g_pad[i].has_acc = 1; return; }
    if (sensor != SDL_SENSOR_GYRO || !g_pad[i].has_acc) return;
    if (!nxhost_input_motion((int)id, g_pad[i].acc, d)) {   // controller motion off: stop reading the sensors
        SDL_SetGamepadSensorEnabled(g_pad[i].pad, SDL_SENSOR_GYRO, false); SDL_SetGamepadSensorEnabled(g_pad[i].pad, SDL_SENSOR_ACCEL, false); g_pad[i].motion = 0;
    }
}
static void pad_add(SDL_JoystickID id) {
    if (pad_slot(id) >= 0) return;
    for (int i = 0; i < PADS; i++) if (!g_pad[i].pad) {
        SDL_Gamepad *p = SDL_OpenGamepad(id); if (!p) return;
        const char *name = SDL_GetGamepadName(p);
        g_pad[i].id = id; g_pad[i].pad = p; g_pad[i].player = nxhost_input_device_added((int)id, name ? name : "gamepad", 0);
        if (g_pad[i].player >= 0) nxhost_rumble_route(g_pad[i].player, true);
        fprintf(stderr, "[front] gamepad %d '%s': player %d\n", (int)id, name ? name : "", g_pad[i].player + 1);
        g_pad[i].motion = 0; pad_motion_on(i);
        return;
    }
}
static void pad_remove(SDL_JoystickID id) {
    int i = pad_slot(id); if (i < 0) return;
    nxhost_input_device_removed((int)id); SDL_CloseGamepad(g_pad[i].pad); g_pad[i].pad = NULL;
    fprintf(stderr, "[front] gamepad %d removed\n", (int)id);
}
// SDL names the face buttons by position, as Android does (BUTTON_A bottom, B right, X left, Y top); the host maps positions to the Switch's labels (input_map.c)
static int pad_key(int b) {
    switch (b) {
    case SDL_GAMEPAD_BUTTON_SOUTH: return AK_BUTTON_A; case SDL_GAMEPAD_BUTTON_EAST: return AK_BUTTON_B; case SDL_GAMEPAD_BUTTON_WEST: return AK_BUTTON_X; case SDL_GAMEPAD_BUTTON_NORTH: return AK_BUTTON_Y;
    case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: return AK_BUTTON_L1; case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: return AK_BUTTON_R1;
    case SDL_GAMEPAD_BUTTON_LEFT_STICK: return AK_BUTTON_THUMBL; case SDL_GAMEPAD_BUTTON_RIGHT_STICK: return AK_BUTTON_THUMBR;
    case SDL_GAMEPAD_BUTTON_START: return AK_BUTTON_START; case SDL_GAMEPAD_BUTTON_BACK: return AK_BUTTON_SELECT; case SDL_GAMEPAD_BUTTON_GUIDE: return AK_BUTTON_MODE;
    case SDL_GAMEPAD_BUTTON_DPAD_UP: return AK_DPAD_UP; case SDL_GAMEPAD_BUTTON_DPAD_DOWN: return AK_DPAD_DOWN; case SDL_GAMEPAD_BUTTON_DPAD_LEFT: return AK_DPAD_LEFT; case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: return AK_DPAD_RIGHT;
    }
    return -1;
}
static void pad_axis(SDL_JoystickID id, int axis, int v) {
    float f = v < 0 ? v / 32768.0f : v / 32767.0f; int a;
    switch (axis) {
    case SDL_GAMEPAD_AXIS_LEFTX: a = AA_X; break; case SDL_GAMEPAD_AXIS_LEFTY: a = AA_Y; break;
    case SDL_GAMEPAD_AXIS_RIGHTX: a = AA_Z; break; case SDL_GAMEPAD_AXIS_RIGHTY: a = AA_RZ; break;
    case SDL_GAMEPAD_AXIS_LEFT_TRIGGER: a = AA_LTRIGGER; break; case SDL_GAMEPAD_AXIS_RIGHT_TRIGGER: a = AA_RTRIGGER; break;
    default: return;
    }
    nxhost_input_axis((int)id, a, f);
}
// The host's rumble thread: intensity 0 stops it; sharpness picks the motor (low frequency: the large one)
static void rumble(void *ctx, int player, float intensity, float sharpness) {
    (void)ctx;
    for (int i = 0; i < PADS; i++) if (g_pad[i].pad && g_pad[i].player == player) {
        Uint16 lo = (Uint16)(65535 * intensity * (1 - 0.5f * sharpness)), hi = (Uint16)(65535 * intensity * (0.5f + 0.5f * sharpness));
        SDL_RumbleGamepad(g_pad[i].pad, lo, hi, intensity > 0 ? 1000 : 0);
    }
}
static void size_update(void) {
    int w = 0, h = 0; SDL_GetWindowSizeInPixels(g_win, &w, &h);
    if (w > 0 && h > 0) { g_anw.width = w; g_anw.height = h; nxhost_touch_layout((float)w, (float)h, 0, 0, 1, 1); }
}
static void quit(const char *why) { fprintf(stderr, "[front] %s: the host is asked to end (SIGTERM)\n", why); kill(getpid(), SIGTERM); }

static void *front_thread(void *arg) {
    (void)arg;
    SDL_SetAppMetadata("Banana Native", NULL, "dev.bananative.linux");
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    int ok = SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD);
    if (ok) {
        const char *ws = getenv("NX_WINDOW");   // NX_WINDOW=1280x720: a window of that size instead of the whole screen
        int w = 1280, h = 720; SDL_WindowFlags fl = SDL_WINDOW_VULKAN | SDL_WINDOW_HIGH_PIXEL_DENSITY;
        if (ws && sscanf(ws, "%dx%d", &w, &h) == 2 && w > 0 && h > 0) fl |= SDL_WINDOW_RESIZABLE; else { w = 1280; h = 720; fl |= SDL_WINDOW_FULLSCREEN; }
        g_win = SDL_CreateWindow("Banana Native", w, h, fl);
    }
    pthread_mutex_lock(&g_mu);
    if (g_win) { size_update(); g_anw.surface = g_win; g_anw.kind = 1; g_ready = 1; fprintf(stderr, "[front] SDL %s window %dx%d\n", SDL_GetCurrentVideoDriver(), g_anw.width, g_anw.height); }
    else {   // a game nobody sees and nobody can close is worse than none: the reason is logged with the display environment and the host ends (NX_NO_WINDOW_OK=1 runs on)
        const char *e[] = {"DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "SDL_VIDEODRIVER", "XDG_RUNTIME_DIR", "XDG_SESSION_TYPE"};
        fprintf(stderr, "[RED] [front] no window (%s)\n", SDL_GetError());
        for (size_t i = 0; i < sizeof e / sizeof *e; i++) { const char *v = getenv(e[i]); fprintf(stderr, "[front]   %s=%s\n", e[i], v ? v : "(unset)"); }
        fprintf(stderr, "[front]   SDL video drivers built in:"); for (int i = 0; i < SDL_GetNumVideoDrivers(); i++) fprintf(stderr, " %s", SDL_GetVideoDriver(i)); fprintf(stderr, "\n");
        const char *k = getenv("NX_NO_WINDOW_OK");
        if (!(k && *k == '1')) { fprintf(stderr, "[RED] [front] the host ends: there is nothing to show the game in\n"); fflush(stderr); _exit(3); }
        g_ready = -1;
    }
    pthread_cond_broadcast(&g_cv); pthread_mutex_unlock(&g_mu);
    if (!ok) return NULL;
    SDL_HideCursor();
    { int n = 0; SDL_JoystickID *j = SDL_GetJoysticks(&n); fprintf(stderr, "[front] %d joysticks\n", n);
      for (int i = 0; j && i < n; i++) fprintf(stderr, "[front] joystick %d '%s' %04x:%04x: %s\n", (int)j[i], SDL_GetJoystickNameForID(j[i]), SDL_GetJoystickVendorForID(j[i]), SDL_GetJoystickProductForID(j[i]), SDL_IsGamepad(j[i]) ? "a gamepad" : "no gamepad mapping");
      SDL_free(j); }
    nxhost_touch_controls_enable(false);   // no on-screen controls are drawn here: every touch goes to the game
    nxhost_rumble_callback(rumble, NULL);
    for (SDL_Event e;;) {
        if (!SDL_WaitEvent(&e)) continue;
        switch (e.type) {
        case SDL_EVENT_QUIT: quit("window closed"); break;
        case SDL_EVENT_KEY_DOWN: if (e.key.key == SDLK_ESCAPE && (e.key.mod & SDL_KMOD_SHIFT)) quit("Shift+Esc"); break;
        case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED: size_update(); break;
        case SDL_EVENT_GAMEPAD_ADDED: pad_add(e.gdevice.which); break;
        case SDL_EVENT_GAMEPAD_REMOVED: pad_remove(e.gdevice.which); break;
        case SDL_EVENT_GAMEPAD_BUTTON_DOWN: case SDL_EVENT_GAMEPAD_BUTTON_UP: { int k = pad_key(e.gbutton.button); if (k >= 0) nxhost_input_key((int)e.gbutton.which, k, e.gbutton.down); break; }
        case SDL_EVENT_GAMEPAD_AXIS_MOTION: pad_axis(e.gaxis.which, e.gaxis.axis, e.gaxis.value); break;
        case SDL_EVENT_GAMEPAD_SENSOR_UPDATE: pad_sensor(e.gsensor.which, e.gsensor.sensor, e.gsensor.data); break;
        case SDL_EVENT_FINGER_DOWN: nxhost_input_touch(AM_POINTER_DOWN, (int)(e.tfinger.fingerID & 0x7fff), e.tfinger.x, e.tfinger.y); break;
        case SDL_EVENT_FINGER_MOTION: nxhost_input_touch(AM_MOVE, (int)(e.tfinger.fingerID & 0x7fff), e.tfinger.x, e.tfinger.y); break;
        case SDL_EVENT_FINGER_UP: case SDL_EVENT_FINGER_CANCELED: nxhost_input_touch(AM_POINTER_UP, (int)(e.tfinger.fingerID & 0x7fff), e.tfinger.x, e.tfinger.y); break;
        }
    }
    return NULL;
}

// The window for nxhost_set_window, or NULL when there is no display (NX_HEADLESS=1 asks for none)
ANativeWindow *nx_linux_window(void) {
    static int started;
    if (getenv("NX_HEADLESS")) return NULL;
    pthread_mutex_lock(&g_mu);
    if (!started) { started = 1; pthread_t t; if (pthread_create(&t, NULL, front_thread, NULL)) g_ready = -1; else pthread_detach(t); }
    while (!g_ready) pthread_cond_wait(&g_cv, &g_mu);
    pthread_mutex_unlock(&g_mu);
    return g_ready > 0 ? &g_anw : NULL;
}
// On X11 (gamescope's Xwayland in Game Mode) the surface is an xcb one on a connection of its own. SDL's window lives on SDL's Xlib connection, which its event thread
// uses; a surface on that same connection has the Vulkan driver's present thread talk on it too, and Xlib then aborts (_XEventsQueued, seen 2026-11-14) or presents time out.
static int on_x11(void) { const char *d = SDL_GetCurrentVideoDriver(); return d && !strcmp(d, "x11"); }
// The instance extensions the window's surface needs (dm/gfx/vk_android/vka_dev.c); 0 without a window
int nx_linux_vk_instance_exts(const char **out, int max) {
    if (g_ready <= 0) return 0;
    if (on_x11() && max >= 2) { out[0] = VK_KHR_SURFACE_EXTENSION_NAME; out[1] = VK_KHR_XCB_SURFACE_EXTENSION_NAME; return 2; }
    Uint32 n = 0; const char *const *e = SDL_Vulkan_GetInstanceExtensions(&n); if (!e || (int)n > max) return 0;
    for (Uint32 i = 0; i < n; i++) out[i] = e[i];
    return (int)n;
}
// The window's VkSurfaceKHR (vka_present.c window_take); 0 = made
int nx_linux_vk_surface(ANativeWindow *w, void *instance, void *surface_out) {
    if (!w || !w->surface) return -1;
    SDL_Window *win = w->surface;
    if (on_x11()) {
        static xcb_connection_t *c;
        if (!c) { c = xcb_connect(NULL, NULL); if (xcb_connection_has_error(c)) { fprintf(stderr, "[RED] [front] no X connection for the surface\n"); c = NULL; return -1; } }
        xcb_window_t xw = (xcb_window_t)SDL_GetNumberProperty(SDL_GetWindowProperties(win), SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0);
        PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)SDL_Vulkan_GetVkGetInstanceProcAddr();
        PFN_vkCreateXcbSurfaceKHR mk = gipa ? (PFN_vkCreateXcbSurfaceKHR)gipa((VkInstance)instance, "vkCreateXcbSurfaceKHR") : NULL;
        VkXcbSurfaceCreateInfoKHR si = {VK_STRUCTURE_TYPE_XCB_SURFACE_CREATE_INFO_KHR, .connection = c, .window = xw};
        VkResult r = mk && xw ? mk((VkInstance)instance, &si, NULL, (VkSurfaceKHR *)surface_out) : VK_ERROR_INITIALIZATION_FAILED;
        if (r) { fprintf(stderr, "[RED] [front] vkCreateXcbSurfaceKHR on window %#x: %d\n", xw, r); return -1; }
        fprintf(stderr, "[front] xcb surface on window %#x, on a connection of its own\n", xw);
        return 0;
    }
    if (!SDL_Vulkan_CreateSurface(win, (VkInstance)instance, NULL, (VkSurfaceKHR *)surface_out)) { fprintf(stderr, "[RED] [front] SDL_Vulkan_CreateSurface: %s\n", SDL_GetError()); return -1; }
    return 0;
}
