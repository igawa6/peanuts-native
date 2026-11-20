#!/usr/bin/env python3
"""Writes the GitHub issue forms (.github/ISSUE_TEMPLATE/<n>_*.yml) from one source, so the fields the forms share stay alike.

    python3 peanuts/scripts/issue_forms.py          # write the forms
    python3 peanuts/scripts/issue_forms.py --check  # exit 1 if a form differs from what this script writes

The games and their versions are the game catalog's (peanuts/scripts/games_catalog.py: the shipped games, each version Android plays), the
newest app version is peanuts/VERSION. .github/workflows/catalog.yml runs this script and the catalog's on every push to main and commits
what changed, so a game added or removed needs no step by hand.
The fields come from what the issues so far had to ask for again and again (the log of the run, the driver really used, the settings,
a rebuild after an update, where the game and the build are, an older version that worked). Every form keeps free text boxes for more.
"""
from pathlib import Path
import importlib.util
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
FORMS = ROOT / '.github/ISSUE_TEMPLATE'
REPO = 'https://github.com/igawa6/peanuts-native'
OTHER_VERSION = 'One of these games, but another update or the base game (say which below)'
ANOTHER_GAME = 'Another game (say which below)'

_spec = importlib.util.spec_from_file_location('games_catalog', Path(__file__).with_name('games_catalog.py'))
catalog = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(catalog)


# ---- Inputs ----

def game_versions(root=ROOT):
    """One option per game and version this app plays, in name order, the newest version first"""
    out = []
    for g in catalog.build(root, warn=lambda m: None)['games']:
        for b in g['builds']:
            shown = b['display'] or f'update v{b["number"]}'
            out.append(f'{g["name"]} {shown} ({g["title_id"]})')
    return out


def versions(root=ROOT):
    """The release options: the newest (peanuts/VERSION) and the two before it, then older ones and test builds"""
    v = (root / 'peanuts/VERSION').read_text(encoding='utf-8').strip()
    major, minor, patch = (int(x) for x in v.split('.'))
    out = [f'{v} (the newest)']
    out += [f'{major}.{minor}.{p}' for p in range(patch - 1, max(patch - 3, -1), -1)]
    if patch - 3 >= 0:
        out.append(f'{major}.{minor}.{patch - 3} or older')
    out.append('A test or development build (say which in "Anything else")')
    return out


# ---- Elements ----

def md(text):
    return {'type': 'markdown', 'attributes': {'value': text}}


def field(kind, id_, label, description=None, placeholder=None, value=None, options=None, multiple=False, required=False):
    a = {'label': label}
    if description: a['description'] = description
    if multiple: a['multiple'] = True
    if options is not None: a['options'] = options
    if placeholder: a['placeholder'] = placeholder
    if value: a['value'] = value
    e = {'type': kind, 'id': id_, 'attributes': a}
    if required: e['validations'] = {'required': True}
    return e


def text(id_, label, description=None, placeholder=None, value=None, required=False):
    return field('textarea', id_, label, description, placeholder, value, required=required)


def line(id_, label, description=None, placeholder=None, required=False):
    return field('input', id_, label, description, placeholder, required=required)


def save_zip(label, why):
    """The optional save field (Game problem, Setup or app problem): the app's Back up saves, or the backup folder with its manifest zipped"""
    return text('save', label, why + (
        "Hold the game's cover › **Saves** › **Back up saves**. If it makes a `.zip`, drag that file into this box. Otherwise zip the newest "
        "folder in `Switch2/save-backups/<title ID>/` together with the `.manifest.tsv` beside it (then **Import save** checks every file), "
        "and drag the zip in. GitHub takes files up to 25 MB.\n"
        "A save holds your progress, not game data, so it is fine to attach; it can hold the names you gave in the game.\n"),
        'Drag the save .zip here')


def pick(id_, label, options, description=None, multiple=False, required=True):
    return field('dropdown', id_, label, description, options=options, multiple=multiple, required=required)


def checks(id_, label, options, description=None):
    """options: [(text, required)]"""
    return {'type': 'checkboxes', 'id': id_, 'attributes': {'label': label, **({'description': description} if description else {}),
                                                              'options': [{'label': t, **({'required': True} if r else {})} for t, r in options]}}


