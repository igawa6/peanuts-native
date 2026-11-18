# GPU drivers

The game's graphics run on a Vulkan driver: the phone's own Qualcomm driver (**System**) or a custom driver such as Turnip, the open-source Mesa driver for Adreno GPUs. Banana Native is tested with both, on a Snapdragon 8 Elite Gen 5. Other drivers may not work. If a game draws wrongly or crashes on one, try the other and say which you used when you report it.

## Which one to use

Per the project's guide and game notes (not every game has been compared on both):

| Game | Driver |
|---|---|
| Donkey Kong Bananza (`040074A01BF12000`) | both, about 30 fps on each |
| Fire Emblem Fortune's Weave (`04007EF01F8AE000`) | on Adreno 740 pick Turnip |
| Mario Kart World (`0400C3F00006E000`) | not compared yet |
| Pokémon Pokopia (`0400A3A01EE4C000`) | both, about 60 fps on each |
| Zelda: Ocarina of Time (`0400486024DEC000`, experimental) | plays on both; see its page |
| Mario Tennis Fever (`0400D4201C6EA000`, experimental) | Turnip; the system driver stalls and shows dark patches |
| Splatoon Raiders (`0400380020056000`, experimental) | a white scene was seen on System: try Turnip |

See [Games](Games) for the current state of each.

## Install Turnip

1. Settings > **GPU driver** > **Install...**
2. **Download...** lists Turnip releases (only when you tap it), or **Pick a zip...** takes a driver zip you already have (a zip with a `meta.json`, as other Android emulators use).
3. The installed driver appears in the list.

## Pick a driver

- **For every game:** Settings > **GPU driver**: **System (the phone's own driver)** or an installed driver. It takes effect at the next game start.
- **For one game:** the game's ... button > **Game settings** > **GPU driver**: **Default**, **System**, or an installed driver.
- **Remove a driver:** Settings > **GPU driver** > **Remove picked...** deletes the app's copy; the game then uses the system driver.

Settings > **Readiness check** shows the GPU and the driver picked. Changing driver starts the GPU driver's pipeline cache again for that driver, so the first start after a switch can be slow.

<!-- TODO screenshot: Settings > GPU driver list -->

For speed differences between drivers see [Getting higher FPS](Getting-Higher-FPS).
