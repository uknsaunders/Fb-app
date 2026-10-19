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

    private EditText etQuery, etName, etLocation, etRadius, etMin, etMax, etExclude, etDescInc, etDescExc;
    private Switch swTitleOnly;
    private Search original;
    private Switch swArea;
    private android.widget.Spinner spCategory, spIncludeMode;
    private android.widget.Spinner spNew, spDrop, spRenewed, spUpdated;

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
        etDescInc = findViewById(R.id.etDescInc);
        etDescExc = findViewById(R.id.etDescExc);
        swTitleOnly = findViewById(R.id.swTitleOnly);
        spCategory = findViewById(R.id.spCategory);
        String[] cats = new String[Search.CATEGORIES.length];
        for (int i = 0; i < cats.length; i++) cats[i] = Search.CATEGORIES[i][1];
        android.widget.ArrayAdapter<String> ca = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, cats);
        ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(ca);
        spIncludeMode = findViewById(R.id.spIncludeMode);
        android.widget.ArrayAdapter<String> ia = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Match ALL keywords", "Match ANY keyword (searches each one)"});
        ia.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spIncludeMode.setAdapter(ia);
        etExclude = findViewById(R.id.etExclude);
        spNew = modeSpinner(R.id.spNew);
        spDrop = modeSpinner(R.id.spDrop);
        spRenewed = modeSpinner(R.id.spRenewed);
        spUpdated = modeSpinner(R.id.spUpdated);
        swArea = findViewById(R.id.swArea);

        String id = getIntent().getStringExtra("id");
        original = id == null ? null : Store.search(this, id);
        Search d = original != null ? original : new Search();
        spNew.setSelection(d.modeNew);
        spDrop.setSelection(d.modeDrop);
        spRenewed.setSelection(d.modeRenewed);
        spUpdated.setSelection(d.modeUpdated);
        swArea.setChecked(original == null || original.strictArea);
        swTitleOnly.setChecked(original == null || original.titleOnly);

        ((TextView) findViewById(R.id.tvHeading)).setText(original == null ? "New search" : "Edit search");
        findViewById(R.id.btnDelete).setVisibility(original == null ? View.GONE : View.VISIBLE);

        if (original != null) {
            etQuery.setText(original.query);
            etName.setText(original.name);
            etLocation.setText(original.location);
            etRadius.setText(original.radiusKm > 0 ? String.valueOf(original.radiusKm) : "");
            etMin.setText(original.minPrice > 0 ? String.valueOf(original.minPrice) : "");
            etMax.setText(original.maxPrice > 0 ? String.valueOf(original.maxPrice) : "");
            etDescInc.setText(original.descInclude);
            etDescExc.setText(original.descExclude);
            for (int i = 0; i < Search.CATEGORIES.length; i++)
                if (Search.CATEGORIES[i][0].equals(original.category)) spCategory.setSelection(i);
            spIncludeMode.setSelection(original.includeAny ? 1 : 0);
            etExclude.setText(original.exclude);
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnPreview).setOnClickListener(v -> preview());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
    }

    private android.widget.Spinner modeSpinner(int id) {
        android.widget.Spinner sp = findViewById(id);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Search.MODE_LABELS);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        return sp;
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
        s.include = "";
        s.titleOnly = swTitleOnly.isChecked();
        s.descInclude = etDescInc.getText().toString().trim();
        s.descExclude = etDescExc.getText().toString().trim();
        s.category = Search.CATEGORIES[spCategory.getSelectedItemPosition()][0];
        s.includeAny = spIncludeMode.getSelectedItemPosition() == 1;
        s.exclude = etExclude.getText().toString().trim();
        s.modeNew = spNew.getSelectedItemPosition();
        s.modeDrop = spDrop.getSelectedItemPosition();
        s.modeRenewed = spRenewed.getSelectedItemPosition();
        s.modeUpdated = spUpdated.getSelectedItemPosition();
        s.strictArea = swArea.isChecked();
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
            etQuery.setError("Enter at least one keyword");
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
            etQuery.setError("Enter at least one keyword");
            return;
        }
        ProgressDialog pd = ProgressDialog.show(this, null, "Checking Facebook…", true, false);
        new Thread(() -> {
            Fetcher.Result r = Fetcher.fetch(s);
            boolean checkArea = s.strictArea && s.radiusKm > 0 && !s.location.trim().isEmpty();
            double[] centre = checkArea ? Geo.centre(getApplicationContext(), s) : null;
            if (centre != null) {
                for (Listing l : r.listings) {
                    double[] at = Geo.of(getApplicationContext(), l);
                    if (at != null) l.km = Geo.km(centre, at);
                }
            }
            final boolean areaUnknown = checkArea && centre == null;
            // Check descriptions for the first few matches so the test reflects those filters too
            final java.util.Set<String> descFail = new java.util.HashSet<>();
            if (s.hasDescFilter()) {
                int n = 0;
                for (Listing l : r.listings) {
                    if (!s.matches(l)) continue;
                    if (n++ >= 8) break;
                    if (!s.descOk(Fetcher.description(l.id))) descFail.add(l.id);
                }
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                pd.dismiss();
                showPreview(s, r, areaUnknown, descFail);
            });
        }).start();
    }

    private void showPreview(Search s, Fetcher.Result r, boolean areaUnknown, java.util.Set<String> descFail) {
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
            if (!s.category.isEmpty()) m.append("\n\nYou've picked the “").append(Search.categoryLabel(s.category))
                    .append("” category. If it stays empty, try Any category and add skip words instead (e.g. artificial, christmas).");
        } else {
            int match = 0, far = 0;
            StringBuilder lines = new StringBuilder();
            for (Listing l : r.listings) {
                if (!s.matches(l)) continue;
                if (l.km >= 0 && l.km > s.radiusKm * 1.15 + 3) {
                    far++;
                    continue;
                }
                if (descFail.contains(l.id)) continue;
                match++;
                if (match <= 12) {
                    lines.append("• ")
                            .append(l.priceOrBlank().isEmpty() ? "" : l.price + " — ")
                            .append(l.title)
                            .append(l.city.isEmpty() ? "" : " (" + l.city
                                    + (l.km >= 0 ? ", " + Math.round(l.km) + " km" : "") + ")")
                            .append('\n');
                }
            }
            title = match + " of " + r.listings.size() + " listings match";
            m.append(lines);
            if (match > 12) m.append("…and ").append(match - 12).append(" more\n");
            if (!descFail.isEmpty()) m.append("\n").append(descFail.size()).append(" more ruled out by their description.\n");
            if (s.fbQueries().size() > 1) m.append("\nSearched Facebook for each keyword separately: ")
                    .append(String.join(" · ", s.fbQueries())).append(".\n");
            if (far > 0) m.append("\n").append(far).append(" more were outside your radius and will be ignored.\n");
            if (areaUnknown) m.append("\nCouldn't find “").append(s.location).append("” on the map, so distances can't be checked. Try the town's full name.\n");
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