LANGUAGE = 'Write in English if you can; another language is fine too.'
NO_FILES = 'Never attach game files, keys, firmware or anything extracted from a game. Logs, saves and screenshots are fine.'


def rules(play='I play a dump of a game I own, made with my own console.'):
    return checks('rules', 'Rules', [(play, True), ('I am not attaching, offering or linking game files, keys or firmware.', True)],
                  'Posts that share, offer or ask for game files, keys or links are edited or removed.')


def what(description):
    return text('what', 'What happened', description, required=True)


OFTEN = pick('often', 'How often', ['Every time', 'Sometimes', 'Once so far'])

REGRESSION = pick('regression', 'Did it work before?', [
    'It never worked, or this is the first try',
    'It worked in an older app version (say which in "Anything else")',
    'It worked before a game update',
    'It worked before I changed a setting, the driver or a mod',
    "I don't know",
])


def game_fields(which):
    """The game and its version as one pick (a form cannot make one list depend on another), and a line for one that is not listed"""
    return [
        pick('game', 'Game and version', game_versions() + [OTHER_VERSION, ANOTHER_GAME],
             f'Every game with every update this app version plays: pick {which}, as its card shows it. Not listed? Pick one of the last '
             'two lines and write it below.'),
        line('game_other', 'Game and version, if not in the list', 'The name, the title ID (16 characters, on the game\'s sheet) and the '
             'update, in either form (1.2.2 or v393216).', 'Super Mario Bros. Wonder, 010015100B514000, 1.2.2'),
    ]


def release():
    return pick('release', 'Peanuts Native version', versions(),
                'Settings › About shows it. In an older version, please try the newest first: many problems are fixed there.')


DEVICE = [
    pick('device', 'Device', [
        'AYN Thor',
        'AYN Odin (2, 3 or another)',
        'Retroid Pocket Duo',
        'Retroid Pocket with the Dual Screen add-on',
        'Another Retroid Pocket',
        'AYANEO Pocket DS',
        'Another handheld',
        'Phone',
        'Foldable phone',
        'Tablet',
    ]),
    line('model', 'Model', 'The exact model, and for the Thor its variant (Base, Pro or Max).', 'AYN Thor Max', required=True),
    pick('chip', 'Chip', [
        'Snapdragon 8 Elite Gen 5 (Adreno 840)',
        'Snapdragon 8 Elite (Adreno 830)',
        'Snapdragon 8s Gen 4 (Adreno 825)',
        'Snapdragon 8 Gen 3 (Adreno 750)',
        'Snapdragon 8s Gen 3 (Adreno 735)',
        'Snapdragon 8 Gen 2 (Adreno 740), as in the AYN Thor',
        'Snapdragon 8 Gen 1 or 8+ Gen 1 (Adreno 730)',
        'Snapdragon 888, 870 or 865 (Adreno 660 or 650)',
        'Snapdragon 7 series (for example 7+ Gen 2 or 7+ Gen 3)',
        'Snapdragon G series (G3x Gen 2, G3 Gen 3, ...)',
        'Qualcomm Dragonwing (as in the Retroid Pocket Duo)',
        'Snapdragon 6 or 4 series, or older',
        'MediaTek Dimensity or Helio (Mali GPU)',
        'Samsung Exynos (Xclipse or Mali GPU)',
        'Google Tensor',
        "Another chip, or I don't know",
    ], 'The device\'s settings show it under About phone, or look the model up. The chip, not the GPU alone.'),
    pick('ram', 'RAM', ['Less than 8 GB', '8 GB (Android may show 7.x GB)', '12 GB', '16 GB', '24 GB or more']),
    pick('android', 'Android version', ['Android 13', 'Android 14', 'Android 15', 'Android 16', 'Android 17', 'Newer than Android 17',
                                        'Older than Android 13']),
]

DRIVER = [
    pick('driver', 'GPU driver (Settings › GPU driver, or the game\'s own)', [
        'System driver',
        'Turnip by Mr. Purple (T-numbers, for example T30)',
        'Turnip Gen8 by StevenMXZ (V-numbers, for example V39)',
        'Another Turnip (mainline Mesa, TurnipZ, Turnip Duo, ...)',
        'PanVK (Mali)',
        'RADV (Xclipse)',
        'Another custom driver',
        "I don't know",
    ], 'The one the game used: a game can have its own. Its log says which driver it really loaded.'),
    line('driver_version', 'Driver name and version, if not the system driver', 'As Settings › GPU driver lists it.', 'Turnip Gen8 V39'),
]

