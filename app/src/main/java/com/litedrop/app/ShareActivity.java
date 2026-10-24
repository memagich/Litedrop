package com.litedrop.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Meta share menija ("Pošalji na PC"). Nema vlastitog ekrana: uzme fajlove, preda ih servisu
 * koji ih šalje u pozadini i odmah se zatvori.
 */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store store = new Store(this);
        List<Uri> uris = collect(getIntent());
        if (!store.isPaired()) {
            Toast.makeText(this, "Prvo upari Litedrop s računarom.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
        } else if (uris.isEmpty()) {
            Toast.makeText(this, "Nema fajlova za slanje.", Toast.LENGTH_SHORT).show();
        } else {
            SyncService.startUpload(this, uris);
            Toast.makeText(this, "Šaljem na PC (" + uris.size() + ")…", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> collect(Intent in) {
        List<Uri> out = new ArrayList<Uri>();
        if (in == null) return out;
        ClipData cd = in.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty()) {
            if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
                ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) out.addAll(l);
            } else {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) out.add(u);
            }
        }
        return out;
    }
}
