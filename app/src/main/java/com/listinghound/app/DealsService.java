package com.listinghound.app;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Runs one deals scan in the background, then stops. */
public class DealsService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Deals.channel(this);
        Notification n = new Notification.Builder(this, Deals.CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Looking for deals…")
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(44, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(44, n);
        }
        if (Deals.scanning) return START_NOT_STICKY;
        Deals.scanning = true;
        notifyListeners();

        PowerManager.WakeLock wl = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListingHound:deals");
        wl.acquire(10 * 60_000L);
        new Thread(() -> {
            try {
                Deals.scan(getApplicationContext());
            } catch (Throwable ignored) {
            } finally {
                Deals.scanning = false;
                if (wl.isHeld()) wl.release();
                new Handler(Looper.getMainLooper()).post(() -> {
                    notifyListeners();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }
        }).start();
        return START_NOT_STICKY;
    }

    private static void notifyListeners() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Runnable r : Deals.listeners) r.run();
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
