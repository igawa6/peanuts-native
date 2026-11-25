# Peanuts Native FAQ

For version 0.1.8. Labels in **bold** are the words the app shows. "Settings" is the gear button on the home screen. "Hold the cover" means a long press on a game's cover (or Y on a controller).

## Quick answers

- **Build is off, or a game stops at startup:** add your keys and firmware in Settings › Console (**Keys**, **Firmware**), then build or play again. See [Keys and firmware](#keys-and-firmware).
- **Black screen, wrong picture or a crash at start:** pick another GPU driver in Settings › Graphics › **GPU driver**, then run Settings › Help › **Readiness check**. See [GPU drivers](#gpu-drivers).
- **A cover says Wrong version:** the update you have is not one this app version supports. See [What does "Wrong version" mean?](#what-does-wrong-version-mean-and-how-do-i-fix-it)
- **The app closes as soon as it opens, on version 0.1.1 or 0.1.2:** on Android 13 or older those two versions close when they find a newer release. Install 0.1.3 or later by hand from the project's [releases page](https://github.com/igawa6/peanuts-native/releases); your games, saves and settings stay. Until then the app opens with Wi-Fi off.
- **The companion is empty or keeps waiting after an app update:** hold the game's cover, open **Storage**, tap **Build again**, then **Rebuild**.

## First run, step by step

### How do I go from a fresh install to a running game?

You need three things from your own console first: your game dumps, your `prod.keys` and a firmware dump. Copy them to the device. Then:

1. Install the APK and open the app. The first screen is the card **Set up your games**. It lists four steps: **1 Keys**, **2 Game folder**, **3 Updates & DLC**, **4 Firmware**.
2. Tap **Keys** and pick your `prod.keys`. You should see the message **Keys accepted**, and the row reads **Added: prod.keys is in the app.**
3. Tap **Game folder** and pick the folder that holds your dumps. The first time, a dialog **Allow file access** appears. Tap **Open settings**, turn on All files access for Peanuts Native, and go back to the app. You should see the folder's path in the row.
4. Optional: tap **Updates & DLC** if your update or DLC files are in other folders. Set **Update folder** and **DLC folder**, then **Done**. Skip this if everything is inside the game folder.
5. Tap **Firmware**, then **Folder** or **Zip file**, and pick your firmware dump. The row reads **Reading the firmware…** and then **Firmware added from …**. Do this after the keys: the fonts cannot be read without them.
6. Tap **Show my games**. You should see a grid of covers. A game that can be built has a **Build** label on its cover.
7. Optional: install a GPU driver in Settings › Graphics › **GPU driver**. See [Which driver is recommended?](#which-driver-is-recommended)
8. Tap a cover. Its sheet opens with the rows **Base**, **Update**, **DLC** and **Dual-screen companion**. Check them and tap **Build**. Android asks to allow notifications: allow it, so you can see the progress outside the app.
9. Wait. The cover shows **Building · N %**. You can leave the app.
10. When the build ends you see **… is ready to play.** and the cover gets a green check. Tap the cover to play.

What you should see at the end: the game on the main screen and, on a dual-screen device, its companion on the second screen.

### Why are my games listed without covers, or not at all, before I add keys?

Without keys the app cannot read inside NSP and XCI files. It then lists a file only when its name contains the 16-digit title ID, without its cover, its real name or its version. Add your keys, then tap Settings › Library › **Rescan**.

## Basics

### What is Peanuts Native, and is it an emulator?

It runs your own Nintendo Switch game dumps on an Android handheld. When you add a game, the app builds it once on the device: it rewrites the game's code for the device's CPU and translates its shaders. After that the game runs directly.

It is not an emulator in the usual sense: it does not emulate the console's hardware while you play. The price is the build step, which takes minutes per game, and the fact that each game needs a profile inside the app.

### Which devices does it run on?

It is developed and tested on the AYN Thor (dual screen, Snapdragon 8 Gen 2, 16 GB). The stated needs are Android 13 or later, a 64-bit ARM device and a Snapdragon (Adreno) GPU with a Vulkan 1.3 driver. Other devices are untested. On a single-screen device the game still runs, without the companion.

### How much RAM do I need?

The documented requirement is 12 GB or more. On 8 GB devices, building large games crashed before 0.1.1. Since 0.1.1 the build uses fewer workers on devices with less memory, so it is slower but should finish. Playing on 8 GB is untested and may not work for every game.

### How much storage do I need?

Plan for the size of the game plus a few GB. Before a build, the game's sheet shows **Needs about … free (a rough estimate)**, or a **Low disk space** warning. The estimate is rough, and a low-space warning does not block the build.

A game compressed as a solid `.nsz` needs about its size once more while it is imported, for the temporary copy (see [Can I build from NSZ or XCZ files?](#can-i-build-from-nsz-or-xcz-files)). The sheet says how much of the estimate that is.

The app also keeps what a build made in its own storage, so that the next build of the game keeps what did not change: at most 4 GB for every game together, the games built longest ago going first, and none that would leave less than 2 GB free. Settings › **Storage** lists it under **Build caches**, one row a game.

### What do I need from my own console?

- Your own game dumps as NSP, XCI, `.nup` files or dump folders, with their updates and DLC if you use them. NSP and XCI files compressed with nsz (`.nsz`, `.xcz`) work too.
- Your own `prod.keys`.
- Your own firmware dump, as a folder or a `.zip`. The app extracts the fonts from it.

You dump your own games, keys and firmware from your own console. The app ships with none of them and does not link to any.

### Where do my files go?

- Your dumps stay where you put them, in the game folder you picked.
- Settings › Library › **Build folders** lets you add folders on internal storage and the SD card. The game sheet's **Build destination** chooses where that game's new build goes.
- Keys and extracted fonts are kept inside the app.
- Saves are in the app's own storage. The game's **Saves** tab shows the exact path.

The setup screen says it plainly: **Keys, firmware and folders stay on this device. Nothing is uploaded.**

### Is it legal to use with my own dumps?

The app runs only dumps you made from your own console, and it contains no game code, firmware or keys. Whether dumping is allowed depends on where you live. This is not legal advice. Do not share built games or their shaders: both are made from your game's files.

### Can I play online?

Not in this version.

## Keys and firmware

Both come from your own console. The app contains no keys and no firmware, and it never downloads them.

### How do I add my keys?

1. Open Settings › Console › **Keys**. The same row is on the first-run card, and a game's sheet has **Add keys…** while keys are missing.
2. The file picker opens with the hint **Pick your prod.keys**. Pick the `prod.keys` file from your own console. A file larger than 1 MB is refused.
3. The app copies the file into its own storage and checks it.

What you should see: the message **Keys accepted**. The row then reads **prod.keys is in the app (N bytes)**.

### The app says "The importer does not accept these keys". What is wrong?

The file is not a usable `prod.keys`. The message names what is missing, for example **has no header_key** or **has no key_area_key_application_00**. Pick the right file to replace it. Note that the rejected file is kept, so the row still reads **prod.keys is in the app**. A build will fail until you replace it.

### A build stops with "prod.keys has no titlekek_…". What now?

Your keys do not have the key this game or update needs. This usually means the keys were dumped on an older firmware than the game needs. Dump the keys again from your own console after updating it, then pick the new file in Settings › Console › **Keys**.

### How do I add the firmware?

1. Add your keys first. Without keys the firmware cannot be read.
2. Open Settings › Console › **Firmware**. The same row is on the first-run card, and a game's sheet has **Choose firmware…** while fonts are missing.
3. A small sheet **Firmware** asks: **Folder** or **Zip file**.
   - **Folder**: pick the folder your firmware dump is extracted into (hint: **Pick your firmware folder**). The app looks for `.nca` files in it, up to three folders deep.
   - **Zip file**: pick the firmware `.zip` (hint: **Pick your firmware zip**), with the `.nca` files inside it.
4. The row reads **Reading the firmware…**. A zip that is not on the device's own storage is copied first (**Copying the firmware zip…**).

What you should see: **Firmware added from** followed by the folder or zip you picked.

The app keeps only two font files from the firmware. It does not copy or install the rest, and you can delete the firmware dump from the device afterwards.

### What do the firmware errors mean?

The row shows **Firmware not added:** and a reason. The last one in this table comes as a dialog instead.

| Reason | Meaning | Fix |
|---|---|---|
| **keys not loaded** | Keys were not added, or not yet read in this session. | Add **Keys** (pick the file again if needed), then pick the firmware again. |
| **no NCA files not found among 0 NCAs** | The folder or zip holds no firmware files the app could read. | Pick the folder or zip of a full firmware dump. |
| **the shared font archives were not found among N NCAs** | It is a firmware dump, but the font archives are missing from it, or your keys cannot open them. | Dump the firmware again, complete, and check your keys. |
| **cannot read FontStandard / FontNintendoExtended … prod.keys has no key_area_key_…** | The font archive is present, but the selected keys lack its encryption generation. | Select your keys that match the firmware, then pick the firmware again. Repeating the import with the same older keys cannot decrypt it. |
| **could not copy the zip** | The zip could not be read from where it is. | Copy the zip to the device's storage and pick it there. |
| **That folder is not on the phone's storage** | You picked a cloud folder. | Pick a folder on the device or its SD card. |

### Can I replace or remove the keys or the firmware?

Replace: pick again. The new file or firmware takes the place of the old one. The app has no button to remove them. They are stored in the app's private storage, not in your folders.

### When do I need the firmware, and why does the app ask for it?

Before the first build. **Build** stays off and the sheet shows **Setup needed** until both keys and firmware are added. The line beside it says which is missing, for example **Firmware is needed before building.**

Some games also need the fonts to start at all, for example Echoes of Wisdom. If such a game was built without them, Play shows the dialog **Firmware needed** instead of crashing:

- **Choose firmware…** opens the same Folder or Zip file choice. Add the fonts, then tap the cover again.
- **Play anyway** starts the game without them. Echoes of Wisdom stops at startup this way.
- **Cancel** goes back.

## Adding and building games

### How do I add and build my games?

1. Open the app. The first screen is **Set up your games**.
2. Add **Keys**, pick the **Game folder**, set **Updates & DLC** if those files are in other folders, and add **Firmware**.
3. Tap **Show my games**.

Then tap a game's cover to open its sheet. Check the **Base**, **Update**, **DLC** and **Dual-screen companion** rows and tap **Build**. The build imports the game, prepares it and installs the companion. Only one build runs at a time.

Later, change the folders in Settings › Library. **Rescan** reads them again.

### Where does the app look for my games?

In the **Game folder**, the **Update folder**, the **DLC folder** and the **Build folder**, each up to two folders deep. It finds `.nsp`, `.xci`, `.nsz`, `.xcz` and `.nup` files and dump folders: in the dump tools' layout (`base/`, `updates/<version>/`), in the console's layout as OunceCake serves it (`base/program/0/`), and one version's folder (`exefs` and `romfs` at its top). A dump folder's version is its `updates/<version>/`, else the version its `main` was built for, so a game updated on a PC shows as that update. Build prepares a dump from `base/` or `updates/<version>/`: for one version's folder, make a folder named `base` in it and move `exefs` and `romfs` there. Updates and DLC are matched to their game by title ID, wherever they are in those folders. A file deeper than two folders is not found: move it up, or point the folder setting at it. Mods for other emulators are skipped: folders named `mods`, `load` or `atmosphere`, and one version's folder whose `exefs` lacks the game's `main`, `main.npdm` or `sdk` (a mod may bring its own `main`, never `sdk`). To use a mod here, add it on the game's page, see [Mods](#mods).

If the home screen says **No games found in …**, put your files in that folder and tap Settings › Library › **Rescan**.

### What do the labels on a cover mean?

| On the cover | Meaning |
|---|---|
| A green check | Ready. Built. Tap the cover to play. |
| **Build** | Found in your folder, not built yet. Tap to open its sheet. |
| **Building** with a percentage, or **Building** and **preparing** | A build is running. Tap to see its card. |
| **Build stopped** | The build failed or was stopped. Its card says why. |
| **Wrong version** | Your update is not one this app version supports. |
| **File gone** | A file of this game is no longer where the app found it. |
| **Not Supported Yet** | This app version has no profile for the game. |
| A green badge with two bars, bottom right | A companion is installed for this game. |

The filters at the top are **All**, **Building** (only while something builds), **Ready**, **Build needed** and **Not Supported Yet**. **Wrong version** and **File gone** games are under **Build needed**. **Find** searches by name.

### What does "Wrong version" mean, and how do I fix it?

Every supported game has a profile in the app, and the profile lists the game versions it was made for. The app compares the version of your update with that list. With no update, it compares the base game's version. **Wrong version** means your version is not on the list.

To see both sides, tap the cover. Under the **Update** row the sheet shows:

- **You have:** the version of the update the build will use;
- **This build supports:** the versions in the profile, with the best one marked **(recommended)**.

The usual causes and their fixes:

| Cause | How it looks | Fix |
|---|---|---|
| No update file was found | **Update** reads **None found · Choose…**, **You have: v0** | Put the update from your own console in the game folder or the **Update folder**, then **Rescan**. Or tap the **Update** row and pick the file. |
| The update is in a folder the app does not scan | The same | Set Settings › Library › **Update folder**, or tap the **Update** row and pick the file. |
| Your update is newer or older than the supported ones | **You have** shows a version that is not in **This build supports** | Use the update the app lists, dumped from your own console. If you have it, tap the **Update** row (**Choose another…**) and pick it. |
| Keys are not added yet | Every version reads **v0** | Add **Keys**, then **Rescan**. |

What you should see when it is fixed: a green line **Matches this build's shader tables.** and the button reads **Build**. A yellow line **Supported, but builds slower the first time** is also fine: the version is supported, the first build just takes longer.

Two notes:

- The file picker for **Choose another…** needs a file on the device's storage. A file from Recent or a cloud provider is refused.
- The label on the cover looks at the newest update found in your folders. The sheet looks at the update you chose. If you keep a newer, unsupported update in the folder and choose the supported one in the sheet, the build is fine even though the cover still says **Wrong version** until it is built.

### How do I read the version numbers?

The app shows a short number such as **v5**, and for a version the profile knows, the game's own version in brackets: **v5 (1.2.0)**.

- **v0** is the base game with no update.
- The short number counts the game's updates. It is not the game's own version number: v5 is 1.2.0 for Fire Emblem: Three Houses and 1.0.5 for Octopath Traveler 0.
- Dump tools and file names often show the long form, for example `v327680`. Divide it by 65536 to get the short form: 327680 is v5, 1507328 is v23.

The project's README lists each tested game with the game version it needs.

### What does Build anyway do, and what can go wrong?

For a version that is not on the list, the button reads **Build anyway**. It asks first (**Build anyway?**) and then builds without the version check.

- The game may not start, may crash, or may draw parts wrongly.
- The first build is slower, because it scans every shader.
- Your dump and your saves are not changed.

It is a try, not a supported path. If it fails, use the supported update and build again. Do not report bugs for a game built this way without saying so.

### What does "Not Supported Yet" mean, and what can I do?

Every game needs a profile built into the app. This app version has none for that game, so it cannot be built or played. There is no Build button for it: its sheet only says **This app version does not include … Install a build that includes it.**

- To see which games this version includes: Settings › **About** › **Games**. Each card has the game's name and the version this app builds; **New in** shows the games that came with this version.
- Check for a newer app version: Settings › App › **Check for updates**, or the project's releases page.
- If no version includes it, you can ask in the GitHub issues. Give the game's name and its title ID (the sheet shows it).

How the three labels differ:

| Label | The problem | Can you fix it? |
|---|---|---|
| **Not Supported Yet** | The app has no profile for the game. | No. Only a newer app version can. |
| **Wrong version** | The app supports the game, but not the update you have. | Yes: use a supported update, or try **Build anyway**. |
| **File gone** | The app supports the game, but a file it found is missing now. | Yes: put the file back, or pick it again. |

### What does "File gone" mean?

The base game or its newest update was in your folder at the last scan and is not there now. Typical causes: the file was moved, renamed or deleted, or the microSD card was removed.

1. Put the file back, or insert the card.
2. Tap Settings › Library › **Rescan**. If the folder itself moved, pick the **Game folder** again.
3. For an update, you can also tap the **Update** row in the sheet and pick the file again.

Until then the sheet says **Pick the file again above before building** and **Build** is off.

For a game that is already built, tapping the cover shows the dialog **Game file gone** with **Pick it again**, **Remove** and **Cancel**. **Remove** takes the game off the list only. If a built game's file stays missing, the app drops it from the list by itself after about a minute. Your saves and your other files are kept either way, and the game comes back when the file is found again.

### Which games are supported?

The project's README lists all 76 games included in 0.1.8, the updates each supports and which have an Eden Duo catalog entry.

The games tested for 0.1.1 were Link's Awakening, Echoes of Wisdom, Fire Emblem: Three Houses, Fire Emblem Engage, Metroid Dread, Super Mario Odyssey, Mario Kart 8 Deluxe, Animal Crossing: New Horizons, Persona 5 Royal, Castlevania: Belmont's Curse, Octopath Traveler 0, Pokémon Brilliant Diamond, Pokémon Sword and Breath of the Wild (new, little tested). Super Mario Bros. Wonder and Dragon Quest III HD-2D Remake are in the app too, but were not tested on a device yet. 0.1.2 adds Pokémon Shining Pearl, Pokémon Shield, Final Fantasy Resonance, Skyward Sword HD, Pokémon Legends: Arceus and Resident Evil 4, all new and little tested, and Kirby's Return to Dream Land Deluxe, which was run by its contributor on their own AYN Thor only. 0.1.4 adds Crash Team Racing Nitro-Fueled, Kirby and the Forgotten Land, Super Mario 3D World + Bowser's Fury, Chained Echoes, My Time at Sandrock, Monster Sanctuary and Yoru, Tomosu, all new and little tested, and Xenoblade Chronicles X: Definitive Edition as an experiment. Super Mario Bros. Wonder was checked on a device for 0.1.4, on its 1.2.1 update, the only one the app builds now.

0.1.5 adds Astral Chain, Captain Tsubasa 2: World Fighters, Dave the Diver, Demon Slayer: The Hinokami Chronicles 2, Diablo III: Eternal Collection, EA Sports FC 27, The Legend of Zelda: Tears of the Kingdom and Tomodachi Life: Living the Dream. Breath of the Wild supports updates 1.6.0 and 1.9.0.

0.1.6 adds Unicorn Overlord, Luigi's Mansion 3, Pokémon Legends: Z-A, STORY OF SEASONS: Grand Bazaar, Double Dragon Revive, Trails in the Sky 2nd Chapter, DRAGON BALL: Sparking! ZERO, Pokémon Scarlet, Pokémon Violet, DRAGON BALL Z: KAKAROT + A NEW POWER AWAKENS SET, STEINS;GATE RE:BOOT, NieR:Automata The End of YoRHa Edition and Hyrule Warriors: Age of Calamity. EA Sports FC 27 also supports update 1.8d.f570, Double Dragon Revive supports 1.0.0 and N1.10, and STEINS;GATE RE:BOOT supports 1.0.3 and 1.0.4.

0.1.7 adds Trails in the Sky 1st Chapter, Yoshi's Crafted World, Tetris Effect: Connected, New Pokémon Snap, Digimon Story Time Stranger, Hogwarts Legacy and Star Ocean The Second Story R. Build New Pokémon Snap on the phone: the PC builder cannot build it yet.

0.1.8 adds Stray, 13 Sentinels: Aegis Rim, Captain Toad: Treasure Tracker, Advance Wars 1+2: Re-Boot Camp, FANTASY LIFE i: The Girl Who Steals Time, Pokémon: Let's Go, Pikachu!, Pokémon: Let's Go, Eevee!, Viewfinder, SPY×ANYA: Operation Memories, Super Mario RPG, WarioWare: Get It Together!, LEGO 2K Drive, LEGO CITY UNDERCOVER, LEGO MARVEL Super Heroes, Minecraft Dungeons II, The Witcher 3: Wild Hunt Complete Edition and Pokémon Mystery Dungeon: Rescue Team DX. Play the two Pokémon: Let's Go games with a Turnip driver: Qualcomm's own driver draws the 3D scene of Let's Go, Pikachu! black, and Let's Go, Eevee! was not tried on it. Build Pokémon Mystery Dungeon: Rescue Team DX from its base game 1.0.0.

The 0.1.5 verification build was checked on an AYN Thor with Turnip v26.2.0 R5. Demon Slayer 2 reached the playable rooftop fight against Daki at about 30 fps. Tears of the Kingdom reached the opening cave and responded to movement. Kirby and the Forgotten Land prepared all 418 pipeline keys on its first launch, then reused the cache on relaunch. The other six additions have earlier, game-specific device checks.

Settings › **About** › **Games** lists the games of the app you have installed, each with the version it builds.

### Why does building take so long?

The app rewrites all of the game's code and translates every shader on the device. Most games take 5 to 40 minutes on the Thor. The size of the game, the number of shaders, the device's memory and its temperature all change the time. Echoes of Wisdom (9.6 GB, 9,858 shaders) took about 50 minutes on the Thor.

### Can I use the device while it builds?

Yes. The build goes on if you leave the app. A second game you build waits in the build queue until the first one is done (see the next question). Some builds start the game once to capture shaders, so close any running game first.

While a build runs or waits, the home screen has a **Screens off** button left of the Settings gear. It makes every screen black at the lowest brightness and keeps the device awake, so a long build does not wear an OLED panel. A tap on a screen brings the app back, and the cover goes away by itself when the build ends. It is new in 0.1.2 and little tested.

### Can I build several games one after the other?

Yes. While a game builds, the sheet's button says **Add to queue**: the game waits in the build queue and starts when the games before it are done, one at a time. You can queue your games, put the device on its charger and go to sleep. **Build all…** on the home screen (beside Find) asks first, then queues every game that needs a build: those that say **Build needed**, those whose build stopped, and built games whose build is of an earlier version of the app. Games whose file is gone or whose version this app does not list are left out: their sheet asks first.

- A waiting game's cover says **Queued** and its place, such as **Queued, 3 of 5**. The **Building** filter lists them under **Up next**, each with **Remove**; the game's sheet (hold the cover, or Y) has **Remove from queue** too. Removing changes none of its files.
- Before each game the queue waits while the device is hot, while the battery is below 20 % and not charging, while you play a game, while a build with mods runs, and while a pack of the next game is being imported (Import pack…). It goes on by itself. The notification says what it waits for.
- A game whose build fails or that you stop is marked **Build stopped**, and the next game starts. Its card says why and offers **Resume** or **Retry**, and **Dismiss** removes the card.
- Before a game starts, the app checks that its `.nup` fits the free space, and that the pack has at least 0.5 GB to be made in. If not, it stops that game before writing anything and goes on with the next.
- **Stop** on the card, or **Cancel** in the notification, stops the game that builds; the queue goes on. **Cancel all** in the notification, or **Stop all** on the Up next card, stops everything. **Pause** on the Prepare card pauses the whole queue; **Resume** on the Up next card goes on where it stopped.
- If Android ends the app, or you restart the device, the queue goes on when Android starts the app's build service again or when you next open the app. The game that was building starts again where it stopped.

### What notifications does a build show?

- While it builds: **Building** and the game's name, with the current step, a progress bar and a **Cancel** action. With more games in the build queue it says the game's place (**2 of 5**) and has **Cancel all**. It is silent.
- While the queue waits between two games: **Waiting to build** and the next game's name, with the reason. A wait for heat has **Go on anyway**.
- When it ends while the app is not in front: **… is ready to play**, or **Building … stopped** with the reason. A queue of several games sends one notification at its end instead, saying which games are ready and which stopped. This one vibrates.

The app asks for the notification permission at your first build. Without it the build still runs, but you see no progress outside the app. Android lists the two kinds as **Building games** and **Build finished**, so you can silence either one.

### What happens if the build is interrupted or the device sleeps?

What is written so far stays. The game's card shows **Build stopped** with **Resume** or **Retry**, and the next build picks up what it can. If it keeps stopping, send its build log with **Share log** › **Newest build** (see "A build failed, stopped or the app closed during it"). If the screen going off stops the build on your device, turn on Settings › **Keep the screen on while preparing** and keep the device charging. A build can also pause by itself for heat or battery and then continue.

### It sits at the same percentage for a long time. Is it stuck?

Probably not. Large games can stay on one step for many minutes. Open the **Building** filter: the card shows the step and what it is working on. If the packer reports nothing for 10 minutes, the app stops the build itself and offers **Resume**.

### Can I build on a microSD card?

Yes. Add folders on internal storage and the microSD card in Settings › Library › **Build folders**. Tap a folder there to make it the default or forget it. Peanuts searches every configured folder for existing builds.

In the game's Build or Rebuild pop-up, tap **Build destination** above the space estimate. Choose **Default** or pick a folder for that game. You can also add a folder there. A newly imported `.nup` and its `pack/` go into the title-ID folder at the selected destination. If you start from an existing `.nup`, only the pack goes to the selected destination.

### What happens when I remove the microSD card?

- Built games on the card disappear from the grid. Nothing is deleted. A button **N hidden · Show** appears beside the filters and shows them again.
- Games on the card that are not built yet are simply not found.
- Put the card back and tap Settings › Library › **Rescan**. The games return.

Do not remove the card during a build or while a game from it runs.

### What is the difference between Build, Rebuild, Import again, Build again and Prepare again?

| Name | Where | What it does |
|---|---|---|
| **Build** | The sheet of a game that is not built | The first build: imports the game into one `.nup`, prepares it and installs the companion. |
| **Rebuild** | The same sheet, opened for a game that is already built | Makes again the parts of the built game that no longer fit this app version (its code, its shaders), and updates the companion. It keeps the `.nup`. |
| **Import again…** | The same sheet, under Build or Rebuild, for a game that has a `.nup` | Makes the `.nup` again from your dumps, with the update and DLC found now, then builds the game. See [below](#what-is-import-again-and-when-do-i-need-it). |
| **Build again** | Hold the cover › **Storage** tab | Opens that sheet for a built game. You then tap **Rebuild**. |
| **Prepare again** | Hold the cover › **Storage** tab, only for a game added as a dump folder | Remakes only the parts of the pack that do not fit, from the dump. It does not import or touch the companion. |
| **Build with mods** | Hold the cover › **Game** tab › **Mods** | Makes a separate build that carries code mods. See [Mods](#mods). |
| **Import pack…** | The sheet of a game that is not built, under Build; for a built game, hold the cover › **Storage** tab | Puts in a pack made on a computer or exported from another phone. See [below](#can-i-use-a-pack-made-on-a-computer-or-on-another-phone). |

A tap on a ready cover plays the game (or asks first, when a newer update or DLC was found beside it: see [How do updates and DLC work?](#how-do-updates-and-dlc-work)), so the way to its sheet is hold the cover › **Storage** › **Build again**.

### What is Build again, and when do I need it?

It opens the game's sheet, where **Rebuild** makes again what no longer fits this app version. Hold the game's cover, open the **Storage** tab, tap **Build again**, then **Rebuild** in the sheet. You need it:

- once after an app update, for a game whose companion uses map or helper features;
- when a message or a setting row asks for it.

It is quick when the game's code is otherwise current, and long when it is not. Rebuild does not read your dumps again: it uses the `.nup` that is there. For a newer game update or added DLC, use **Import again**.

### What is Import again, and when do I need it?

It makes the game's `.nup` again from your dumps (the base, the update, the DLC), then builds the game. Nothing else does: Build and Rebuild use the `.nup` that is already there. It is new in 0.1.2 and little tested, so a report helps if it does something else than described here. You need it:

- when DLC was not in the folders at the first build, and you have it now;
- after you add a newer game update;
- when a build says its shaders "come from files your import does not have".

Open the game's sheet (for a built game: hold the cover › **Storage** › **Build again**) and tap **Import again…**, or tap **Build** in the question a built game's cover asks when it found a newer update or DLC. It asks first, and says (from the cover's question only when it would take another update than the one the question named):

- what is deleted: the old `.nup`, with its size and where it is. It is replaced only when the new one is complete. If the import fails or you stop it, the game stays as it was;
- what is kept: your saves, mods and settings, and the built game until the new build replaces it;
- what it found: the base game, the update and how many DLC.

The dumps must still be in their folders. If one is missing, the app names it and the folders it read, and changes nothing. Every part the old `.nup` has must be found again: its base, an update at least as new, and each of its DLC.

The new file is written beside the old one, so for a while both are on the storage. If there is not enough room, the app says how much is needed. When deleting the old file first would make room, it offers that as a second choice. The risk: if the import then fails or is stopped, the game has no `.nup` and cannot be played until a build from its sheet has made one.

### Can I build from NSZ or XCZ files?

Yes. Files compressed with nsz (`.nsz`, `.xcz`) build like `.nsp` and `.xci`, also mixed with them: a compressed base with an uncompressed update, for example. You do not need to keep an uncompressed copy of the game. The game's `.nup` comes out the same as from the uncompressed files.

- Before anything is written, the app rebuilds every compressed part of the game and checks it against the checksums the game's own files record. A damaged or incomplete file stops the build with its name, and nothing is changed: copy or compress the file again, or build from the uncompressed file.
- A **solid** `.nsz` (nsz's default for `.nsz`) cannot be read out of order, so the import first unpacks it into a temporary file in a hidden folder beside the `.nup`. That needs about the game's size in extra free space for the length of the import, and it is deleted when the import ends, also when it fails or you tap Stop. The game's sheet counts it in its estimate.
- A **block** file (nsz's default for `.xcz`, or `nsz -B`) is read where it is and needs no extra space. The check reads it once more, which takes a little longer.
- When a folder holds both the `.nsp` and the `.nsz` of a game, the app uses the `.nsp`.
- The app reads files made with nsz's own settings. A file compressed with settings it cannot read stops with a message that says so: compress it again with nsz's defaults.
- The keys are your own `prod.keys` and the file's ticket, as for an `.nsp`.

### Can I build on a PC or a Mac instead?

Yes. A script prepares the game on a computer and pushes it to the device over USB debugging. See [BUILDER.md](BUILDER.md). It is tested end to end on macOS and on Linux. Windows is written but not yet tested. Games that make shaders while they run, for example Fire Emblem: Three Houses and Octopath Traveler 0, must be built on the device.

### Can I use a pack made on a computer or on another phone?

Yes. A game's pack is what Build makes: its code for this app and its shaders. **Export pack…** on a built game's **Storage** tab writes it as one `.zip`, and the desktop builder makes one too ([BUILDER.md](BUILDER.md)).

- For a game that is not built yet, open its sheet and tap **Import pack…** under Build. Pick the `.zip`, or the pack's folder (the one with `pack.tsv` and `shaders/`).
- When the game is a dump folder or a `.nup`, the pack is checked against that file and the game is ready to play at once.
- When the game is still an `.nsp` or `.xci`, the pack must be made from the version Build uses: the update the sheet shows, or the base game. Then tap **Build**: it makes the `.nup` and uses the pack, so the shaders are not translated again.
- The app checks everything before it replaces anything. A pack of another game, of another version or of another build is refused with what to do next, and nothing is changed.
- A built game has **Import pack…** on its **Storage** tab.

### How do updates and DLC work?

Put them in the game folder, or set Settings › Library › **Update folder** and **DLC folder**. They are matched to their games by title ID. The sheet shows the update it will use, the newest one found unless you chose another (**Choose another…** picks a different file), and the DLC it found (**Add…** picks more).

For a game that is built already, the cover says when it found a newer update the app knows for the game, or DLC its `.nup` does not hold yet: an **Update** mark, and "Update 3.0.4 found" or "An add-on found" in its state. A tap asks first: **Build** takes them in with Import again, **Play** plays the game as it is, **Not now** asks no more for these (the mark then reads **Update later**). Nothing is built without the tap. Such a build keeps what did not change since the last build of the game (its shaders, its code, the game files the shader search read) and makes only the rest, so it takes a fraction of a first build; its ready message says what came from earlier builds. Later, while the device is plugged in and no game runs, the app checks every shader it kept against a translation made from the start.

### A build says shaders "come from files your import does not have". What now?

Some of the shaders the app knows for the game are in files your import does not have, and the import has no add-on content (DLC). The app's guess is that those files come with the game's DLC. It is a guess: no game is known to need this yet and the message was not seen on a real one, so if you see it, a report with the message's details helps. The message names one of the files.

This is not the message for a game the app knows in several versions. There the app's shader table holds every version's shaders, and a dump of one version simply does not have the others'. That is normal: the build goes on and only notes it in its log.

The build stops there and lets you choose. Its card has two buttons:

- **Import again with DLC**, when the app found DLC for the game. Without a DLC folder the button is **Set the DLC folder**: set it (or add the DLC file in the game's sheet, **DLC** row), then tap **Import again with DLC**. It makes the `.nup` again with the DLC and builds the game. Whether the missing shaders are then found is not known: no game has needed this yet. See [Import again](#what-is-import-again-and-when-do-i-need-it).
- **Try anyway** builds without those shaders: the base game runs, and what the add-on adds is drawn without those materials. The game's sheet then says "Built without DLC shaders", and **Import again** there is the way to try for them later.

Older versions of the app went on without asking here, so a game built by one of them can lack these shaders with no message. **Import again** is the thing to try there too.

### A built game has no cover, only a letter. How do I get it back?

The app puts a game's cover into the `.nup` it makes, so the cover stays when the original NSP or XCI is gone and the app's data is cleared. A `.nup` made by an older version of the app has no cover in it: the app then keeps the cover as a `.png` beside the `.nup`, as soon as it has one.

A game built by an older version whose original was deleted before that, and whose app data was cleared since, has no cover left anywhere. To get it back, put the original NSP or XCI, or one of the game's updates, into your game folder once and tap Settings › Library › **Rescan**. The cover is then kept beside the `.nup`, and you can delete the original again. **Import again** also makes a new `.nup` with the cover in it. The game plays the same either way.

### How do I free up storage?

- Settings › Library › **Free up space** deletes the original dump of a game that is already built. The game keeps playing from its built copy, and keeps its cover. Keep the original if you will add a later game update.
- Settings › Library › **Storage** removes leftovers and old files. It never removes saves.
- A game imported with an update by 0.1.6 or older: **Import again** makes its `.nup` smaller, see [Why is a `.nup` smaller since 0.1.7?](#why-is-a-nup-smaller-since-017).

### Why is a `.nup` smaller since 0.1.7?

With an update, the `.nup` now holds the update alone. The update already carries the whole game, so the base game inside was a second copy. A game whose data is one big file the update changes is about half the size: Yoshi's Crafted World went from 11.34 GB to 5.67 GB. An older `.nup` keeps working as it is. To get the smaller file, tap **Import again** on the game's sheet: it needs the base game's NSP or XCI and the update again, and builds the game again. Such a `.nup` plays its update only, not the base game: to build a game's base version, import it without an update. Version 0.1.6 opens the new `.nup` too.

## Playing

### How do I start and stop a game, and which controls work?

Tap a ready cover. To stop, open the in-game panel and choose **Stop the game**. The panel opens with a swipe in from the right edge, with Back, or on a controller with Home or by holding − and + for half a second.

Games take the handheld's own buttons and sticks, external controllers and on-screen touch controls. Settings has **On-screen controls** (Auto, On, Off), **Button mapping**, **Xbox button label swap** and **Rumble**. Each game can override them in its own settings.

### What frame rate do games run at?

Most supported games run at 60 fps on the Thor. A few games that are 30 on the Switch run at 60 here because the app changes the game's frame step, for example Octopath Traveler 0 and Pokémon Sword. Some games lower their own rate in heavy scenes. The speed settings are **Frame rate cap**, **Hold 60 fps** and **Full game speed**.

**Frame rate cap** lists only the caps the game can hold. A cap above the highest refresh rate of your screen shows greyed out and says why; a cap you saved before stays saved and runs at the screen's rate. Some picks apply at the game's next start, and the row says so.

Games that used to start at their own rate (**Game owned**) until you picked a cap now start at **60 fps**, or 30 where the game has no 60. This covers, among others, Star Ocean The Second Story R, Hogwarts Legacy, Kirby and the Forgotten Land, Diablo III and Yoshi's Crafted World. **Game owned** is still in the list, and a cap you picked before stays.

Animal Crossing: New Horizons runs at 30 on the Switch. For update 3.0.3 it starts at **60 fps**: the game then runs with tmouh's ACNH 60 FPS + SpeedFix built in, which keeps the game at its normal speed at 60 fps. Nothing to install. Pick **Game owned** for the game's own 30. A build made before 0.1.8 does not have it yet: the game starts at 30 and the in-game panel says to build it again.

### What is Peanuts cracked?

The count on Settings › **About**. A peanut cracks open each time you tap one on the drifting background, and the count also grows as you use the app: 25 for building a game version you had not built before, 1 for every 1,000 shaders that build prepared, 5 for every hour you play (the time a game's **Played** row shows, which counts only while the game runs), 10 for a game's first start and 5 for your first own Mii. Building the same version again (**Import again**, **Rebuild**) adds nothing. The bananas you popped before are part of it. On a dual-screen device the second screen shows the count and the next milestone while About is open.

### How do I find a setting?

Settings has ten sections: Library, Console, Graphics, Controls, Screens, Users, App, Help, Developer and About. On a wide screen such as the AYN Thor they are listed on the left; on a tablet they are tiles across the screen (tap one, Back returns to the tiles); on a phone the button with three lines opens the list. The search button at the top of each section finds a row in any section by its name, a word of what it does or its value, and the matching switches of Advanced. Tap a result to go to it.

### Is there frame generation or upscaling?

- **Frame generation** needs your own `Lossless.dll` from Lossless Scaling. It is never part of the app.
- **Upscaler** offers Nearest neighbor, Bilinear, FSR 1, Native and SGSR 1, with **FSR sharpness**.
- **Resolution** and **Dynamic resolution** appear for games that support them.

Some of these rows show only with Settings › Developer › **Tester settings** on.

### Can I choose docked or handheld mode?

Yes. **Console mode** is in Settings › Graphics and in each game's settings (hold the cover › **Performance** › Picture). **Docked** makes the game draw as it does on a TV, up to 1080p. **Handheld** makes it draw as on the Switch's own screen, up to 720p: that is lighter, so it can hold a higher frame rate, and the app's upscaler fills the screen. Handheld also turns on the game's touch screen. With nothing chosen, each game uses its own mode, which is Docked for most.

The row applies from the next start. To change it while you play, open the in-game panel: **Console mode** is at the top of its first tab, above **Frame rate cap**. It changes at once, as when a Switch leaves its dock, and is kept for that game. Some games take the new picture size only at their next scene or their next start.

A few games are fixed to Handheld because they need the touch screen or the pointer there. Games that have their own **Resolution** row do not show Console mode: their Resolution sets it.

### Can I make a game sharper, or lighter, than its own resolution?

Yes. **Resolution scale** is in Settings › Graphics and in each game's settings (hold the cover › **Performance** › Picture). It draws the game's picture at a multiple of the size the game itself asks for: **1.25x**, **1.5x** or **2x** is sharper and asks more of the GPU, **0.75x** or **0.5x** is lighter and the app's upscaler fills the screen. **1x** is the game's own size. A game in Handheld mode draws at 720p, so 1.5x gives it exactly a 1080p screen. The choice applies from the next start.

It works on the app's side, so it is there for every game, but not every part of a picture follows it. Some effects (a blur, an outline, anti-aliasing) are made for the game's own size and can look a little different, and a game that reads its picture back, or passes it through its own upscaler, keeps that part at 1x. If a game looks wrong with it, set it back to 1x for that game and tell us which game and which scale. Games that have their own **Resolution** and **Upscaling quality** rows do not show this one.

### Can I change how the overlay looks?

Yes. Settings › Graphics › **Overlay**, then tap the row. **Style** is **Table** (the default), **Pill** or **Classic**. The pill is one line that floats at the top centre: the frame rate, a short graph of the newest frames and the temperature, and more with the **Full** preset. **Preset** is **Minimal**, **Normal**, **Full** or **Custom** (each item by hand). **Position** has six places, **Size** goes from 75% to 200%, and **With bottom stats** hides the overlay while the second screen shows the game's stats.

### What do Fit, Stretch and Fill the screen do?

The game's page › **Graphics** › **Display** (the **Performance** tab on a narrow screen). **Fit (keep aspect)**, the default, keeps the game's 16:9 picture with bars on a screen of another shape. **Stretch to screen** fills the screen by stretching the picture. Some games also offer **Fill the screen**: the game draws for your screen's shape, with nothing stretched and no bars, from its next start. Most games that offer it have not been tried that way on a device yet, and the choice then says so: if the game looks wrong or does not start, pick **Fit** to go back.

### Is there a CRT look?

Yes. Settings › Graphics › **CRT filter** is **Off**, **Scanlines**, **CRT** (scanlines, a mask and a vignette) or **Curved CRT** for every game; each game can have its own in its page's **Graphics** area. The in-game panel's **Display** tab changes it while you play.

### Can I play on a TV or monitor?

By default the device decides what a connected TV or monitor shows, as for any other app, and Peanuts Native keeps its own pages off it: a TV is never taken for the second screen. Settings › Screens › **TV and monitors** › **Play on the TV** starts a game full screen on the TV or monitor instead. The screen the game left goes black while it plays there, and a companion stays on the second screen.

### What is Tester settings?

A switch in Settings › Developer. Off, a game that has **Resolution** choices shows just those. On, it also shows the rows made for testing: advanced resolution, **Upscaler** and **Dynamic resolution**. Leave it off unless you are trying things out or were asked to change one of those rows.

### Where are a game's own settings, such as its language?

Hold its cover. A game's settings override the defaults in Settings. **Reset to defaults** puts them back.

On a wide screen, such as the AYN Thor's top screen, a tablet or a phone on its side, the page shows the game at the left (its cover, version, play time and **Play**) and its settings as areas at the right: **Graphics**, **Mods & Cheats**, **Saves**, **Game options**, **Controls**, **Companion**, **Files**, **Help** and **Advanced**. A opens an area, L1 and R1 step through them, B goes back. On a narrow screen the page has four tabs instead: **Performance**, **Game**, **Saves** and **Storage**.

This FAQ names the tabs. On a wide screen: **Performance** is **Graphics**, the **Game** tab's rows are in **Game options**, **Mods & Cheats** and **Companion**, and **Storage** is **Files**. The game's language is **Game options** › **Game language** (**Game** tab › **Game language** on a narrow screen).

### Can I turn off the vibration and the animations in the app?

Yes. **Settings › App** has two rows. **Haptics** is **System** or **Off**. **Motion** is **System** or **Reduced**. With **System**, the app also follows Android's own touch feedback and remove animations settings.

### Where are my saves, and can I import one?

Saves are in the app's own storage, under `saves/<title ID>`. The **Saves** tab shows the path and has **Back up saves**, **Restore saves**, **Import save** (a `.zip` or a save folder) and **Delete save data**, which starts the game from the beginning. Stop the game first. Before a restore, an import or a delete, the app backs up the current save, so **Restore saves** can bring it back.

Each user in **Settings › Users** has a save of their own: the first user's is `saves/<title ID>`, another user's is `saves/<title ID>_user_<their ID>` beside it. With two or more users the **Saves** tab shows a card for each user at its top, starting at the user playing now; tap one, and the rows work on that user's save: a backup, a restore or an import goes to them only, and every question names them. **Copy to another user** gives the picked user's save to another user (theirs is backed up first). A game's device save, which some games keep apart (Mario Kart 8 Deluxe), is every user's and stays as it is. A removed user's saves stay on the phone and show as **Removed user**: back them up, copy them to a user or delete them. In **Settings › Users**, a user's card has **Show saves**: the games they have a save of; tap one to open its **Saves** tab with that user picked.

### Where is the game log?

Every run of a game writes a log. To send it:

- one game's newest log: hold its cover › **Storage** tab › **Share log…**;
- the newest log of any run, including a Graphics self-test: Settings › Help › **Share log**.

After a crash the app's notice has a **Share log** button for that run. When the game also has a build log, each of these asks which one you want: the newest game run or the newest build, each with when it happened and how it ended (for example "Stopped today at 14:32 after 18 min").

### A build failed, stopped or the app closed during it. What do I send?

The build log puts everything a build of one game left into one `.zip`, small enough to attach to an issue. It is behind every **Share log**:

- the game's card on Home while its build is stopped or failed;
- the Prepare card while its job has failed or is paused, and in its **⋯** menu;
- hold the game's cover › **Storage** tab › **Share log…**;
- Settings › Help › **Share log**.

When the game also has a game run's log, Share log asks which one you want: **Newest build** is the `.zip`, with when the build happened and how it ended (for example "Failed while translating shaders today at 14:32 after 22 min").

The sheet says how many files the `.zip` holds and how big it is. Tap **Share**, or **Save to a folder** and attach the file from there. It holds:

- the build's own log: what the import and the build did, from which files, with how much memory and space, and how the build ended;
- each step's log of the game's build, and of a build with mods when there is one;
- the job file and its test settings (`env.txt`; a value whose name looks like a password or a token is hidden);
- the app's own messages of the build and of crashes;
- a summary: the app's version, the device, its memory and free space, the build's state, and how Android ended the app's processes last (to free memory, a crash, or not responding).

It never holds your keys, game files, `.nup` files, saves or packs. Folder paths and game names are in it, as in the game log. Long logs are cut to their first and last part. The app writes a build's own log only since version 0.1.5: for a build made with an older version, the `.zip` has its step logs and the summary.

## GPU drivers

### What is a GPU driver here, and do I need a custom one?

The GPU driver is the software that draws the game's picture through Vulkan. Your device has one built in, shown as **System (the phone's own driver)**. A custom driver is another one that the app loads only for games, without changing the device. The usual custom driver is Turnip, the open-source driver for Adreno GPUs.

- The system driver is fine when it supports Vulkan 1.3 and your games draw correctly on it.
- You need a custom driver when the system driver is older than Vulkan 1.3. The Readiness check then says the game needs Vulkan 1.3. This is reported on the Adreno 650 (Snapdragon 865).
- Try a custom driver when a game is slow, draws wrongly or crashes on the system driver.

### Which driver is recommended?

A Turnip driver. Honest details:

- The developer's test device is the AYN Thor (Snapdragon 8 Gen 2, Adreno 740). It is used and tested with a Turnip driver. The one in the developer's logs is **Turnip Adreno Driver T30 (Mr. Purple)**, from the **Mr. Purple Turnip** entry of the app's Download list.
- A device whose stock driver is older than Vulkan 1.3 must use a custom driver. The one report so far is an Adreno 650 with the same T30 driver: the app's checks passed, but that game still showed a black screen. So older Adreno chips are not known to work yet.
- Other chips are untested. If the system driver passes the Readiness check and your games look right, you can stay on it.

The app does not mark any driver as recommended in its list. Whichever you use, name it when you report a bug.

### Why does the app say a driver draws broken on this phone?

Some driver builds are known to draw wrongly on some GPUs. The app marks such a driver in its GPU driver lists, and when a game starts with it you can tap **Play anyway** or **Change driver**. Nothing is blocked.

- **Turnip v27.0.0-R3** (StevenMXZ) on Adreno 7xx, such as the AYN Thor's Adreno 740: the game's picture is striped and the app's own screens (the stats page, the in-game panel) blink or break. Turnip 27.0.0-R2 or another driver works.

The app knows the build from what the driver reports about itself, not from the name of its folder.

### How do I install a driver from the app's Download list?

You need an internet connection. The app downloads nothing until you tap a file.

1. Open **GPU driver** from Settings or from a game's **Performance** tab.
2. Under **Add a driver**, open a source such as **Balemuni Turnip** for Adreno or **PanVK for Mali** for supported Mali and Immortalis devices.
3. Browse the release names, supported GPUs and file sizes. Tap **Install** on the file you want.

When you see **Installed and picked**, the driver is ready for the next game start. An install from a game's page picks the driver for that game only.

If you see **No network (cannot reach GitHub)** or **GitHub rate limit, try again later**, check the connection or wait and try again.

### How do I install a driver from a zip I already have?

1. Open **GPU driver**, then **From a file** › **Pick a zip file**.
2. Pick the driver zip. It must be a driver package with a `meta.json` inside, the format other Android emulators use.

The page shows **Installed and picked** with the driver's name, or **Not installed** with a reason.

### How do I pick a driver for all games, or for one game?

For all games:

1. Open Settings › Graphics › **GPU driver**.
2. Pick **System** or an installed driver, then go back.

For one game:

1. Hold the game's cover and open the **Performance** tab.
2. Tap **GPU driver**.
3. Pick **Default** to follow Settings, **Recommended** if a device-specific recommendation is available, **System**, or an installed driver.

What you should see: the row shows the name you picked. A game's own choice wins over the one in Settings. The change applies the next time the game starts, not to a running game.

### How do I go back to the system driver, or remove a driver?

Go back: Settings › Graphics › **GPU driver** › **System (the phone's own driver)**. For one game, set its own **GPU driver** row to **Default** or **System**.

Remove:

1. Open **GPU driver** and tap **Remove** beside the driver.
2. Confirm with **Remove driver**. This removes the driver's installed copy from the entire app.

### How do I check which driver a game really used?

- **Settings row.** Settings › Graphics › **GPU driver** shows **System** or the picked driver's name.
- **Readiness check.** Settings › Help › **Readiness check**, section **GPU**. The line **GPU and driver** names the GPU, its Vulkan version and the driver, and ends with **the system's driver** or **custom driver** and its name. **GPU driver picked** shows your choice. A game with its own driver has a line **Its own GPU driver**.
- **Graphics self-test.** Since 0.1.1 it runs on the driver picked in Settings. Its log has the line `graphics selftest: GPU driver: system` or `graphics selftest: GPU driver:` and the driver's name.
- **Game log.** Near the top, look for `GPU driver: system` or `GPU driver:` and the driver's name, followed by `(picked in the launcher; …)`. With a custom driver the log also has `[nxlaunch] env DM_VK_DRIVER=…` and `[vk] custom GPU driver: …`. The line `[vk] driver: …` names the driver that really ran. A line with `the system driver is used` means the custom driver could not be loaded.

### A driver made things worse. What can I try?

1. Go back to **System (the phone's own driver)** and start the game again, to confirm the driver is the cause.
2. Open a source under **Add a driver** to try a different release.
3. Try a driver from another source in the list.
4. If only one game is affected, give that game its own driver (hold the cover › **Performance** › **GPU driver**) and leave the others alone.
5. Run Settings › Help › **Readiness check** and **Graphics self-test** on the new driver.

The first start on a new driver can stutter or show objects late, because each driver builds its own pipeline cache. Judge it on the second start.

If the game closes with **The GPU driver stopped responding, so the game had to close.**, that driver does not work for this game: pick another.

### Can a Mali or other non-Adreno device use a custom driver?

Turnip is for Adreno GPUs only. A Mali or Immortalis GPU can use PanVK, the open Mesa driver for Arm GPUs, from **PanVK faux123** or **PanVK for Mali** under **Add a driver**. On a Xclipse or PowerVR GPU the system driver is the only choice.

On a Mali GPU the phone's own driver has no placed memory maps. The app needs them to show the game's picture: without them the screen stays black while the game runs, and the app says so before the game starts (**Not ready for this game**). So on a Mali phone, **System** is no way out. Not every PanVK build has placed memory maps either:

- **PanVK faux123** v1.1.5 and later: placed memory maps on Mali-G57 and G77, as its release notes say. On a Retroid Pocket 4 Pro (Mali-G77) v1.1.6 passed every check of the app and the game started. In Banana Native's device reports, a phone with a Mali-G720 ran Ocarina of Time on v1.1.6 at 50 to 60 fps.
- **PanVK for Mali**: its newer builds report placed memory maps, but with Arm's kernel driver mapping them fails. The app checks this before the game starts and says so.

When you try a PanVK build, open Settings › **Readiness check**. The line **GPU and driver** must end in **custom driver** and the driver's name. If it ends in **the system's driver**, the PanVK build did not load on this phone, and the start-up notice says so too.

Whether games draw correctly past the start on PanVK is not known yet. Mali devices are untested by the developer, so a report with the Readiness check's **Copy report** and the game's log helps.

### What do the Readiness check and the Graphics self-test tell me?

Settings › Help › **Readiness check** checks Android, memory, file access, the GPU, the picked driver and each game's needs. Its verdict is **Ready**, **Ready, with limits** or **Not ready**, and a failed item has a button that opens the fix, such as **Pick a GPU driver** or **Allow file access**. **Run again** repeats it, and **Copy report** copies the text for a bug report. If you change the driver while it is open, leave the screen and open it again.

Settings › Help › **Graphics self-test** needs no game. It draws a test picture for a few seconds and ends in **PASS** or **FAIL**, with a return code. Since 0.1.1 it runs on the driver you picked. A FAIL on the system driver with a PASS on a custom one means: use the custom one.

When a game starts, the app runs the same check for that game on that game's driver. If something required is missing you see **Not ready for this game** with **Try anyway**.

### What does "% of draws skipped" mean?

The driver could not draw part of the picture. If the message says **Part of the picture is not drawn on this GPU driver**, try another driver. If it says **This GPU has no mesh shading**, another driver may not change it.

### The picture is wrong. What can I try?

- Another GPU driver.
- **Recommended** at the top of the game's **Performance** tab, if it shows.
- Another **Upscaler**. FSR 1 is too bright on Qualcomm's driver in some games.
- **Build again**, if the game's page or a message asks for it.

The flickering title screen and black races in Mario Kart 8 Deluxe are fixed in 0.1.1. If you still see them, report it with the log.

### Objects are black or missing for a few seconds on the first launch after an update. Is that a bug?

No. After an app update that changes how shaders are made, the GPU driver has to compile each game's drawing pipelines again. Until a pipeline is ready, the things that need it are not drawn, so they look black or missing and then appear. This happens once per game. The second launch is back to normal. If it still happens on every launch, report it with the log.

### The device gets hot and the frame rate drops. What now?

That is thermal throttling. Let the device cool down; a cooler helps. A lower **Frame rate cap** or **Resolution** reduces the load.

## Audio

### Is the audio crackle fixed, and what do I send if it is not?

0.1.1 changes the pacing of the audio renderer, which fixes crackle in games that use it. Octopath Traveler 0 also got a fix for its six-channel output. Whether every game is clean is not known yet.

If it still crackles, open an issue with the game and its version, where in the game it happens, your device, and the log of that run (**Share log**).

## Second screen companions

### What is a companion, and what does it need?

A companion is a set of pages for one game, shown on the second screen while the game runs on the main one. The pages are read live from the running game: a map, your party, items or quests. It is a separate download per game, installed from a catalog.

It needs:

- a second display. The app uses the first display Android reports besides the one the game is on. That is the built-in second screen of a dual-screen handheld such as the AYN Thor. An external display should count too, but only the Thor is tested;
- a built game, with the game update the companion supports;
- the companion installed for that game.

On a single-screen device the game runs without it. A game without a companion leaves the second screen free for Android.

### What does the second screen show while I am in the app, not in a game?

The game the controller is on, and what you can do with it:

- a built game: its name, **Ready**, its companion and when you last played it, with **Play** and **Game page**;
- a game to build: what the app found for it, with **Build…** (it opens the game's sheet);
- a game that is building: the build's step and progress, with **Stop build** and **Screens off**;
- after **Play**, for a game with a companion: its cover until the companion is up. This also shows when the game starts from a home screen shortcut or a frontend;
- anything else (the first start, Settings and its pages, the Readiness check, a game's page): the Peanuts Native logo.

With a controller connected, each button shows the controller button that does the same: **A** is the main action, **Y** the second one, **X** opens Settings. Without one, they show the app's own icons (the gear for Settings). You can always tap them. The controller always stays on the main screen. While a question or a game's sheet is open on the main screen, the second screen dims and waits until it closes. The drifting peanuts behind both screens are one background: they carry on from one screen to the other.

In Settings, the second screen explains what the D-pad is on: a section with its rows and their values, or a row with what it does, its value now and the app default. A switch can be flipped there too.

During a game without a companion the second screen shows the game's live stats by default (Settings › Screens › **Games without a companion**, which can also show the game's cover or leave the screen to Android): the frame rate against the game's target and how many frames are on pace, a frame-time graph, the 1% low, the GPU's load and clock, the CPU's clock, the SoC's temperature, the battery's power in watts and the time left, the RAM, and whether the phone is throttling; tap it again for the game's cover. The gear in the stats card's corner picks what it shows (**Simple**, **Normal**, **Full**, or each item by hand) and the screen guards for an OLED panel: a black background, a pixel shifter that moves the page a pixel every 30 seconds, dimmer fixed labels, a dim after 10 minutes without a touch and a black screen after 30. A touch brings it back. The choices are kept for every game.

Tap the console mode (**DOCKED** or **HANDHELD**) or the frame rate on the stats page to change it for the running game, as the in-game panel does. While the stats page shows, the overlay over the game hides, since the same values are below; Settings › Graphics › **Overlay** › **With bottom stats** brings it back.

A four-finger tap on the game hides the companion or the stats page and gives the second screen to Android until the next four-finger tap, which shows it again.

On Home, open Settings with the top-screen gear or press **X**, as shown under the gear. The second screen also has a Settings button. To give the second screen back to Android while you are in the launcher, turn off Settings › Screens › **Bottom screen**. This setting controls the launcher and the optional cover shown while playing without a companion.

### Which games have a companion?

Settings › Screens › **Dual-screen companions** shows the catalog: **Your games** first, then **For games you have not added yet**. Most games on the tested list have a companion: the README's table says which. The catalog is read online, so the list can change without an app update.

### How do I install a companion for the first time?

With the build (the usual way):

1. Tap the game's cover to open its sheet.
2. Look at the **Dual-screen companion** row. It shows the companion's version and size, and **Installs after the build** while its switch is on.
3. Tap **Build**. The companion is downloaded and installed as the last step.

Later, for a game that is already built:

1. Open Settings › Screens › **Dual-screen companions**.
2. Find the game under **Your games** and tap **Install**.
3. Wait for the line at the top to read **Installed …**.

You can also do it from the game's own page: hold the cover › **Game** tab › **Companion** section › **Install**.

What you should see: the row reads **installed** with the version and **up to date**, and the game's cover has a green badge with two bars. Start the game: the companion appears on the second screen. Close the game before you install. A companion installed while the game runs loads at the game's next start.

### How do I see which version is installed, update it, or go back to an earlier one?

- **See the version:** the **Dual-screen companion** row in the game's sheet, in Settings › Screens › **Dual-screen companions**, or in the game's **Game** tab. It reads **installed** and the version, then **up to date** or the newer version that is available.
- **Update:** tap **Update** in Settings › Screens › **Dual-screen companions** or in the game's **Game** tab. **Rebuild** also updates it.
- **Go back to an earlier version:**
  1. Open the game's sheet and tap **Versions…** (in the game's **Game** tab the button is **Versions**).
  2. The sheet lists every release, newest first, with its date and size. The installed one is marked **installed**. Tap a row to read its release notes.
  3. Tap **Roll back** on an older version, or **Switch to** on a newer one.

What you should see: **… is active. It loads at the game's next start.** A version you used before is kept on the device (**on this device**) and switches without a download. **Forget** deletes such a kept version.

Each download is checked against its published checksum. A release without a checksum cannot be installed.

### How do I install a companion without the internet?

Install it from its `.dsmod.zip` file, the same file the catalog downloads (for example `010015100B514000-SuperMarioWonderDS-1.0.1.dsmod.zip` from the [eden-duo-companions](https://github.com/igawa6/eden-duo-companions) releases, copied to the device).

1. Open Settings › Screens › **Dual-screen companions** and tap **Install from file**. In a game's own page it is the **Companion** section › **Install from file**: that one takes only that game's companion.
2. Pick the `.dsmod.zip` file.
3. The app says what happened: **Installed** with the companion's name, version and game, **Updated … from version …**, or why it was not installed.

The file goes through the same installer as a catalog download: it must be a companion for one game, its zip must be whole and its module must match the checksum in its manifest, and it must not need a newer app. A file has no published checksum, so the companion shows as **unverified**. A file for a game that is not in your library yet is installed too: the app says so, and the companion is used once you add and build the game. A file older than the installed version asks first; the installed version stays on the device under **Versions**. Close the game before you install. **Update** and **Rebuild** later install the catalog's version when it is newer.

What the refusals mean:

- **This file is not a dual-screen companion:** it is not a `.dsmod.zip`, or not a whole one. Pick the companion's file.
- **This companion file is damaged:** download it again.
- **This companion is for another game:** you picked it from another game's page. Use that game's page, or Settings.
- **This companion needs a newer version of the app:** update the app first.

### How do I remove a companion, or turn the second screen off for one game?

Remove: tap **Remove** in Settings › Screens › **Dual-screen companions** or in the game's **Game** tab, or **Remove companion** in the Versions sheet. Close the game first. The game and its saves stay, and the catalog can install the companion again.

Turn it off without removing it:

1. Hold the game's cover and open the **Game** tab.
2. In the **Companion** section tap **Second screen**.
3. Pick **Off**. **Physical display** turns it back on.

What you should see: the row reads **Off: the companion does not load**. It applies at the game's next start, and the second screen is left to Android.

On a device without a second display the row starts at **Off: no second display found, the companion does not load**, and a new build does not install the companion unless you tick it. Pick **Physical display** to load it anyway, for a display you connect later.

### What can I do on a companion's pages?

It depends on the companion. In general:

- **Views.** Most pages only show the game's state and change nothing. Fire Emblem: Three Houses shows the chapter, turn and phase, the battle map with every unit, and the selected unit's card. Echoes of Wisdom shows your location and equipped items.
- **Touch.** Pages take taps and gestures, for example to switch pages, and give haptic feedback. Some send button presses to the game.
- **Settings inside a companion.** Some companions have their own options, such as hiding a part of the game's HUD on the main screen because the second screen already shows it. It is per companion and covers only the parts that companion handles. There is no app-wide switch for it.

The companions are published with screenshots and a description of each at https://github.com/igawa6/eden-duo-companions.

### Why is the companion empty or waiting?

- **After an app update:** a companion that uses map or helper features needs **Build again** once. Hold the cover › **Storage** › **Build again** › **Rebuild**.
- **Game update:** your game update must be the one the companion supports. Check **You have** in the game's sheet against the README's list. After switching to or installing a version that does not list your game's build, the app warns that it may fail to load.
- **Installed while the game was running:** it loads at the game's next start. Stop the game and start it again.
- **Second screen is Off** for that game: see the entry above.
- **No second display:** on a single-screen device nothing is shown.
- **App too old for the companion:** the install is refused with a message that asks you to update the app.

### What happens with companions when I am offline?

The catalog is read from the internet each time. Offline:

- Settings › Screens › **Dual-screen companions** says **The catalog could not be loaded** and you cannot install or update.
- A game's sheet may read **No dual-screen companion is available for this game** even though one exists. Go online and tap Settings › Library › **Rescan**.
- A build still finishes. The game is ready to play without the companion, and you can install it later.
- A companion that is already installed keeps working. It is on the device.
- The Versions sheet needs the catalog too, so you cannot switch versions offline.

### What are the current limits?

- A companion works only with the game update it was made for.
- A companion cannot be installed, switched or removed while the game runs.
- Octopath Traveler 0: the stats pages and hiding the map HUD work. Hiding the battle party cards and moving the command panel do not work yet.
- Only the AYN Thor is tested as a second screen.

## Mods

### What kinds of mods work?

Local mods only: `romfs` file replacements, cheats, `exefs` code and IPS patches (in `exefs_patches`, or beside the code in `exefs`). The app downloads nothing and has no mod catalog.

### How do I add or remove a mod?

Mods are in the game's page, not in the build sheet. The game must be built first: for a game that is not built the section reads **Build the game first**.

1. Hold the game's cover. Its page opens.
2. Open **Mods & Cheats** (on a narrow screen: the **Game** tab › **Mods & Cheats**), and pick **Mods** at its top.
3. Tap **Add** and pick the mod's folder or zip file. Atmosphère-layout folders and plain `romfs`/`exefs` folders are recognized.
4. The mod appears in the list, turned on. The line under its name says what it carries: **Files**, **Cheats** or **Code**. Files and cheats apply at the game's next start. A mod with code is built by itself, see [What is a build with mods?](#what-is-a-build-with-mods)

A mod made for another game, or for another version of the game, is refused with a message that names the build or title ID it is for. Nothing is installed then.

To remove a mod, tap the bin at the end of its row and confirm with **Delete**. Its files are deleted from the device. The game and its saves are not changed.

### How do I turn a mod on or off?

Use the switch in the mod's row. A change applies at the game's next start. When the switch changes which code mods are on, their build starts by itself. When two mods that are on touch the same file, the one higher in the list wins. The list is in the order the mods were installed.

### What is a build with mods?

File mods and cheats apply without a build. A mod that carries code reads **Code: needs a build with mods**. You do not start that build yourself: it starts when the mod is installed, and again whenever a switch or a delete leaves a set of code mods that has no build yet. The **Build with mods** row in the same **Mods** section shows the progress, then **Ready**, or **Failed** with the reason (tap the row to read all of it). The build is a separate variant: the normal build is kept as it is.

Only one build runs at a time. If another build is running, or a mod is still being installed or deleted, the row reads **Waiting** and the build starts when that has finished. Keep the app open while it waits. If the app was closed in between, open the game's **Mods** section again and the build starts.

A build that failed or was cancelled does not start again by itself. Tap **Build with mods** to try again, or change which code mods are on.

A mod that hooks the game through an `.ips` patch and a module of its own can work this way: Luminescent Platinum on Pokémon Brilliant Diamond builds and runs. A mod that patches the game's code while the game runs (exlaunch-style hooks) does not work yet. The section then shows **A module's hooks will not run** before you build. If a set of mods cannot be built at all, it shows **Cannot build with these mods** and the reason.

Once it reads **Ready**, every start of the game uses the mods that are on: the page's **Play**, a tap on the cover, a home screen shortcut. Turn a mod off or delete it and the game starts without it. While code mods are on but their build is not ready yet (it is running, waiting or failed), the game starts without them and says so. Nothing of a code mod is used then, not its files or cheats either: they belong to its code. Mods that are only files or cheats apply either way. If you change which code mods are on, the new set is built by itself, and a set that was built before is used again as it is. After you rebuild the game itself, replace a mod's files or update the app, tap **Rebuild with mods**: it redoes the parts that changed and keeps the rest.

### How do I use cheats?

Open the game's page › **Mods & Cheats**. Cheats that come with the app are listed there. **Add** takes a cheat file (`.txt`), a `.zip` or a folder, in the Atmosphère, Eden, Yuzu or Ryujinx layout. Each cheat has a switch; a switch works at once while the game runs and is kept for its next start. A file made for another game is refused, and one for another version of the game asks first.

While you play, the in-game panel has a **Mods** tab with the same switches, each with its state (**Running**, the buttons it waits for, **Not possible here** or **Next start**). A cheat that sets a number has a field to change it; the cheat file itself is never changed. Settings › Controls › **Mods tab** turns the tab off.

## Troubleshooting

### The game crashes at start or shows a black screen. What should I check?

1. Settings › Help › **Readiness check**.
2. The firmware (Settings › Console › **Firmware**).
3. Another GPU driver.
4. The game update: **You have** in the game's sheet must be a version under **This build supports**.
5. **Build again**, if the app was updated since the build.

After a crash the app shows the reason it knows, with a **Share log** button.

For a black screen, try another GPU driver first. On a device whose system driver is older than Vulkan 1.3, install a custom driver. On a Mali GPU, see **Can a Mali or other non-Adreno device use a custom driver?** Then run the **Graphics self-test**. If it passes and the game is still black, report it with the log.

### The build ran out of storage or memory. What now?

Out of storage: the card offers **Free up space** and **Retry**. Free space on the volume the build writes to, then retry. The build picks up what it can.

Out of memory, typically on an 8 GB device: update to 0.1.1 or later, which builds with fewer workers on low-memory devices. A build that Android ended for memory resumes with fewer workers, so tap **Resume**. Close other apps before a large build. If it still fails, build the game on a PC or a Mac, or report it with its build log (**Share log** › **Newest build**): its summary says whether Android ended the build for memory.

### How do I report a bug well?

Open an issue at https://github.com/igawa6/peanuts-native/issues and pick the form that fits:

- **Game problem**: a game crashes, freezes, shows a black screen, draws wrong, runs slowly, or its sound, controls or saves misbehave. It asks for the log of that run: hold the game's cover › **Storage** › **Share log** right after the problem, before you start the game again, or Settings › Help › **Share log**. Its file name starts with the game's title ID.
- **Build problem**: adding a game went wrong (the import, Prepare or the companion fails, stops, never finishes, or the app closes during it). It asks for the `.zip` of **Share log** › **Newest build**, attached as it is.
- **Setup or app problem**: keys, firmware, the game folder and the library (a missing game, **Wrong version**, **File gone**), storage, installing a driver, a companion or a mod, saves, the second screen, or the app itself.
- **Desktop builder problem**: building a game on a computer ([BUILDER.md](BUILDER.md)) fails.
- **Game request** and **Feature request**: one game or one idea per post; search first and add a 👍 to an existing one.
- A companion's page shows something wrong: that goes to the [companions' own issues](https://github.com/igawa6/eden-duo-companions/issues).

The problem forms ask the rest mostly as picks from a list: the game with its version, the app version, the device, chip, RAM and Android version, the GPU driver, the Readiness check's verdict (its **Copy report** text goes in the last box), the settings you changed and whether it worked before. Before you report, update the app and build the game again (hold the cover › **Storage** › **Build again**): many reports are fixed by that.

A screenshot or a short video helps for a wrong picture. Never attach or link game files, keys or firmware.

## Updating the app

### Do I need to rebuild my games after an update?

A game built with 0.1.7 does not need a new build for its shaders: the first time you play it on 0.1.8 they are updated in a few seconds (**Updating shaders**), instead of being made again. If that cannot be done for a game, it plays with its old shaders and offers the shader update as before. Animal Crossing: New Horizons needs **Build again** for its new 60 fps choice.

In general, rebuilding every game after an app update is recommended. An update can change how a game's code and shaders are prepared, and a game built by an older version keeps the old result until it is built again. Hold the game's cover, open **Storage**, tap **Build again**, then **Rebuild**. Your saves are not touched. It is quick when the game's code is otherwise current. What the update changed is made again, which can take as long as a first build (all the shaders, when the update changed how they are translated); what it did not change is kept from the last build.

The game's sheet, opened with **Build again**, says which app version made its graphics. "Built with 0.1.7" or "Built with this app version" is fine: shaders from 0.1.7 update themselves in a few seconds the next time the game starts. A warning **Built with 0.1.3**, or any version before 0.1.7, means the game still plays but misses the graphics fixes since then, and only **Rebuild** brings them.

### Will I lose my saves?

No. An update keeps the app's storage, and saves are in it. Uninstalling can delete them: Android asks whether to keep the app's data. Use **Back up saves** first.

### How do I tell which version I have?

The build name is under the app's title on the home screen and in Settings › **About**. A release build reads `peanuts-native-0.1.8`. Every log starts with it too.

## Credits

Peanuts Native is a fork of Banana Native by moceure, which is based on nxnative by seedds. Companions run on the Eden Duo runtime.
