#!/usr/bin/env python3
"""The Peanuts icons: the one source of the launcher's android/app/src/peanuts/res/drawable/pi_*.xml.

Every icon is a shell with at most one kernel, like the nut, on a 24 x 24 grid. An icon is a list of layers, each (kind, path data):

  "s"  shell   stroke 1.75 wide, round caps and joins, no fill, in the ink colour (?attr/pnIconInk)
  "k"  kernel  filled with the kernel colour (?attr/pnIconKernel, the accent): the seed inside the shell, kept clear of the stroke.
               One idea per icon; repeated seeds (the list's bullets) count as one. Arrows and marks have none.
  "f"  fill    filled with the ink: dots that stay neutral (a console's buttons, a launcher's row of apps)

Only path data is used (no circle or rect elements), so the strings are VectorDrawable pathData as they are. Ink and kernel come from the
theme, so one file serves the light and the dark theme; Drawable.setTint() on a filled button turns the whole icon one colour.

  python3 peanuts/scripts/icons.py           writes every pi_<name>.xml and removes a pi_*.xml no icon here names
  python3 peanuts/scripts/icons.py --check   writes nothing; exit 1 when a file differs from what this would write (peanuts/tests/test_icons.py)

A new icon: add its layers below, run the script, and use it by name (dev.peanuts.ui.Icons.get, SettingsSections).
"""

import pathlib
import sys

STROKE = 1.75
REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = REPO / 'android/app/src/peanuts/res/drawable'
HEAD = '<!-- Peanuts icon, written by peanuts/scripts/icons.py. Change it there and run the script, not here. -->'


def circle(cx, cy, r):
    return f"M{cx - r:g} {cy:g}a{r:g} {r:g} 0 1 0 {2 * r:g} 0a{r:g} {r:g} 0 1 0 {-2 * r:g} 0Z"


def rrect(x, y, w, h, r):
    return (f"M{x + r:g} {y:g}H{x + w - r:g}A{r:g} {r:g} 0 0 1 {x + w:g} {y + r:g}V{y + h - r:g}"
            f"A{r:g} {r:g} 0 0 1 {x + w - r:g} {y + h:g}H{x + r:g}A{r:g} {r:g} 0 0 1 {x:g} {y + h - r:g}"
            f"V{y + r:g}A{r:g} {r:g} 0 0 1 {x + r:g} {y:g}Z")


# The peanut: two lobes and a waist. The brand outline.
PEANUT = ("M12 2.5C15 2.5 17 4.6 17 7.2C17 9 16 10.2 15.4 11.2C15 11.9 15 12.4 15.5 13.2"
          "C16.4 14.4 17.6 15.6 17.6 17C17.6 20 15.2 21.5 12 21.5C8.8 21.5 6.4 20 6.4 17"
          "C6.4 15.6 7.6 14.4 8.5 13.2C9 12.4 9 11.9 8.6 11.2C8 10.2 7 9 7 7.2C7 4.6 9 2.5 12 2.5Z")
SEED_TOP = "M12 4.6c1.6 0 2.7 1.1 2.7 2.6S13.6 9.9 12 9.9 9.3 8.7 9.3 7.2 10.4 4.6 12 4.6Z"
SEED_BOTTOM = "M12 14.1c1.8 0 3.1 1.2 3.1 2.8s-1.3 2.8-3.1 2.8-3.1-1.2-3.1-2.8 1.3-2.8 3.1-2.8Z"
CONTROLLER = ("M7 7h10c2.8 0 4.5 2.5 4.5 6v2.5c0 2.3-1.5 3.5-3 3.5-1.5 0-2.3-1-3-2l-.7-1H9.2l-.7 1"
              "c-.7 1-1.5 2-3 2S2.5 17.8 2.5 15.5V13C2.5 9.5 4.2 7 7 7Z")
# A device with two screens, the top one wider (the companion, Screens)
TOP_SCREEN = rrect(2.5, 3, 19, 8.5, 2.5)
BOTTOM_SCREEN = rrect(4.5, 13.5, 15, 7.5, 2.5)

