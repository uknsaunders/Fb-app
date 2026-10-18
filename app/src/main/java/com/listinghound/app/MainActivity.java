package com.listinghound.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private TextView tvState, tvDetail, tvLogin;
    private View dot, rowBattery;
    private Button btnToggle, btnLogin, btnSlower;
    private Spinner spInterval, spStopAfter;
    private LinearLayout llSearches, llFinds;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean binding;
    private long shownCheck = -1;
    private boolean backfilling;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            long last = Store.lastCheck(MainActivity.this);
            if (last != shownCheck) {
                shownCheck = last;
                renderFinds();
                backfillListingTimes();
            }
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Notif.channels(this);

        tvState = findViewById(R.id.tvState);
        tvDetail = findViewById(R.id.tvDetail);
        tvLogin = findViewById(R.id.tvLogin);
        dot = findViewById(R.id.dot);
        rowBattery = findViewById(R.id.rowBattery);
        btnToggle = findViewById(R.id.btnToggle);
        btnLogin = findViewById(R.id.btnLogin);
        btnSlower = findViewById(R.id.btnSlower);
        spInterval = findViewById(R.id.spInterval);
        spStopAfter = findViewById(R.id.spStopAfter);
        llSearches = findViewById(R.id.llSearches);
        llFinds = findViewById(R.id.llFinds);

        btnToggle.setOnClickListener(v -> toggle());
        findViewById(R.id.btnCheckNow).setOnClickListener(v -> checkNow());
        btnSlower.setOnClickListener(v -> {
            Store.stepInterval(this, +1);
            WatchService.send(this, WatchService.ACT_RESCHEDULE);
            bindSpinners();
            refreshStatus();
        });
        btnLogin.setOnClickListener(v -> loginOrOut());
        findViewById(R.id.btnBattery).setOnClickListener(v -> askBattery());
        findViewById(R.id.btnAdd).setOnClickListener(v ->
                startActivity(new Intent(this, EditSearchActivity.class)));
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            Store.clearFinds(this);
            renderFinds();
        });

        setupSpinners();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindSpinners();
        renderSearches();
        renderFinds();
        refreshLogin();
        refreshBattery();
        backfillListingTimes();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ---- Status ----

    private void refreshStatus() {
        boolean running = Store.running(this);
        int on = 0;
        for (Search s : Store.searches(this)) if (s.enabled) on++;

        tvState.setText(running ? "Watching" : "Stopped");
        dot.setBackgroundTintList(ColorStateList.valueOf(getColor(running ? R.color.ok : R.color.subtext)));
        btnToggle.setText(running ? "Stop watching" : "Start watching");
        btnSlower.setEnabled(running && Store.intervalMin(this) < Store.INTERVALS[Store.INTERVALS.length - 1]);

        StringBuilder d = new StringBuilder();
        if (running) {
            d.append(on).append(" search").append(on == 1 ? "" : "es")
                    .append(" every ").append(Store.intervalMin(this)).append(" min");
            long next = Store.nextCheck(this);
            if (next == 0) d.append("\nChecking now…");
            else d.append("\nNext check ").append(Notif.time(this, next));
            long stopAt = Store.stopAt(this);
            if (stopAt > 0) d.append(" · stops ").append(Notif.time(this, stopAt));
        } else {
            d.append(on == 0 ? "Add a search, then start watching." : on + " search" + (on == 1 ? "" : "es") + " ready");
        }
        long last = Store.lastCheck(this);
        if (last > 0) {
            d.append("\nLast check ")
                    .append(DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    .append(" — ").append(Store.lastResult(this));
        }
        tvDetail.setText(d.toString());
    }

    private void toggle() {
        if (Store.running(this)) {
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_STOP));
        } else {
            boolean any = false;
            for (Search s : Store.searches(this)) if (s.enabled && !s.query.trim().isEmpty()) any = true;
            if (!any) {
                Toast.makeText(this, "Add a search first", Toast.LENGTH_SHORT).show();
                return;
            }
            Store.setStopAt(this, 0);
            WatchService.start(this);
            if (!Fetcher.loggedIn()) {
                Toast.makeText(this, "Tip: log in to Facebook below for more reliable results", Toast.LENGTH_LONG).show();
            }
        }
        handler.postDelayed(this::refreshStatus, 400);
    }

    private void checkNow() {
        if (Store.running(this)) {
            WatchService.send(this, WatchService.ACT_CHECK);
            handler.postDelayed(this::refreshStatus, 400);
            return;
        }
        // One-off check without starting the watcher
        Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Checker.Summary sum = Checker.runAll(app);
            handler.post(() -> {
                Store.setLastCheck(app, System.currentTimeMillis());
                Store.setLastResult(app, sum.text);
                Notif.finds(app, sum.fresh);
                Toast.makeText(app, sum.text, Toast.LENGTH_LONG).show();
                refreshStatus();
                renderFinds();
            });
        }).start();
    }

    // ---- Timing ----

    private void setupSpinners() {
        String[] iv = new String[Store.INTERVALS.length];
        for (int i = 0; i < iv.length; i++) iv[i] = Store.INTERVALS[i] + " minutes";
        ArrayAdapter<String> a1 = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, iv);
        a1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInterval.setAdapter(a1);

        String[] sa = new String[Store.STOP_AFTER_HOURS.length];
        for (int i = 0; i < sa.length; i++) {
            int h = Store.STOP_AFTER_HOURS[i];
            sa[i] = h == 0 ? "Never — run until I stop it" : h + (h == 1 ? " hour" : " hours");
        }
        ArrayAdapter<String> a2 = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sa);
        a2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spStopAfter.setAdapter(a2);

        spInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (binding) return;
                int m = Store.INTERVALS[pos];
                if (m == Store.intervalMin(MainActivity.this)) return;
                Store.setIntervalMin(MainActivity.this, m);
                WatchService.send(MainActivity.this, WatchService.ACT_RESCHEDULE);
                refreshStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        spStopAfter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (binding) return;
                int h = Store.STOP_AFTER_HOURS[pos];
                if (h == Store.stopAfterHours(MainActivity.this)) return;
                Store.setStopAfterHours(MainActivity.this, h);
                if (Store.running(MainActivity.this)) {
                    Store.setStopAt(MainActivity.this, h > 0 ? System.currentTimeMillis() + h * 3600_000L : 0);
                    WatchService.send(MainActivity.this, WatchService.ACT_RESCHEDULE);
                }
                refreshStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void bindSpinners() {
        binding = true;
        spInterval.setSelection(indexOf(Store.INTERVALS, Store.intervalMin(this)), false);
        spStopAfter.setSelection(indexOf(Store.STOP_AFTER_HOURS, Store.stopAfterHours(this)), false);
        // Listeners fire after layout; release the guard once they have
        handler.post(() -> binding = false);
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    // ---- Login & battery ----

    private void refreshLogin() {
        boolean in = Fetcher.loggedIn();
        tvLogin.setText(in ? "Facebook: logged in" : "Facebook: not logged in");
        btnLogin.setText(in ? "Log out" : "Log in");
    }

    private void loginOrOut() {
        if (Fetcher.loggedIn()) {
            new AlertDialog.Builder(this)
                    .setMessage("Log out of Facebook inside Listing Hound?")
                    .setPositiveButton("Log out", (d, w) -> {
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(ok -> {
                            cm.flush();
                            runOnUiThread(this::refreshLogin);
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
    }

    private void refreshBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean ignoring = pm.isIgnoringBatteryOptimizations(getPackageName());
        rowBattery.setVisibility(ignoring ? View.GONE : View.VISIBLE);
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---- Lists ----

    private void renderSearches() {
        llSearches.removeAllViews();
        List<Search> list = Store.searches(this);
        LayoutInflater inf = getLayoutInflater();
        if (list.isEmpty()) {
            addEmpty(llSearches, "No searches yet. Tap “+ Add search”.");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Search s = list.get(i);
            View row = inf.inflate(R.layout.item_row, llSearches, false);
            ((TextView) row.findViewById(R.id.title)).setText(s.displayName());
            ((TextView) row.findViewById(R.id.sub)).setText(s.summary());
            Switch sw = row.findViewById(R.id.toggle);
            sw.setVisibility(View.VISIBLE);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener((btn, on) -> {
                Search cur = Store.search(this, s.id);
                if (cur == null) return;
                cur.enabled = on;
                Store.upsert(this, cur);
                WatchService.send(this, WatchService.ACT_RESCHEDULE);
                refreshStatus();
            });
            row.setOnClickListener(v -> startActivity(
                    new Intent(this, EditSearchActivity.class).putExtra("id", s.id)));
            if (i > 0) addDivider(llSearches);
            llSearches.addView(row);
        }
    }

    private void renderFinds() {
        llFinds.removeAllViews();
        List<Listing> finds = Store.finds(this);
        if (finds.isEmpty()) {
            addEmpty(llFinds, "New listings and price drops will appear here. The first check of a search only records what's already listed, so you're alerted to new ones after that.");
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < Math.min(50, finds.size()); i++) {
            Listing l = finds.get(i);
            View row = inf.inflate(R.layout.item_row, llFinds, false);
            ((TextView) row.findViewById(R.id.title)).setText(l.headline());
            ((TextView) row.findViewById(R.id.sub)).setText(findDetail(l));
            row.setOnClickListener(v -> open(l.url()));
            if (i > 0) addDivider(llFinds);
            llFinds.addView(row);
        }
    }

    private String findDetail(Listing l) {
        StringBuilder b = new StringBuilder(l.searchName);
        if (!l.city.isEmpty()) b.append(" · ").append(l.city);
        b.append("\nSpotted ").append(Notif.time(this, l.foundAt));
        if ("new".equals(l.kind)) {
            if (l.listedAt > 0) {
                long mins = Math.max(0, (l.foundAt - l.listedAt) / 60_000L);
                b.append(" · listed ").append(Notif.time(this, l.listedAt))
                        .append(" (").append(mins < 1 ? "under a minute" : mins + " min").append(" before)");
            } else if (l.listedAt == 0) {
                b.append(" · looking up when it was listed…");
            } else {
                b.append(" · listing time not available");
            }
        } else if ("updated".equals(l.kind) && !l.oldTitle.isEmpty()) {
            b.append(" · was “").append(l.oldTitle).append("”");
        }
        if (!DateUtils.isToday(l.foundAt)) {
            b.append(" · ").append(DateUtils.formatDateTime(this, l.foundAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
        }
        return b.toString();
    }

    /** Finds saved before listing times were recorded, or whose lookup hasn't run yet. */
    private void backfillListingTimes() {
        if (backfilling) return;
        List<Listing> todo = new java.util.ArrayList<>();
        for (Listing l : Store.finds(this)) {
            if ("new".equals(l.kind) && l.listedAt == 0 && todo.size() < 5) todo.add(l);
        }
        if (todo.isEmpty()) return;
        backfilling = true;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            for (Listing l : todo) Store.setListedAt(app, l.key(), Fetcher.listedAt(l.id));
            handler.post(() -> {
                backfilling = false;
                renderFinds();
            });
        }).start();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open the listing", Toast.LENGTH_SHORT).show();
        }
    }

    private void addEmpty(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.subtext));
        t.setTextSize(14);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        t.setPadding(0, p, 0, p);
        parent.addView(t);
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.line));
        parent.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) getResources().getDisplayMetrics().density)));
    }
}
