package com.tabpdf.doc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * Stands in for an app whose provider gives other bytes each time a file is read: a 1×1 picture
 * the first time, a 4000×4000 one after that (SecurityTest). Java on purpose, as TestPdfProvider:
 * the test APK's providers run in its own process, which has no Kotlin runtime.
 */
public class ChangingImageProvider extends ContentProvider {
    public static final String AUTHORITY = "com.tabpdf.test.images";
    public static final int LARGE = 4000;

    private final Map<String, Integer> reads = new HashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int read;
        synchronized (reads) {
            Integer n = reads.get(uri.getPath());
            read = n == null ? 0 : n;
            reads.put(uri.getPath(), read + 1);
        }
        int side = read == 0 ? 1 : LARGE;
        File file = new File(getContext().getCacheDir(), "changing-" + side + ".png");
        try {
            if (!file.exists()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(png(side, side));
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /** A black greyscale PNG, written row by row as SecurityTest's. */
    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        chunk(data, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).array());
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(pixels)) {
            byte[] row = new byte[width + 1]; // filter byte, then the row
            for (int y = 0; y < height; y++) z.write(row);
        }
        chunk(data, "IDAT", pixels.toByteArray());
        chunk(data, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(DataOutputStream data, String type, byte[] body) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        data.writeInt(body.length);
        data.write(name);
        data.write(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        data.writeInt((int) crc.getValue());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[] {uri.getLastPathSegment()});
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
