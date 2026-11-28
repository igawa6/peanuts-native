// A program's stage keys in the host's side table (hle_nvn.c "Programs"): which record a program object finds, and who may drop it. Free of locks and of NVN, so
// dm/host/tests/t_nvn_progkeys.c can check it; the caller holds its lock.
// The record is found by the program's serial, which nvnProgramSetShaders gives and a copy of the NVNprogram carries, not by the object's address: a game may copy its
// program objects after SetShaders and draw with the copies (Ori and the Will of the Wisps, Unity 2018.4), and a copy found no keys by address. The record holds the
// object SetShaders ran on (its owner); a Finalize or a new SetShaders of another object, a copy, leaves the record to the owner, which may still draw with it.
#ifndef NVN_PROGKEYS_H
#define NVN_PROGKEYS_H
#include <stdint.h>

// The side table's key for a serial: never 0 (the Android pointer map's empty slot) nor 1 (its tombstone); serials start at 1
static inline const void *nvn_pk_id(uint64_t serial) { return (const void *)(uintptr_t)(serial + 2); }
// Whether the program object p may drop the record its serial finds: the owner may, and so may anyone when it has none (keys a frame capture set)
static inline int nvn_pk_may_drop(const void *owner, const void *p) { return !owner || owner == p; }

#endif
