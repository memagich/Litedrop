package com.litedrop.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.litedrop.core.Api;

/** Postavke aplikacije: podaci o uparenom računaru i zadnji status. */
final class Store {
    private final SharedPreferences p;

    Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences("litedrop", Context.MODE_PRIVATE);
    }

    String host() {
        return p.getString("host", null);
    }

    int port() {
        return p.getInt("port", Api.DEFAULT_PORT);
    }

    String token() {
        return p.getString("token", null);
    }

    String pcName() {
        return p.getString("pcName", "");
    }

    boolean isPaired() {
        return token() != null && host() != null;
    }

    /** false kad je korisnik pauzirao prijem. */
    boolean enabled() {
        return p.getBoolean("enabled", true);
    }

    void setEnabled(boolean v) {
        p.edit().putBoolean("enabled", v).apply();
    }

    String status() {
        return p.getString("status", "");
    }

    void setStatus(String s) {
        p.edit().putString("status", s).apply();
    }

    void savePairing(String host, int port, String token, String pcName) {
        p.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("token", token)
                .putString("pcName", pcName)
                .putBoolean("enabled", true)
                .apply();
    }

    /** Računar je promijenio IP adresu; uparivanje ostaje. */
    void saveAddress(String host, int port) {
        p.edit().putString("host", host).putInt("port", port).apply();
    }

    /** Token više ne vrijedi (opozvan na računaru). */
    void clearToken() {
        p.edit().remove("token").apply();
    }

    void clearAll() {
        p.edit().remove("token").remove("host").remove("pcName").remove("status").apply();
    }
}
