package com.litedrop.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Pomoć oko mreže: sav promet aplikacije ide preko Wi-Fi/Etherneta, ne preko mobilnih podataka ili VPN-a. */
final class Net {
    private Net() {
    }

    /**
     * Veže proces na Wi-Fi mrežu (ako postoji). Time se računar na lokalnoj mreži dostiže čak i kad
     * Wi-Fi nema internet pa je Android odabrao mobilne podatke kao zadanu mrežu.
     *
     * @return true ako je Wi-Fi (ili Ethernet) pronađen
     */
    @SuppressWarnings("deprecation")
    static boolean bindWifi(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    cm.bindProcessToNetwork(n);
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // nema dozvole ili servis nije dostupan: nastavi bez vezivanja
        }
        return false;
    }
}
