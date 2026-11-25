// PEANUTS: new file. Saves for each user (issue #422) on the JVM, run by peanuts/tests/test_user_saves.py with SaveStore and UserSaveDirs.
package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Whose a save folder and a backup are (SaveStore.userOf, the manifest's user line), the folders of today's and removed users (UserSaveDirs), another user's save as
 * their account save alone (a backup without the device save, a restore that leaves the game's device save as it is), the first user's save taking another user's,
 * a restore into another user's save stopped part way and finished at a game's start (UserSaveDirs.recoverUsers), and the lock a copy into the first user's save
 * must leave to SaveStore. Prints "PASS" and the number of checks.
 */
public final class UserSavesJvmTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final String A = "0123456789abcdef0123456789abcdef", B = "fedcba9876543210fedcba9876543210", C = "00112233445566778899aabbccddeeff";

    public static void main(String[] args) throws Exception {
        File t = Files.createTempDirectory("usersaves").toFile();
        try {
            names();
            File saves = new File(t, "saves");
            folders(saves);
            backups(new File(t, "b"));
            restores(new File(t, "r"));
            recovery(new File(t, "j"));
            locks(new File(t, "l"));
        } finally {
            SaveStore.deleteTree(t);
        }
        System.out.println("PASS " + checks + " checks");
    }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    /** Users.saveDir for another user (Users has Android classes; UsersJvmTest checks it): &lt;save&gt;_user_&lt;uid&gt; beside the save */
    private static File userDir(File base, String uid) { return new File(base.getParentFile(), base.getName() + "_user_" + uid); }

    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** A save folder with one file (and a folder inside), as a game leaves it */
    private static File save(File dir, String what) throws IOException {
        write(new File(dir, "game.dat"), what);
        write(new File(dir, "sub/more.dat"), what + " more");
        return dir;
    }

    private static void names() {
        File base = new File("/x/saves/0100152000022000");
        check(SaveStore.userOf(base).equals(SaveStore.FIRST_USER), "the game's own save folder is the first user's");
        check(SaveStore.userOf(new File(base.getPath() + "_user_" + A)).equals(A), "<save>_user_<uid> is that user's");
        check(SaveStore.userOf(new File(base.getPath() + "_user_" + A.toUpperCase())).equals(SaveStore.FIRST_USER), "a uid in capitals is no user's folder");
        check(SaveStore.userOf(new File(base.getPath() + "_user_" + A + "_device")).equals(SaveStore.FIRST_USER), "a user's folder with _device after it is none");
        check(SaveStore.userOf(new File("/x/saves/_user_" + A)).equals(SaveStore.FIRST_USER), "nothing before _user_: not a user's folder of a game");
        check(SaveStore.userOf(new File(base.getPath() + "_user_" + A.substring(1))).equals(SaveStore.FIRST_USER), "31 hex digits: none");
        check(!SaveStore.accountOnly(base) && SaveStore.accountOnly(new File(base.getPath() + "_user_" + B)), "accountOnly: another user's folder alone");
        SaveStore.Manifest m = new SaveStore.Manifest();
        check(SaveStore.userOf(m).equals(SaveStore.FIRST_USER), "a backup with no user line and no source: the first user's (made before users)");
        m.source = base.getPath() + "_user_" + B;
        check(SaveStore.userOf(m).equals(B), "no user line: the folder it was made from");
        m.user = A;
        check(SaveStore.userOf(m).equals(A), "the user line wins");
        m.user = "not a uid";
        check(SaveStore.userOf(m).equals(B), "a user line that is no uid is not taken");
        check(UserSaveDirs.fileName("Anna Lee").equals("Anna_Lee") && UserSaveDirs.fileName(" é/x ").equals("__x") && UserSaveDirs.fileName("x".repeat(40)).length() == 32,
                "a user's name in a zip's file name");
    }

    private static void folders(File saves) throws IOException {
        File base = save(new File(saves, "0100152000022000"), "first");
        save(new File(saves, "0100152000022000_user_" + A), "a");
        new File(saves, "0100152000022000_user_" + B).mkdirs();   // made by the game for a user, still empty
        save(new File(saves, "0100152000022000_user_" + C), "c");
        save(new File(saves, "0100152000022000_device"), "device");
        save(new File(saves, "0100152000022000_userX"), "x");
        save(new File(saves, "0100F43008C44000_user_" + C), "another game");
        write(new File(saves, "0100152000022000_user_" + A + ".lock"), "");
        Map<String, File> o = UserSaveDirs.others(base);
        check(new java.util.ArrayList<>(o.keySet()).equals(Arrays.asList(C, A, B)), "three users' folders beside the save, in name order: " + o.keySet());
        check(o.containsKey(A) && o.containsKey(B) && o.containsKey(C) && o.get(A).getName().equals("0100152000022000_user_" + A), "others: by uid, this game's only");
        List<String> gone = UserSaveDirs.removed(base, Arrays.asList(SaveStore.FIRST_USER, A));
        check(gone.equals(Collections.singletonList(C)), "removed: a folder with a save whose user is gone (an empty one is not a save): " + gone);
        check(UserSaveDirs.removed(new File(saves, "0100AAAA00000000"), Collections.singletonList(SaveStore.FIRST_USER)).isEmpty(), "a game without users' folders");
    }

    private static void backups(File t) throws IOException {
        File base = save(new File(t, "saves/0100152000022000"), "first");
        save(SaveStore.device(base), "device");
        File user = save(userDir(base, A), "anna");
        File bk = new File(t, "save-backups/0100152000022000");
        SaveStore.userNames(Collections.singletonMap(A, "Anna"));
        SaveStore.Backup b = SaveStore.backup(user, bk, "20261123-100000");
        check(!b.device && !new File(b.dir, SaveStore.DEVICE).exists() && new File(b.dir, SaveStore.MAIN + "/game.dat").isFile(), "another user's backup: their save alone, no device save");
        check(b.user.equals(A) && b.who.equals("Anna"), "the backup says whose: " + b.user + " " + b.who);
        String mf = read(new File(bk, b.name + SaveStore.MANIFEST));
        check(mf.contains("\nuser\t" + A + "\tAnna\n"), "the manifest's user line");
        SaveStore.Manifest back = SaveStore.readManifest(new File(bk, b.name + SaveStore.MANIFEST));
        check(back.user.equals(A) && back.who.equals("Anna") && back.diff(SaveStore.scan(b.dir)) == null, "read back, and the backup still checks against it");
        SaveStore.Backup f = SaveStore.backup(base, bk, "20261123-100100");
        check(f.device && f.user.equals(SaveStore.FIRST_USER) && f.who.isEmpty(), "the first user's backup: with the device save, the first user's (no name given)");
        // a manifest of an older version: no user line; its source names whose it was
        File old = new File(bk, "20261101-090000");
        SaveStore.copyTree(new File(b.dir, SaveStore.MAIN), new File(old, SaveStore.MAIN));
        String noUser = mf.replace("user\t" + A + "\tAnna\n", "");
        write(new File(bk, old.getName() + SaveStore.MANIFEST), noUser);
        List<SaveStore.Backup> l = SaveStore.list(bk);
        check(l.size() == 3, "list: the three backups");
        SaveStore.Backup o = null;
        for (SaveStore.Backup x : l) if (x.name.equals(old.getName())) o = x;
        check(o != null && o.user.equals(A) && o.who.isEmpty(), "an older backup: its user from the folder it was made from");
        // an older reader skips the line: it knows source, layout, files, bytes, f and d only
        for (String line : mf.split("\n")) if (line.startsWith("user\t")) check(line.split("\t", -1).length == 3, "one line, three fields: " + line);
        // a name with a carriage return, a tab or a line break in it: the line stays one line (Files.readAllLines splits at \r too)
        SaveStore.userNames(Collections.singletonMap(A, "An\rn\ta\n"));
        SaveStore.Backup odd = SaveStore.backup(user, bk, "20261123-100200");
        SaveStore.Manifest oddBack = SaveStore.readManifest(new File(bk, odd.name + SaveStore.MANIFEST));
        check(oddBack.user.equals(A) && oddBack.who.equals("An n a "), "control characters in a name become spaces: [" + oddBack.who + "]");
    }

    private static void restores(File t) throws IOException {
        File base = save(new File(t, "saves/0100152000022000"), "first");
        File dev = save(SaveStore.device(base), "device");
        File anna = userDir(base, A);
        File bk = new File(t, "save-backups/0100152000022000");
        SaveStore.Backup first = SaveStore.backup(base, bk, "20261123-110000");
        SaveStore.Manifest devBefore = SaveStore.scan(dev), baseBefore = SaveStore.scan(base);
        // the first user's backup (with the device save) into Anna's save, which has none yet
        SaveStore.Restored r = SaveStore.restore(first.dir, anna, bk, "20261123-110100");
        check(r.before == null && r.parts.equals(Collections.singletonList(SaveStore.MAIN)), "into another user's save: the main part alone, no backup of an empty save");
        check(read(new File(anna, "game.dat")).equals("first"), "Anna's save is the backup's");
        check(!SaveStore.device(anna).exists(), "no device save made beside another user's save");
        check(SaveStore.scan(dev).diff(devBefore) == null && SaveStore.scan(base).diff(baseBefore) == null, "the game's own save and device save as they were");
        // Anna's save given to the first user (Copy to another user): the device save stays, the first user's save is backed up first (with it)
        write(new File(anna, "game.dat"), "anna");
        r = SaveStore.restore(anna, base, bk, "20261123-110200");
        check(read(new File(base, "game.dat")).equals("anna") && SaveStore.scan(dev).diff(devBefore) == null, "the first user takes Anna's save and keeps the device save");
        check(r.before != null && r.before.device && r.before.user.equals(SaveStore.FIRST_USER), "the first user's save backed up first, with the device save, as theirs");
        check(read(new File(anna, "game.dat")).equals("anna"), "Anna's save only read");
        // a backup of another user (another phone's: its source is somewhere else) into the first user's save
        File elsewhere = save(new File(t, "other/saves/0100152000022000_user_" + B), "ben");
        SaveStore.userNames(Collections.singletonMap(B, "Ben"));
        SaveStore.Backup ben = SaveStore.backup(elsewhere, new File(t, "other/save-backups"), "20261120-080000");
        r = SaveStore.restore(ben.dir, base, bk, "20261123-110300");
        check(read(new File(base, "game.dat")).equals("ben") && SaveStore.scan(dev).diff(devBefore) == null, "another user's backup into the first user's: the device save stays");
        // a plain folder without a device save, into the first user's save that has one: refused as before (it says nothing of whose it was)
        File plain = save(new File(t, "import/save"), "plain");
        try {
            SaveStore.restore(plain, base, bk, "20261123-110400");
            check(false, "a folder without a device save into a save with one is refused");
        } catch (IOException e) {
            check(e.getMessage().contains("has no device save"), "refused for the device save: " + e.getMessage());
        }
        check(read(new File(base, "game.dat")).equals("ben"), "the refused restore changed nothing");
        // a backup of Anna's save, into Ben's (this phone): the automatic backup before it is Ben's
        File benHere = save(userDir(base, B), "ben here");
        SaveStore.userNames(Collections.singletonMap(B, "Ben"));
        SaveStore.Backup annas = SaveStore.backup(anna, bk, "20261123-110500");
        r = SaveStore.restore(annas.dir, benHere, bk, "20261123-110600");
        check(read(new File(benHere, "game.dat")).equals("anna") && r.before != null && r.before.user.equals(B) && r.before.who.equals("Ben") && !r.before.device,
                "Anna's backup into Ben's save: Ben's backed up first, as Ben's, without a device save");
        check(!SaveStore.device(benHere).exists() && SaveStore.scan(dev).diff(devBefore) == null, "and the device save untouched");
        // a backup that holds only the device save (the first user's main folder was missing) into another user's save: nothing of theirs, refused
        File onlyDev = new File(t, "devonly/saves/0100152000022000");
        save(SaveStore.device(onlyDev), "device only");
        SaveStore.Backup devOnly = SaveStore.backup(onlyDev, new File(t, "devonly/save-backups"), "20261123-110700");
        check(devOnly.device && !new File(devOnly.dir, SaveStore.MAIN).exists(), "a backup of the device save alone");
        SaveStore.Manifest benBefore = SaveStore.scan(benHere);
        int backups = SaveStore.list(bk).size();
        try {
            SaveStore.restore(devOnly.dir, benHere, bk, "20261123-110800");
            check(false, "a device save alone into another user's save is refused");
        } catch (IOException e) {
            check(e.getMessage().contains("only a device save"), "refused: only a device save: " + e.getMessage());
        }
        check(SaveStore.scan(benHere).diff(benBefore) == null && SaveStore.list(bk).size() == backups, "Ben's save as it was, and no backup made for nothing");
    }

    /** A restore into another user's save stopped after its set file, and one stopped at its marker: finished at a game's start, and only there and once */
    private static void recovery(File t) throws IOException {
        File base = save(new File(t, "saves/0100152000022000"), "first");
        File anna = save(userDir(base, A), "old");
        File ben = save(userDir(base, B), "ben");
        check(UserSaveDirs.recoverUsers(base) == null, "nothing to finish");
        File copy = save(new File(anna.getPath() + SaveStore.RESTORE_SUFFIX), "new");
        Files.write(SaveStore.setFile(anna).toPath(), (anna.getAbsolutePath() + "\t" + copy.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
        File benCopy = save(new File(ben.getPath() + SaveStore.RESTORE_SUFFIX), "ben new");
        Files.write(SaveStore.marker(ben, 1).toPath(), benCopy.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        check(SaveStore.pending(anna) && SaveStore.pending(ben), "both pending");
        String note = UserSaveDirs.recoverUsers(base);
        check(note != null && note.contains("finished") && note.contains("_user_" + A) && note.contains("_user_" + B) && !note.contains("left as it is"), "something finished: " + note);
        check(read(new File(anna, "game.dat")).equals("new") && read(new File(ben, "game.dat")).equals("ben new"), "each user's save is the restored one, whole");
        check(!SaveStore.pending(anna) && !SaveStore.pending(ben) && !copy.exists() && !benCopy.exists(), "no journal left");
        check(read(new File(base, "game.dat")).equals("first"), "the game's own save untouched");
        check(UserSaveDirs.recoverUsers(base) == null, "once");
        // a marker naming a copy that is gone (SaveStore refuses: stage 1 would empty the save): that folder is left as it is, the others are still finished
        File gone = new File(anna.getPath() + SaveStore.RESTORE_SUFFIX);
        Files.write(SaveStore.marker(anna, 1).toPath(), gone.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        File benCopy2 = save(new File(ben.getPath() + SaveStore.RESTORE_SUFFIX), "ben newer");
        Files.write(SaveStore.marker(ben, 1).toPath(), benCopy2.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        String mixed = UserSaveDirs.recoverUsers(base);
        check(mixed != null && mixed.contains("left as it is") && mixed.contains("_user_" + A) && mixed.contains("finished first: ") , "one left, one finished: " + mixed);
        check(read(new File(anna, "game.dat")).equals("new") && SaveStore.marker(anna, 1).isFile(), "Anna's save and its marker left as they were");
        check(read(new File(ben, "game.dat")).equals("ben newer") && !SaveStore.pending(ben), "Ben's restore finished");
    }

    /**
     * The game's lock (the first user's folder), held as UserSaves.locked holds it for another user's save: a backup or restore of another user's save works beside
     * it, one of the first user's save would be refused (so locked leaves that lock to SaveStore when the target is the first user's save)
     */
    private static void locks(File t) throws IOException {
        File base = save(new File(t, "saves/0100152000022000"), "first");
        File anna = save(userDir(base, A), "anna");
        File bk = new File(t, "save-backups/0100152000022000");
        try (SaveStore.Lock game = SaveStore.lock(base)) {
            check(SaveStore.inUse(base), "the game's save in use while its lock is held");
            SaveStore.Backup b = SaveStore.backup(anna, bk, "20261123-120000");
            check(b.user.equals(A), "another user's backup works under the game's lock");
            try {
                SaveStore.restore(anna, base, bk, "20261123-120100");
                check(false, "a restore into the first user's save under its held lock is refused");
            } catch (IOException e) {
                check(e.getMessage().contains("in use"), "refused: in use");
            }
        }
        SaveStore.Restored r = SaveStore.restore(anna, base, bk, "20261123-120200");
        check(r.now.files == 2 && read(new File(base, "game.dat")).equals("anna"), "with the lock given back, SaveStore takes it itself");
    }
}
