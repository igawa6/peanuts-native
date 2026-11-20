package dev.peanuts.ui;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which GPU a driver download is made for, as far as its names say it: the file's name first ("turnip_a8xx.zip", "Turnip_Gen8_V39.zip",
 * "..._8gen2.zip"), else the release's name, else a "for a7xx" in its notes, else the repository's name ("GameHub-8Elite-Drivers"). A file without a
 * family of its own beside files that name one is the general build. PanVK packages are labelled for Mali and Immortalis.
 * No Android classes, so it runs on the JVM.
 */
final class DriverMatch {
    private DriverMatch() {}

    static final String MALI = "mali";
    private static final Pattern ARM_GPU = Pattern.compile("(?i)\\b(?:mali|immortalis)\\b");
    private static final Pattern FAMILY = Pattern.compile("(?i)(?<![0-9a-z])(?:adreno[ _-]?)?a?([678])xx(?![0-9a-z])");   // a8xx, 7XX, Adreno 8xx
    // a model needs its "A" or "Adreno": a bare 840 is as often Qualcomm's driver version
    private static final Pattern MODEL = Pattern.compile("(?i)(?<![0-9a-z])(?:adreno(?:\\s*\\(tm\\))?[ _-]?a?|a)([678])\\d\\d(?![0-9])");
    private static final Pattern GEN = Pattern.compile("(?i)(?<![0-9a-z])gen[ _-]?8(?![0-9])");   // Turnip's name for Adreno 8xx
    private static final Pattern SOC = Pattern.compile("(?i)(?<![0-9a-z])(?:sd[ _-]?)?8[ _-]?g(?:en)?[ _-]?([123])(?![0-9])");   // Snapdragon 8 Gen 1 to 3: Adreno 7xx
    private static final Pattern ELITE = Pattern.compile("(?i)8[ _-]?elite");   // Snapdragon 8 Elite: Adreno 8xx
    private static final Pattern NOTES = Pattern.compile("(?i)\\b(?:for|unified:?)\\s+((?:adreno\\s+)?a?[678]xx(?:\\s*(?:,|/|and|or)\\s*(?:adreno\\s+)?a?[678]xx)*)");

    /** The families a name says ("a8xx", "Adreno 740", "Gen8", "8gen2", "8 Elite"); empty when it names none. A version number alone ("v840") is no GPU */
    static TreeSet<String> families(String name) {
        TreeSet<String> out = new TreeSet<>();
        if (name == null) return out;
        String s = name.replaceAll("(?i)(?<![0-9a-z])v\\d[\\d.]*", " ").replaceAll("(?i)(?:driver|qualcomm)[ _-]?\\d[\\d.]*", " ");   // driver versions: v840, Qualcomm_840
        for (Pattern p : new Pattern[] {FAMILY, MODEL}) {
            Matcher m = p.matcher(s);
            while (m.find()) out.add(m.group(1) + "xx");
        }
        if (GEN.matcher(s).find() || ELITE.matcher(s).find()) out.add("8xx");
        if (SOC.matcher(s).find()) out.add("7xx");
        return out;
    }

    /** The families a release's notes say it is for ("For a7xx", "Unified: A6XX, A7XX, A8XX"); a GPU only mentioned in passing is no claim */
    static TreeSet<String> noted(String notes) {
        TreeSet<String> out = new TreeSet<>();
        if (notes == null) return out;
        Matcher m = NOTES.matcher(notes);
        while (m.find()) out.addAll(families(m.group(1)));
        return out;
    }

    /** One file's families: its own name, else (when no file of the release names one) the release's name, its notes, the repository's name */
    static TreeSet<String> forFile(String file, List<String> releaseFiles, String release, String notes, String repo) {
        if (file.toLowerCase(Locale.ROOT).startsWith("mali-panvk-")) return new TreeSet<>(List.of(MALI));
        TreeSet<String> own = families(file.replaceAll("(?i)\\.zip$", ""));
        if (!own.isEmpty()) return own;
        if ("Balemuni/Balemunis-Aurora".equals(repo)) return new TreeSet<>(List.of("6xx", "7xx", "8xx"));
        for (String f : releaseFiles) if (!families(f.replaceAll("(?i)\\.zip$", "")).isEmpty()) return own;   // the general build beside named ones
        TreeSet<String> r = families(release);
        if (r.isEmpty()) r = noted(notes);
        if (r.isEmpty()) r = families(repo == null ? null : repo.replaceAll(".*/", ""));
        return r;
    }

    /** This phone's GPU family, or null if unknown */
    static String family(String gpuName) {
        if (gpuName == null) return null;
        if (ARM_GPU.matcher(gpuName).find()) return MALI;
        Matcher m = Pattern.compile("(?i)adreno\\D*([678])\\d\\d").matcher(gpuName);
        return m.find() ? m.group(1) + "xx" : null;
    }

    /** The GPU family label shown under a download */
    static String words(TreeSet<String> families) {
        List<String> l = new java.util.ArrayList<>(families);
        if (l.isEmpty()) return "";
        if (families.contains(MALI)) return "Mali and Immortalis";
        if (l.size() == 1) return "Adreno " + l.get(0);
        return "Adreno " + String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    /** Whether a file claims families and this phone's is not one of them */
    static boolean otherGpu(TreeSet<String> families, String phone) { return phone != null && !families.isEmpty() && !families.contains(phone.toLowerCase(Locale.ROOT)); }
}
