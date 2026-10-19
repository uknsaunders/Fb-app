package com.listinghound.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Daily deals alarm, and re-arming it after the phone restarts. */
public class DealsReceiver extends BroadcastReceiver {
    public static final String ACT_DAILY = "com.listinghound.DEALS_DAILY";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ACT_DAILY.equals(a)) {
            try {
                Deals.runNow(c);
            } catch (Exception ignored) {
            }
        }
        Deals.schedule(c); // next day, or after a restart
    }
}
