# Mario Tennis Fever, Switch 2 (0400D4201C6EA000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a
# module's NSO build ID ("Registered patches" there). The game's code is one NRO, nro0, which main decodes at run time; the pack's rewrite of it is keyed by the NRO's build ID.

# 1.1.1 nro0: the scenes' size below what the graphics scales give (Game settings > Resolution, Phone 540p; dm/host/android/mtf_size.c). One observation gate where the size function's
# paths join, after the graphics scale is applied (x20 the scene's size record, w9 its width). The callback does nothing unless the launcher passed a graphics size (DM_GRAPHICS_SIZE)
# smaller than the game's pick. The original word is position independent and checked by the rewriter
_MTF_SIZE = 34   # OBS_MTF_SIZE in dm/host/dm.h
OBS = {
  '250a510a934129cb38e930c9a29082b6': [
    (0x125eb98, _MTF_SIZE, 'b9410a6a', "scene size after the graphics scale (ldr w10,[x19,#0x108], where the scale paths join); x20 the size record (u16 width +0x1c, height +0x1e), w9 its width"),
  ],
}
BINDING = [
    {'what': 'scene size: the scenes drawn below the smallest size the graphics scales give (Game settings > Resolution, Phone 540p)', 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': "the size function is the game's own code at a fixed address of this build; no Horizon query gives a smaller size",
     'blocked': 'one build on hand; the join could be found by function fingerprint (the 4/5 and 2/3 multiplies after GetCurrentGraphicsScale)'},
]