READINESS = pick('readiness', 'Readiness check (Settings › Readiness check)', ['Ready', 'Ready, with limits', 'Not ready', 'Not run'],
                 'Its **Copy report** copies the full text: paste it into "Anything else" when it says more than Ready.')

STORAGE = ['Internal storage', 'microSD card', 'USB drive or SSD', "I don't know"]

LOG_BLOCK = '<details>\n<summary>Log (click to expand)</summary>\n\n```text\npaste the log here, or delete this block when you attach the file\n```\n\n</details>\n'

BUILD_LOG_HOW = ("Tap **Share build log** on the game's card on Home (it shows while a build has stopped or failed), on the Prepare card, or hold the "
                 "game's cover › **Storage** › **Share build log**; then **Save to a folder**, and drag that .zip into this box as it is.")


def searched(extra=()):
    return checks('checks', 'Before sending', [('I searched the open issues, and this is not one of them.', False)] + list(extra))


def anything(description):
    return text('extra', 'Anything else', description)


# ---- The forms ----

def game_problem():
    return {
        'name': 'Game problem',
        'description': 'A game crashes, freezes, shows a black screen, draws wrong, runs slowly, sounds wrong, or its controls or saves misbehave',
        'labels': ['bug'],
        'body': [
            md('Thanks for the report. Your device is probably not the one this was tested on, so these details are what a problem is traced '
               'with. Most fields are a pick from a list.\n'
               '**Adding a game went wrong** (the import, Prepare or the companion)? Use **Build problem**. **Keys, firmware, the library, '
               'storage, a driver or companion install, saves import** or the app itself? Use **Setup or app problem**.\n'
               f'{NO_FILES} {LANGUAGE}\n'),
            what('What you did and what you saw, and where in the game (a level, a menu, a scene, a race). Text on the screen is worth '
                 'copying in, word for word.'),
            pick('kind', 'What goes wrong', [
                'The game closes or crashes',
                'The game freezes (the picture stops; say whether sound goes on)',
                'Black or blank screen',
                'Objects, characters, water or sky are black, missing or invisible',
                'Flicker, glow, particles or effects look wrong',
                'Text, menus or videos look wrong',
                'It runs slowly, stutters or the frame rate drops',
                'The game runs too fast or too slow (its speed, not the frame rate)',
                'The device gets hot, or the app says it is too hot',
                'Sound (crackling, missing, out of sync)',
                'Controls, gyro or touch',
                'Saves (not saved, lost, or will not load)',
                'The companion on the second screen',
                'Something else (say what in "What happened")',
            ], multiple=True, description='Pick all that apply.'),
            pick('when', 'When', [
                'When the game starts, before its title screen',
                'In the game\'s menus',
                'While playing',
                'After playing for a while (minutes or hours)',
                'After coming back to the game (from Home, or closing and opening the lid)',
                'Other (say when in "What happened")',
            ]),
            OFTEN,
            REGRESSION,
            *game_fields('the one the game runs with'),
            release(),
            *DEVICE,
            *DRIVER,
            pick('driver_tried', 'With another driver', [
                'The same problem with another driver',
                'Only with this driver',
                'Not tried',
            ]),
            READINESS,
            pick('settings', 'Settings you changed for this game', [
                'Nothing, all as they came',
                'Console mode (docked or handheld)',
                'Resolution, its scale, or Dynamic resolution',
                'Upscaler or FSR sharpness',
                'Frame rate cap (not "Game owned")',
                'Hold 60 fps',
                'Frame generation',
                'Use fast cores',
                'RAM saver',
                'Advanced switches, Tester settings or extra environment',
                'Swap screens',
                'Mods (say which in "Anything else")',
            ], 'Pick all that apply, and give the values in "Anything else".', multiple=True, required=False),
            pick('controls', 'Controls used, if the problem is with controls', [
                'The device\'s own buttons',
                'A Bluetooth controller (Switch Pro, Xbox, DualSense, ...)',
                'A USB controller',
                'On-screen controls',
                'The device\'s gyro',
                'A controller\'s gyro',
            ], multiple=True, required=False),
            text('log', 'Log of the run', (
                "Right after the problem, before you start the game again: hold the game's cover › **Storage** › **Share log** (that game's "
                "newest run), or Settings › **Share log**. After \"The game stopped unexpectedly\", the dialog's **Share log** works too. Its "
                "file name starts with the game's title ID.\n"
                "Best: save the file, zip it and drag the .zip into this box. Or paste the whole log into the block below (it is collapsed in "
                "the issue), and keep the `<details>` lines.\n"), value=LOG_BLOCK, required=True),
            text('build_log', 'Build log, if the problem began after a build',
                 'When the game ran before and the problem began after it was built, prepared or updated again. ' + BUILD_LOG_HOW,
                 'Drag the build log .zip here'),
            save_zip('Save zip, to reproduce it', (
                "With your save, the problem can be played on another device instead of guessed at: most of all when it happens at a certain "
                "place or point in the game (for a crash before the title screen it is not needed). Back up right where the problem starts.\n")),
            line('save_from', 'From the save to the problem',
                 'Which slot or file to load and what to do from there. Say so if the save comes from another game version than the one '
                 'picked above, a console or an emulator.', 'Slot 2: walk north out of the village; it freezes on the bridge'),
            anything('The values of the settings you changed, the readiness report, a phone cooler or charger, other apps open, screenshots '
                     'or a short video (a link to YouTube or similar is fine).'),
            searched([
                ("The log is from the run with the problem, of this game (its file name starts with the game's title ID).", True),
                ("I built the game again after the last app update (hold the cover › Storage › Build again), or it was built with this version.",
                 False),
            ]),
            rules(),
        ],
    }


