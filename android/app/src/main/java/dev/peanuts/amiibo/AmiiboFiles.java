package dev.peanuts.amiibo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** The files and folders the player picks for an import (the system's picker), read through AmiiboImport. Off the main thread */
public final class AmiiboFiles {
    private AmiiboFiles() {}

    static final int DEPTH = 6;

    /** Files the player picked (.bin, .zip); off the main thread */
    public static AmiiboImport.Result files(Context c, List<Uri> uris) {
        AmiiboImport.Result r = new AmiiboImport.Result();
        for (Uri u : uris) one(c, u, name(c, u), r);
        return r;
    }

    /** A folder the player picked (a document tree), with its subfolders; off the main thread */
    public static AmiiboImport.Result folder(Context c, Uri tree) {
        AmiiboImport.Result r = new AmiiboImport.Result();
        walk(c, tree, DocumentsContract.getTreeDocumentId(tree), 0, r);
        return r;
    }

    private static void walk(Context c, Uri tree, String doc, int depth, AmiiboImport.Result r) {
        if (depth > DEPTH) return;
        ContentResolver cr = c.getContentResolver();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor q = cr.query(kids, cols, null, null, null)) {
            while (q != null && q.moveToNext()) {
                String id = q.getString(0), name = q.getString(1), mime = q.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(c, tree, id, depth + 1, r);
                else one(c, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, r);
            }
        } catch (RuntimeException e) { /* a folder the provider refuses: left out */ }
    }

    private static void one(Context c, Uri u, String name, AmiiboImport.Result r) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) { r.skipped++; return; }
            if (low.endsWith(".zip")) { AmiiboImport.zip(c.getFilesDir(), in, r); return; }
            if (!low.endsWith(".bin") && !low.isEmpty()) return;   // other files in a folder are not counted
            byte[] b = AmiiboImport.read(in, AmiiboBin.MAX);
            if (b == null) { r.skipped++; return; }
            AmiiboImport.bytes(c.getFilesDir(), name, b, r);
        } catch (IOException | RuntimeException e) { r.skipped++; }
    }

    private static String name(Context c, Uri u) {
        try (Cursor q = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (RuntimeException e) { /* fall through */ }
        String p = u.getLastPathSegment();
        return p == null ? "" : AmiiboImport.leaf(p);
    }
}
