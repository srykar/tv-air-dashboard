package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Shell-only, write-only provisioning into private, non-backed-up app storage. */
public final class DysonSetupProvider extends ContentProvider {
    private File file(Uri uri) throws FileNotFoundException {
        if (!"/connection".equals(uri.getPath())) throw new FileNotFoundException();
        return new File(getContext().getFilesDir(), DysonConfig.FILE);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"w".equals(mode)) throw new FileNotFoundException("Write-only setup");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return file(uri).delete() ? 1 : 0; } catch (FileNotFoundException e) { return 0; }
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