def build_problem():
    return {
        'name': 'Build problem',
        'description': 'Adding a game went wrong: the import, Prepare or the companion failed, stopped or never finished, or the app closed during it',
        'labels': ['bug'],
        'body': [
            md('Thanks for the report. A build is traced with its **build log**, so that field matters most. Most other fields are a pick '
               'from a list.\n'
               '**The game was built and then misbehaves** when you play? Use **Game problem**. **The game is not listed, has no Build '
               'button, or says Wrong version, File gone or Not Supported Yet**? Use **Setup or app problem**.\n'
               f'{NO_FILES} The build log holds none of them. {LANGUAGE}\n'),
            what('What you did and what you saw.'),
            line('message', 'The message on the card', 'Word for word, as the game\'s card or the Prepare card shows it.',
                 'Finding the shaders stopped'),
            pick('stage', 'Where it stopped', [
                'Importing (reading the NSP, XCI or dump folder)',
                'Rewriting the game\'s code',
                'Finding or translating the shaders',
                'Companion (downloading or installing it)',
                'It finished, but the card still asks to build or prepare',
                "I don't know",
            ], 'The step the card showed last, with its percentage in "What happened" if it had one.'),
            pick('outcome', 'What the build did', [
                'It failed with a message',
                'It stopped or paused, and Resume does not get further',
                'The progress stands still for a long time',
                'The app closed during it',
                'The device restarted, or got very hot',
                'Something else (say what in "What happened")',
            ]),
            OFTEN,
            REGRESSION,
            *game_fields('the one you built (another update means Build anyway)'),
            pick('source', 'What you built it from', [
                'An NSP',
                'An XCI',
                'A dump folder (exefs and romfs)',
                'A .nup package',
                'Something else (say what in "What happened")',
            ]),
            pick('mods', 'Mods', ['No mods', 'With mods (say which and their version in "What happened")']),
            pick('game_storage', 'Where the game files are', STORAGE),
            pick('build_storage', 'Where the build goes (Settings › Library › Build folder)', STORAGE),
            line('free', 'Free space before the build', 'Roughly, on the storage the build went to. The build log\'s summary has it too.',
                 '40 GB'),
            pick('other_apps', 'Other apps during the build', [
                'None, only Peanuts Native',
                'Some apps in the background',
                'Another game or an emulator',
                'I used the device for something else meanwhile',
            ]),
            release(),
            *DEVICE,
            READINESS,
            text('build_log', 'Build log', BUILD_LOG_HOW + (
                '\nIt holds the build\'s own log, its step logs, the job\'s state and a summary (the app, the device, memory, free space, and '
                'why Android ended the app if it did). Never keys, game files, `.nup` files or saves. If you tried again since, that is fine: '
                'the log keeps the recent tries one after the other.\n'), 'Drag the build log .zip here', required=True),
            anything('The readiness report, what you tried (Resume, Build again, a restart), whether another game builds, a screenshot of the '
                     'card.'),
            searched([("The build log is of this game (its file name has the game's title ID).", True)]),
            rules('I build from a dump of a game I own, made with my own console.'),
        ],
    }


