// nn::account: the real device manages users via the acc:u0 service; the host keeps the users the front end gives it.
// Users: DM_USERS=<uid>:<name>,<uid>:<name>,... (uid: 32 hex digits, the Uid's two words; name: UTF-8, percent-encoded, at most 32 bytes) and DM_USER=<uid>, the user the game
// starts with (the console's preselected user). Without DM_USERS there is one user, the fixed k_uid named DM_NICK (default Player), as before. k_uid's save is the application's
// save directory (DM_SAVE), the one every earlier run kept; another user's is DM_SAVE_user_<uid> (dm_account_save_dir), so each user has a save of its own as on the console
// (Super Mario Bros. Wonder keeps a save file per user and adds one through the user selector, which with one user could offer nobody).
// The user selector (ShowUserSelector) asks the front end (front_user_select: the Android launcher's picker); without one it takes the preselected user, as before.
// Online accounts (network service account / Nintendo Account authorization / tokens) are out of scope and uniformly return RES_FAIL; the async context's completion event fires immediately.
#include "hle.h"
#include "front.h"

typedef struct { uint64_t v[2]; } Uid;                 // nn::account::Uid, 16 bytes
typedef struct { uint64_t v[3]; } UserHandle;          // nn::account::UserHandle, 24 bytes
_Static_assert(sizeof(Uid) == 16, "Uid");
static const Uid k_uid = {{0x78626c0000000001ull, 0x53574e4154495645ull}};   // Non-zero means valid
#define HANDLE_MAGIC 0x55534552484e444cull                                    // "USERHNDL"
#define USERS_MAX 8                                                           // the console's limit
#define RES_ACC_CANCELLED ((Result)(124 | (1 << 9)))                          // account module 124, description 1: the user selector was cancelled (2124-0001)

typedef struct { Uid uid; char name[33]; } user_t;
static user_t g_users[USERS_MAX]; static int g_nusers, g_def;
static pthread_once_t g_users_once = PTHREAD_ONCE_INIT;

