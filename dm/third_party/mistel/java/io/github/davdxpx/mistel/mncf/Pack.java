package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A pack after it was read (section 13): its manifest, signature and files. Immutable. */
public final class Pack {

    private final String name;
    private final SignatureResult signature;
    private final List<PackFile> files;
    private final List<String> authors;
    private final String about;
    private final String fileVersion;
    private final String id;
    private final List<byte[]> signers;
    private final Update update;
    private final List<Release> changes;
    private final List<String> warnings;

    Pack(String name, SignatureResult signature, List<PackFile> files, List<String> authors, String about,
            String fileVersion, Updates.Keys keys, List<byte[]> signers, List<String> warnings) {
        this.name = name;
        this.signature = signature;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        this.authors = Collections.unmodifiableList(new ArrayList<>(authors));
        this.about = about;
        this.fileVersion = fileVersion;
        this.id = keys.id;
        this.signers = MncfFile.copies(signers);
        this.update = keys.update;
        this.changes = Collections.unmodifiableList(new ArrayList<>(keys.changes));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /** Returns the pack's name. */
    public String name() {
        return name;
    }

    /** Returns the result of checking the manifest's signature lines. */
    public SignatureResult signature() {
        return signature;
    }

    /** Returns the files the manifest lists, in its order. */
    public List<PackFile> files() {
        return files;
    }

    /** Returns the file the manifest lists under that path, or null. */
    public PackFile file(String path) {
        for (PackFile f : files) {
            if (f.path().equals(path)) {
                return f;
            }
        }
        return null;
    }

    /** Returns the authors. */
    public List<String> authors() {
        return authors;
    }

    /** Returns the description, or null. */
    public String about() {
        return about;
    }

    /** Returns the pack's own version, or null. */
    public String fileVersion() {
        return fileVersion;
    }

    /** Returns the identity of the pack across all its versions (section 17.6), or null. */
    public String id() {
        return id;
    }

    /**
     * Returns the 32-byte public keys of the manifest's signers key (section 12), in its order,
     * as copies; empty when it has none or the key was ignored.
     */
    public List<byte[]> signers() {
        return MncfFile.copies(signers);
    }

    /** Returns where the newest version of the pack is ([update], section 17.6), or null. */
    public Update update() {
        return update;
    }

    /**
     * Returns the pack's change entries that are valid, newest first. Their lists name the
     * paths of the pack's files.
     */
    public List<Release> changes() {
        return changes;
    }

    /**
     * Returns the warnings: unknown-key:&lt;key&gt; for each key of the manifest the format does
     * not define, signers for a signers key that was ignored, and id, update, changes and
     * changes:&lt;version or position&gt; for the keys of section 17.1 that were left out.
     */
    public List<String> warnings() {
        return warnings;
    }

    /** Returns whether a runtime may apply the pack's files at all: not when the manifest's signature is bad. */
    public boolean usable() {
        String r = signature.result();
        return !r.equals(SignatureResult.INVALID) && !r.equals(SignatureResult.REVOKED);
    }
}
