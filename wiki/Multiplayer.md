# Multiplayer

Pokémon Pokopia (`0400A3A01EE4C000`) has multiplayer: you can visit another player's world. On Banana Native this is **Link Play**, an experimental way to join a game hosted on a PC. It does not use Nintendo online services, and the phone can join only; it cannot host yet.

## What works

- Your phone joins a compatible PC host (the Recompia PC runtime, native protocol) over the same Wi-Fi or over the Internet.
- One test on a Samsung Galaxy Z Fold 7 (Turnip driver) had two players in the same world for over 90 seconds, each seeing the other move. The visitor returned home normally.
- Not tested yet: the phone as host, automatic discovery on the LAN, Story co-op, Interact everywhere, reconnecting, and joining through Tailscale or the Internet in the current build.

## What you need

- A PC running a compatible host (Recompia) with the native protocol. In its settings, `link_interact_everywhere` and `link_story_coop` must be `false`, with no active gameplay mods; the phone does not use them, and a mismatch hides the host or refuses the join. Restart the PC game after changing them.
- Separate save identities for host and visitor, with Link Play already unlocked in both saves. A copied save identity may be rejected, and an early tutorial save cannot use the terminal yet. Keep your saves; do not delete them.
- The PC's address that your phone can reach.

## Set it up on the phone

1. Leave the game if it is running. The setting applies at the next start.
2. Open the game's card menu, then **Game settings > Game > Link Play**.
3. Choose one:
   - **Off** (default). Keeps the saved address and code for later.
   - **Join PC host, on the same LAN**: enter the PC's IPv4 address and optionally a port (default 47600).
   - **Join PC host, Internet / Tailscale**: enter the reachable address and the host's 16-symbol room code (spaces and hyphens are accepted).
4. Start the game. In the game: **PC > Link Play > Local connection > Visit**.

Only IPv4 addresses are accepted, plus `localhost` for a test on one device. A wrong address or code keeps the dialog open.

## Privacy and safety

Internet mode proves the room code but does not encrypt the game traffic: anyone on the network path can read it. Tailscale is an optional way to reach the host, not a discovery mode. Logs can contain peer addresses and room codes; remove them before sharing a log.

If you set a game environment line by hand, it overrides this setting. Check [Game settings](Settings-Game) if the game behaves differently from what you chose.

See also the [Pokémon Pokopia page](Game-Pokemon-Pokopia), and report results in an issue with your phone, driver and PC host version.
