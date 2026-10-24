package com.litedrop.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Pokreće prijem nakon paljenja telefona i nakon ažuriranja aplikacije. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Store store = new Store(context);
        if (store.isPaired() && store.enabled()) SyncService.start(context);
    }
}
