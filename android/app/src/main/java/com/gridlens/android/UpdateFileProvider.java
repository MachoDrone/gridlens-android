package com.gridlens.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Only the explicitly granted installer may read the one verified APK. */
public final class UpdateFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File apk(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || !"/update.apk".equals(uri.getEncodedPath())
                || uri.getEncodedQuery() != null || uri.getEncodedFragment() != null) {
            throw new FileNotFoundException("Unknown update.");
        }
        File file = new File(getContext().getCacheDir(), "updates/update.apk");
        if (!file.isFile()) throw new FileNotFoundException("No downloaded update.");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Updates are read only.");
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { apk(uri); } catch (FileNotFoundException error) { return null; }
        return "application/vnd.android.package-archive";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = apk(uri); } catch (FileNotFoundException error) { return null; }
        String[] columns = projection == null
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "gridlens-android.apk";
            if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
