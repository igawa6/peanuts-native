# Mistel: the Java library for .mncf cheat files

<!-- PEANUTS: this folder is Peanuts Native's; upstream Banana Native has none of it. Written by peanuts/scripts/mncf-vendor.sh: do not edit by hand. -->

The launcher reads, checks, converts and writes cheat files in the Mistel Native Cheat Format (`.mncf`, packs `.mncp`) with the Java library of
[Mistel](https://github.com/davdxpx/mistel)'s `format/` folder, package `io.github.davdxpx.mistel.mncf`. Upstream: `format/lib/java/src/main/java/`
at commit `f25485c19df4d4723c5caeb1557d357d7b244aac` (`f25485c`), **unmodified**. License: MIT, see [LICENSE](LICENSE) (Mistel's `format/LICENSE`). Only `format/` of Mistel is MIT;
nothing of its GPL-3.0 parts is in this repository.

`android/app/build.gradle` adds `java/` to the app's Java sources (`java.srcDirs`), so the library is compiled with the launcher, which uses it in
`android/app/src/main/java/dev/peanuts/mods/`. Nothing is downloaded at build time.

Only the files the launcher needs are kept: those Peanuts' own code names, and every file of the library they name in turn. 59 files, 15318 lines:
`Arm64`, `Atmosphere`, `AtmosphereException`, `Build`, `Carry`, `Cheat`, `CheatReport`, `CheatResult`, `Choices`, `CodeModule`,
`CodeModuleException`, `Conversion`, `ConversionReport`, `ConvertException`, `Converter`, `Ed25519`, `Encode`, `Evaluation`, `Evaluator`,
`GuestMemory`, `Lz4`, `Lz4Exception`, `Marks`, `Mncf`, `MncfException`, `MncfFile`, `Model`, `Module`, `Pack`, `PackFile`, `PackReader`, `Patch`,
`PatchFile`, `PatchFileException`, `PatchSource`, `Patches`, `Patterns`, `Platform`, `Preset`, `Py`, `Release`, `ResolvedOp`, `Runtime`, `Schema`,
`SignatureResult`, `Signatures`, `Source`, `TrustList`, `Update`, `UpdateCheck`, `Updates`, `Utf8`, `ValueSpec`, `Values`, `Writer`, `Zip`,
`toml/Toml`, `toml/TomlDateTime`, `toml/TomlException`.

Left out, as the launcher needs none of them: `Change`, `CheatState`, `Engine`, `Pass`, `Shown`.

[MISTEL.sha256](MISTEL.sha256) lists every file with its SHA-256; `peanuts/tests/test_mncf_vendor.py` fails when a file here is changed, added or taken
away. A fix goes to Mistel first; then `sh peanuts/scripts/mncf-vendor.sh <mistel checkout> [<commit>]` copies the new commit's files and writes this
README, the list and the commit in [../README.md](../README.md) again. It keeps what the list has, adds what the code now names, and drops a file only
when its line is taken out of the list before the run.
