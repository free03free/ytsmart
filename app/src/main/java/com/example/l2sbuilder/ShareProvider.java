package com.example.l2sbuilder;

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
import java.io.IOException;

/** يقدّم ملفات مؤقتة للمشاركة (مثل MainActivity.java) عبر content:// بلا أي مكتبة خارجية. */
public class ShareProvider extends ContentProvider {
    public static final String AUTH = "com.example.l2sbuilder.share";

    static File root(Context c) {
        File d = new File(c.getCacheDir(), "share_out");
        d.mkdirs();
        return d;
    }

    static Uri uriFor(Context c, File f) {
        String rel = root(c).toURI().relativize(f.toURI()).getPath();
        return new Uri.Builder().scheme("content").authority(AUTH).encodedPath("/" + Uri.encode(rel, "/")).build();
    }

    private File resolve(Uri u) throws FileNotFoundException {
        try {
            File r = root(getContext()).getCanonicalFile();
            File f = new File(r, u.getPath()).getCanonicalFile();
            if (!f.getPath().startsWith(r.getPath() + File.separator) || !f.isFile()) throw new FileNotFoundException();
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor mc = new MatrixCursor(cols);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            }
            mc.addRow(row);
            return mc;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
