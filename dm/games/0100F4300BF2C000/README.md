# New Pokemon Snap

In bring-up on Android.

- SDK 10, NVN + Wwise, Bandai Namco nu/nv.
- RomFS 3153 files, 5.81 GB; 724 new-only imports.
- Shaders: 3316 (8 compute, 2 geometry, 1743 pixel, 1563 vertex), all from the DRPF archives the RomFS keeps them in (shader_split.py's drp.py reader; only main's 8 GLSLC outputs sit outside them); 4689 variation pairs; dual-source blending for two keys.
- Known issue: returning to the lab from a run crashes the guest ResourceManager thread with a null dereference (a lost completion message in the guest's job graph, the same transition that stalled the macOS bring-up; see the bring-up notes).
