package com.litedrop.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.litedrop.core.Api;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** Sprema primljene fajlove u javnu mapu Downloads (MediaStore; ne traži dozvolu za pohranu). */
final class Saver {
    private Saver() {
    }

    /**
     * Preuzima fajl s računara direktno u Downloads. Dok traje preuzimanje fajl je "pending"
     * (nevidljiv drugim aplikacijama), a nepotpun fajl se briše. Ako ime već postoji,
     * Android sam dodaje " (1)".
     *
     * @return ime pod kojim je fajl spremljen
     */
    static String saveDownload(Context ctx, Api api, Api.Item it, Api.Progress p) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, it.name);
        v.put(MediaStore.Downloads.MIME_TYPE, mimeFor(it.name));
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Ne mogu napraviti fajl u Downloads");
        try {
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IOException("Ne mogu otvoriti fajl za pisanje");
            try {
                api.download(it, out, p);
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            try {
                cr.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // nema šta više
            }
            throw e;
        }
        return finalName(cr, uri, it.name);
    }

    private static String finalName(ContentResolver cr, Uri uri, String fallback) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
            // ime nije bitno za uspjeh
        } finally {
            if (c != null) c.close();
        }
        return fallback;
    }

    static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}
