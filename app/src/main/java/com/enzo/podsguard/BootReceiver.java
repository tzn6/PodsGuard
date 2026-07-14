package com.enzo.podsguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (Prefs.get(context).getBoolean(Prefs.ENABLED, false)) {
            GuardService.start(context);
            PodsGuardListener.requestRefresh(context);
        }
    }
}
