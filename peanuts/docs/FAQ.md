# Peanuts Native FAQ

For version 0.1.4. Labels in **bold** are the words the app shows. "Settings" is the gear button on the home screen. "Hold the cover" means a long press on a game's cover (or Y on a controller).

## Quick answers

- **Build is off, or a game stops at startup:** add your keys and firmware in Settings › Library (**Keys**, **Firmware**), then build or play again. See [Keys and firmware](#keys-and-firmware).
- **Black screen, wrong picture or a crash at start:** pick another GPU driver in Settings › **GPU driver**, then run Settings › **Readiness check**. See [GPU drivers](#gpu-drivers).
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
7. Optional: install a GPU driver in Settings › **GPU driver**. See [Which driver is recommended?](#which-driver-is-recommended)
8. Tap a cover. Its sheet opens with the rows **Base**, **Update**, **DLC** and **Companion**. Check them and tap **Build**. Android asks to allow notifications: allow it, so you can see the progress outside the app.
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

### What do I need from my own console?

- Your own game dumps as NSP, XCI, `.nup` files or dump folders, with their updates and DLC if you use them.
- Your own `prod.keys`.
- Your own firmware dump, as a folder or a `.zip`. The app extracts the fonts from it.

You dump your own games, keys and firmware from your own console. The app ships with none of them and does not link to any.

### Where do my files go?

- Your dumps stay where you put them, in the game folder you picked.
- A build goes beside the game by default. Settings › Library › **Build folder** moves all builds to one folder, with one folder per game.
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

1. Open Settings › Library › **Keys**. The same row is on the first-run card, and a game's sheet has **Add keys…** while keys are missing.
2. The file picker opens with the hint **Pick your prod.keys**. Pick the `prod.keys` file from your own console. A file larger than 1 MB is refused.
3. The app copies the file into its own storage and checks it.

What you should see: the message **Keys accepted**. The row then reads **prod.keys is in the app (N bytes)**.

### The app says "The importer does not accept these keys". What is wrong?

The file is not a usable `prod.keys`. The message names what is missing, for example **has no header_key** or **has no key_area_key_application_00**. Pick the right file to replace it. Note that the rejected file is kept, so the row still reads **prod.keys is in the app**. A build will fail until you replace it.

### A build stops with "prod.keys has no titlekek_…". What now?

Your keys do not have the key this game or update needs. This usually means the keys were dumped on an older firmware than the game needs. Dump the keys again from your own console after updating it, then pick the new file in Settings › Library › **Keys**.

### How do I add the firmware?

1. Add your keys first. Without keys the firmware cannot be read.
2. Open Settings › Library › **Firmware**. The same row is on the first-run card, and a game's sheet has **Choose firmware…** while fonts are missing.
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

Then tap a game's cover to open its sheet. Check the **Base**, **Update**, **DLC** and **Companion** rows and tap **Build**. The build imports the game, prepares it and installs the companion. Only one build runs at a time.

Later, change the folders in Settings › Library. **Rescan** reads them again.

### Where does the app look for my games?

In the **Game folder**, the **Update folder**, the **DLC folder** and the **Build folder**, each up to two folders deep. It finds `.nsp`, `.xci` and `.nup` files and dump folders. Updates and DLC are matched to their game by title ID, wherever they are in those folders. A file deeper than two folders is not found: move it up, or point the folder setting at it.

If the home screen says **No games found in …**, put your files in that folder and tap Settings › Library › **Rescan**.

### What do the labels on a cover mean?

| On the cover | Meaning |
|---|---|
| A green check | Ready. Built. Tap the cover to play. |
| **Build** | Found in your folder, not built yet. Tap to open its sheet. |
| **Building · N %** or **Building · preparing** | A build is running. Tap to see its card. |
| **Build stopped** | The build failed or was stopped. Its card says why. |
| **Wrong version** | Your update is not one this app version supports. |
| **File gone** | A file of this game is no longer where the app found it. |
| **Not in this app version** | This app version has no profile for the game. |
| A green badge with two bars, bottom right | A companion is installed for this game. |

The filters at the top are **All**, **Building** (only while something builds), **Ready**, **Build needed** and **Not in this app version**. **Wrong version** and **File gone** games are under **Build needed**. **Find** searches by name.

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

### What does "Not in this app version" mean, and what can I do?

Every game needs a profile built into the app. This app version has none for that game, so it cannot be built or played. There is no Build button for it: its sheet only says **This app version does not include … Install a build that includes it.**

- To see which games this version includes: Settings › scroll to **About** › **Games in this app:**. The list has each game's name and title ID.
- Check for a newer app version: Settings › **Check for updates**, or the project's releases page.
- If no version includes it, you can ask in the GitHub issues. Give the game's name and its title ID (the sheet shows it).

How the three labels differ:

| Label | The problem | Can you fix it? |
|---|---|---|
| **Not in this app version** | The app has no profile for the game. | No. Only a newer app version can. |
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

The project's README lists the tested games with the update each one needs. In 0.1.1 they are Link's Awakening, Echoes of Wisdom, Fire Emblem: Three Houses, Fire Emblem Engage, Metroid Dread, Super Mario Odyssey, Mario Kart 8 Deluxe, Animal Crossing: New Horizons, Persona 5 Royal, Castlevania: Belmont's Curse, Octopath Traveler 0, Pokémon Brilliant Diamond, Pokémon Sword and Breath of the Wild (new, little tested). Super Mario Bros. Wonder and Dragon Quest III HD-2D Remake are in the app too, but were not tested on a device yet. 0.1.2 adds Pokémon Shining Pearl, Pokémon Shield, Final Fantasy Resonance, Skyward Sword HD, Pokémon Legends: Arceus and Resident Evil 4, all new and little tested, and Kirby's Return to Dream Land Deluxe, which was run by its contributor on their own AYN Thor only. 0.1.4 adds Crash Team Racing Nitro-Fueled, Kirby and the Forgotten Land, Super Mario 3D World + Bowser's Fury, Chained Echoes, My Time at Sandrock, Monster Sanctuary and Yoru, Tomosu, all new and little tested, and Xenoblade Chronicles X: Definitive Edition as an experiment. Super Mario Bros. Wonder was checked on a device for 0.1.4, on its 1.2.1 update, the only one the app builds now. The README's table says what was seen of each.

Settings › **About** lists the games of the app you have installed, each with its title ID.

### Why does building take so long?

The app rewrites all of the game's code and translates every shader on the device. Most games take 5 to 40 minutes on the Thor. The size of the game, the number of shaders, the device's memory and its temperature all change the time. Echoes of Wisdom (9.6 GB, 9,858 shaders) took about 50 minutes on the Thor.

### Can I use the device while it builds?

Yes. The build goes on if you leave the app. You cannot build a second game at the same time. Some builds start the game once to capture shaders, so close any running game first.

While a build runs or waits, the home screen has a **Screens off** button left of the Settings gear. It makes every screen black at the lowest brightness and keeps the device awake, so a long build does not wear an OLED panel. A tap on a screen brings the app back, and the cover goes away by itself when the build ends. It is new in 0.1.2 and little tested.

### What notifications does a build show?

- While it builds: **Building** and the game's name, with the current step, a progress bar and a **Cancel** action. It is silent.
- When it ends while the app is not in front: **… is ready to play**, or **Building … stopped** with the reason. This one vibrates.

The app asks for the notification permission at your first build. Without it the build still runs, but you see no progress outside the app. Android lists the two kinds as **Building games** and **Build finished**, so you can silence either one.

### What happens if the build is interrupted or the device sleeps?

What is written so far stays. The game's card shows **Build stopped** with **Resume** or **Retry**, and the next build picks up what it can. If the screen going off stops the build on your device, turn on Settings › **Keep the screen on while preparing** and keep the device charging. A build can also pause by itself for heat or battery and then continue.

### It sits at the same percentage for a long time. Is it stuck?

Probably not. Large games can stay on one step for many minutes. Open the **Building** filter: the card shows the step and what it is working on. If the packer reports nothing for 10 minutes, the app stops the build itself and offers **Resume**.

### Can I build on a microSD card?

Yes. By default a build is written beside the game, so a game on the card is built on the card. To choose, pick a folder in Settings › Library › **Build folder**, on the device or on the microSD card. Every new build then goes there, one folder per game, named by title ID. Hold that row to forget the folder. Builds made before you set the folder stay where they are and keep working.

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

A tap on a ready cover plays the game, so the way to its sheet is hold the cover › **Storage** › **Build again**.

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

Open the game's sheet (for a built game: hold the cover › **Storage** › **Build again**) and tap **Import again…**. It asks first, and says:

- what is deleted: the old `.nup`, with its size and where it is. It is replaced only when the new one is complete. If the import fails or you stop it, the game stays as it was;
- what is kept: your saves, mods and settings, and the built game until the new build replaces it;
- what it found: the base game, the update and how many DLC.

The dumps must still be in their folders. If one is missing, the app names it and the folders it read, and changes nothing. Every part the old `.nup` has must be found again: its base, an update at least as new, and each of its DLC.

The new file is written beside the old one, so for a while both are on the storage. If there is not enough room, the app says how much is needed. When deleting the old file first would make room, it offers that as a second choice. The risk: if the import then fails or is stopped, the game has no `.nup` and cannot be played until a build from its sheet has made one.

### Can I build on a PC or a Mac instead?

Yes. A script prepares the game on a computer and pushes it to the device over USB debugging. See [BUILDER.md](BUILDER.md). It is tested end to end on macOS and on Linux. Windows is written but not yet tested. Games that make shaders while they run, for example Fire Emblem: Three Houses and Octopath Traveler 0, must be built on the device.

### How do updates and DLC work?

Put them in the game folder, or set Settings › Library › **Update folder** and **DLC folder**. They are matched to their games by title ID. The sheet shows the update it will use, the newest one found unless you chose another (**Choose another…** picks a different file), and the DLC it found (**Add…** picks more).

### A build says shaders "come from files your import does not have". What now?

Some of the shaders the app knows for the game are in files your import does not have, and the import has no add-on content (DLC). The app's guess is that those files come with the game's DLC. It is a guess: no game is known to need this yet and the message was not seen on a real one, so if you see it, a report with the message's details helps. The message names one of the files.

This is not the message for a game the app knows in several versions. There the app's shader table holds every version's shaders, and a dump of one version simply does not have the others'. That is normal: the build goes on and only notes it in its log.

The build stops there and lets you choose. Its card has two buttons:

- **Import again with DLC**, when the app found DLC for the game. Without a DLC folder the button is **Set the DLC folder**: set it (or add the DLC file in the game's sheet, **DLC** row), then tap **Import again with DLC**. It makes the `.nup` again with the DLC and builds the game. Whether the missing shaders are then found is not known: no game has needed this yet. See [Import again](#what-is-import-again-and-when-do-i-need-it).
- **Try anyway** builds without those shaders: the base game runs, and what the add-on adds is drawn without those materials. The game's sheet then says "Built without DLC shaders", and **Import again** there is the way to try for them later.

Older versions of the app went on without asking here, so a game built by one of them can lack these shaders with no message. **Import again** is the thing to try there too.

### How do I free up storage?

- Settings › Library › **Free up space** deletes the original dump of a game that is already built. The game keeps playing from its built copy. Keep the original if you will add a later game update.
- Settings › **Storage** removes leftovers and old files. It never removes saves.

## Playing

### How do I start and stop a game, and which controls work?

Tap a ready cover. To stop, open the in-game panel and choose **Stop the game**. The panel opens with a swipe in from the right edge, with Back, or on a controller with Home or by holding − and + for half a second.

Games take the handheld's own buttons and sticks, external controllers and on-screen touch controls. Settings has **On-screen controls** (Auto, On, Off), **Button mapping**, **Xbox button label swap** and **Rumble**. Each game can override them in its own settings.

### What frame rate do games run at?

Most supported games run at 60 fps on the Thor. Animal Crossing: New Horizons runs at 30, as on the Switch. A few games that are 30 on the Switch run at 60 here because the app changes the game's frame step, for example Octopath Traveler 0 and Pokémon Sword. Some games lower their own rate in heavy scenes. The speed settings are **Frame rate cap**, **Hold 60 fps** and **Full game speed**.

### Is there frame generation or upscaling?

- **Frame generation** needs your own `Lossless.dll` from Lossless Scaling. It is never part of the app.
- **Upscaler** offers Nearest neighbor, Bilinear, FSR 1, Native and SGSR 1, with **FSR sharpness**.
- **Resolution** and **Dynamic resolution** appear for games that support them.

Some of these rows show only with Settings › **Tester settings** on.

### Can I choose docked or handheld mode?

Yes. **Console mode** is in Settings › Performance and picture and in each game's settings (hold the cover › **Performance** › Picture). **Docked** makes the game draw as it does on a TV, up to 1080p. **Handheld** makes it draw as on the Switch's own screen, up to 720p: that is lighter, so it can hold a higher frame rate, and the app's upscaler fills the screen. Handheld also turns on the game's touch screen. With nothing chosen, each game uses its own mode, which is Docked for most.

The row applies from the next start. To change it while you play, open the in-game panel: **Console mode** is at the top of its first tab, above **Frame rate cap**. It changes at once, as when a Switch leaves its dock, and is kept for that game. Some games take the new picture size only at their next scene or their next start.

A few games are fixed to Handheld because they need the touch screen or the pointer there. Games that have their own **Resolution** row do not show Console mode: their Resolution sets it.

### Can I make a game sharper, or lighter, than its own resolution?

Yes. **Resolution scale** is in Settings › Performance and picture and in each game's settings (hold the cover › **Performance** › Picture). It draws the game's picture at a multiple of the size the game itself asks for: **1.25x**, **1.5x** or **2x** is sharper and asks more of the GPU, **0.75x** or **0.5x** is lighter and the app's upscaler fills the screen. **1x** is the game's own size. A game in Handheld mode draws at 720p, so 1.5x gives it exactly a 1080p screen. The choice applies from the next start.

It works on the app's side, so it is there for every game, but not every part of a picture follows it. Some effects (a blur, an outline, anti-aliasing) are made for the game's own size and can look a little different, and a game that reads its picture back, or passes it through its own upscaler, keeps that part at 1x. If a game looks wrong with it, set it back to 1x for that game and tell us which game and which scale. Games that have their own **Resolution** and **Upscaling quality** rows do not show this one.

### What is Tester settings?

A switch in Settings › Help. Off, a game that has **Resolution** choices shows just those. On, it also shows the rows made for testing: advanced resolution, **Upscaler** and **Dynamic resolution**. Leave it off unless you are trying things out or were asked to change one of those rows.

### Where are a game's own settings, such as its language?

Hold its cover. The page has four tabs: **Performance**, **Game**, **Saves** and **Storage**. A game's settings override the defaults in Settings. **Reset to defaults** puts them back. The game's language is **Game** tab › **Game language**.

### Can I turn off the vibration and the animations in the app?

Yes. **Settings › Library** has two rows. **Haptics** is **System** or **Off**. **Motion** is **System** or **Reduced**. With **System**, the app also follows Android's own touch feedback and remove animations settings.

### Where are my saves, and can I import one?

Saves are in the app's own storage, under `saves/<title ID>`. The **Saves** tab shows the path and has **Back up saves**, **Restore saves**, **Import save** (a `.zip` or a save folder) and **Delete save data**, which starts the game from the beginning. Stop the game first. Before a restore, an import or a delete, the app backs up the current save, so **Restore saves** can bring it back.

### Where is the game log?

Every run of a game writes a log. To send it:

- one game's newest log: hold its cover › **Storage** tab › **Share log**;
- the newest log of any run, including a Graphics self-test: Settings › **Share log**.

After a crash the app's notice has a **Share log** button for that run.

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

### How do I install a driver from the app's Download list?

You need an internet connection. The app downloads nothing until you tap a file.

1. Open Settings › **GPU driver**. The dialog **GPU driver (takes effect at the next game start)** opens.
2. Tap **Install…**, then **Download…**.
3. **Download a driver (GitHub releases)** lists the sources, for example **Mr. Purple Turnip** and **KIMCHI Turnip**. Tap one.
4. The app reads that source's releases and lists the driver zips, each with its release name and size. Tap the one you want. For the recommended driver, pick the T30 release under **Mr. Purple Turnip**.
5. Wait for **Downloading …**.

What you should see: the message **Installed and picked** with the driver's name, and the driver dialog again with that driver selected. The Settings row **GPU driver** now shows its name. It is used from the next game start.

If you see **No network (cannot reach GitHub)** or **GitHub rate limit, try again later**, check the connection or wait and try again.

### How do I install a driver from a zip I already have?

1. Open Settings › **GPU driver** › **Install…** › **Pick a zip…**.
2. Pick the driver zip. It must be a driver package with a `meta.json` inside, the format other Android emulators use.

What you should see: **GPU driver installed and picked:** with the driver's name. If the zip is not a driver package you see **GPU driver not installed:** and the reason, for example **no meta.json in the zip**. Nothing changes in that case.

### How do I pick a driver for all games, or for one game?

For all games:

1. Open Settings › **GPU driver**.
2. Tap **System (the phone's own driver)** or an installed driver. The dialog closes.

For one game:

1. Hold the game's cover and open the **Performance** tab.
2. Tap **GPU driver**.
3. Pick **Default (the main screen's: …)** to follow Settings, **System (the phone's own driver)**, or an installed driver.

What you should see: the row shows the name you picked. A game's own choice wins over the one in Settings. The change applies the next time the game starts, not to a running game.

### How do I go back to the system driver, or remove a driver?

Go back: Settings › **GPU driver** › **System (the phone's own driver)**. For one game, set its own **GPU driver** row to **Default** or **System**.

Remove:

1. Open Settings › **GPU driver** and pick the driver you want to remove. The dialog closes.
2. Open Settings › **GPU driver** again and tap **Remove picked…**.
3. Confirm with **Remove driver**.

What you should see: **Removed …; the system driver is used**. Only the app's copy of the driver is deleted. Your zip, games, builds and saves are kept.

### How do I check which driver a game really used?

- **Settings row.** Settings › **GPU driver** shows **System** or the picked driver's name.
- **Readiness check.** Settings › **Readiness check**, section **GPU**. The line **GPU and driver** names the GPU, its Vulkan version and the driver, and ends with **the system's driver** or **custom driver** and its name. **GPU driver picked** shows your choice. A game with its own driver has a line **Its own GPU driver**.
- **Graphics self-test.** Since 0.1.1 it runs on the driver picked in Settings. Its log has the line `graphics selftest: GPU driver: system` or `graphics selftest: GPU driver:` and the driver's name.
- **Game log.** Near the top, look for `GPU driver: system` or `GPU driver:` and the driver's name, followed by `(picked in the launcher; …)`. With a custom driver the log also has `[nxlaunch] env DM_VK_DRIVER=…` and `[vk] custom GPU driver: …`. The line `[vk] driver: …` names the driver that really ran. A line with `the system driver is used` means the custom driver could not be loaded.

### A driver made things worse. What can I try?

1. Go back to **System (the phone's own driver)** and start the game again, to confirm the driver is the cause.
2. Try another release of the same driver, older or newer, from **Download…**.
3. Try a driver from another source in the list.
4. If only one game is affected, give that game its own driver (hold the cover › **Performance** › **GPU driver**) and leave the others alone.
5. Run Settings › **Readiness check** and **Graphics self-test** on the new driver.

The first start on a new driver can stutter or show objects late, because each driver builds its own pipeline cache. Judge it on the second start.

If the game closes with **The GPU driver stopped responding, so the game had to close.**, that driver does not work for this game: pick another.

### Can a Mali or other non-Adreno device use Turnip?

No. The custom drivers the app can install are for Adreno GPUs only. On a Mali, Xclipse or PowerVR GPU the system driver is the only choice. If it lacks what a game needs, the app says so before the game starts (**Not ready for this game**), and that device cannot draw the game yet. Non-Adreno devices are untested.

### What do the Readiness check and the Graphics self-test tell me?

Settings › **Readiness check** checks Android, memory, file access, the GPU, the picked driver and each game's needs. Its verdict is **Ready**, **Ready, with limits** or **Not ready**, and a failed item has a button that opens the fix, such as **Pick a GPU driver** or **Allow file access**. **Run again** repeats it, and **Copy report** copies the text for a bug report. If you change the driver while it is open, leave the screen and open it again.

Settings › **Graphics self-test** needs no game. It draws a test picture for a few seconds and ends in **PASS** or **FAIL**, with a return code. Since 0.1.1 it runs on the driver you picked. A FAIL on the system driver with a PASS on a custom one means: use the custom one.

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

### Which games have a companion?

Settings › Library › **Companions** shows the catalog: **Your games** first, then **Other companions · add the game first**. Most games on the tested list have a companion: the README's table says which. The catalog is read online, so the list can change without an app update.

### How do I install a companion for the first time?

With the build (the usual way):

1. Tap the game's cover to open its sheet.
2. Look at the **Companion** row. It shows the companion's name, version and size, and its switch is on: **Install after the build**.
3. Tap **Build**. The companion is downloaded and installed as the last step.

Later, for a game that is already built:

1. Open Settings › Library › **Companions**.
2. Find the game under **Your games** and tap **Install**.
3. Wait for the line at the top to read **Installed …**.

You can also do it from the game's own page: hold the cover › **Game** tab › **Companion** section › **Install**.

What you should see: the row reads **installed** with the version and **up to date**, and the game's cover has a green badge with two bars. Start the game: the companion appears on the second screen. Close the game before you install. A companion installed while the game runs loads at the game's next start.

### How do I see which version is installed, update it, or go back to an earlier one?

- **See the version:** the **Companion** row in the game's sheet, in Settings › Library › **Companions**, or in the game's **Game** tab. It reads **installed** and the version, then **up to date** or the newer version that is available.
- **Update:** tap **Update** in Settings › Library › **Companions** or in the game's **Game** tab. **Rebuild** also updates it.
- **Go back to an earlier version:**
  1. Open the game's sheet and tap **Versions…** (in the game's **Game** tab the button is **Versions**).
  2. The sheet lists every release, newest first, with its date and size. The installed one is marked **installed**. Tap a row to read its release notes.
  3. Tap **Roll back** on an older version, or **Switch to** on a newer one.

What you should see: **… is active. It loads at the game's next start.** A version you used before is kept on the device (**on this device**) and switches without a download. **Forget** deletes such a kept version.

Each download is checked against its published checksum. A release without a checksum cannot be installed.

### How do I remove a companion, or turn the second screen off for one game?

Remove: tap **Remove** in Settings › Library › **Companions** or in the game's **Game** tab, or **Remove companion** in the Versions sheet. Close the game first. The game and its saves stay, and the catalog can install the companion again.

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

- Settings › Library › **Companions** shows **Catalog unavailable:** and you cannot install or update.
- A game's sheet may read **No companion in the catalog for this game** even though one exists. Go online and tap Settings › Library › **Rescan**.
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
2. Open the **Game** tab and find the **Mods** section, above **Companion**.
3. Tap **Install mod…** and pick the mod's **Folder** or **Zip file**. Atmosphère-layout folders and plain `romfs`/`exefs` folders are recognized.
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

## Troubleshooting

### The game crashes at start or shows a black screen. What should I check?

1. Settings › **Readiness check**.
2. The firmware (Settings › Library › **Firmware**).
3. Another GPU driver.
4. The game update: **You have** in the game's sheet must be a version under **This build supports**.
5. **Build again**, if the app was updated since the build.

After a crash the app shows the reason it knows, with a **Share log** button.

For a black screen, try another GPU driver first. On a device whose system driver is older than Vulkan 1.3, install a custom driver. Then run the **Graphics self-test**. If it passes and the game is still black, report it with the log.

### The build ran out of storage or memory. What now?

Out of storage: the card offers **Free up space** and **Retry**. Free space on the volume the build writes to, then retry. The build picks up what it can.

Out of memory, typically on an 8 GB device: update to 0.1.1 or later, which builds with fewer workers on low-memory devices. A build that Android ended for memory resumes with fewer workers, so tap **Resume**. Close other apps before a large build. If it still fails, build the game on a PC or a Mac, or report it.

### How do I report a bug well?

Open an issue at https://github.com/igawa6/peanuts-native/issues with:

- what happened, and what you did just before;
- the device, its RAM and the Android version;
- the game, its title ID and its update version;
- the app version and the GPU driver;
- the Readiness check report (**Copy report**);
- the log: hold the game's cover › **Storage** › **Share log**, or Settings › **Share log** for the newest log of any game.

A screenshot or a short video helps for a wrong picture. Never attach or link game files, keys or firmware.

## Updating the app

### Do I need to rebuild my games after an update?

Rebuilding every game after an app update is strongly recommended. An update can change how a game's code and shaders are prepared, and a game built by an older version keeps the old result until it is built again. Hold the game's cover, open **Storage**, tap **Build again**, then **Rebuild**. Your saves are not touched. It is quick when the game's code is otherwise current, and takes as long as a first build when it is not.

### Will I lose my saves?

No. An update keeps the app's storage, and saves are in it. Uninstalling can delete them: Android asks whether to keep the app's data. Use **Back up saves** first.

### How do I tell which version I have?

The build name is under the app's title on the home screen and in Settings › **About**. A release build reads `peanuts-native-0.1.4`. Every log starts with it too.

## Credits

Peanuts Native is a fork of Banana Native by moceure, which is based on nxnative by seedds. Companions run on the Eden Duo runtime.
