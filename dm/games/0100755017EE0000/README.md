<!-- PEANUTS: offline Android profile for update 1.5.0. -->
# FANTASY LIFE i: The Girl Who Steals Time (`0100755017EE0000`)

Update 1.5.0 (v524288); Unreal Engine 4, NVN, SDK 18.3.7. The shader map has 14,962 keys, including 2,215 vertex shaders. The opening uses dynamic texture slots. The profile has no other registered build.

## Offline New Game

The offline host does not provide Epic Online Services. On startup the game subscribes to notifications through an absent interface; during New Game it asks for the platform handle and local Epic account ID. The [build-specific cuts](patches.py) skip the two subscriptions and make both getters return null. They do not create an account or enable network play.

When the host reports dictionary version zero, the game's avatar form rejects ordinary names before running its two word checks. The final cut leaves both checks reachable when there is no dictionary version. On an Odin 2 with Turnip v27.0.0-R1, the 1.5.0 build accepted a name, started a new game, and allowed movement and conversations on the opening ship. Cold-loading the resulting save also reached the ship. Later quests, multiplayer, and the system GPU driver were not tested.
