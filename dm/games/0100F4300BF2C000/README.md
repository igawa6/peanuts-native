# New Pokemon Snap

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

In bring-up on Android.

- SDK 10, NVN + Wwise, Bandai Namco nu/nv.
- RomFS 3153 files, 5.81 GB; 724 new-only imports.
- Shaders: 3316 (8 compute, 2 geometry, 1743 pixel, 1563 vertex), all from the DRPF archives the RomFS keeps them in (shader_split.py's drp.py reader; only main's 8 GLSLC outputs sit outside them); 4689 variation pairs; dual-source blending for two keys.
- Memory: the game maps its own memory into the alias region and asks the SDK's region allocator for a 160-192 MB window at a park run and at the return to the lab, unchecked; the profile gives it 8 GB of alias in an 8.625 GB block (the first profile's 4.375 GB in 5 GB pinned the region table at +4.25 GB and the allocation failed, so the guest memset a null pointer). Two sessions past the old cap (the lab return and the park start) ran with no refusal.
