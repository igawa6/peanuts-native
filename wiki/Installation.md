# Installation

New here? The [Quick start](Quick-Start) has the five steps on one page, and the [Glossary](Glossary) explains the words. Banana Native is an Android app (an APK). This page says what phone you need, where to get the app, and what to turn on after installing. Your game is added afterwards: see [First start and Prepare](First-Start-and-Prepare).

## What you need

- **Android 13 or newer**, a 64-bit phone with a **Snapdragon (Adreno) GPU** and **at least 12 GB of RAM**. Mario Kart World (`0400C3F00006E000`) needs 16 GB.
- Tested on a Snapdragon 8 Elite Gen 5 (Adreno 840) with 16 GB, with the phone's own Qualcomm (system) driver and with the latest Turnip. Other drivers and phones may not work; reports are welcome ([Tested devices](Tested-Devices)).
- **Your own dump** of the game, made from your own console, as a folder (or a `.nup` file of one). The folder has `base/`, and `updates/<version>/` and `dlc/` if you have them, each with `exefs/` and `romfs/`. NSP and XCI files do not work. No keys or firmware are needed, and this wiki does not explain how to get games.
- About 1 GB free for the prepared game (the dump itself is read in place). Preparing on the phone needs 2 GB more while it runs.
- Recommended: a phone cooler (strongly for Mario Kart World), and a Windows PC or a Mac to prepare games faster ([PC or Mac](First-Start-and-Prepare#preparing-on-a-pc-or-a-mac)).
- Optional: your own `Lossless.dll` from Lossless Scaling for Windows, for frame generation. It is not included.

Expected folder layout for a dump (copy it as it is):

```
Switch2/
  DonkeyKongBananza/
    base/program/0/                the game (exefs/, romfs/)
    updates/<version>/program/0/   its update, if any
    dlc/<title id>/<version>/      extra content, if any
```

A flattened folder, with `exefs/` and `romfs/` directly in `base/` and `updates/<version>/`, works too.

## Get the app

1. Open the project's [releases page](https://git.eden-emu.dev/moceure/BananaNative/releases) on the official site and download the newest APK. Download only from there.
2. Install it. Android may ask you to allow installing from your browser or file manager; that is a normal Android prompt.
3. Open **Banana Native**.

Later versions come through the app's own updater ([Updates](Updates)).

## Allow file access

Home shows an **Allow file access** card with an **Allow** button. It opens Android's settings, where you turn on **All files access** for Banana Native; then come back. With it on, the app reads your dump where it is.

You can skip it: a `.nup` file is then used where it is, and a dump folder is copied into the app's own storage (needs the dump's size again, plus 2 GB). See [Saves and storage](Saves-and-Storage#storage-access) for the three ways and what each means on uninstall.

## Check the phone

Settings > **Readiness check** tells you whether this phone can run your games: Android, memory, file access, storage, the GPU and its driver, and each game's needs. Items that need fixing have a button that opens the right place.

<!-- TODO screenshot: Home with the Allow file access card -->
<!-- TODO screenshot: Settings > Readiness check result -->

## Next

[First start and Prepare](First-Start-and-Prepare)