def setup_problem():
    return {
        'name': 'Setup or app problem',
        'description': 'Keys, firmware, the game folder and library, storage, a GPU driver, a companion or mod install, saves, the second screen, '
                       'or the app itself',
        'labels': ['bug', 'app'],
        'body': [
            md('Thanks for the report. For a game that misbehaves while you play, use **Game problem**; for a build that fails, '
               '**Build problem**.\n'
               f'{NO_FILES} {LANGUAGE}\n'),
            pick('area', 'What it is about', [
                'Keys (prod.keys)',
                'Firmware',
                'The game folder or the library (a game is missing, has no cover, or its base, update or DLC is the wrong one)',
                'A cover says Wrong version, File gone or Not Supported Yet',
                'Storage (microSD, a USB drive, the build folder, file access)',
                'Installing or picking a GPU driver',
                'Installing or updating a companion',
                'Installing a mod',
                'Saves (Back up saves, Restore saves, Import save, or a save from an emulator)',
                'The second screen (which display shows what, Swap screens, an add-on screen)',
                'A warning that the device is too hot',
                'The app itself (it closes, a setting does not stick, the overlay, an app update)',
                'Something else',
            ]),
            what('What you did, step by step, and what you saw.'),
            line('message', 'The message the app shows', 'Word for word, for example the text on the Keys or Firmware row.',
                 'The importer does not accept these keys'),
            OFTEN,
            REGRESSION,
            line('firmware', 'Firmware version, for keys and firmware problems', 'As the Firmware row shows it.', '22.5.0'),
            pick('same_console', 'Keys and firmware, for keys and firmware problems', [
                'Both from my console, dumped at the same system version',
                'Both from my console, dumped at different times',
                "I don't know",
            ], 'Keys and firmware have to match: firmware newer than the keys cannot be read.', required=False),
            pick('storage', 'Storage involved', STORAGE, 'Pick all that apply.', multiple=True, required=False),
            line('game', 'Game, if one is involved', 'Its name, title ID and version, as its card or sheet shows them.',
                 'Super Mario Odyssey, 0100000000010000, 1.4.1'),
            release(),
            *DEVICE,
            *DRIVER,
            READINESS,
            text('log', 'Log or screenshots', (
                "Settings › **Share log**, or a screenshot of the screen with the message. For storage problems, the folder's path as the app "
                "shows it. For the library, the folder tree as text (names only, never the files).\n"), 'Drag the log or screenshots here'),
            save_zip('Save zip, for a save problem', (
                "When a save does not back up, restore, import or load: the save it is about, so the same steps can be tried with it. A save "
                "from a console or an emulator: zip it as you tried to import it, and say where it came from in \"What happened\".\n")),
            anything('The readiness report, what you tried, anything that differs from a usual setup (a launcher, a custom ROM, a file '
                     'manager).'),
            searched(),
            rules(),
        ],
    }


