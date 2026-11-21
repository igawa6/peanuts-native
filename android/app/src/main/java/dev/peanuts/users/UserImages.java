package dev.peanuts.users;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * The users' pictures as a game sees them: files/users/images/<uid>.jpg, 256 x 256 JPEG on the user's background, as the console keeps a profile image. The host
 * gives them to a game that asks (hle_account.c LoadProfileImage, DM_USER_IMAGES set by dev.peanuts.build.GameEnv). Written when a picture is picked and when the
 * Mii a picture shows is edited; a user without a picture has no file, and the game gets none, as before.
 */
public final class UserImages {
    private UserImages() {}

    static final int SIZE = 256;

    public static File dir(Context c) { return new File(new File(c.getFilesDir(), "users"), "images"); }

    /** Draws user uid's picture and keeps it as its JPEG (later, off the main thread's back); removes it when the user has none */
    public static void write(Context c, String uid, UserPictures.Picture p) {
        Context app = c.getApplicationContext();
        File f = new File(dir(app), uid + ".jpg");
        if (p.none()) { if (f.exists() && !f.delete()) Log.w("peanuts", "user picture: cannot remove " + f); return; }
        UserFace.picture(app, p, SIZE, b -> {
            if (b == null) return;
            Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawColor(0xff000000 | p.color());
            cv.drawBitmap(b, null, new Rect(0, 0, SIZE, SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
            new Thread(() -> save(f, out), "user-image").start();
        });
    }

    private static void save(File f, Bitmap b) {
        File d = f.getParentFile(), tmp = new File(f.getPath() + ".tmp");
        try {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
            try (FileOutputStream o = new FileOutputStream(tmp)) { if (!b.compress(Bitmap.CompressFormat.JPEG, 90, o)) throw new IOException("JPEG"); }
            if (!tmp.renameTo(f)) throw new IOException("cannot replace " + f);
        } catch (IOException e) { Log.w("peanuts", "user picture " + f.getName() + ": " + e.getMessage()); if (tmp.exists() && !tmp.delete()) Log.w("peanuts", "user picture: " + tmp + " left behind"); }
    }

    /** A Mii was edited: every user whose picture it is gets the new face */
    public static void miiChanged(Context c, String createId) {
        List<UsersPort.User> users = UsersPort.list(c);
        for (int i = 0; i < users.size(); i++) {
            UserPictures.Picture p = UserPictures.of(c.getFilesDir(), users.get(i).uid, i);
            if (createId.equals(p.mii())) write(c, users.get(i).uid, p);
        }
    }

    /** A Mii was deleted: the users whose picture it was have none now */
    public static void miiGone(Context c, String createId) {
        List<UsersPort.User> users = UsersPort.list(c);
        for (int i = 0; i < users.size(); i++)
            if (createId.equals(UserPictures.of(c.getFilesDir(), users.get(i).uid, i).mii())) removed(c, users.get(i).uid);
    }

    /** A removed user's picture file goes too */
    public static void removed(Context c, String uid) {
        File f = new File(dir(c), uid + ".jpg");
        if (f.exists() && !f.delete()) Log.w("peanuts", "user picture: cannot remove " + f);
    }
}
