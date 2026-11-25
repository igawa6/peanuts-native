// PEANUTS: the game's voice play-state changes (nn::audio::SetVoicePlayState), for the audio follower (peanuts_audiosync.h). A game that keeps a sound in step
// with its frame timeline pauses that sound's voices for a moment when the sound runs ahead, and starts them again: Super Mario Odyssey sets the volume of the
// 8 voices of its synced streams to 0, pauses all 8, and starts them again about 70 ms later, at every one of its ~100 ms dropouts. A group of voices paused and
// started again within 300 ms is one such resync (pn_as_resync); a pause menu or a stopped sound lasts longer. The take-over calls the SDK's own function first
// and only notes the change.
#include "../host/hle.h"
#include "peanuts_audiosync.h"
#include <pthread.h>

enum { PV = 64 };
#define SHORT_NS 300000000ull   // a pause this short is a resync, not the game stopping its sound
#define GROUP_NS 50000000ull    // restarts this close together are one resync

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static struct { void *v; uint64_t t; } g_paused[PV];   // voices paused, and when
static uint64_t g_last_group;

static void (*g_orig_state)(void *, int);
HLE_FORCE("_ZN2nn5audio17SetVoicePlayStateEPNS0_9VoiceTypeENS1_9PlayStateE", "voice play state: the SDK's own, then noted for the audio follower (peanuts_audiovoice.c)",
          void, pn_h_SetVoicePlayState, void *voice, int state) {
    if (!g_orig_state) g_orig_state = guest_sym("_ZN2nn5audio17SetVoicePlayStateEPNS0_9VoiceTypeENS1_9PlayStateE");
    if (g_orig_state) g_orig_state(voice, state);
    uint64_t now = now_ns(); int group = 0;
    pthread_mutex_lock(&g_mu);
    int i = 0; while (i < PV && g_paused[i].v != voice) i++;
    if (state == 2) {   // PlayState_Paused
        if (i == PV) { i = 0; for (int k = 1; k < PV; k++) if (g_paused[k].t < g_paused[i].t) i = k; }   // a free slot (t 0) or the oldest
        g_paused[i].v = voice; g_paused[i].t = now;
    } else if (i < PV) {
        if (state == 0 && now - g_paused[i].t < SHORT_NS && now - g_last_group > GROUP_NS) { g_last_group = now; group = 1; }   // PlayState_Started soon after the pause
        g_paused[i].v = NULL; g_paused[i].t = 0;
    }
    pthread_mutex_unlock(&g_mu);
    if (group) pn_as_resync(now);
}
