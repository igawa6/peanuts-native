package dev.peanuts.build;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Share build log's bundles, read-only, for the app the player shares them with: content://&lt;application ID&gt;.buildlog/build-log-&lt;title ID&gt;-&lt;time&gt;.zip,
 * from {@link BuildLogShare#outDir} only and only names {@link BuildLogBundle#validName} accepts (no folder, no other file of the app). The grant is the
 * share intent's one-time read permission. A bundle stays a day ({@link BuildLogBundle#MAX_AGE}), so an app that reads it later (a mail draft, after this
 * process has ended: Android starts it again for the provider) still finds it. Manifest: android/app/src/main/AndroidManifest.xml, a PEANUTS block.
 */
public final class BuildLogProvider extends ContentProvider {
    public static String authority(Context c) { return c.getPackageName() + ".buildlog"; }

    public static Uri uri(Context c, File zip) { return Uri.parse("content://" + authority(c) + "/" + Uri.encode(zip.getName())); }

    private File file(Uri uri) throws FileNotFoundException {
        String n = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || !BuildLogBundle.validName(n)) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(BuildLogShare.outDir(getContext()), n);
        if (!f.isFile()) throw new FileNotFoundException(n + ": no longer there (a build log is kept for a day)");
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("build logs are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = file(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) { return BuildLogBundle.validName(uri.getLastPathSegment()) ? "application/zip" : null; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
