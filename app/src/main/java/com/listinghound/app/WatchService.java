package com.listinghound.app;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that checks the searches on a timer until stopped. */
public class WatchService extends Service {
    public static final String ACT_START = "com.listinghound.START";
    public static final String ACT_STOP = "com.listinghound.STOP";
    public static final String ACT_SLOWER = "com.listinghound.SLOWER";
    public static final String ACT_CHECK = "com.listinghound.CHECK";
    public static final String ACT_RESCHEDULE = "com.listinghound.RESCHEDULE";
    public static final String ACT_ALARM = "com.listinghound.ALARM";

    private Handler handler;
    private ExecutorService worker;
    private PowerManager.WakeLock wakeLock;
    private boolean busy;
    private final Random rnd = new Random();
    private final Runnable tick = this::runCheck;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, WatchService.class).setAction(ACT_START));
    }

    public static void send(Context c, String action) {
        if (!Store.running(c)) return;
        c.startService(new Intent(c, WatchService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
        Notif.channels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(false);

        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            // Restarted by the system after being killed
            if (!Store.running(this)) {
                stopWatching();
                return START_NOT_STICKY;
            }
            action = ACT_START;
        }

        switch (action) {
            case ACT_STOP:
                stopWatching();
                return START_NOT_STICKY;

            case ACT_SLOWER:
                Store.stepInterval(this, +1);
                reschedule();
                break;

            case ACT_RESCHEDULE:
                reschedule();
                break;

            case ACT_ALARM:
                // Woken by the system alarm: run if a check is due (the in-app timer may have been paused)
                holdWakeLock();
                if (!busy && System.currentTimeMillis() >= Store.nextCheck(this) - 20_000L) {
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                } else if (!busy) {
                    AlarmReceiver.set(this, Store.nextCheck(this));
                }
                break;

            case ACT_CHECK:
                handler.removeCallbacks(tick);
                handler.post(tick);
                break;

            case ACT_START:
            default:
                boolean wasRunning = Store.running(this);
                Store.setRunning(this, true);
                int h = Store.stopAfterHours(this);
                if (!wasRunning || Store.stopAt(this) == 0) {
                    Store.setStopAt(this, h > 0 ? System.currentTimeMillis() + h * 3600_000L : 0);
                }
                holdWakeLock();
                handler.removeCallbacks(tick);
                handler.post(tick);
                break;
        }
        refresh();
        return START_STICKY;
    }

    private void goForeground(boolean checking) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notif.ID_STATUS, Notif.status(this, checking),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notif.ID_STATUS, Notif.status(this, checking));
        }
    }

    private void refresh() {
        if (!Store.running(this)) return;
        getSystemService(NotificationManager.class).notify(Notif.ID_STATUS, Notif.status(this, busy));
    }

    private void holdWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListingHound:watch");
            wakeLock.setReferenceCounted(false);
        }
        // Renewed on every check, so it never outlives a stopped watcher by long
        wakeLock.acquire(2 * 3600_000L);
    }

    private void runCheck() {
        if (!Store.running(this)) return;
        long stopAt = Store.stopAt(this);
        if (stopAt > 0 && System.currentTimeMillis() >= stopAt) {
            Store.setLastResult(this, "stopped automatically at " + Notif.time(this, stopAt));
            stopWatching();
            return;
        }
        if (busy) return;
        busy = true;
        holdWakeLock();
        Store.setNextCheck(this, 0);
        refresh();

        final Context app = getApplicationContext();
        worker.execute(() -> {
            Checker.Summary sum;
            try {
                sum = Checker.runAll(app);
            } catch (Throwable t) {
                sum = new Checker.Summary();
                sum.text = "Error: " + t.getClass().getSimpleName();
            }
            final Checker.Summary result = sum;
            handler.post(() -> afterCheck(result));
        });
    }

    private void afterCheck(Checker.Summary sum) {
        busy = false;
        Store.setLastCheck(this, System.currentTimeMillis());
        Store.setLastResult(this, sum.text);
        Notif.finds(this, sum.fresh);

        if (sum.loginNeeded) {
            if (!Store.loginWarned(this)) {
                Store.setLoginWarned(this, true);
                Notif.problem(this, "Log in to Facebook",
                        "Facebook is showing a login page instead of results. Tap to log in inside Listing Hound.", true);
            }
        } else if (sum.failed == 0 && sum.searched > 0) {
            if (Store.loginWarned(this)) {
                Store.setLoginWarned(this, false);
                Notif.cancelProblem(this);
            }
        }

        if (!Store.running(this)) return;
        schedule();
        refresh();
    }

    private void schedule() {
        handler.removeCallbacks(tick);
        long base = Store.intervalMin(this) * 60_000L;
        long jitter = (long) (base * 0.1 * (rnd.nextDouble() * 2 - 1));
        long delay = Math.max(60_000L, base + jitter);
        Store.setNextCheck(this, System.currentTimeMillis() + delay);
        handler.postDelayed(tick, delay);
        AlarmReceiver.set(this, System.currentTimeMillis() + delay);
    }

    /** Interval changed: keep the last check, move the next one. */
    private void reschedule() {
        if (busy) return; // the check in progress will schedule itself
        handler.removeCallbacks(tick);
        long last = Store.lastCheck(this);
        long base = Store.intervalMin(this) * 60_000L;
        long delay = last == 0 ? 0 : Math.max(5_000L, last + base - System.currentTimeMillis());
        Store.setNextCheck(this, System.currentTimeMillis() + delay);
        handler.postDelayed(tick, delay);
        AlarmReceiver.set(this, System.currentTimeMillis() + delay);
    }

    private void stopWatching() {
        Store.setRunning(this, false);
        Store.setNextCheck(this, 0);
        Store.setStopAt(this, 0);
        if (handler != null) handler.removeCallbacks(tick);
        AlarmReceiver.cancel(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        if (worker != null) worker.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
