// PEANUTS: the audio renderer's output follows the game's pace (peanuts_audiosync.c).
// A Switch game whose logic counts frames runs its timeline at 1/60 s per presented frame, while its sound plays in real time. On the console both come from one
// clock and the game holds its frame rate. Here the panel can refresh slower than 60 Hz (the AYN Thor: 59.84 Hz) and a heavy scene runs slower than 60 fps, so
// the frame timeline falls behind the sound. A game that keeps a sound in step with its timeline catches up itself: Super Mario Odyssey pauses its synced
// streams for about 100 ms each time the sound is about 100 ms ahead (the intro: every few seconds at 58-59 fps; Bonneton's first scene at 47.5 fps: every 0.5 s).
// The host plays the game's sound at the game's pace instead: it measures game time (presented frames / the game's frame rate) against real time, and how far
// the sound ran ahead of the game, and sets the tempo so both stay together (0.5x to 1.03x). The pitch stays: a time-stretch (WSOLA) changes the tempo, a
// resampler takes only the last 1 % (inaudible). Loads, pauses and menus (no frames, or a pace far from the game's rate) play in real time.
// Which games: a game whose sound is not tied to its frames (its world runs in real time) must keep real-time sound. So by default (PN_AUDIO_FOLLOW=1) the
// output follows only within 1 % (inaudible: a slower panel, short drops), until the game shows that it keeps its sound in step: it pauses a group of voices and
// starts them again within 300 ms while it runs slow (peanuts_audiovoice.c), twice within 60 s. From then on, and on later starts (<DM_TEMP>/audio_follow), the
// output follows fully. PN_AUDIO_FOLLOW=0 turns it off, 2 follows fully from the start.
#pragma once
#include <stdint.h>

// One presented game frame (present thread, from peanuts_tick); fps is the game's own frame rate (60, or 30 for a game on present interval 2)
void pn_as_frame(uint64_t now_ns, int fps);
// The render thread of renderer `index`: `n` stereo int16 frames of the renderer's 48 kHz output in buf, replaced by what to play now, at the game's pace (at
// most `cap` frames; 0 while the stretch fills its look-ahead). Returns the number of frames now in buf
int pn_audio_follow(int index, int16_t *buf, int n, int cap);
// The tempo the output follows now (1 = real time); for the log and the tests
double pn_as_ratio(void);
// A group of the game's voices was paused and started again within 300 ms (peanuts_audiovoice.c, the game's thread)
void pn_as_resync(uint64_t now_ns);
// For the tests: forget every state (renderers, frames, tempo) and read PN_AUDIO_FOLLOW again
void pn_as_reset(void);
// For the tests: the tempo is `t` from now on (no measurement), or measured again with t <= 0
void pn_as_force(double t);
