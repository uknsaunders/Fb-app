package com.listinghound.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

public class EditSearchActivity extends Activity {

    private EditText etQuery, etName, etLocation, etRadius, etMin, etMax, etInclude, etExclude;
    private Search original;
    private Switch swDrops, swUpdates;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_edit);

        etQuery = findViewById(R.id.etQuery);
        etName = findViewById(R.id.etName);
        etLocation = findViewById(R.id.etLocation);
        etRadius = findViewById(R.id.etRadius);
        etMin = findViewById(R.id.etMin);
        etMax = findViewById(R.id.etMax);
        etInclude = findViewById(R.id.etInclude);
        etExclude = findViewById(R.id.etExclude);
        swDrops = findViewById(R.id.swDrops);
        swUpdates = findViewById(R.id.swUpdates);

        String id = getIntent().getStringExtra("id");
        original = id == null ? null : Store.search(this, id);
        swDrops.setChecked(original == null || original.alertDrops);
        swUpdates.setChecked(original == null || original.alertUpdates);

        ((TextView) findViewById(R.id.tvHeading)).setText(original == null ? "New search" : "Edit search");
        findViewById(R.id.btnDelete).setVisibility(original == null ? View.GONE : View.VISIBLE);

        if (original != null) {
            etQuery.setText(original.query);
            etName.setText(original.name);
            etLocation.setText(original.location);
            etRadius.setText(original.radiusKm > 0 ? String.valueOf(original.radiusKm) : "");
            etMin.setText(original.minPrice > 0 ? String.valueOf(original.minPrice) : "");
            etMax.setText(original.maxPrice > 0 ? String.valueOf(original.maxPrice) : "");
            etInclude.setText(original.include);
            etExclude.setText(original.exclude);
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnPreview).setOnClickListener(v -> preview());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
    }

    private Search fromForm() {
        Search s = new Search();
        if (original != null) {
            s.id = original.id;
            s.enabled = original.enabled;
        }
        s.query = etQuery.getText().toString().trim();
        s.name = etName.getText().toString().trim();
        s.location = etLocation.getText().toString().trim();
        s.radiusKm = num(etRadius);
        s.minPrice = num(etMin);
        s.maxPrice = num(etMax);
        s.include = etInclude.getText().toString().trim();
        s.exclude = etExclude.getText().toString().trim();
        s.alertDrops = swDrops.isChecked();
        s.alertUpdates = swUpdates.isChecked();
        return s;
    }

    private static int num(EditText e) {
        try {
            return Math.max(0, Integer.parseInt(e.getText().toString().trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void save() {
        Search s = fromForm();
        if (s.query.isEmpty()) {
            etQuery.setError("Enter something to search for");
            return;
        }
        if (s.minPrice > 0 && s.maxPrice > 0 && s.minPrice > s.maxPrice) {
            etMax.setError("Max is below min");
            return;
        }
        // New results area: record what's there now on the next check instead of alerting on all of it
        if (s.resultsDifferFrom(original)) Store.clearSeen(this, s.id);
        Store.upsert(this, s);
        WatchService.send(this, WatchService.ACT_RESCHEDULE);
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage("Delete this search?")
                .setPositiveButton("Delete", (d, w) -> {
                    Store.delete(this, original.id);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void preview() {
        Search s = fromForm();
        if (s.query.isEmpty()) {
            etQuery.setError("Enter something to search for");
            return;
        }
        ProgressDialog pd = ProgressDialog.show(this, null, "Checking Facebook…", true, false);
        new Thread(() -> {
            Fetcher.Result r = Fetcher.fetch(s);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                pd.dismiss();
                showPreview(s, r);
            });
        }).start();
    }

    private void showPreview(Search s, Fetcher.Result r) {
        StringBuilder m = new StringBuilder();
        String title;
        if (r.error != null) {
            title = "Couldn't check";
            m.append(r.error).append("\n\nCheck your connection and try again.");
        } else if (r.loginWall) {
            title = "Login needed";
            m.append("Facebook showed a login page instead of results. Log in from the main screen, then test again.");
        } else if (r.listings.isEmpty()) {
            title = "No listings found";
            m.append("Facebook returned a page but no listings could be read. Try a broader search, check the location, or log in.");
        } else {
            int match = 0;
            StringBuilder lines = new StringBuilder();
            for (Listing l : r.listings) {
                if (!s.matches(l)) continue;
                match++;
                if (match <= 12) {
                    lines.append("• ")
                            .append(l.priceOrBlank().isEmpty() ? "" : l.price + " — ")
                            .append(l.title)
                            .append(l.city.isEmpty() ? "" : " (" + l.city + ")")
                            .append('\n');
                }
            }
            title = match + " of " + r.listings.size() + " listings match";
            m.append(lines);
            if (match > 12) m.append("…and ").append(match - 12).append(" more\n");
            if (match == 0) m.append("Your title filters rule them all out — loosen “must contain” or “skip” words.");
            else m.append("\nThese are already listed, so they won't alert. You'll be told about new ones and price drops.");
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(m.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }
}
