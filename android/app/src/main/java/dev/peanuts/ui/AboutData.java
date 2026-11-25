package dev.peanuts.ui;

/**
 * What About › Credits and Licenses list, with where each opens (its GitHub page or its notice). The README's credits are the source: the makers of the
 * three projects Peanuts Native stands on, the contributors of its own Contributors list (davdxpx first), and the notices of android/NOTICE.md and
 * dm/third_party/README.md for the parts the app carries. peanuts/tests/test_peanuts_cracked.py checks the contributors against the README.
 */
final class AboutData {
    private AboutData() {}

    static final String REPO = "https://github.com/igawa6/peanuts-native";
    static final String CONTRIBUTORS = REPO + "/graphs/contributors";

    /** The makers: name, project, page */
    static final String[][] MADE_BY = {
            {"igawa6", "Peanuts Native", "https://github.com/igawa6"},
            {"moceure", "Banana Native", "https://git.eden-emu.dev/moceure"},
            {"seedds", "nxnative", "https://github.com/seedds"},
    };

    /** The contributors of the README's list, davdxpx first, then in the README's order */
    static final String[] CONTRIBUTED = {"davdxpx", "intermission88", "elliott-groves", "tapionallanaire", "dnyan007", "skyhong2002", "ssv0225", "999sian", "ts-miller", "shamonj03", "fireworksordie", "nomnomab", "danielcamilo1", "g0giberry", "gerdguerrero", "ArchOwlen"};

    /** Built on: name, what it is, its license, its page */
    static final String[][] BUILT_ON = {
            {"Banana Native", "moceure", "GPL-3.0-or-later", "https://git.eden-emu.dev/moceure/BananaNative"},
            {"nxnative", "seedds", "MIT", "https://github.com/seedds/nxnative"},
            {"Eden Duo", "The companions' runtime", "Its notice", "https://github.com/igawa6/eden-duo"},
            {"All notices", "This app's license notice", "Read", REPO + "/blob/main/android/NOTICE.md"},
    };

    /** In the app: name, what it does here, its license, its notice */
    static final String[][] THIRD_PARTY = {
            {"Frame generation", "From Eden and lsfg-vk", "GPL-3.0-or-later", REPO + "/blob/main/android/NOTICE.md"},
            {"FidelityFX FSR 1", "The FSR upscaler", "MIT", REPO + "/blob/main/dm/third_party/README.md"},
            {"libadrenotools", "Loads a custom GPU driver", "BSD-2", REPO + "/blob/main/dm/third_party/README.md"},
            {"Zstandard", "Unpacks the shader library", "BSD", REPO + "/blob/main/dm/third_party/README.md"},
            {"Noto Sans", "The font games fall back to", "OFL-1.1", REPO + "/blob/main/dm/third_party/README.md"},
            {"Ryujinx", "Shader translation, the default Miis", "MIT", REPO + "/blob/main/dm/third_party/README.md"},
            {"nx2android", "Some fixes, adapted", "MIT", "https://github.com/shirai91/nx2android"},
            {"vee", "The Miis' colours and face layout", "MIT", "https://github.com/j0lol/vee"},
            {"bcdec", "Unpacks the Mii parts' textures", "MIT", "https://github.com/iOrange/bcdec"},
            {"ACNH 60 FPS + SpeedFix", "tmouh's 60 fps for Animal Crossing", "CC BY-NC 4.0", "https://github.com/tmouh/acnh-60fps"},
    };
}