def builder_problem():
    return {
        'name': 'Desktop builder problem',
        'description': 'Building a game on a computer (BUILDER.md) or another tool of the repository fails',
        'labels': ['bug'],
        'body': [
            md(f'Building on the device? Use **Build problem**. The desktop builder is described in [BUILDER.md]({REPO}/blob/main/peanuts/docs/'
               'BUILDER.md).\n'
               'Give paths only as far as they matter, and never attach game files, keys or anything the builder extracted from a game. '
               f'{LANGUAGE}\n'),
            what('What you ran and what happened.'),
            pick('os', 'Computer', ['Linux', 'macOS (Apple silicon)', 'macOS (Intel)', 'Windows', 'Windows with WSL', 'Something else']),
            line('os_version', 'System version', 'For example the distribution and its version.', 'Ubuntu 24.04'),
            line('source', 'Source', 'The release or the commit you built from (git rev-parse --short HEAD).', 'v0.1.5 or 04cad56'),
            line('command', 'Command', 'The command line as you ran it.', 'peanuts/scripts/peanuts-build.sh "Game.nsp" "Game-update.nsp" --push',
                 required=True),
            line('tools', 'Tool versions', 'Python, .NET and whatever else the failing step uses.', 'Python 3.14.0, .NET 10.0.100'),
            line('game', 'Game, if one is involved', 'Its name, title ID and version.', 'Super Mario Odyssey, 0100000000010000, 1.4.1'),
            pick('device_push', 'The device, if the build is pushed to one', ['No device involved', 'AYN Thor', 'Another device (say which below)'],
                 required=False),
            text('output', 'The output', 'The whole console output of the failing step, from its start.', value=LOG_BLOCK, required=True),
            anything('What you tried, a survey report, anything that differs from BUILDER.md\'s setup.'),
            searched(),
            checks('rules', 'Rules', [('I am not attaching, offering or linking game files, keys, firmware or anything extracted from a game.',
                                       True)]),
        ],
    }


def game_request():
    return {
        'name': 'Game request',
        'description': 'Ask for a game the app does not have yet, or another version of a game it has',
        'labels': ['new-game'],
        'body': [
            md('Every game needs a profile in the app, and a new one takes from a day to weeks, so a request is a wish, not a promise. '
               'One request per game: first search the open issues, and add a 👍 to an existing request instead of a new one.\n'
               f'Want to try it yourself? [NEW-GAME.md]({REPO}/blob/main/peanuts/docs/NEW-GAME.md) says how a profile is made.\n'
               f'**No game files, keys, firmware or links to them**, and no asking where to get them. {LANGUAGE}\n'),
            pick('kind', 'What you ask for', [
                'A game the app does not have yet',
                'Another version (update) of a game it has',
                'DLC of a game it has',
                'A game it has, with a mod that needs another version (for example CTGP)',
            ]),
            line('name', 'Game', 'Its name as the Switch shows it.', 'Hyrule Warriors: Definitive Edition', required=True),
            line('title_id', 'Title ID', 'The 16 characters the game\'s sheet shows (for example under Not Supported Yet).',
                 '0100A1B2C3D4E000', required=True),
            line('version', 'Version you have', 'The update you would play it with.', '1.0.6'),
            pick('runs_elsewhere', 'Where it runs today', [
                'On a Switch emulator (Eden, ...)',
                'Only on the console',
                "I don't know",
            ], multiple=True, required=False),
            text('why', 'Why this game', 'What makes it worth adding, for example it runs badly elsewhere, or it has a second-screen use.'),
            line('device', 'Your device', 'If it matters, for example a low-RAM device.', 'AYN Thor Base'),
            anything('Anything that helps: what you know of the game\'s engine, a test you made, a link to a page about the game (never to '
                     'files).'),
            checks('checks', 'Before sending', [('I searched the open issues, and there is no request for this game yet.', True)]),
            rules(),
        ],
    }


def feature_request():
    return {
        'name': 'Feature request',
        'description': 'Suggest something the app could do: performance, picture, controls, the second screen, the library, builds, mods, '
                       'saves and more',
        'labels': ['feature-request'],
        'body': [
            md('One idea per post: it is easier to discuss and to close when done. First search the open issues, and add a 👍 to an existing '
               f'one instead of a new one. {LANGUAGE}\n'),
            pick('area', 'Area', [
                'Frame rate, speed or frame generation',
                'Picture: resolution, upscaling, HDR, effects',
                'Controls, gyro, controllers or on-screen layouts',
                'The second screen or companions',
                'The library, game formats or importing (for example compressed games)',
                'Builds: speed, storage, the build folder',
                'Mods',
                'Saves',
                'GPU drivers',
                'Online or local multiplayer',
                'The app\'s screens and settings',
                'Building on a computer (the desktop builder)',
                'Something else',
            ]),
            text('idea', 'The idea', 'What the app should do, as you would use it.', required=True),
            text('why', 'Why', 'What it would make possible or easier, and for which games or devices.'),
            line('device', 'Your device', 'If it matters.', 'AYN Thor Pro'),
            anything('How another app does it, a sketch or a screenshot, details of how it should behave.'),
            checks('checks', 'Before sending', [('I searched the open issues, and this is not one of them.', True)]),
            checks('rules', 'Rules', [("My post has no game files, keys, firmware or links to them, and doesn't ask for any.", True)]),
        ],
    }


