package com.tabpdf.tabs;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stands in for another app sharing a PDF: serves one asset of the test APK, like a messenger or
 * download manager would, without offering persistable permissions.
 *
 * Java on purpose: providers of the test APK run in its own process, which has no Kotlin runtime
 * (the test code itself runs inside the app's process, which does).
 */
public class TestPdfProvider extends ContentProvider {
    public static final String AUTHORITY = "com.tabpdf.test.pdfs";
    public static final String ASSET = "sample.pdf";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), ASSET);
        if (!file.exists()) {
            try (InputStream in = getContext().getAssets().open(ASSET); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = uri.getLastPathSegment();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                // As file apps do: the size, so a file opened again can be known (기획서 5.1).
                try (InputStream in = getContext().getAssets().open(ASSET)) {
                    long size = 0;
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) size += n;
                    row[i] = size;
                } catch (IOException e) {
                    row[i] = null;
                }
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
