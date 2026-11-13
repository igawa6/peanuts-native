// PEANUTS: the friends service online (PN_ONLINE=1), replacing hle_friends.c's "unavailable" for the calls a game makes around online play. Each succeeds and
// changes nothing: no friends, nothing recorded, no presence sent (as Lemon's friends service answers them, GPL-3.0-or-later). Mario Kart 8 Deluxe declares an online
// play session and registers the players it meets once it is in a room; with upstream's failures it showed "An error has occurred" at character select.
// Each call is logged the first few times, to see where a game is.
#include "pn_net.h"
#include <stdatomic.h>

#define WHY "online (dm/peanuts/net): friends, succeeds and changes nothing"
#define SEEN(what) do { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 3) LOG("online: friends %s", what); } while (0)

PN_FORCE_BIG("_ZN2nn7friends28DeclareOpenOnlinePlaySessionERKNS_7account3UidE", WHY, Result, pn_fr_Open, const void *u) { (void)u; SEEN("DeclareOpenOnlinePlaySession"); return RES_OK; }
PN_FORCE_BIG("_ZN2nn7friends29DeclareCloseOnlinePlaySessionERKNS_7account3UidE", WHY, Result, pn_fr_Close, const void *u) { (void)u; SEEN("DeclareCloseOnlinePlaySession"); return RES_OK; }
// The key is left as the game passed it, as Lemon's service leaves its buffer
PN_FORCE_BIG("_ZN2nn7friends29GetPlayHistoryRegistrationKeyEPNS0_26PlayHistoryRegistrationKeyERKNS_7account3UidEb", WHY, Result, pn_fr_Key, void *key, const void *u, unsigned local) {
    (void)key; (void)u; (void)local; SEEN("GetPlayHistoryRegistrationKey"); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7friends14AddPlayHistoryERKNS_7account3UidERKNS0_26PlayHistoryRegistrationKeyERKNS0_15InAppScreenNameESA_", WHY, Result, pn_fr_AddU, const void *u, const void *k, const void *a, const void *b) {
    (void)u; (void)k; (void)a; (void)b; SEEN("AddPlayHistory"); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7friends14AddPlayHistoryERKNS0_26PlayHistoryRegistrationKeyERKNS0_15InAppScreenNameES6_", WHY, Result, pn_fr_Add, const void *k, const void *a, const void *b) {
    (void)k; (void)a; (void)b; SEEN("AddPlayHistory"); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7friends13GetFriendListEPiPNS0_6FriendERKNS_7account3UidEiiRKNS0_12FriendFilterE", WHY, Result, pn_fr_ListU, int *n, void *f, const void *u, int off, int cap, const void *flt) {
    (void)f; (void)u; (void)off; (void)cap; (void)flt; if (n) *n = 0; SEEN("GetFriendList (none)"); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7friends13GetFriendListEPiPNS0_6FriendEiiRKNS0_12FriendFilterE", WHY, Result, pn_fr_List, int *n, void *f, int off, int cap, const void *flt) {
    (void)f; (void)off; (void)cap; (void)flt; if (n) *n = 0; SEEN("GetFriendList (none)"); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7friends12UserPresence10InitializeERKNS_7account3UidE", WHY, Result, pn_fr_PresInit, void *self, const void *u) { (void)self; (void)u; SEEN("UserPresence::Initialize"); return RES_OK; }
PN_FORCE_BIG("_ZN2nn7friends12UserPresence14SetDescriptionEPKc", WHY, Result, pn_fr_PresDesc, void *self, const char *d) { (void)self; (void)d; SEEN("UserPresence::SetDescription"); return RES_OK; }
PN_FORCE_BIG("_ZN2nn7friends12UserPresence6CommitEv", WHY, Result, pn_fr_PresCommit, void *self) { (void)self; SEEN("UserPresence::Commit"); return RES_OK; }