static int uid_eq(const Uid *a, const Uid *b) { return a->v[0] == b->v[0] && a->v[1] == b->v[1]; }
static int hexv(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
static int uid_parse(const char *s, Uid *out) {   // 32 hex digits → the Uid (v[0] first); 1 when valid and not zero
    uint64_t w[2] = {0, 0};
    for (int i = 0; i < 32; i++) { int h = hexv(s[i]); if (h < 0) return 0; w[i / 16] = w[i / 16] << 4 | (uint64_t)h; }
    out->v[0] = w[0]; out->v[1] = w[1];
    return (w[0] | w[1]) != 0;
}
static void uid_hex(const Uid *u, char out[33]) { snprintf(out, 33, "%016llx%016llx", (unsigned long long)u->v[0], (unsigned long long)u->v[1]); }
static void users_load(void) {
    const char *e = getenv("DM_USERS");
    for (const char *p = e; p && *p && g_nusers < USERS_MAX; ) {
        const char *end = strchr(p, ','); size_t len = end ? (size_t)(end - p) : strlen(p);
        user_t u; memset(&u, 0, sizeof u);
        if (len > 33 && p[32] == ':' && uid_parse(p, &u.uid)) {
            size_t o = 0;
            for (size_t i = 33; i < len && o < 32; i++) {   // the name, percent-decoded (so a comma or a colon in it cannot end the entry)
                if (p[i] == '%' && i + 2 < len && hexv(p[i + 1]) >= 0 && hexv(p[i + 2]) >= 0) { u.name[o++] = (char)(hexv(p[i + 1]) << 4 | hexv(p[i + 2])); i += 2; }
                else u.name[o++] = p[i];
            }
            int dup = 0; for (int k = 0; k < g_nusers; k++) dup |= uid_eq(&g_users[k].uid, &u.uid);
            if (!dup) g_users[g_nusers++] = u; else LOG("[RED] account: DM_USERS names user %.32s twice: the second is left out", p);
        } else LOG("[RED] account: DM_USERS entry \"%.*s\" is not <32 hex digits>:<name>: left out", (int)(len > 60 ? 60 : len), p);
        p = end ? end + 1 : p + len;
    }
    if (!g_nusers) {   // one local user, as before
        const char *n = getenv("DM_NICK");
        g_users[0].uid = k_uid; strncpy(g_users[0].name, n && *n ? n : "Player", 32); g_nusers = 1;
    }
    for (int k = 0; k < g_nusers; k++) if (!g_users[k].name[0]) snprintf(g_users[k].name, sizeof g_users[k].name, "Player %d", k + 1);
    const char *d = getenv("DM_USER"); Uid du;
    if (d && *d && uid_parse(d, &du)) {
        for (int k = 0; k < g_nusers; k++) if (uid_eq(&g_users[k].uid, &du)) g_def = k;
        if (!uid_eq(&g_users[g_def].uid, &du)) LOG("[RED] account: DM_USER %s is none of the %d users: the first starts", d, g_nusers);
    }
    if (g_nusers > 1 || e) {
        char h[33]; uid_hex(&g_users[g_def].uid, h);
        LOG("account: %d user(s), the game starts with %s (%s)", g_nusers, g_users[g_def].name, h);
    }
}
static void users(void) { pthread_once(&g_users_once, users_load); }
static int user_index(const Uid *u) { users(); if (!u) return -1; for (int k = 0; k < g_nusers; k++) if (uid_eq(&g_users[k].uid, u)) return k; return -1; }

// A user's save directory under the application's save directory base (DM_SAVE, no trailing slash): base itself for k_uid (the save every earlier run kept) and for no user
// (the device save's caller), base_user_<uid> for every other user. 1 when it is base. hle_fs.c MountSaveData / EnsureSaveData / IsSaveDataExisting
int dm_account_save_dir(const void *uid, const char *base, char *out, size_t cap) {
    const Uid *u = uid;
    if (!u || uid_eq(u, &k_uid) || !(u->v[0] | u->v[1])) { snprintf(out, cap, "%s", base); return 1; }
    char h[33]; uid_hex(u, h);
    snprintf(out, cap, "%s_user_%s", base, h);
    return 0;
}
// PEANUTS: the save directory of the user the game starts with (DM_USER, else the first), as dm_account_save_dir names it: what the companion runtime reads as the
// game's save (dm/peanuts/peanuts.c rt_start), which read the first user's whoever played
int dm_account_start_save_dir(const char *base, char *out, size_t cap) { users(); return dm_account_save_dir(&g_users[g_def].uid, base, out, cap); }
// How many users the console has (DM_USERS, else the one fixed user): hle_fs.c frees a save's mount name on Unmount only when there is more than one
int dm_account_users(void) { users(); return g_nusers; }

// The front end's user picker (front.h): without one (the Mac, iOS, the unit tests) -2, and the host takes the preselected user
__attribute__((weak)) int front_user_select(const char *const *names, const int *offered, int n, int def) { (void)names; (void)offered; (void)n; (void)def; return -2; }

HLE_FORCE("_ZN2nn7account10InitializeEv", "connects to the acc:u0 account service; the host keeps the front end's users (DM_USERS)", void, h_acc_Init, void) { users(); }

static int list_users(Uid *out, int max) { users(); int k = 0; for (; out && k < max && k < g_nusers; k++) out[k] = g_users[k].uid; return k; }
HLE_FORCE("_ZN2nn7account13ListOpenUsersEPiPNS0_3UidEi", "list of open users; the preselected user", Result, h_acc_ListOpen, int *n, Uid *out, int max) {
    users(); *n = 0;
    if (max > 0 && out) { out[0] = g_users[g_def].uid; *n = 1; }
    return RES_OK;
}
HLE_FORCE("_ZN2nn7account12ListAllUsersEPiPNS0_3UidEi", "list of all users (DM_USERS; Super Mario 3D World + Bowser's Fury)", Result, h_acc_ListAll, int *n, Uid *out, int max) {
    *n = list_users(out, max); return RES_OK;
}
// UserSelectionSettings: the users not to offer (8 Uids at 0: a game lists the users that already have a save, for instance) and isSkipEnabled at 0x80 (with one user to
// offer, the console takes it without showing the selector)
HLE_FORCE("_ZN2nn7account16ShowUserSelectorEPNS0_3UidERKNS0_21UserSelectionSettingsE", "user selection applet; the front end's picker (DM_USERS), else the preselected user", Result, h_acc_ShowSel, Uid *out, const void *settings) { DM_APPLET("user selector");
    users();
    const Uid *inv = settings; const uint8_t *s = settings;
    int offered[USERS_MAX], nof = 0, last = -1;
    for (int k = 0; k < g_nusers; k++) {
        offered[k] = 1;
        for (int j = 0; settings && j < 8; j++) if ((inv[j].v[0] | inv[j].v[1]) && uid_eq(&inv[j], &g_users[k].uid)) offered[k] = 0;
        if (offered[k]) { nof++; last = k; }
    }
    if (!nof) { LOG("account: user selector: every user is on the game's list of users not to offer: cancelled"); return RES_ACC_CANCELLED; }
    int pick;
    if (g_nusers == 1 || (nof == 1 && settings && s[0x80])) pick = last;   // one user (as before), or one to offer with skipping allowed
    else {
        const char *names[USERS_MAX]; for (int k = 0; k < g_nusers; k++) names[k] = g_users[k].name;
        pick = front_user_select(names, offered, g_nusers, g_def);
        if (pick == -2) pick = offered[g_def] ? g_def : last;   // no picker: the preselected user, else one the game offers
        if (pick < 0 || pick >= g_nusers || !offered[pick]) { LOG("account: user selector cancelled"); return RES_ACC_CANCELLED; }
    }
    LOG("account: user selector: %s (%d of %d offered)", g_users[pick].name, nof, g_nusers);
    *out = g_users[pick].uid; return RES_OK;
}
// Take the users the system opened on the application's behalf (Mario 1.2.1 asks this first and falls back to TryOpenPreselectedUser on 0; the game only reads the count, not the return value):
// the host has no such users and returns 0, so the game opens the preselected user itself, as in v0
HLE_FORCE("_ZN2nn7account12PopOpenUsersEPiPNS0_10UserHandleEi", "users opened by the system: none (the game falls back to the preselected user)", void, h_acc_PopOpen, int *n, UserHandle *out, int max) { (void)out; (void)max; *n = 0; }
// Hand the open users to the next program of a multi-program application (Octopath Traveler 0 imports it with oe::ExecuteProgram): the host runs one program, so there is nothing to hand over
HLE_FORCE("_ZN2nn7account13PushOpenUsersEv", "hand the open users to the next program: the host runs only this one", void, h_acc_PushOpen, void) {}
HLE_FORCE("_ZN2nn7account22TryOpenPreselectedUserEPNS0_10UserHandleE", "user preselected at launch: DM_USER (returns bool)", unsigned, h_acc_TryOpenPre, UserHandle *h) {
    users(); h->v[0] = g_users[g_def].uid.v[0]; h->v[1] = g_users[g_def].uid.v[1]; h->v[2] = HANDLE_MAGIC; return 1;
}
// The aborting form (Super Mario Odyssey, SDK 7): without the hook the SDK's IPC call fails and aborts with no message. In SDK 6 the same symbol returns a Result, and Fire Emblem: Three Houses
// skips its save mount when it is non-zero (main+0x53b7e0); returning void left the handle pointer in w0, so the game never mounted "save" and its loading screen waited forever for save:/system.
// It returns 0 now, which SDK 7+ callers ignore
HLE_FORCE("_ZN2nn7account19OpenPreselectedUserEPNS0_10UserHandleE", "user preselected at launch: DM_USER (returns a Result in SDK 6, later versions abort on failure)", Result, h_acc_OpenPre, UserHandle *h) {
    users(); h->v[0] = g_users[g_def].uid.v[0]; h->v[1] = g_users[g_def].uid.v[1]; h->v[2] = HANDLE_MAGIC; return RES_OK;
}
HLE_FORCE("_ZN2nn7account17GetLastOpenedUserEPNS0_3UidE", "last opened user: the preselected user (Super Mario Odyssey)", Result, h_acc_LastOpened, Uid *out) { users(); *out = g_users[g_def].uid; return RES_OK; }
HLE_FORCE("_ZN2nn7account8OpenUserEPNS0_10UserHandleERKNS0_3UidE", "open user; the handle holds the Uid itself", Result, h_acc_OpenUser, UserHandle *h, const Uid *uid) {
    h->v[0] = uid->v[0]; h->v[1] = uid->v[1]; h->v[2] = HANDLE_MAGIC; return RES_OK;
}
HLE_FORCE("_ZN2nn7account9GetUserIdEPNS0_3UidERKNS0_10UserHandleE", "gets the Uid from the handle", Result, h_acc_GetUserId, Uid *out, const UserHandle *h) {
    if (h->v[2] != HANDLE_MAGIC) die("account::GetUserId: the handle was not issued by the host (%#llx)", h->v[2]);
    out->v[0] = h->v[0]; out->v[1] = h->v[1]; return RES_OK;
}
// nn::account::Nickname = struct { char name[33]; }, UTF-8
HLE_FORCE("_ZN2nn7account11GetNicknameEPNS0_8NicknameERKNS0_3UidE", "user nickname: the user's name in DM_USERS (DM_NICK for the one default user)", Result, h_acc_GetNick, char *nick, const Uid *uid) {
    int k = user_index(uid);
    memset(nick, 0, 33); strncpy(nick, g_users[k < 0 ? g_def : k].name, 32);
    return RES_OK;
}

// ---------- Online accounts: cut ----------
HLE_FORCE("_ZN2nn7account36EnsureNetworkServiceAccountAvailableERKNS0_10UserHandleE", "network service account, out of scope; cut: always unavailable", Result, h_acc_EnsureNsa, const UserHandle *h) { (void)h; return RES_FAIL; }
HLE_FORCE("_ZN2nn7account26GetNetworkServiceAccountIdEPNS0_23NetworkServiceAccountIdERKNS0_10UserHandleE", "network service account, out of scope; cut", Result, h_acc_GetNsaId, void *id, const UserHandle *h) { (void)id; (void)h; return RES_FAIL; }
HLE_FORCE("_ZN2nn7account37LoadNetworkServiceAccountIdTokenCacheEPmPcmRKNS0_10UserHandleE", "network service account token, out of scope; cut", Result, h_acc_LoadTok, uint64_t *n, char *buf, uint64_t sz, const UserHandle *h) {
    (void)buf; (void)sz; (void)h; if (n) *n = 0; return RES_FAIL;
}
HLE_FORCE("_ZN2nn7account40ShowLicenseRequirementsForNetworkServiceERKNS0_10UserHandleE", "online membership notice applet, out of scope; cut", Result, h_acc_ShowLic, const UserHandle *h) { (void)h; return RES_FAIL; }
// The Nintendo Account's cached profile (birthday, country): no Nintendo Account is linked on the host, so loading fails and the game skips its getters (Big Brain Academy: Brain vs. Brain, main+0xab8bc)
HLE_FORCE("_ZN2nn7account29LoadCachedNintendoAccountInfoEPNS0_25CachedNintendoAccountInfoERKNS0_10UserHandleEPvm", "cached Nintendo Account info: none linked, fails", Result, h_acc_LoadNaInfo,
          void *info, const UserHandle *h, void *buf, uint64_t sz) { (void)info; (void)h; (void)buf; (void)sz; return RES_FAIL; }

// AsyncContext / NintendoAccountAuthorizationRequestContext: object contents are read and written only by the interfaces below; construction writes no memory, the completion event fires immediately, and the result is always failure
HLE_FORCE("_ZN2nn7account12AsyncContextC1Ev", "async context constructor; the host creates no IPC object", void, h_acc_AcCtor, void *self) { (void)self; }
HLE_FORCE("_ZN2nn7account44EnsureNetworkServiceAccountIdTokenCacheAsyncEPNS0_12AsyncContextERKNS0_10UserHandleE", "network service account token, out of scope; cut", Result, h_acc_EnsureTokAsync, void *ctx, const UserHandle *h) { (void)ctx; (void)h; return RES_FAIL; }
HLE_FORCE("_ZN2nn7account12AsyncContext14GetSystemEventEPNS_2os11SystemEventE", "async completion event; fires immediately", Result, h_acc_AcGetEv, void *self, void *ev) { (void)self; hle_signal_system_event(ev); return RES_OK; }
HLE_FORCE("_ZN2nn7account12AsyncContext9GetResultEv", "async result; networking is cut, always fails", Result, h_acc_AcGetRes, void *self) { (void)self; return RES_FAIL; }
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContextC1Ev", "Nintendo Account authorization, out of scope; the constructor writes no memory", void, h_acc_NaCtor, void *self) { (void)self; }
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContextD1Ev", "Nintendo Account authorization, out of scope", void, h_acc_NaDtor, void *self) { (void)self; }
HLE_FORCE("_ZN2nn7account41CreateNintendoAccountAuthorizationRequestEPNS0_42NintendoAccountAuthorizationRequestContextERKNS0_10UserHandleERKNS0_45NintendoAccountAuthorizationRequestParametersEPvm",
          "Nintendo Account authorization, out of scope; cut", Result, h_acc_NaCreate, void *ctx, const UserHandle *h, const void *prm, void *buf, uint64_t sz) {
    (void)ctx; (void)h; (void)prm; (void)buf; (void)sz; return RES_FAIL;
}
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContext14GetSystemEventEPNS_2os11SystemEventE", "authorization completion event; fires immediately", Result, h_acc_NaGetEv, void *self, void *ev) { (void)self; hle_signal_system_event(ev); return RES_OK; }
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContext34GetResultWithInteractionIfRequiredEv", "authorization result; always fails", Result, h_acc_NaGetRes, void *self) { (void)self; return RES_FAIL; }
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContext10GetIdTokenEPmPcm", "authorization token; always fails", Result, h_acc_NaGetTok, void *self, uint64_t *n, char *buf, uint64_t sz) {
    (void)self; (void)buf; (void)sz; if (n) *n = 0; return RES_FAIL;
}
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContext20GetAuthorizationCodeEPmPcm", "authorization code; always fails", Result, h_acc_NaGetCode, void *self, uint64_t *n, char *buf, uint64_t sz) {
    (void)self; (void)buf; (void)sz; if (n) *n = 0; return RES_FAIL;
}
HLE_FORCE("_ZN2nn7account42NintendoAccountAuthorizationRequestContext8GetStateEPmPcm", "authorization state string; always fails", Result, h_acc_NaGetState, void *self, uint64_t *n, char *buf, uint64_t sz) {
    (void)self; (void)buf; (void)sz; if (n) *n = 0; return RES_FAIL;
}

