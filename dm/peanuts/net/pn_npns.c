// PEANUTS: npns:u, the console's push notifications, online (PN_ONLINE=1). Animal Crossing: New Horizons links its own client of npns (hle_sf.c) and connects to
// it once it is online, after its first requests to the island services; upstream refuses every service it does not serve (sm's 0xe15, "not registered"), and
// the game aborts on that (nn::diag abort at main+0x598bc8, 3.0.3).
//
// Nextendo Network has no push server, so this is a console whose push channel never delivers anything: listening succeeds, Receive answers 0x69a (nothing to
// receive, which ends the game's read loop; any other failure aborts it, as ACNHRecomp found), and the receive and state-change events never fire. Any other
// command succeeds with zeroed output (hle_sf.c logs each the first time). Without pushes, the game sees changes to its friends only when it reads them again.
// Every other service name goes to upstream's answer, unchanged.
#include "pn_net.h"
#include <string.h>

#define WHY "online (dm/peanuts/net): npns:u, push notifications that never arrive"

static Result npns_answer(unsigned cmd, uint32_t *copy) {
    switch (cmd) {
    case 3: case 4: return (3u << 9) | 154u;              // Receive, ReceiveRaw: 0x69a, nothing to receive
    case 5: case 7: *copy = hle_new_event(NULL); return RES_OK;   // GetReceiveEvent, GetStateChangeEvent: an event never signaled
    default: return RES_OK;                               // ListenAll, ListenTo, ... : accepted
    }
}

PN_FORCE("_ZN2nn2sf4hipc20ConnectToHipcServiceEPNS_3svc6HandleEPKc", WHY, Result, pn_sf_Connect, uint32_t *out, const char *name) {
    if (name && !strncmp(name, "npns:u", 8)) {
        *out = pn_sf_session("npns:u", npns_answer);
        LOG("online: npns:u → host session %#x (push notifications that never arrive)", *out);
        return RES_OK;
    }
    return ((Result (*)(uint32_t *, const char *))pn_upstream("_ZN2nn2sf4hipc20ConnectToHipcServiceEPNS_3svc6HandleEPKc"))(out, name);
}
