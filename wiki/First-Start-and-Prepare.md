# First start and Prepare

Each game is prepared once into a **pack**: its code rewritten for Android and its shaders translated for Vulkan. You can do this on the phone alone, or on a Windows PC or a Mac, which is much faster. This page covers adding the game and both ways to prepare it. Nothing is uploaded anywhere.

## Phone only

1. **Copy your dump** to the phone, for example to `Switch2/DonkeyKongBananza`.
2. [Install the app](Installation) and allow **All files access** (or skip it, see [Saves and storage](Saves-and-Storage#storage-access)).
3. Tap **Add game**, choose **A folder (with base/, and updates/ if any)** or **A .nup file**, and pick your dump. Without All files access the choices are **A .nup file (used where it is)** and **A dump folder (copied into the app: ...)**.
4. The app checks the dump and asks **Prepare ...?** Tap **Prepare**.
5. When the card says it is ready, tap **Play** ([Playing](Playing)).

What to expect while it prepares:

- It runs in the background with a notification. **Pause** and **Resume** on the game's card stop and continue it.
- Keep the phone on the charger. If the screen going off stops it, turn on Settings > **Keep the screen on while preparing**.
- A hot phone pauses Prepare ("Paused: the phone is too hot") and it goes on when the phone has cooled.
- **How long:** the project's pages disagree and it depends on the game: "about an hour per game" (getting-started guide) and "about 20-45 minutes, mostly translating shaders" for the first Prepare (main page). Mario Tennis Fever (`0400D4201C6EA000`) has 35,045 shaders and takes much longer. Not measured on a range of phones yet.
- Prepare is incremental: later runs reuse existing shaders and only rewrite code when its key changes (a few minutes).
- If it stops, the card says why and offers **Retry**. Close other apps if memory ran out; free space if storage is full.
- A computer is recommended for Mario Kart World (`0400C3F00006E000`).

## Preparing on a PC or a Mac

A Mac prepares Donkey Kong Bananza (`040074A01BF12000`) in about 10-15 minutes. The Mac packer is tested; the Windows one is new and not yet tested on a real PC, so please report how it goes.

1. Get the project's repository from the [official site](https://git.eden-emu.dev/moceure/BananaNative) (clone or download). Double-click `scripts\bananative.bat` (Windows) or `scripts/bananative.command` (Mac).
2. The first time, it asks for a folder for its tools and installs them there (Python, the shader tools and the .NET runtime, about 400 MB).
3. In the window, pick the game (**.nup file...** or **Dump folder...**) and click **Prepare**. **Stop** ends it; the next run goes on from where it stopped.
4. The pack ends up in `<title ID>-work/android-pack` next to the game folder (about 250-400 MB). **Show pack** opens it.
5. Copy the game folder to the phone, and either:
   - copy only `android-pack` beside it as `Switch2/<title ID>-work/android-pack`, then tap **Add game** and pick the game folder: the app finds the pack; or
   - copy `android-pack` anywhere, tap **Add game**, and when the app asks **Prepare ...?** choose **Import pack...** > **A pack folder (with pack.tsv and shaders/)**.

If it fails, the window's log and tool check say what is missing; `scripts/bananative.command check` (or `scripts\bananative.bat check`) checks the tools again.

## Moving a pack between phones

The game's ... button > **Export pack...** writes one `.zip` you can keep or move; **Import pack...** takes it (or a pack folder) back. Both need All files access. See [Updates](Updates#after-an-app-update) for what happens to the shaders when the app updates.

## Version warnings

If your dump is another version than the one the game was tested with, the card says it is untested; it may still work and the app asks before it starts. If a game is "Not in this app version", your app does not include it (Settings > **About** lists the games).

<!-- TODO screenshot: the Prepare progress card -->

## Next

[Playing](Playing) · [Troubleshooting](Troubleshooting)
