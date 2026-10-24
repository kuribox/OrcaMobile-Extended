package ru.ytkab0bp.slicebeam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import ru.ytkab0bp.slicebeam.theme.ThemesRepo;
import ru.ytkab0bp.slicebeam.utils.Prefs;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;
import ru.ytkab0bp.slicebeam.view.BeamButton;

public class SafeStartActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(ru.ytkab0bp.slicebeam.utils.AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.WHITE);
            View v = getWindow().getDecorView();
            v.setSystemUiVisibility(v.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setText(R.string.AppCrashed);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12), 0);
        ll.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView desc = new TextView(this);
        desc.setTextColor(0x99000000);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        String log = getString(R.string.AppCrashedDesc, Build.VERSION.RELEASE, Build.BRAND + " " + Build.MODEL, Prefs.getPrefs().getString("crash", ""));
        desc.setText(log);
        desc.setPadding(0, 0, 0, ViewUtils.dp(12));
        scroll.setPadding(ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12), 0);
        scroll.addView(desc);
        ll.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        BeamButton share = new BeamButton(this);
        share.setText(R.string.AppCrashedShare);
        share.setOnClickListener(v -> {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, log);
            sendIntent.setType("text/plain");

            Intent shareIntent = Intent.createChooser(sendIntent, null);
            startActivity(shareIntent);
        });
        ll.addView(share, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)) {{
            leftMargin = rightMargin = ViewUtils.dp(12);
        }});

        TextView restart = new TextView(this);
        restart.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        restart.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        restart.setGravity(Gravity.CENTER);
        restart.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        restart.setText(R.string.AppCrashedRestart);
        restart.setBackground(ViewUtils.createRipple(ColorUtils.setAlphaComponent(ThemesRepo.getColor(android.R.attr.colorAccent), 0x21), 16));
        restart.setOnClickListener(v -> {
            Prefs.getPrefs().edit().remove("crash").apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        ll.addView(restart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)) {{
            leftMargin = rightMargin = ViewUtils.dp(12);
            topMargin = ViewUtils.dp(8);
            bottomMargin = ViewUtils.dp(12);
        }});

        setContentView(ll);
    }
}
