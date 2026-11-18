# Heat and battery

A game on a phone runs the CPU and the GPU hard, and the phone answers by warming up and slowing down. This page says how to see that happening, what the app does about it, and what can be done. Battery drain and temperature figures for the games are **not measured yet** (there is no test of watts or percent per hour); the numbers below are single observations from the project's logs, not typical values.

## See it: the overlay

With the [overlay](Settings-Performance#overlay) on (default), the top-right block has:

- A **throttle dot**: **green** when nothing limits the phone; **yellow** for thermal status light, thermal headroom of 0.85 or more, or the main CPU cluster capped below 85 % of its top clock; **red** for thermal status moderate or worse, headroom of 1.0 or more, or the main cluster capped below 65 %. Grey when nothing could be read.
- **CPU clock caps** and **Battery temperature and level** as separate items.
- Frame rate, frame time and GPU time per frame.

One log shows how it reads on a phone: on one handheld (Honor PNM-N49) a game ran at 30 fps with the main cluster at 2.5 GHz or more and dropped to 20 fps when the phone, once warm, capped it at 2.0 to 2.23 GHz (55 to 61 % of the top clock), while the thermal status still read light. That read red. The 2.5 GHz cap the phone keeps even when cool (69 %) reads yellow.

## What the app does about heat

- **Prepare pauses when the phone is too hot.** Prepare (the long first-time step) waits while the thermal status is severe or worse and goes on at moderate or better. A pause below critical offers **Go on anyway**. The card shows the readings. On one handheld the pause stayed on a phone that was cool because the phone's thermal report was stuck; that is why the button and the readings exist ([First start and Prepare](First-Start-and-Prepare), [Troubleshooting](Troubleshooting)).
- **Keep the screen on while preparing** (Settings > System) is for phones that stop a background job when the screen goes off. Preparation heats the phone; keep it charged ([Installation](Installation)).
- **A suspended game uses no game CPU.** **Pause the game** and **Pause on return** freeze the game's clock, graphics, threads and audio. A game left in the background is suspended the same way, so leaving a game to another app does not keep running it. The cost: a game that has not saved loses its progress if you stop it while it is paused or in the background.
- The Mac and iPhone host caps games at 30 fps when the phone's thermal state is serious. Whether this applies on Android is **not confirmed** in the sources used for this wiki.

## What you can do

Fewer frames and fewer pixels mean less work, which tends to mean less heat. The savings are **not measured yet** in watts, but they follow the speed ladder in [Getting higher FPS](Getting-Higher-FPS):

1. **A lower frame rate cap.** It is the cheapest change. Ocarina of Time's profile ships with a 60 fps limit because one of its scenes (Link's house) wandered between 27 and 38 fps and heated the phone, and no cap could hold it before.
2. **A lower resolution** or the **Performance** preset; **Full vegetation off** (Ocarina of Time).
3. **Do not run frame generation** if the phone is already hot: it adds GPU work.
4. **Turn the overlay's battery and CPU items on while you test**, so you can see when it starts to throttle.
5. **Take the phone out of a case** or let it cool between sessions; hot phones also slow the first-start pipeline build ([Getting higher FPS](Getting-Higher-FPS#before-you-start)).

## Observations from the logs

These are single runs on single phones; they show what is possible, not what to expect.

- Donkey Kong Bananza, first level, one test of 2026-11-02: the phone was thermally capped (prime core at 1.0 to 2.2 GHz of 4.6 GHz) with the battery at 39 to 40 °C.
- Mario Kart World, one race run on a phone with CPU caps of 2,227 and 1,862 MHz (of 3,628 and 4,608) and the dot red, thermal status 1 (the game's own note: the work of recording a frame would be about half at full clocks).
- Ocarina of Time in Link's house: 38 to 39 fps on Qualcomm's driver on a warm run, and 30 to 33 fps once the phone reached thermal status 2.
- Pokopia: one slowdown was checked and was **not** heat (thermal status 0 throughout, the prime core at its full clock).
- Donkey Kong Bananza, on Turnip: before a fix, a missing GPU clock floor held it at 20 to 30 fps (fixed since). If the overlay shows a green dot and a cool phone, heat is not the cause of a slow game; look at the driver and the settings.

## Battery

- **Battery saver and low-power modes** of Android reduce the clocks and are likely to lower the frame rate; effect **not measured yet**.
- **Rumble** and frame generation add some work; effect **not measured yet**.
- Keep the phone **charged for Prepare**, which can take a long time on a big game (Mario Tennis Fever's first Prepare is "long"; see [First start and Prepare](First-Start-and-Prepare)).

## Sources

`Overlay.java` (throttle dot, items), `docs/reference/packing.md` (Prepare pause), `docs/reference/frame-rate.md` (thermal cap for iPhone, GPU clock floor), `dm/games/040074A01BF12000/README.md`, `dm/games/0400C3F00006E000/README.md`, `dm/games/0400486024DEC000/README.md`, `dm/games/0400A3A01EE4C000/README.md`, `android/README.md` (Pause), `dm/games/README.md` (`fps_limit`).
