# Digimon Story Time Stranger (010062E01FE0C000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a module's
# NSO build ID ("Registered patches" there).

# PEANUTS: main 1.2.2's animation job pool (its workers are the PoseWorker threads). The pool keeps two job vectors: the first at pool+0x80 (a high-water count, the take index at
# +0x84, begin / end / capacity at +0x88 / +0x90 / +0x98), which its workers take from holding the mutex at pool+0x48; the second at pool+0xa0, filled by the submit
# (main+0xb7ebc0) holding the mutex at pool+0x10, which a worker moves into the first (main+0xb7fb50: it appends, empties the second and raises the first's high-water count).
# When every worker has gone idle, the pool's wait (main+0xb7f090) "tidies" both vectors with main+0xb7f240, holding neither mutex: it grows the vector to its high-water
# count (null slots), copies it into an array of exactly that size, and empties it (end = begin, main+0xb7f30c) without looking at the take index or at jobs not taken yet.
# A submit on another thread and a worker it wakes run meanwhile: a worker takes null slots of the half-tidied vector (CRASH addr=0x378 / 0x380 at main+0xb7f980 / +0xb7f958),
# or the jobs a worker has just moved in are emptied away, or the take index is left past the new end so the next jobs are skipped; the count of jobs not done (pool+0x8) then
# never returns to 0, the workers spin and the wait never ends (the picture stops, sound goes on). The tidy only shrinks the two vectors' capacity: the high-water count is
# read nowhere else, and the workers already empty the first vector whenever they take its last job. So the cut skips it (a branch over both calls and the assert loop after
# them, which reads the idle list and writes nothing); the vectors keep the capacity of their largest batch (8 bytes a job). Taking each vector's mutex around the tidy
# (tried first) ended the crashes but not the stops: the tidy still emptied jobs not taken.
CUTS = {
  '135c83cdea06aec2d858e1bc7d53d72f': [
    (0xb7f178, '60020291', '23000014',
     'the pool wait skips its vectors\' tidy: add x0, x19, #0x80 (before bl main+0xb7f240) → b main+0xb7f204'),
  ],
}

BINDING = [
    {'what': 'animation job pool: the wait no longer tidies the job vectors it may empty under running workers (crash at main+0xb7f958 / +0xb7f980, the picture stopping)', 'now': 'build', 'goal': 'build', 'purpose': 'race',
     'why': "the game's own pool empties its job vectors holding neither mutex; the cut's word is a fixed address of main 1.2.2"},
]