def question():
    return {
        'name': 'Question, device report or development topic',
        'description': 'Ask how something works, tell how a game runs on your device, or discuss how to build or contribute something',
        'body': [
            md('For a problem, use **Game problem**, **Build problem**, **Setup or app problem** or **Desktop builder problem**: their logs '
               'and details are what a problem is traced with. For a wish, **Game request** or **Feature request**.\n'
               '**No game files, keys, firmware or links to them**: don\'t share them, offer them or ask where to get them. Peanuts Native '
               f'works with your own dump of a game you own. Posts that break this are edited or removed. {LANGUAGE}\n'),
            pick('kind', 'What is this?', [
                'A question about using Peanuts Native',
                'A device report: how a game runs on my device',
                'A development topic (how to build, change or contribute something)',
                'A mistake in the README, the FAQ or the wiki',
                'Something else',
            ]),
            text('body', 'Your post', 'What you want to know or tell, and why. For a device report: the game and its version, the device, the '
                                      'driver, the settings and how it runs. For development, the part of the code you are looking at and '
                                      'what you have read or tried so far.', required=True),
            line('context', 'Device, app version and game, if they matter', placeholder='AYN Thor Max, Peanuts Native 0.1.5, Mario Kart 8 Deluxe'),
            text('links', 'Logs, screenshots or links', 'Anything that goes with the post.'),
            checks('rules', 'Rules', [("My post has no game files, keys, firmware or links to them, and doesn't ask for any.", True)]),
        ],
    }


FILES = {
    '1_game_problem.yml': game_problem,
    '2_build_problem.yml': build_problem,
    '3_setup_problem.yml': setup_problem,
    '4_builder_problem.yml': builder_problem,
    '5_game_request.yml': game_request,
    '6_feature_request.yml': feature_request,
    '7_question.yml': question,
}


# ---- YAML ----

def q(s):
    return json.dumps(s, ensure_ascii=False)


def block(lines, key, s, indent):
    lines.append(' ' * indent + f'{key}: |')
    for ln in s.rstrip('\n').split('\n'):
        lines.append((' ' * (indent + 2) + ln) if ln else '')


def render(form):
    out = ['# Written by peanuts/scripts/issue_forms.py: change that script, then run it.', f'name: {q(form["name"])}',
           f'description: {q(form["description"])}']
    if form.get('labels'):
        out.append('labels: [' + ', '.join(q(x) for x in form['labels']) + ']')
    out.append('body:')
    for e in form['body']:
        out.append(f'  - type: {e["type"]}')
        if 'id' in e:
            out.append(f'    id: {e["id"]}')
        out.append('    attributes:')
        for k, v in e['attributes'].items():
            if k == 'options':
                out.append('      options:')
                for o in v:
                    if isinstance(o, dict):
                        out.append(f'        - label: {q(o["label"])}')
                        if o.get('required'):
                            out.append('          required: true')
                    else:
                        out.append(f'        - {q(o)}')
            elif isinstance(v, bool):
                out.append(f'      {k}: {"true" if v else "false"}')
            elif '\n' in v:
                block(out, k, v, 6)
            else:
                out.append(f'      {k}: {q(v)}')
        if e.get('validations'):
            out.append('    validations:')
            out.append('      required: true')
    return '\n'.join(out) + '\n'


def main(argv):
    check = '--check' in argv
    bad = []
    for name, make in FILES.items():
        path = FORMS / name
        want = render(make())
        if check:
            if not path.is_file() or path.read_text(encoding='utf-8') != want:
                bad.append(name)
        else:
            path.write_text(want, encoding='utf-8', newline='\n')
    extra = sorted(p.name for p in FORMS.glob('*.yml') if p.name != 'config.yml' and p.name not in FILES)
    if extra:
        print('forms this script does not write: ' + ', '.join(extra))
        return 1
    if bad:
        print('out of date (run python3 peanuts/scripts/issue_forms.py): ' + ', '.join(bad))
        return 1
    print('issue forms: ' + ('up to date' if check else 'written') + f' ({len(FILES)})')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
