package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An .mncf file after it was read and checked. Immutable; made by {@link Mncf#load}. */
public final class MncfFile {

    private final byte[] data;
    private final byte[] signed;
    private final SignatureResult signature;
    private final Map<String, Object> doc;
    private final String name;
    private final Platform platform;
    private final String gameId;
    private final String gameName;
    private final List<String> authors;
    private final String about;
    private final String fileVersion;
    private final String id;
    private final List<byte[]> signers;
    private final Update update;
    private final List<Release> changes;
    private final String language;
    private final Map<String, Object> origin;
    private final List<String> required;
    private final List<String> optional;
    private final Map<String, Build> builds;
    private final Map<String, Map<Number, String>> choices;
    private final List<Cheat> cheats;
    private final List<Preset> presets;
    private final Map<String, Object> i18n;
    private final List<String> warnings;

    /** The checked parts of a file while the schema fills them in. */
    static final class Parts {
        Map<String, Object> doc;
        String name;
        Platform platform;
        String gameId;
        String gameName;
        List<String> authors = new ArrayList<>();
        String about;
        String fileVersion;
        String id;
        List<byte[]> signers = new ArrayList<>();
        Update update;
        List<Release> changes = new ArrayList<>();
        String language = "en";
        Map<String, Object> origin;
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        Map<String, Build> builds = new LinkedHashMap<>();
        Map<String, Map<Number, String>> choices = new LinkedHashMap<>();
        List<Cheat> cheats = new ArrayList<>();
        List<Preset> presets = new ArrayList<>();
        Map<String, Object> i18n = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
    }

    MncfFile(byte[] data, byte[] signed, SignatureResult signature, Parts p) {
        this.data = data.clone();
        this.signed = signed.clone();
        this.signature = signature;
        this.doc = p.doc;
        this.name = p.name;
        this.platform = p.platform;
        this.gameId = p.gameId;
        this.gameName = p.gameName;
        this.authors = Collections.unmodifiableList(new ArrayList<>(p.authors));
        this.about = p.about;
        this.fileVersion = p.fileVersion;
        this.id = p.id;
        this.signers = copies(p.signers);
        this.update = p.update;
        this.changes = Collections.unmodifiableList(new ArrayList<>(p.changes));
        this.language = p.language;
        this.origin = p.origin;
        this.required = Collections.unmodifiableList(new ArrayList<>(p.required));
        this.optional = Collections.unmodifiableList(new ArrayList<>(p.optional));
        this.builds = Collections.unmodifiableMap(new LinkedHashMap<>(p.builds));
        Map<String, Map<Number, String>> lists = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Number, String>> e : p.choices.entrySet()) {
            lists.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.choices = Collections.unmodifiableMap(lists);
        this.cheats = Collections.unmodifiableList(new ArrayList<>(p.cheats));
        this.presets = Collections.unmodifiableList(new ArrayList<>(p.presets));
        this.i18n = p.i18n;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(p.warnings));
    }

    static List<byte[]> copies(List<byte[]> keys) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] k : keys) {
            out.add(k.clone());
        }
        return out;
    }

    /** Returns a copy of the file's bytes. */
    public byte[] data() {
        return data.clone();
    }

    /** Returns a copy of the bytes the signature covers: the file up to its first signature line. */
    public byte[] signedBytes() {
        return signed.clone();
    }

    /** Returns the result of checking the file's signature lines. */
    public SignatureResult signature() {
        return signature;
    }

    /** Returns the whole document as parsed TOML, unmodifiable. */
    public Map<String, Object> doc() {
        return doc;
    }

    /** Returns the file's name. */
    public String name() {
        return name;
    }

    /** Returns the platform of the game. */
    public Platform platform() {
        return platform;
    }

    /** Returns the game's ID on its platform. */
    public String gameId() {
        return gameId;
    }

    /** Returns the game's name, or null. */
    public String gameName() {
        return gameName;
    }

    /** Returns the authors. */
    public List<String> authors() {
        return authors;
    }

    /** Returns the description, or null. */
    public String about() {
        return about;
    }

    /**
     * Returns the file's own version, or null. With [update] or [[changes]] it is a version of
     * section 17.1, which {@link Updates#compare} compares.
     */
    public String fileVersion() {
        return fileVersion;
    }

    /** Returns the identity of the file across all its versions (section 17.1), or null. */
    public String id() {
        return id;
    }

    /**
     * Returns the 32-byte public keys of the file's signers key (section 12), in file order, as
     * copies; empty when it has none or the key was ignored.
     */
    public List<byte[]> signers() {
        return copies(signers);
    }

    /** Returns where the newest version of the file is ([update], section 17.3), or null. */
    public Update update() {
        return update;
    }

    /** Returns the change entries ([[changes]], section 17.1) that are valid, newest first. */
    public List<Release> changes() {
        return changes;
    }

    /** Returns the language of the file's texts; "en" when the file names none. */
    public String language() {
        return language;
    }

    /**
     * Returns the source a converter read the file from, unmodifiable: its "format", and maybe
     * its "name" and the "site" it came from. Null when the file was not converted.
     */
    public Map<String, Object> origin() {
        return origin;
    }

    /** Returns the extensions the file needs, with their versions. */
    public List<String> requiredExtensions() {
        return required;
    }

    /** Returns the extensions the file can use, with their versions. */
    public List<String> optionalExtensions() {
        return optional;
    }

    /** Returns the builds the file knows, by key, in file order. */
    public Map<String, Build> builds() {
        return builds;
    }

    /** Returns the named choice lists: for each list, the name of each number, in file order. */
    public Map<String, Map<Number, String>> choices() {
        return choices;
    }

    /** Returns the cheats, in file order. */
    public List<Cheat> cheats() {
        return cheats;
    }

    /** Returns the cheat with that id, or null. */
    public Cheat cheat(String id) {
        for (Cheat c : cheats) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Returns the presets that are valid, in file order. */
    public List<Preset> presets() {
        return presets;
    }

    /** Returns the translations as the file wrote them, by language, unmodifiable. */
    public Map<String, Object> i18n() {
        return i18n;
    }

    /**
     * Returns the warnings: unknown-key:&lt;key&gt; for a key outside a cheat that the format does
     * not define, preset:&lt;id&gt; for a preset that was dropped, i18n for a broken i18n table,
     * signers for a signers key that was ignored, and id, update, changes and
     * changes:&lt;version or position&gt; for the keys of section 17.1 that were left out.
     */
    public List<String> warnings() {
        return warnings;
    }
}
