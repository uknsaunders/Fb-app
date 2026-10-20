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

    private TextView tvState, tvDetail, tvLogin, tvStats;
    private View dot, rowBattery, rowExact;
    private Button btnToggle, btnLogin, btnSlower;
    private Spinner spInterval, spStopAfter;
    private LinearLayout llSearches, llFinds, llFilters, llDeals;
    private final Runnable dealsChanged = this::renderDeals;
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
        Categories.load(this);
        Notif.channels(this);

        tvState = findViewById(R.id.tvState);
        tvDetail = findViewById(R.id.tvDetail);
        tvLogin = findViewById(R.id.tvLogin);
        tvStats = findViewById(R.id.tvStats);
        dot = findViewById(R.id.dot);
        rowBattery = findViewById(R.id.rowBattery);
        rowExact = findViewById(R.id.rowExact);
        btnToggle = findViewById(R.id.btnToggle);
        btnLogin = findViewById(R.id.btnLogin);
        btnSlower = findViewById(R.id.btnSlower);
        spInterval = findViewById(R.id.spInterval);
        spStopAfter = findViewById(R.id.spStopAfter);
        llSearches = findViewById(R.id.llSearches);
        llFinds = findViewById(R.id.llFinds);
        llFilters = findViewById(R.id.llFilters);
        llDeals = findViewById(R.id.llDeals);
        findViewById(R.id.btnDealsNow).setOnClickListener(v -> {
            Deals.runNow(this);
            Toast.makeText(this, "Checking for deals in the background…", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnDealsSettings).setOnClickListener(v ->
                startActivity(new Intent(this, DealsSettingsActivity.class)));
        Deals.schedule(this);
        setupTabs();

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
        findViewById(R.id.btnExact).setOnClickListener(v -> askExact());
        findViewById(R.id.btnAdd).setOnClickListener(v ->
                startActivity(new Intent(this, EditSearchActivity.class)));
        findViewById(R.id.btnClear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Clear all recent finds? Your searches aren't affected.")
                .setPositiveButton("Clear", (d, w) -> {
                    Store.clearFinds(this);
                    renderFinds();
                })
                .setNegativeButton("Cancel", null)
                .show());
        findViewById(R.id.btnTidy).setOnClickListener(v -> tidyFinds());

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
        Deals.listeners.add(dealsChanged);
        renderDeals();
        if (getIntent().getBooleanExtra("showDeals", false)) {
            getIntent().removeExtra("showDeals");
            showTab("deals");
        }
        handler.post(ticker);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onPause() {
        Deals.listeners.remove(dealsChanged);
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
            else if (next < System.currentTimeMillis() - 60_000L)
                d.append("\nCheck overdue (was due ").append(Notif.time(this, next)).append(") — phone paused it");
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
                Notif.finds(app, sum.alerts);
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
        rowExact.setVisibility(AlarmReceiver.canBeExact(this) ? View.GONE : View.VISIBLE);
        // Permissions may have just been granted: put the alarm on the precise path
        if (Store.running(this)) WatchService.send(this, WatchService.ACT_RESCHEDULE);
    }

    private void askExact() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Open Settings › Apps › Listing Hound › Alarms & reminders", Toast.LENGTH_LONG).show();
        }
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
        tvStats.setText(Timing.headline(this));
        llFinds.removeAllViews();
        String stats = Timing.summary(this);
        if (!stats.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(stats);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(14);
            t.setLineSpacing(0, 1.15f);
            int p = (int) (12 * getResources().getDisplayMetrics().density);
            t.setPadding(0, p, 0, p);
            llFinds.addView(t);
            addDivider(llFinds);
        }
        List<Listing> all = Store.finds(this);
        String filter = getSharedPreferences("ui", MODE_PRIVATE).getString("findFilter", "all");
        renderFilters(all, filter);
        List<Listing> finds = new java.util.ArrayList<>();
        for (Listing l : all) if (filter.equals("all") || filter.equals(l.kind)) finds.add(l);
        if (finds.isEmpty()) {
            if (!all.isEmpty()) {
                addEmpty(llFinds, "Nothing in this category yet.");
                return;
            }
            if (!stats.isEmpty()) return;
            addEmpty(llFinds, "New listings and price drops will appear here. The first check of a search only records what's already listed, so you're alerted to new ones after that.");
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < Math.min(50, finds.size()); i++) {
            Listing l = finds.get(i);
            View row = inf.inflate(R.layout.item_row, llFinds, false);
            ((TextView) row.findViewById(R.id.title)).setText(l.headline());
            ((TextView) row.findViewById(R.id.sub)).setText(findDetail(l));
            row.setOnClickListener(v -> startActivity(ListingViewActivity.intent(this, l)));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(l.headline())
                        .setItems(new String[]{"Open listing", "Remove from list", "Check listing time (diagnostic)"}, (d, which) -> {
                            if (which == 0) startActivity(ListingViewActivity.intent(this, l));
                            else if (which == 1) {
                                Store.removeFind(this, l.key());
                                renderFinds();
                            } else diagnose(l);
                        })
                        .show();
                return true;
            });
            if (i > 0) addDivider(llFinds);
            llFinds.addView(row);
        }
    }

    private static final String[] TAB_KEYS = {"setup", "searches", "deals", "finds"};
    private static final int[] TAB_IDS = {R.id.tabSetup, R.id.tabSearches, R.id.tabDeals, R.id.tabFinds};
    private static final int[] SEC_IDS = {R.id.secSetup, R.id.secSearches, R.id.secDeals, R.id.secFinds};

    private void setupTabs() {
        for (int i = 0; i < TAB_KEYS.length; i++) {
            String k = TAB_KEYS[i];
            findViewById(TAB_IDS[i]).setOnClickListener(v -> showTab(k));
        }
        intro(R.id.secSetup, "How often Listing Hound checks Facebook, and when it stops by itself. "
                + "Log in to Facebook here, and allow battery and alarm access so checks keep running while your phone sleeps.");
        intro(R.id.secSearches, "What you're looking for. Each search has its own area, price range and words to include or skip. "
                + "You choose what you're told about: new listings, price drops, renewed listings and title changes. "
                + "Tap a search to change it; use its switch to pause it.");
        intro(R.id.secDeals, "Once a day Listing Hound scores what's for sale now from 0 to 100: price against the typical price, "
                + "reductions, how long it's been unsold, how new it is and how far away. "
                + "High scores send a “Consider this” alert. Settings changes what counts.");
        intro(R.id.secFinds, "Everything the watcher has spotted since it started, newest first. "
                + "Use the buttons to show one kind. Tap to open, long-press to remove; Tidy removes anything outside your radius.");
        // Deal settings also live under Set-up, so all settings are in one place
        LinearLayout setup = findViewById(R.id.secSetup);
        Button ds = new Button(this, null, 0, R.style.TextButton);
        ds.setText("DealRank settings ›");
        ds.setOnClickListener(v -> startActivity(new Intent(this, DealsSettingsActivity.class)));
        setup.addView(ds);

        String saved = getSharedPreferences("ui", MODE_PRIVATE).getString("tab", null);
        if (saved == null) saved = Store.searches(this).isEmpty() ? "searches" : "finds";
        showTab(saved);
    }

    private void intro(int sectionId, String text) {
        LinearLayout sec = findViewById(sectionId);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.subtext));
        t.setTextSize(14);
        t.setLineSpacing(0, 1.15f);
        int p = (int) (4 * getResources().getDisplayMetrics().density);
        t.setPadding(p, 0, p, p * 3);
        sec.addView(t, 0);
    }

    private void showTab(String key) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("tab", key).apply();
        for (int i = 0; i < TAB_KEYS.length; i++) {
            boolean on = TAB_KEYS[i].equals(key);
            findViewById(SEC_IDS[i]).setVisibility(on ? View.VISIBLE : View.GONE);
            TextView t = findViewById(TAB_IDS[i]);
            t.setBackgroundResource(on ? R.drawable.pill_on : 0);
            t.setTextColor(getColor(on ? R.color.on_accent : R.color.text));
            t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void renderDeals() {
        llDeals.removeAllViews();
        StringBuilder st = new StringBuilder();
        if (Deals.scanning) {
            st.append("Checking for deals…");
        } else {
            long last = Deals.lastScan(this);
            if (last > 0) {
                st.append("Last checked ").append(Timing.when(this, last, System.currentTimeMillis()))
                        .append(" — ").append(Deals.lastScanNote(this));
            } else {
                st.append("No deals check yet. Tap “Check now”, or wait for the daily check.");
            }
            long next = Deals.nextScan(this);
            st.append(next > 0 ? "\nNext daily check " + Timing.when(this, next, System.currentTimeMillis())
                    + " · alerts at DealRank " + Deals.minScore(this) + "+" : "\nDaily check is off");
        }
        TextView status = new TextView(this);
        status.setText(st.toString());
        status.setTextColor(getColor(R.color.subtext));
        status.setTextSize(13);
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        status.setPadding(0, p, 0, p);
        llDeals.addView(status);

        List<Listing> deals = Deals.results(this);
        if (deals.isEmpty()) return;
        float dp = getResources().getDisplayMetrics().density;

        // Column headings
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(0, (int) (2 * dp), 0, (int) (6 * dp));
        head.addView(colHead("Score"), new LinearLayout.LayoutParams((int) (46 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
        head.addView(colHead("Price"), new LinearLayout.LayoutParams((int) (68 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
        head.addView(colHead("Item"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView sh = colHead("Search");
        sh.setGravity(android.view.Gravity.END);
        head.addView(sh, new LinearLayout.LayoutParams((int) (80 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
        addDivider(llDeals);
        llDeals.addView(head);

        LayoutInflater inf = getLayoutInflater();
        int min = Deals.minScore(this);
        for (int i = 0; i < Math.min(15, deals.size()); i++) {
            Listing l = deals.get(i);
            addDivider(llDeals);
            View row = inf.inflate(R.layout.item_deal, llDeals, false);
            TextView sc = row.findViewById(R.id.score);
            sc.setText(String.valueOf(l.score));
            // Badge colour: alert-worthy = orange, worth a look = green, weak = grey
            int col = l.score >= min ? R.color.accent : l.score >= 50 ? R.color.ok : R.color.subtext;
            sc.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(col)));
            ((TextView) row.findViewById(R.id.price)).setText(l.priceOrBlank().isEmpty() ? "—" : l.price);
            ((TextView) row.findViewById(R.id.item)).setText(l.title);
            StringBuilder why = new StringBuilder();
            if (!l.city.isEmpty()) why.append(l.city);
            if (l.km >= 0) why.append(why.length() > 0 ? " · " : "").append(Math.round(l.km)).append(" km");
            String cat = Categories.nameFor(l.categoryId);
            if (!cat.isEmpty()) why.append(why.length() > 0 ? " · " : "").append(cat);
            if (!l.reasons.isEmpty()) why.append(why.length() > 0 ? "\n" : "").append(l.reasons);
            ((TextView) row.findViewById(R.id.why)).setText(why.toString());
            ((TextView) row.findViewById(R.id.tag)).setText(l.searchName);
            row.setOnClickListener(v -> startActivity(ListingViewActivity.intent(this, l)));
            llDeals.addView(row);
        }
    }

    private TextView colHead(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(java.util.Locale.UK));
        t.setTextColor(getColor(R.color.subtext));
        t.setTextSize(11);
        t.setLetterSpacing(0.06f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private static final String[][] FILTERS = {
            {"all", "All"}, {"new", "New"}, {"drop", "Price drops"}, {"renewed", "Renewed"}, {"updated", "Title changed"}};

    private void renderFilters(List<Listing> all, String current) {
        llFilters.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (String[] f : FILTERS) {
            int n = 0;
            for (Listing l : all) if (f[0].equals("all") || f[0].equals(l.kind)) n++;
            if (n == 0 && !f[0].equals("all") && !f[0].equals(current)) continue;
            TextView t = new TextView(this);
            boolean on = f[0].equals(current);
            t.setText(f[1] + " " + n);
            t.setTextSize(14);
            t.setTextColor(getColor(on ? R.color.on_accent : R.color.text));
            t.setBackgroundResource(on ? R.drawable.pill_on : R.drawable.pill_off);
            t.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            t.setOnClickListener(v -> {
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("findFilter", f[0]).apply();
                renderFinds();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * dp));
            llFilters.addView(t, lp);
        }
    }

    private String findDetail(Listing l) {
        StringBuilder b = new StringBuilder(l.searchName);
        if (!l.city.isEmpty()) b.append(" · ").append(l.city);
        if (l.km >= 0) b.append(" (").append(Math.round(l.km)).append(" km)");
        String cat = Categories.nameFor(l.categoryId);
        if (!cat.isEmpty()) b.append(" · ").append(cat);
        String timing = Timing.explain(this, l);
        if (!timing.isEmpty()) {
            b.append('\n').append(timing);
        } else {
            b.append("\nSpotted ").append(Notif.time(this, l.foundAt));
            if ("new".equals(l.kind)) {
                if (l.listedAt == 0) b.append(" · looking up when it was listed…");
                else if (l.listedAt < 0) b.append(" · listing time not available");
            } else if ("updated".equals(l.kind) && !l.oldTitle.isEmpty()) {
                b.append(" · was “").append(l.oldTitle).append("”");
            }
        }
        b.append("\nListing ").append(l.id.length() > 6 ? "…" + l.id.substring(l.id.length() - 6) : l.id);
        if (!DateUtils.isToday(l.foundAt)) {
            b.append(" · ").append(DateUtils.formatDateTime(this, l.foundAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
        }
        return b.toString();
    }

    /** Finds saved before listing times were recorded, or whose lookup hasn't run yet. */
    private void backfillListingTimes() {
        if (backfilling) return;
        {
            List<Listing> all = Store.finds(this);
            boolean changed = false;
            for (Listing l : all) {
                if ("new".equals(l.kind) && l.listedAt > 0 && Timing.resurfaced(l)) {
                    l.kind = "renewed";
                    changed = true;
                }
            }
            if (changed) {
                Store.saveFinds(this, all);
                handler.post(this::renderFinds);
            }
        }
        android.content.SharedPreferences fx = getSharedPreferences("fixes", MODE_PRIVATE);
        if (!fx.getBoolean("listedAtV3", false)) {
            List<Listing> all = Store.finds(this);
            for (Listing l : all) if ("new".equals(l.kind)) l.listedAt = 0;
            Store.saveFinds(this, all);
            fx.edit().putBoolean("listedAtV3", true).apply();
        }
        List<Listing> todo = new java.util.ArrayList<>();
        for (Listing l : Store.finds(this)) {
            if ("new".equals(l.kind) && l.listedAt == 0 && todo.size() < 20) todo.add(l);
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

    /** Removes finds that are further away than their search's radius. */
    private void tidyFinds() {
        Toast.makeText(this, "Checking distances…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            java.util.Map<String, Search> byName = new java.util.HashMap<>();
            for (Search s : Store.searches(app)) byName.put(s.displayName(), s);
            java.util.Map<String, double[]> centres = new java.util.HashMap<>();
            List<Listing> keep = new java.util.ArrayList<>();
            int removed = 0, unknown = 0;
            for (Listing l : Store.finds(app)) {
                Search s = byName.get(l.searchName);
                if (s == null || s.radiusKm <= 0 || s.location.trim().isEmpty()) {
                    keep.add(l);
                    continue;
                }
                if (l.km < 0) {
                    double[] c = centres.containsKey(s.id) ? centres.get(s.id) : Geo.centre(app, s);
                    centres.put(s.id, c);
                    double[] at = Geo.of(app, l);
                    if (c != null && at != null) l.km = Geo.km(c, at);
                }
                if (l.km < 0) {
                    unknown++;
                    keep.add(l);
                } else if (l.km > s.radiusKm * 1.15 + 3) {
                    removed++;
                } else {
                    keep.add(l);
                }
            }
            Store.saveFinds(app, keep);
            final String msg = removed == 0 ? "Nothing outside your radius"
                    : "Removed " + removed + " outside your radius";
            final String extra = unknown > 0 ? " · " + unknown + " kept (no town given)" : "";
            handler.post(() -> {
                Toast.makeText(app, msg + extra, Toast.LENGTH_LONG).show();
                renderFinds();
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void diagnose(Listing l) {
        android.app.ProgressDialog pd = android.app.ProgressDialog.show(this, null, "Checking the listing page…", true, false);
        new Thread(() -> {
            String report = Fetcher.diagnose(l.id);
            handler.post(() -> {
                if (isFinishing()) return;
                pd.dismiss();
                new AlertDialog.Builder(this)
                        .setTitle("Listing time check")
                        .setMessage(report + "\n\nScreenshot this and send it to Claude.")
                        .setPositiveButton("OK", null)
                        .show();
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
