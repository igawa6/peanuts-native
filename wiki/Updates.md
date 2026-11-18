# Updates

How the app updates itself, what happens to your game's shaders afterwards, and where updates come from. Updates come only from the project's official releases.

## Updating the app

Since v0.1.4 the app offers a newer release at start (**Update** or **Later**), downloads it from the project's [releases](https://git.eden-emu.dev/moceure/BananaNative/releases) and installs it after you confirm. Settings > **Updates** > **Check for updates** checks at any time. Updates download from the official site only.

An update keeps the app's storage, so your saves, packs and copied games stay.

## After an app update

A new version may come with new shaders. What you see depends on what changed:

- **No shader changes:** the pack's shaders are taken over at once.
- **Some shaders changed:** the card says **Ready · updating shaders ...** with the count and time left. Only the changed shaders are translated, in the background, from your dump on the phone, and you can keep playing with the old shaders meanwhile.
- **The app cannot update them itself:** the card says **Ready · update** and the game plays as before. Open the game's page: a **Shader update available** card at the top has an **Update shaders** button (or its button reads **Prepare shaders**, or **Storage > Prepare again**). The update is optional. A pack made on a computer can be made again there and imported instead ([First start and Prepare](First-Start-and-Prepare)).

Shader updates can take a long time on a phone for games with many shaders (for example all 8,161 of Pokémon Pokopia `0400A3A01EE4C000` in the worst case); keep the phone charged.

## Release history

The project's release notes are on the releases page. A short list is in the repository's `docs/players/history.md`.

## Safety

Download the APK only from the official releases page. Nobody connected to the project will ask for money, keys or game files.
