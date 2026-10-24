package ru.ytkab0bp.slicebeam.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;

import java.util.Locale;

/**
 * In-app language override, without relying on Android 13 per-app locales (minSdk is 21).
 *
 * <p>Two layers have to agree:
 * <ul>
 *   <li>Android resources (`values-es/…`) — resolved from the Activity context, so every Activity
 *       wraps its base context through {@link #wrap(Context)}.</li>
 *   <li>OrcaSlicer `.po` strings — {@code Slic3rLocalization} keys off {@link Locale#getDefault()},
 *       which {@link #resolveLocale} keeps in sync.</li>
 * </ul>
 */
public final class AppLocale {

    private static final String KEY_APP_LANGUAGE = "app_language";

    /** Empty = follow the system language. */
    public static final String SYSTEM = "";
    public static final String ENGLISH = "en";
    public static final String SPANISH = "es";
    public static final String RUSSIAN = "ru";

    private AppLocale() {}

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getLanguageTag(Context ctx) {
        return prefs(ctx).getString(KEY_APP_LANGUAGE, SYSTEM);
    }

    public static boolean isSystem(Context ctx) {
        String tag = getLanguageTag(ctx);
        return tag == null || tag.isEmpty();
    }

    public static Locale resolveLocale(Context ctx) {
        String tag = getLanguageTag(ctx);
        if (tag == null || tag.isEmpty()) return Locale.getDefault();
        return Locale.forLanguageTag(tag);
    }

    /** Applies the selected language to the default locale and the given context's resources. */
    public static void apply(Context ctx) {
        Locale locale = resolveLocale(ctx);
        Locale.setDefault(locale);

        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
        // Deprecated on API 25+, but the only way to retarget an already-created Resources object
        // on older devices; Activity contexts get a proper per-locale Context in wrap().
        //noinspection deprecation
        ctx.getResources().updateConfiguration(cfg, ctx.getResources().getDisplayMetrics());
    }

    /** Base context for an Activity: same context, but resolving resources in the chosen language. */
    public static Context wrap(Context base) {
        Locale locale = resolveLocale(base);
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
        return base.createConfigurationContext(cfg);
    }

    /** App-wide language for {@code Application.getString()} callers. */
    public static Context localized(Context ctx) {
        Locale locale = resolveLocale(ctx);
        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
        return ctx.createConfigurationContext(cfg);
    }

    /**
     * Persists the choice and recreates the UI so every string is re-resolved.
     * Passing {@link #SYSTEM} restores the OS language.
     */
    public static void setLanguage(Context ctx, String tag) {
        prefs(ctx).edit().putString(KEY_APP_LANGUAGE, tag == null ? SYSTEM : tag).apply();
        apply(ctx.getApplicationContext());
        Activity act = findActivity(ctx);
        if (act != null && !act.isFinishing()) {
            act.recreate();
        }
    }

    public static void setLanguage(Activity act, String tag) {
        setLanguage((Context) act, tag);
    }

    private static Activity findActivity(Context ctx) {
        Context current = ctx;
        while (current != null) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) return null;
            current = base;
        }
        return null;
    }

    public static void applyOnStartup(Application app) {
        apply(app);
    }

    /** Human-readable name of a language tag, in that language. */
    public static String displayName(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        switch (tag) {
            case ENGLISH: return "English";
            case SPANISH: return "Español";
            case RUSSIAN: return "Русский";
            default: return tag;
        }
    }


}
