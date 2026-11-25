package io.github.davdxpx.mistel.mncf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One patch of a patch file: the records it writes, in file order. Each record's offset counts
 * from the start of the module's NSO file, its 0x100-byte header first and then the image.
 * Immutable.
 */
public final class Patch {

    /** One record of a patch: an offset and the bytes written there. Immutable. */
    public static final class Record {
        private final BigInteger offset;
        private final byte[] data;

        /** Makes a record of an offset, which may lie anywhere, and its bytes. */
        public Record(BigInteger offset, byte[] data) {
            this.offset = offset;
            this.data = data.clone();
        }

        /**
         * Returns the offset from the start of the NSO file. It is a BigInteger because an
         * IPSwitch file's offset_shift may move it anywhere, also below 0.
         */
        public BigInteger offset() {
            return offset;
        }

        /** Returns the bytes. */
        public byte[] data() {
            return data.clone();
        }

        byte[] dataShared() {
            return data;
        }
    }

    private final String name;
    private final boolean enabled;
    private final List<Record> records;

    Patch(String name, boolean enabled, List<Record> records) {
        this.name = name;
        this.enabled = enabled;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    /** Returns the comment that names the patch in an IPSwitch file; empty for an IPS file. */
    public String name() {
        return name;
    }

    /** Returns whether the patch is applied: @enabled in an IPSwitch file; an IPS file always is. */
    public boolean enabled() {
        return enabled;
    }

    /** Returns the records, in file order. */
    public List<Record> records() {
        return records;
    }
}
