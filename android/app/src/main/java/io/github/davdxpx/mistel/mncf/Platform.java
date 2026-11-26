package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** One platform of Appendix A: what a reader needs to know about it. Immutable. */
public final class Platform {

    private static final Pattern HEX_ID = Pattern.compile("[0-9A-Fa-f]{1,64}");
    private static final Pattern SHA256_ID = Pattern.compile("sha256:[0-9A-Fa-f]{64}");
    private static final Pattern PC_BUTTON = Pattern.compile("[A-Z0-9_]+");

    private static final Map<String, Platform> PLATFORMS;

    static {
        List<String> switchButtons = Arrays.asList(("A B X Y L R ZL ZR PLUS MINUS LSTICK RSTICK DUP DDOWN DLEFT DRIGHT "
                + "LSTICK_UP LSTICK_DOWN LSTICK_LEFT LSTICK_RIGHT RSTICK_UP RSTICK_DOWN RSTICK_LEFT RSTICK_RIGHT SL SR")
                .split(" "));
        List<String> switch2Buttons = new ArrayList<>(switchButtons);
        switch2Buttons.add("C");
        List<String> n64Buttons = Arrays.asList("A B Z START L R CUP CDOWN CLEFT CRIGHT DUP DDOWN DLEFT DRIGHT".split(" "));
        List<String> switchModules = new ArrayList<>(Arrays.asList("main", "sdk"));
        for (int i = 0; i < 10; i++) {
            switchModules.add("subsdk" + i);
        }
        List<String> switchRegions = Arrays.asList("heap", "alias", "aslr");
        List<String> none = Collections.emptyList();
        Map<String, Platform> all = new LinkedHashMap<>();
        all.put("switch", new Platform("switch", Pattern.compile("[0-9A-Fa-f]{16}"), Arrays.asList("arm64"),
                false, 8, switchModules, false, switchRegions, false, switchButtons));
        all.put("switch2", new Platform("switch2", Pattern.compile("[0-9A-Fa-f]{16}"), Arrays.asList("arm64"),
                false, 8, switchModules, false, switchRegions, false, switch2Buttons));
        all.put("n64", new Platform("n64", Pattern.compile("[\\x21-\\x7E]{4}"), Arrays.asList("mips"),
                true, 4, Arrays.asList("rom"), true, none, true, n64Buttons));
        all.put("pc", new Platform("pc", Pattern.compile("[a-z0-9-]+:" + Py.NOT_SPACE + "+"),
                Arrays.asList("x86-64", "arm64"), false, 8, none, true, none, false, none));
        PLATFORMS = Collections.unmodifiableMap(all);
    }

    private final String name;
    private final Pattern gameId;
    private final List<String> archs;
    private final boolean bigEndian;
    private final int pointerSize;
    private final List<String> modules;
    private final boolean sha256Ids;
    private final List<String> regions;
    private final boolean addresses;
    private final List<String> buttons;

    private Platform(String name, Pattern gameId, List<String> archs, boolean bigEndian, int pointerSize,
            List<String> modules, boolean sha256Ids, List<String> regions, boolean addresses, List<String> buttons) {
        this.name = name;
        this.gameId = gameId;
        this.archs = Collections.unmodifiableList(new ArrayList<>(archs));
        this.bigEndian = bigEndian;
        this.pointerSize = pointerSize;
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        this.sha256Ids = sha256Ids;
        this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
        this.addresses = addresses;
        this.buttons = Collections.unmodifiableList(new ArrayList<>(buttons));
    }

    /** Returns the platform of that name, or null when Appendix A has none. */
    public static Platform forName(String name) {
        return PLATFORMS.get(name);
    }

    /** Returns every platform of Appendix A, in the appendix's order. */
    public static List<Platform> all() {
        return Collections.unmodifiableList(new ArrayList<>(PLATFORMS.values()));
    }

    /** Returns the platform's name, such as "switch". */
    public String name() {
        return name;
    }

    /** Returns the architectures a runtime of this platform can have; the first is the default. */
    public List<String> archs() {
        return archs;
    }

    /** Returns whether numbers are stored big-endian; otherwise they are little-endian. */
    public boolean bigEndian() {
        return bigEndian;
    }

    /** Returns the size of a pointer in bytes. */
    public int pointerSize() {
        return pointerSize;
    }

    /** Returns the names a code module can have, or an empty list when any name is allowed. */
    public List<String> modules() {
        return modules;
    }

    /**
     * Returns how a module identity is written: "hex" for a build ID that matches by prefix, or
     * "sha256" for the hash of the module file.
     */
    public String moduleIdKind() {
        return sha256Ids ? "sha256" : "hex";
    }

    /** Returns the names of the platform's memory regions. */
    public List<String> regions() {
        return regions;
    }

    /** Returns whether {@code address} locations exist on this platform. */
    public boolean addresses() {
        return addresses;
    }

    /** Returns the button names, or an empty list when any upper-case name is allowed. */
    public List<String> buttons() {
        return buttons;
    }

    /** Returns whether the text is a game ID of this platform. */
    public boolean isValidGameId(String id) {
        return Py.matches(gameId, id);
    }

    /** Returns whether the value is a module identity as a file writes it for this platform. */
    public boolean isValidModuleId(Object value) {
        return Py.matches(sha256Ids ? SHA256_ID : HEX_ID, value);
    }

    /**
     * Returns whether a module identity from a file matches the identity of a module that runs
     * (section 5): a build ID matches when one is a prefix of the other, a hash when both are
     * the same. Case does not matter.
     */
    public boolean moduleIdMatches(String listed, String running) {
        String a = Py.lower(listed);
        String b = Py.lower(running);
        if (!sha256Ids) {
            return a.length() <= b.length() ? b.startsWith(a) : a.startsWith(b);
        }
        return a.equals(b);
    }

    /** Returns whether the name is a button of this platform. */
    public boolean isValidButton(String name) {
        if (!buttons.isEmpty()) {
            return buttons.contains(name);
        }
        return Py.matches(PC_BUTTON, name);
    }

    @Override
    public String toString() {
        return name;
    }
}