// ---------- Account interfaces used by newer SDKs: user enumeration / existence / profile image, the remaining AsyncContext members ----------
HLE_FORCE("_ZN2nn7account12GetUserCountEPi", "user count (DM_USERS)", Result, h_acc_UserCount, int *n) { users(); *n = g_nusers; return RES_OK; }
HLE_FORCE("_ZN2nn7account18ListQualifiedUsersEPiPNS0_3UidEi", "list of qualified users: every user (DM_USERS)", Result, h_acc_ListQual, int *n, Uid *out, int max) {
    *n = list_users(out, max); return RES_OK;
}
HLE_FORCE("_ZN2nn7account16GetUserExistenceEPbRKNS0_3UidE", "whether a user exists: one of DM_USERS", Result, h_acc_Exists, uint8_t *out, const Uid *uid) {
    *out = user_index(uid) >= 0; return RES_OK;
}
HLE_FORCE("_ZN2nn7account15ShowUserCreatorEv", "user creation applet; users are added in the launcher's settings", Result, h_acc_ShowCreator, void) { DM_APPLET("user creator"); return RES_OK; }
HLE_FORCE("_ZN2nn7account16LoadProfileImageEPmPvmRKNS0_3UidE", "user profile image; the host has none (fails)", Result, h_acc_LoadImage, uint64_t *n, void *buf, uint64_t sz, const Uid *uid) {
    (void)buf; (void)sz; (void)uid; if (n) *n = 0; return RES_FAIL;
}
// The save-data thumbnail is what the system's data management screen shows next to a save (The Great Ace Attorney Chronicles, SDK 10); the host has no such screen and keeps none
HLE_FORCE("_ZN2nn7account27StoreSaveDataThumbnailImageERKNS0_3UidEPKvm", "save-data thumbnail for the system's data management screen; the host keeps none", Result, h_acc_StoreThumb, const Uid *uid, const void *img, uint64_t n) {
    (void)uid; (void)img; (void)n; return RES_OK;
}
HLE_FORCE("_ZN2nn7account28DeleteSaveDataThumbnailImageERKNS0_3UidE", "save-data thumbnail; the host keeps none", Result, h_acc_DeleteThumb, const Uid *uid) { (void)uid; return RES_OK; }
HLE_FORCE("_ZN2nn7account32IsNetworkServiceAccountAvailableEPbRKNS0_10UserHandleE", "network service account, out of scope; always unavailable", Result, h_acc_NsaAvail, uint8_t *out, const UserHandle *h) { (void)h; *out = 0; return RES_OK; }
HLE_FORCE("_ZN2nn7account9CloseUserERKNS0_10UserHandleE", "close user; the handle holds no session", void, h_acc_CloseUser, const UserHandle *h) { (void)h; }
HLE_FORCE("_ZN2nn7account12AsyncContext6CancelEv", "async context cancel; no request", Result, h_acc_AcCancel, void *self) { (void)self; return RES_OK; }
HLE_FORCE("_ZN2nn7account12AsyncContext7HasDoneEPb", "async context: always done", Result, h_acc_AcHasDone, void *self, uint8_t *done) { (void)self; *done = 1; return RES_OK; }
HLE_FORCE("_ZN2nn7account12AsyncContextD1Ev", "async context destructor; no session to close", void, h_acc_AcDtor, void *self) { (void)self; }
HLE_FORCE("_ZN2nn7account12AsyncContextaSEOS1_", "async context move assignment; the object holds no host state", void *, h_acc_AcMove, void *self, void *other) { (void)other; return self; }
