# FAQ

Short answers, with links to the page that has the detail.

**What is Banana Native?** An Android app that runs Switch 2 games from your own dumps. Like Wine, it emulates no hardware: the game's code runs on the phone's CPU and the system services and graphics are supplied by the app. It is an Android port of nxnative by seedds. Most of the code was written with an AI coding assistant (Claude) and tested by hand.

**Does it include games, keys or firmware?** No. It contains no Nintendo code, firmware or keys and decrypts nothing. It reads only a dump folder (or a `.nup` file of one) from your own console. This project does not explain how to get games; please don't ask.

**Which phones work?** Android 13+, 64-bit, Snapdragon (Adreno) GPU, 12 GB RAM or more (16 GB for Mario Kart World). Tested on a Snapdragon 8 Elite Gen 5 only by the author; other reports are in [Tested devices](Tested-Devices). See [Installation](Installation).

**Which games work?** See [Games](Games). Donkey Kong Bananza (`040074A01BF12000`), Fire Emblem Fortune's Weave (`04007EF01F8AE000`), Mario Kart World (`0400C3F00006E000`) and Pokémon Pokopia (`0400A3A01EE4C000`) are supported; several others are experimental.

**Why does Prepare take so long?** It rewrites the game's code and translates every shader. A PC or a Mac is much faster than the phone ([First start and Prepare](First-Start-and-Prepare)).

**Do I need a computer?** No, except it is recommended for Mario Kart World.

**Which GPU driver should I use?** Try the system driver first, and Turnip if a picture is wrong; some games name one ([GPU drivers](GPU-Drivers)).

**The game is slow or the phone is hot.** See [Getting higher FPS](Getting-Higher-FPS) and [Heat and battery](Heat-and-Battery). A phone cooler helps.

**Can I use frame generation?** Yes, with your own `Lossless.dll` ([Playing](Playing#frame-generation)). It adds some latency.

**Can I start games from ES-DE, Daijisho and similar?** Yes, with `.nup` files ([Playing](Playing#starting-from-a-frontend-or-home-screen)).

**Where are my saves, and are they safe when I uninstall?** [Saves and storage](Saves-and-Storage). Back up first.

**Where do I download the app?** Only from the [official releases](https://git.eden-emu.dev/moceure/BananaNative/releases). The project has no Discord, subreddit or social media, and nobody will ask for money, keys or game files.

**Does it work on iPhone or Mac?** The Mac and iOS code is upstream nxnative's. This wiki covers the Android app only; a Mac is used only to prepare packs.

**How do I report a bug?** [Troubleshooting](Troubleshooting#opening-an-issue).
