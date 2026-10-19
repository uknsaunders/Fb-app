package com.listinghound.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Sliders for how DealRank scores, when it runs and when it alerts. */
public class DealsSettingsActivity extends Activity {

    private LinearLayout body;
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        body.setPadding(pad, (int) (20 * dp), pad, (int) (32 * dp));
        sv.addView(body);
        setContentView(sv);

        heading("Deal settings");

        LinearLayout when = card();
        Switch daily = new Switch(this);
        daily.setText("Daily “Consider this” check");
        daily.setTextColor(getColor(R.color.text));
        daily.setTextSize(16);
        daily.setMinHeight((int) (48 * dp));
        daily.setChecked(Deals.dailyOn(this));
        daily.setOnCheckedChangeListener((v, on) -> {
            Deals.setDailyOn(this, on);
            Deals.schedule(this);
        });
        when.addView(daily);
        label(when, "Time of day");
        Spinner hour = new Spinner(this);
        String[] hours = new String[24];
        for (int h = 0; h < 24; h++) hours[h] = String.format(java.util.Locale.UK, "%02d:00", h);
        ArrayAdapter<String> ha = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, hours);
        ha.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        hour.setAdapter(ha);
        hour.setSelection(Deals.hour(this), false);
        hour.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, android.view.View v, int pos, long id) {
                Deals.setHour(DealsSettingsActivity.this, pos);
                Deals.schedule(DealsSettingsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });
        when.addView(hour, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp)));

        slider(when, "Alert me when DealRank is at least", 40, 95, Deals.minScore(this), v -> v + "",
                v -> Deals.setMinScore(this, v));
        slider(when, "Also look beyond each search's radius by", 0, 100, Deals.extraPct(this),
                v -> v == 0 ? "no extra" : "+" + v + "%", v -> Deals.setExtraPct(this, v));

        LinearLayout w = card();
        TextView intro = new TextView(this);
        intro.setText("How much each thing counts towards DealRank");
        intro.setTextColor(getColor(R.color.text));
        intro.setTextSize(16);
        w.addView(intro);
        for (String[] f : Deals.FACTORS) {
            String key = f[0];
            slider(w, f[1], 0, 100, Deals.weight(this, key), v -> v == 0 ? "off" : v + "",
                    v -> Deals.setWeight(this, key, v));
        }
        Button reset = new Button(this, null, 0, R.style.TextButton);
        reset.setText("Reset to defaults");
        reset.setOnClickListener(v -> {
            for (String[] f : Deals.FACTORS) Deals.setWeight(this, f[0], Deals.defaultWeight(f[0]));
            Deals.setMinScore(this, 75);
            Deals.setExtraPct(this, 50);
            recreate();
        });
        w.addView(reset);

        TextView note = new TextView(this);
        note.setText("Changes apply from the next check. Tap “Check now” on the main screen to see the effect straight away.");
        note.setTextColor(getColor(R.color.subtext));
        note.setTextSize(13);
        body.addView(note);
    }

    interface Fmt { String f(int v); }
    interface Save { void s(int v); }

    private void slider(LinearLayout parent, String name, int min, int max, int value, Fmt fmt, Save save) {
        TextView t = new TextView(this);
        t.setTextColor(getColor(R.color.subtext));
        t.setTextSize(14);
        t.setPadding(0, (int) (14 * dp), 0, 0);
        parent.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        t.setText(name + ": " + fmt.f(value));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(name + ": " + fmt.f(p + min));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save.s(s.getProgress() + min);
            }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * dp)));
    }

    private void heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(24);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, (int) (12 * dp));
        body.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = (int) (16 * dp);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (12 * dp);
        body.addView(c, lp);
        return c;
    }

    private void label(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.subtext));
        t.setTextSize(14);
        t.setPadding(0, (int) (12 * dp), 0, 0);
        parent.addView(t);
    }
}