ICONS = {
    # Marks: shells only
    "check": [("s", "M5 12.5l5 5 9-10.5")],
    "close": [("s", "M6.5 6.5l11 11M17.5 6.5l-11 11")],
    "external": [("s", "M14 4.5h5.5V10M19.5 4.5 11 13M17 14v4a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18V8.5A1.5 1.5 0 0 1 6 7h4")],
    "menu": [("s", "M4 6.5h16M4 12h16M4 17.5h16")],

    # Library
    "library": [("s", rrect(3.5, 3.5, 7.5, 7.5, 2)), ("k", rrect(5.75, 5.75, 3, 3, 0.8)), ("s", rrect(13, 3.5, 7.5, 7.5, 2)),
                ("s", rrect(3.5, 13, 7.5, 7.5, 2)), ("s", rrect(13, 13, 7.5, 7.5, 2))],
    "list": [("s", "M10 6.5H20M10 12H20M10 17.5H20"), ("k", circle(5.5, 6.5, 1.7)), ("k", circle(5.5, 12, 1.7)), ("k", circle(5.5, 17.5, 1.7))],
    "search": [("s", circle(10.5, 10.5, 6)), ("s", "M15 15l5 5"), ("k", circle(10.5, 10.5, 2.4))],
    "game": [("s", rrect(5.5, 3.5, 13, 17, 2.5)), ("k", rrect(8.5, 6.75, 7, 6.5, 1.2)), ("s", "M9.5 17h5")],
    "folder": [("s", "M3.5 7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z"), ("k", rrect(6.5, 11, 11, 5, 1.2))],
    "storage": [("s", "M9 3.5h8a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-11Z"),
                ("k", rrect(9.25, 6.5, 1.5, 3.5, 0.75)), ("k", rrect(12.25, 6.5, 1.5, 3.5, 0.75)), ("k", rrect(15.25, 6.5, 1.5, 3.5, 0.75))],

    # Actions
    "play": [("s", "M8 5.3 18.5 12 8 18.7Z"), ("k", "M10.6 9.6 14.4 12l-3.8 2.4Z")],
    "build": [("k", "M13.1 6.6 17.4 10.9 16.3 12 12 7.7Z"), ("s", "M12.73 4.91 19.09 11.27 16.27 14.09 9.91 7.73Z"),
              ("s", "M12.9 11.1 4.8 19.2")],
    "update": [("s", "M19.5 12A7.5 7.5 0 1 1 15.6 5.4"), ("k", "M13.4 2.3 18.6 5.6 13.6 8.6Z")],
    "download": [("s", "M12 3.5V15M7.5 10.5 12 15l4.5-4.5M5 19.5h14")],
    "share": [("s", "M12 3.5V14M8 7.5l4-4 4 4M5 11v7.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V11")],
    "trash": [("s", "M4.5 6.5h15M9.5 6.5v-2h5v2M6.5 6.5l1 12.5a1.5 1.5 0 0 0 1.5 1.5h6a1.5 1.5 0 0 0 1.5-1.5l1-12.5M10.5 10v6.5M13.5 10v6.5")],
    "settings": [("s", "M4 6h8.2M17.8 6H20M4 12h2.2M11.8 12H20M4 18h6.2M15.8 18H20"),
                 ("s", circle(15, 6, 2.6)), ("k", circle(15, 6, 1.1)), ("s", circle(9, 12, 2.6)), ("k", circle(9, 12, 1.1)),
                 ("s", circle(13, 18, 2.6)), ("k", circle(13, 18, 1.1))],

    # Game content
    "dlc": [("k", "M12 5.6 16.8 8 12 10.4 7.2 8Z"), ("s", "M12 3.5 20.5 8 12 12.5 3.5 8Z"), ("s", "M3.5 12 12 16.5 20.5 12M3.5 16 12 20.5 20.5 16")],
    "companion": [("s", TOP_SCREEN), ("s", BOTTOM_SCREEN), ("k", rrect(7.25, 15.75, 9.5, 3, 1))],
    "peanut": [("s", PEANUT), ("k", SEED_TOP), ("k", SEED_BOTTOM)],

    # System
    "controller": [("s", CONTROLLER), ("s", "M7.5 10.5v4M5.5 12.5h4"), ("k", circle(16, 11.3, 1.25)), ("k", circle(18.2, 13.5, 1.25))],
    "driver": [("s", rrect(6.5, 6.5, 11, 11, 2.5)), ("k", rrect(9.5, 9.5, 5, 5, 1.2)),
               ("s", "M9.5 3.5v3M14.5 3.5v3M9.5 17.5v3M14.5 17.5v3M3.5 9.5h3M3.5 14.5h3M17.5 9.5h3M17.5 14.5h3")],
    "key": [("s", circle(8, 15.5, 4.5)), ("k", circle(8, 15.5, 1.8)), ("s", "M11.2 12.3 19.5 4M16.3 7.2l2.2 2.2M14 9.5l1.6 1.6")],
    "gauge": [("s", "M4.5 16.5a8 8 0 1 1 15 0M12 14l4-4.5"), ("k", circle(12, 14, 2))],
    "sleep": [("s", "M19.5 14.5A8 8 0 1 1 9.5 4.5a6.5 6.5 0 0 0 10 10Z"), ("k", "M8.9 9.4a4.6 4.6 0 0 0 5.9 7.1 5.6 5.6 0 0 1-5.9-7.1Z")],
    "awake": [("s", circle(12, 12, 4.5)), ("k", circle(12, 12, 2)),
              ("s", "M12 2.75v2M12 19.25v2M2.75 12h2M19.25 12h2M5.45 5.45l1.4 1.4M17.15 17.15l1.4 1.4M5.45 18.55l1.4-1.4M17.15 6.85l1.4-1.4")],
    "clock": [("s", circle(12, 12, 8.5)), ("s", "M12 7.5V12l3 2"), ("k", circle(12, 12, 1.5))],
    "user": [("s", circle(12, 8.5, 4)), ("s", "M4.5 20.5c.8-3.6 3.8-5.5 7.5-5.5s6.7 1.9 7.5 5.5"), ("k", circle(12, 8.5, 1.5))],

    # The Mii editor (Users with Miis): a Mii, flip, random (a die) and undo
    "mii": [("s", circle(12, 12, 8)), ("s", "M6.6 9.2c1.6-2.8 3.9-4 6.4-4 2.3 0 4.2 1 5 2.6"), ("k", circle(9.5, 12.5, 1.2) + circle(14.5, 12.5, 1.2)),
            ("s", "M10 16q2 1.4 4 0")],
    "flip": [("s", "M4 8h13.5M14 4.5 17.5 8 14 11.5M20 16H6.5M10 12.5 6.5 16l3.5 3.5"), ("k", circle(12, 8, 1.2))],
    "random": [("s", rrect(4, 4, 16, 16, 3.5)), ("k", circle(8.6, 8.6, 1.35)), ("k", circle(15.4, 8.6, 1.35)), ("k", circle(12, 12, 1.35)),
               ("k", circle(8.6, 15.4, 1.35)), ("k", circle(15.4, 15.4, 1.35))],
    "undo": [("s", "M9 4.5 4.5 9 9 13.5M4.5 9H14a5.5 5.5 0 0 1 0 11H10")],

    # Console mode: the console with its two controllers (handheld), the console in its dock at a TV (docked)
    "console": [("s", rrect(1.5, 6.75, 21, 10.5, 3.25)), ("s", "M6.5 6.75V17.25M17.5 6.75V17.25"), ("k", rrect(8.75, 9, 6.5, 6, 1)),
                ("f", circle(4, 10.3, 1.2)), ("f", circle(20, 13.7, 1.2))],
    "docked": [("s", rrect(2.5, 4, 19, 12.5, 2.5)), ("k", rrect(5.5, 7, 13, 6.5, 1.2)), ("s", "M8.5 20.5h7M12 16.5v4")],

    # Screens: what the second screen shows (Android's apps, the game's cover), where the game plays after a swap, how the screen turns
    "screens_android": [("s", TOP_SCREEN), ("s", BOTTOM_SCREEN), ("f", circle(8.25, 17.25, 0.95)), ("f", circle(10.75, 17.25, 0.95)),
                        ("f", circle(13.25, 17.25, 0.95)), ("f", circle(15.75, 17.25, 0.95))],
    "screens_cover": [("s", TOP_SCREEN), ("s", BOTTOM_SCREEN), ("k", rrect(10.25, 15.1, 3.5, 4.3, 0.7))],
    "swap_off": [("s", TOP_SCREEN), ("s", BOTTOM_SCREEN), ("k", "M10.25 4.9 14.5 7.25 10.25 9.6Z")],
    "swap_on": [("s", TOP_SCREEN), ("s", BOTTOM_SCREEN), ("k", "M10.5 15.1 14.25 17.25 10.5 19.4Z")],
    "screen_landscape": [("s", rrect(2.5, 6.5, 19, 11, 2.5)), ("k", rrect(5.5, 9.25, 11, 5.5, 1.2)), ("s", "M19 10.5v3")],
    "screen_rotate": [("s", rrect(8.5, 4, 7, 16, 2)), ("k", rrect(10.5, 7, 3, 7.5, 0.8)), ("s", "M11 17.25h2"),
                      ("s", "M3.77 16.75A9.5 9.5 0 0 1 5.28 5.28M2.68 5.28H5.28V7.88"),
                      ("s", "M20.23 7.25A9.5 9.5 0 0 1 18.72 18.72M21.32 18.72H18.72V16.12")],

    # Status: the kernel takes the status colour instead of the accent
    "info": [("s", circle(12, 12, 8.5)), ("s", "M12 11v5.5"), ("k", circle(12, 7.9, 1.3))],

    # Screens: a TV on its stand, the play mark as the kernel (Settings › Screens › TV and monitors, #407)
    "tv": [("s", "M5 4H19A2.5 2.5 0 0 1 21.5 6.5V14A2.5 2.5 0 0 1 19 16.5H5A2.5 2.5 0 0 1 2.5 14V6.5A2.5 2.5 0 0 1 5 4Z"),
           ("s", "M8.5 20.5h7M12 16.5v4"), ("k", "M10.4 8.1 14.4 10.25 10.4 12.4Z")],

    # Mods & Cheats, game page and the other #447 icons (drawn by hand there, the same layers here)
    "add": [("s", "M12 5v14M5 12h14")],
    "aim": [("s", "M5.5 12a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0Z"), ("s", "M12 2.75v4M12 17.25v4M2.75 12h4M17.25 12h4"), ("k", "M10.1 12a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0Z")],
    "aspect": [("s", "M5.5 6H18.5A2.5 2.5 0 0 1 21 8.5V15.5A2.5 2.5 0 0 1 18.5 18H5.5A2.5 2.5 0 0 1 3 15.5V8.5A2.5 2.5 0 0 1 5.5 6Z"), ("s", "M13.5 6v12"), ("k", "M6.85 8.75H9.65A1.1 1.1 0 0 1 10.75 9.85V14.15A1.1 1.1 0 0 1 9.65 15.25H6.85A1.1 1.1 0 0 1 5.75 14.15V9.85A1.1 1.1 0 0 1 6.85 8.75Z")],
    "buttons": [("s", "M9.2 6.3a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z"), ("s", "M3.5 12a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z"), ("s", "M14.9 12a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z"), ("s", "M9.2 17.7a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z"), ("k", "M16.5 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0Z")],
    "cartridge": [("s", "M7 3.5h7.5l3.5 3.5v11.5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2Z"), ("k", "M9.45 9.5H13.55A1.2 1.2 0 0 1 14.75 10.7V13.8A1.2 1.2 0 0 1 13.55 15H9.45A1.2 1.2 0 0 1 8.25 13.8V10.7A1.2 1.2 0 0 1 9.45 9.5Z"), ("s", "M9 17.5h5")],
    "clarity": [("s", "M9 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0Z"), ("s", "M3 8.5h4.5M3 12h3M3 15.5h4.5"), ("k", "M12.3 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0Z")],
    "contrast": [("s", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z"), ("k", "M12 5.9a6.1 6.1 0 0 1 0 12.2Z")],
    "copies": [("s", "M6 3.5H12A2.5 2.5 0 0 1 14.5 6V12A2.5 2.5 0 0 1 12 14.5H6A2.5 2.5 0 0 1 3.5 12V6A2.5 2.5 0 0 1 6 3.5Z"), ("s", "M17.5 7v8a3 3 0 0 1-3 3h-8"), ("s", "M20.5 10v7.5a3 3 0 0 1-3 3H10"), ("k", "M7.7 6.5H10.3A1.2 1.2 0 0 1 11.5 7.7V10.3A1.2 1.2 0 0 1 10.3 11.5H7.7A1.2 1.2 0 0 1 6.5 10.3V7.7A1.2 1.2 0 0 1 7.7 6.5Z")],
    "cores": [("s", "M5.5 4.5H12A2.5 2.5 0 0 1 14.5 7V17A2.5 2.5 0 0 1 12 19.5H5.5A2.5 2.5 0 0 1 3 17V7A2.5 2.5 0 0 1 5.5 4.5Z"), ("s", "M18.5 8.5H20A1.5 1.5 0 0 1 21.5 10V14A1.5 1.5 0 0 1 20 15.5H18.5A1.5 1.5 0 0 1 17 14V10A1.5 1.5 0 0 1 18.5 8.5Z"), ("k", "M7.2 7.5H10.3A1.2 1.2 0 0 1 11.5 8.7V15.3A1.2 1.2 0 0 1 10.3 16.5H7.2A1.2 1.2 0 0 1 6 15.3V8.7A1.2 1.2 0 0 1 7.2 7.5Z")],
    "display": [("s", "M5 5H19A2.5 2.5 0 0 1 21.5 7.5V16.5A2.5 2.5 0 0 1 19 19H5A2.5 2.5 0 0 1 2.5 16.5V7.5A2.5 2.5 0 0 1 5 5Z"), ("s", "M7.1 10.4 5.5 12l1.6 1.6M16.9 10.4 18.5 12l-1.6 1.6"), ("k", "M10.2 8.5H13.8A1.2 1.2 0 0 1 15 9.7V14.3A1.2 1.2 0 0 1 13.8 15.5H10.2A1.2 1.2 0 0 1 9 14.3V9.7A1.2 1.2 0 0 1 10.2 8.5Z")],
    "dynamic": [("s", "M5.5 4.5H18.5A2.5 2.5 0 0 1 21 7V17A2.5 2.5 0 0 1 18.5 19.5H5.5A2.5 2.5 0 0 1 3 17V7A2.5 2.5 0 0 1 5.5 4.5Z"), ("s", "M12 8v8M10 10l2-2 2 2M10 14l2 2 2-2"), ("k", "M7 9.5H7A1 1 0 0 1 8 10.5V13.5A1 1 0 0 1 7 14.5H7A1 1 0 0 1 6 13.5V10.5A1 1 0 0 1 7 9.5Z"), ("k", "M17 9.5H17A1 1 0 0 1 18 10.5V13.5A1 1 0 0 1 17 14.5H17A1 1 0 0 1 16 13.5V10.5A1 1 0 0 1 17 9.5Z")],
    "fast_forward": [("s", "M4 6.5 11 12l-7 5.5ZM12.5 6.5l7 5.5-7 5.5Z"), ("k", "M14.4 10.3 16.4 12l-2 1.7Z")],
    "history": [("s", "M5.6 8A7.5 7.5 0 1 1 4.5 12M4.5 4.5v3.5H8"), ("s", "M12 8v4l2.8 1.8"), ("k", "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z")],
    "hourglass": [("s", "M7 3.5h10M7 20.5h10M8 3.5c0 4.5 4 6 4 8.5s-4 4-4 8.5M16 3.5c0 4.5-4 6-4 8.5s4 4 4 8.5"), ("k", "M9.4 19c.5-1.6 2.6-2.5 2.6-2.5s2.1.9 2.6 2.5Z")],
    "labels": [("s", "M3.5 7.5a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z"), ("s", "M12.5 16.5a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z"), ("s", "M13.5 5.5h2.5a2.5 2.5 0 0 1 2.5 2.5v1.5M17 8l1.5 1.5L20 8"), ("s", "M10.5 18.5H8A2.5 2.5 0 0 1 5.5 16v-1.5M7 16l-1.5-1.5L4 16"), ("k", "M5.8 7.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z")],
    "language": [("s", "M5.5 4.5h13a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H11l-4.5 3.5v-3.5h-1a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z"), ("k", "M8 7.75H16A1 1 0 0 1 17 8.75V8.75A1 1 0 0 1 16 9.75H8A1 1 0 0 1 7 8.75V8.75A1 1 0 0 1 8 7.75Z"), ("k", "M8 11.25H12.5A1 1 0 0 1 13.5 12.25V12.25A1 1 0 0 1 12.5 13.25H8A1 1 0 0 1 7 12.25V12.25A1 1 0 0 1 8 11.25Z")],
    "layers": [("s", "M12 4H18A2.5 2.5 0 0 1 20.5 6.5V12.5A2.5 2.5 0 0 1 18 15H12A2.5 2.5 0 0 1 9.5 12.5V6.5A2.5 2.5 0 0 1 12 4Z"), ("s", "M8 8.5H5.5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V17"), ("k", "M13.6 7H16.4A1.1 1.1 0 0 1 17.5 8.1V10.9A1.1 1.1 0 0 1 16.4 12H13.6A1.1 1.1 0 0 1 12.5 10.9V8.1A1.1 1.1 0 0 1 13.6 7Z")],
    "leaf": [("s", "M5 19C5 10.5 10.5 5 19.5 4.5 19 13.5 13.5 19 5 19Z"), ("s", "M5 19l6-6"), ("k", "M13 9.4a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z")],
    "link": [("s", "M3.5 12a5.25 5.25 0 1 0 10.5 0a5.25 5.25 0 1 0 -10.5 0Z"), ("s", "M10 12a5.25 5.25 0 1 0 10.5 0a5.25 5.25 0 1 0 -10.5 0Z"), ("k", "M12 9.1A4 4 0 0 1 12 14.9A4 4 0 0 1 12 9.1Z")],
    "lock": [("s", "M7.5 10.5H16.5A2.5 2.5 0 0 1 19 13V18A2.5 2.5 0 0 1 16.5 20.5H7.5A2.5 2.5 0 0 1 5 18V13A2.5 2.5 0 0 1 7.5 10.5Z"), ("s", "M8.25 10.5V8a3.75 3.75 0 0 1 7.5 0v2.5"), ("k", "M12 13.5H12A1.25 1.25 0 0 1 13.25 14.75V16.25A1.25 1.25 0 0 1 12 17.5H12A1.25 1.25 0 0 1 10.75 16.25V14.75A1.25 1.25 0 0 1 12 13.5Z")],
    "log": [("s", "M8 3H16A2.5 2.5 0 0 1 18.5 5.5V18.5A2.5 2.5 0 0 1 16 21H8A2.5 2.5 0 0 1 5.5 18.5V5.5A2.5 2.5 0 0 1 8 3Z"), ("k", "M9.75 6.75H14.25A1.25 1.25 0 0 1 15.5 8V8A1.25 1.25 0 0 1 14.25 9.25H9.75A1.25 1.25 0 0 1 8.5 8V8A1.25 1.25 0 0 1 9.75 6.75Z"), ("s", "M9 12.5h6M9 16h3.5")],
    "memory": [("s", "M4.5 6.5H19.5A2 2 0 0 1 21.5 8.5V14A2 2 0 0 1 19.5 16H4.5A2 2 0 0 1 2.5 14V8.5A2 2 0 0 1 4.5 6.5Z"), ("s", "M6.5 16v3M10 16v3M14 16v3M17.5 16v3"), ("k", "M6.4 9.25H9.6A0.9 0.9 0 0 1 10.5 10.15V12.35A0.9 0.9 0 0 1 9.6 13.25H6.4A0.9 0.9 0 0 1 5.5 12.35V10.15A0.9 0.9 0 0 1 6.4 9.25Z"), ("k", "M14.4 9.25H17.6A0.9 0.9 0 0 1 18.5 10.15V12.35A0.9 0.9 0 0 1 17.6 13.25H14.4A0.9 0.9 0 0 1 13.5 12.35V10.15A0.9 0.9 0 0 1 14.4 9.25Z")],
    "mods": [("s", "M4.5 8.5h4.1a2.4 2.4 0 1 1 3.8 0h4.1v3.6a2.4 2.4 0 1 1 0 3.8v4.6h-12Z"), ("k", "M8.45 11.25H12.05A1.2 1.2 0 0 1 13.25 12.45V16.55A1.2 1.2 0 0 1 12.05 17.75H8.45A1.2 1.2 0 0 1 7.25 16.55V12.45A1.2 1.2 0 0 1 8.45 11.25Z")],
    "pack_in": [("s", "M15 4.5h3a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-3"), ("s", "M3.5 12h10M10 8.5l3.5 3.5-3.5 3.5"), ("k", "M16.75 9.5H16.75A1 1 0 0 1 17.75 10.5V13.5A1 1 0 0 1 16.75 14.5H16.75A1 1 0 0 1 15.75 13.5V10.5A1 1 0 0 1 16.75 9.5Z")],
    "pack_out": [("s", "M9 4.5H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h3"), ("s", "M10.5 12H20M16.5 8.5 20 12l-3.5 3.5"), ("k", "M7.5 9.5H7.5A1 1 0 0 1 8.5 10.5V13.5A1 1 0 0 1 7.5 14.5H7.5A1 1 0 0 1 6.5 13.5V10.5A1 1 0 0 1 7.5 9.5Z")],
    "passes": [("s", "M5 6H8A2 2 0 0 1 10 8V16A2 2 0 0 1 8 18H5A2 2 0 0 1 3 16V8A2 2 0 0 1 5 6Z"), ("s", "M16 6H19A2 2 0 0 1 21 8V16A2 2 0 0 1 19 18H16A2 2 0 0 1 14 16V8A2 2 0 0 1 16 6Z"), ("s", "M10 12h4"), ("k", "M17.25 9H17.75A1 1 0 0 1 18.75 10V14A1 1 0 0 1 17.75 15H17.25A1 1 0 0 1 16.25 14V10A1 1 0 0 1 17.25 9Z")],
    "pause": [("s", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z"), ("k", "M10 8.25H10A1.1 1.1 0 0 1 11.1 9.35V14.65A1.1 1.1 0 0 1 10 15.75H10A1.1 1.1 0 0 1 8.9 14.65V9.35A1.1 1.1 0 0 1 10 8.25Z"), ("k", "M14 8.25H14A1.1 1.1 0 0 1 15.1 9.35V14.65A1.1 1.1 0 0 1 14 15.75H14A1.1 1.1 0 0 1 12.9 14.65V9.35A1.1 1.1 0 0 1 14 8.25Z")],
    "picture": [("s", "M5.5 4.5H18.5A2.5 2.5 0 0 1 21 7V17A2.5 2.5 0 0 1 18.5 19.5H5.5A2.5 2.5 0 0 1 3 17V7A2.5 2.5 0 0 1 5.5 4.5Z"), ("s", "M6 16.5l4-4.5 3.5 3.5 2-2 2.5 3"), ("k", "M14 8.6a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z")],
    "pie": [("s", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z"), ("k", "M12.9 5.7A6.3 6.3 0 0 1 18.3 12.9H12.9Z")],
    "pipeline": [("s", "M3.5 6.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z"), ("s", "M14.5 17.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z"), ("s", "M9.5 6.5H13a4.5 4.5 0 0 1 4.5 4.5v3.5"), ("k", "M16.3 17.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0Z")],
    "prism": [("s", "M9.5 4 16.5 18.5H2.5Z"), ("s", "M14.6 13.2l6.9-2.2M15.6 15.8l5.9 1.4"), ("k", "M9.5 9.6 12.6 15.8H6.4Z")],
    "quality": [("s", "M7 4.5h10l3.5 5L12 20 3.5 9.5Z"), ("s", "M3.5 9.5h17"), ("k", "M9.4 11.6h5.2L12 16.6Z")],
    "resolution": [("s", "M4 8.5v-3A1.5 1.5 0 0 1 5.5 4h3M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3M20 15.5v3a1.5 1.5 0 0 1-1.5 1.5h-3M8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3"), ("k", "M9.75 8.25H14.25A1.5 1.5 0 0 1 15.75 9.75V14.25A1.5 1.5 0 0 1 14.25 15.75H9.75A1.5 1.5 0 0 1 8.25 14.25V9.75A1.5 1.5 0 0 1 9.75 8.25Z")],
    "rumble": [("s", "M9.5 4.5H14.5A2.5 2.5 0 0 1 17 7V17A2.5 2.5 0 0 1 14.5 19.5H9.5A2.5 2.5 0 0 1 7 17V7A2.5 2.5 0 0 1 9.5 4.5Z"), ("s", "M3.8 9.5c-.9 1.6-.9 3.4 0 5M20.2 9.5c.9 1.6.9 3.4 0 5"), ("k", "M10.85 7.5H13.15A1.1 1.1 0 0 1 14.25 8.6V10.9A1.1 1.1 0 0 1 13.15 12H10.85A1.1 1.1 0 0 1 9.75 10.9V8.6A1.1 1.1 0 0 1 10.85 7.5Z")],
    "save_down": [("s", "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V7.5A1.5 1.5 0 0 1 19 9H5A1.5 1.5 0 0 1 3.5 7.5V6A1.5 1.5 0 0 1 5 4.5Z"), ("s", "M5 9v8.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"), ("k", "M8.8 11.2H15.2L12 15.7Z")],
    "save_up": [("s", "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V7.5A1.5 1.5 0 0 1 19 9H5A1.5 1.5 0 0 1 3.5 7.5V6A1.5 1.5 0 0 1 5 4.5Z"), ("s", "M5 9v8.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"), ("k", "M12 10.9 15.2 15.4H8.8Z")],
    "saves": [("s", "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V7.5A1.5 1.5 0 0 1 19 9H5A1.5 1.5 0 0 1 3.5 7.5V6A1.5 1.5 0 0 1 5 4.5Z"), ("s", "M5 9v8.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"), ("k", "M10.5 11.75H13.5A1 1 0 0 1 14.5 12.75V12.75A1 1 0 0 1 13.5 13.75H10.5A1 1 0 0 1 9.5 12.75V12.75A1 1 0 0 1 10.5 11.75Z")],
    "scale": [("s", "M7 3.5H17A3.5 3.5 0 0 1 20.5 7V17A3.5 3.5 0 0 1 17 20.5H7A3.5 3.5 0 0 1 3.5 17V7A3.5 3.5 0 0 1 7 3.5Z"), ("s", "M9.5 7.5H14.5A2 2 0 0 1 16.5 9.5V14.5A2 2 0 0 1 14.5 16.5H9.5A2 2 0 0 1 7.5 14.5V9.5A2 2 0 0 1 9.5 7.5Z"), ("k", "M11.15 10.25H12.85A0.9 0.9 0 0 1 13.75 11.15V12.85A0.9 0.9 0 0 1 12.85 13.75H11.15A0.9 0.9 0 0 1 10.25 12.85V11.15A0.9 0.9 0 0 1 11.15 10.25Z")],
    "scene": [("s", "M12 3.5 19.5 7.5v9L12 20.5 4.5 16.5v-9Z"), ("s", "M4.5 7.5 12 11.5l7.5-4M12 11.5v9"), ("k", "M12 5.6 15.6 7.5 12 9.4 8.4 7.5Z")],
    "shortcut": [("s", "M4 10.5 12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19Z"), ("k", "M10.4 11.6 14.6 14.2 10.4 16.8Z")],
    "sun": [("s", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z"), ("k", "M10.1 12a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0Z"), ("s", "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4")],
    "terminal": [("s", "M5.5 4.5H18.5A2.5 2.5 0 0 1 21 7V17A2.5 2.5 0 0 1 18.5 19.5H5.5A2.5 2.5 0 0 1 3 17V7A2.5 2.5 0 0 1 5.5 4.5Z"), ("s", "M7 9.5l3 2.5-3 2.5"), ("k", "M12.9 13.6H16.1A0.9 0.9 0 0 1 17 14.5V14.5A0.9 0.9 0 0 1 16.1 15.4H12.9A0.9 0.9 0 0 1 12 14.5V14.5A0.9 0.9 0 0 1 12.9 13.6Z")],
    "timing": [("s", "M5 13.5a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z"), ("s", "M12 13.5V10M10 3.5h4M17.3 7.2l1.4-1.4"), ("k", "M10.5 13.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z")],
    "upscale": [("s", "M7 3.5H17A3.5 3.5 0 0 1 20.5 7V17A3.5 3.5 0 0 1 17 20.5H7A3.5 3.5 0 0 1 3.5 17V7A3.5 3.5 0 0 1 7 3.5Z"), ("s", "M12.5 11.5 16.5 7.5M13 7.5h3.5V11"), ("k", "M7.6 13H9.9A1.1 1.1 0 0 1 11 14.1V16.4A1.1 1.1 0 0 1 9.9 17.5H7.6A1.1 1.1 0 0 1 6.5 16.4V14.1A1.1 1.1 0 0 1 7.6 13Z")],
    "users": [("s", "M5.5 8.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z"), ("s", "M3 19.5c.7-3.2 3-5 6-5s5.3 1.8 6 5"), ("s", "M15.5 4.6a3 3 0 1 1 0 5.8M17.5 14.6c2 .6 3.2 2.3 3.6 4.4"), ("k", "M7.7 8.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0Z")],
    "warning": [("s", "M12 4 21 19.5H3Z"), ("s", "M12 10v4"), ("k", "M10.7 16.7a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0Z")],
    "window": [("s", "M5.5 4.5H18.5A2.5 2.5 0 0 1 21 7V17A2.5 2.5 0 0 1 18.5 19.5H5.5A2.5 2.5 0 0 1 3 17V7A2.5 2.5 0 0 1 5.5 4.5Z"), ("s", "M3 9h18"), ("k", "M7.6 12H12.4A1.1 1.1 0 0 1 13.5 13.1V15.4A1.1 1.1 0 0 1 12.4 16.5H7.6A1.1 1.1 0 0 1 6.5 15.4V13.1A1.1 1.1 0 0 1 7.6 12Z")],

    # Stats page tiles
    # the SoC temperature: a thermometer, its bulb the kernel (#523, drawn by hand in this style by intermission88)
    "temp": [("s", "M10 13.6V5.5a2 2 0 0 1 4 0v8.1a4 4 0 1 1 -4 0Z"), ("k", "M10.25 17a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0 -3.5 0Z")],
    # the battery and its power: a battery, its charge the kernel (#523, drawn by hand in this style by intermission88)
    "battery": [("s", "M5.5 7h11a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-11a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2ZM21 10.5v3"), ("k", "M7.25 10H12.25A0.75 0.75 0 0 1 13 10.75V13.25A0.75 0.75 0 0 1 12.25 14H7.25A0.75 0.75 0 0 1 6.5 13.25V10.75A0.75 0.75 0 0 1 7.25 10Z")],
    # the frame time: a stopwatch, its hub the kernel (#523, drawn by hand in this style by intermission88)
    "frame": [("s", "M5 13.5a7 7 0 1 0 14 0a7 7 0 1 0 -14 0ZM10 3.5h4M12 3.5v3M12 13.5l2.75 -2.75"), ("k", "M10.6 13.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z")],
    # the GPU: a graphics card with its fan, the fan's hub the kernel (#523, drawn by hand in this style by intermission88)
    "gpu": [("s", "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2ZM7 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0ZM21.25 9.5v5M7 17v2.5M11 17v2.5"), ("k", "M8.75 12a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z")],
    # the CPU: a chip with its pins, its die the kernel (#523, drawn by hand in this style by intermission88)
    "cpu": [("s", "M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2ZM10 4v3M14 4v3M10 17v3M14 17v3M4 10h3M4 14h3M17 10h3M17 14h3"), ("k", "M11 10.25H13A0.75 0.75 0 0 1 13.75 11V13A0.75 0.75 0 0 1 13 13.75H11A0.75 0.75 0 0 1 10.25 13V11A0.75 0.75 0 0 1 11 10.25Z")],
}


def vector(layers):
    rows = []
    for kind, d in layers:
        if kind == "s":
            rows.append(f'    <path android:pathData="{d}" android:strokeColor="?attr/pnIconInk" android:strokeWidth="{STROKE}"\n'
                        f'        android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>')
        elif kind == "k":
            rows.append(f'    <path android:pathData="{d}" android:fillColor="?attr/pnIconKernel"/>')
        elif kind == "f":
            rows.append(f'    <path android:pathData="{d}" android:fillColor="?attr/pnIconInk"/>')
        else:
            raise ValueError(f"a layer is s, k or f, not {kind!r}")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            + HEAD + '\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
            + "\n".join(rows) + "\n</vector>\n")


def files():
    """pi_<name>.xml -> its text, for every icon"""
    return {f"pi_{name}.xml": vector(layers) for name, layers in ICONS.items()}


def main(argv):
    check = argv[1:] == ["--check"]
    if argv[1:] and not check:
        raise SystemExit("usage: icons.py [--check]")
    want = files()
    have = {p.name for p in OUT.glob("pi_*.xml")}
    wrong = sorted(n for n, text in want.items() if not (OUT / n).is_file() or (OUT / n).read_bytes() != text.encode("utf-8"))
    extra = sorted(have - set(want))
    if check:
        for n in wrong:
            print(f"{n}: not what icons.py writes (run peanuts/scripts/icons.py)")
        for n in extra:
            print(f"{n}: no icon in icons.py names it")
        return 1 if wrong or extra else 0
    for n in wrong:
        (OUT / n).write_text(want[n], encoding="utf-8", newline="\n")
    for n in extra:
        (OUT / n).unlink()
    print(f"{len(want)} icons, {len(wrong)} written, {len(extra)} removed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
