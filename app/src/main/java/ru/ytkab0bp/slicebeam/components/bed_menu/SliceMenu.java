package ru.ytkab0bp.slicebeam.components.bed_menu;

import static ru.ytkab0bp.slicebeam.utils.DebugUtils.assertTrue;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.util.Pair;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.ContentType;
import cz.msebera.android.httpclient.message.BasicHeader;
import ru.ytkab0bp.slicebeam.BuildConfig;
import ru.ytkab0bp.slicebeam.MainActivity;
import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.components.BeamAlertDialogBuilder;
import ru.ytkab0bp.slicebeam.components.UnfoldMenu;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.events.NeedDismissSnackbarEvent;
import ru.ytkab0bp.slicebeam.events.NeedSnackbarEvent;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.recycler.SimpleRecyclerItem;
import ru.ytkab0bp.slicebeam.slic3r.GCodeProcessorResult;
import ru.ytkab0bp.slicebeam.slic3r.GCodeViewer;
import ru.ytkab0bp.slicebeam.slic3r.Slic3rLocalization;
import ru.ytkab0bp.slicebeam.theme.IThemeView;
import ru.ytkab0bp.slicebeam.theme.ThemesRepo;
import ru.ytkab0bp.slicebeam.utils.GCode3mfPackager;
import ru.ytkab0bp.slicebeam.utils.GCodeStats;
import ru.ytkab0bp.slicebeam.utils.ViewLegend;
import ru.ytkab0bp.slicebeam.utils.Prefs;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;
import ru.ytkab0bp.slicebeam.view.DividerView;
import ru.ytkab0bp.slicebeam.view.PositionScrollView;
import ru.ytkab0bp.slicebeam.view.SegmentsView;
import ru.ytkab0bp.slicebeam.view.SnackbarsLayout;

public class SliceMenu extends ListBedMenu {
    private AsyncHttpClient client = new AsyncHttpClient();

    {
        client.setLoggingEnabled(true);
        client.setMaxRetriesAndTimeout(0, 10000);
    }

    private final static List<String> SUPPORTED_SEND = Arrays.asList("octoprint", "moonraker", "flashforge");
    private int lastUid;

    @Override
    protected List<SimpleRecyclerItem> onCreateItems(boolean portrait) {
        lastUid = SliceBeam.CONFIG_UID;
        List<SimpleRecyclerItem> items = new ArrayList<>(Arrays.asList(
                new BedMenuItem(R.string.MenuSliceInfo, R.drawable.clock_circle_dashed_outline_24).onClick(v -> fragment.showUnfoldMenu(new InfoMenu(this), v)),
                new BedMenuItem(R.string.MenuSliceLayers, R.drawable.square_stack_up_outline_28).onClick(v -> fragment.showUnfoldMenu(new LayersMenu(), v)),
                new BedMenuItem(R.string.MenuSliceExportToFile, R.drawable.folder_simple_arrow_right_outline_28).onClick(v -> showFormatDialog(false)),
                new BedMenuItem(R.string.MenuSliceShare, R.drawable.share_external_28).onClick(v -> showFormatDialog(true)),
                new BedMenuItem(R.string.MenuSlicePerformance, R.drawable.sparkle_28)
                        .setTitleTextSize(8f)
                        .setCheckable((buttonView, isChecked) -> {
                            Prefs.setPerformanceModeEnabled(isChecked);
                            GCodeViewer viewer = fragment.getGlView().getRenderer().getViewer();
                            if (viewer != null) {
                                viewer.setInfillVisibilityDepth(isChecked ? 5 : -1);
                                fragment.getGlView().requestRender();
                            }
                        }, Prefs.isPerformanceModeEnabled())
        ));
        ConfigObject obj = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        assertTrue(obj != null);
        String type = obj.get("host_type");
        if (type == null) type = "octoprint";
        String host = obj.get("print_host");
        String apiKey = obj.get("printhost_apikey");
        if (SUPPORTED_SEND.contains(type) && !TextUtils.isEmpty(host)) {
            String finalType = type;
            items.add(new BedMenuItem(R.string.MenuSliceSendToPrinter, R.drawable.send_outline_28).onClick(v -> upload(finalType, host, apiKey, false)));
            items.add(new BedMenuItem(R.string.MenuSliceSendToPrinterAndPrint, R.drawable.send_28).onClick(v -> upload(finalType, host, apiKey, true)));
        }
        return items;
    }

    private void upload(String type, String host, String apiKey, boolean print) {
        String name = fragment.getGlView().getRenderer().getGcodeResult().getRecommendedName();
        switch (type) {
            default:
            case "octoprint":
                if (!host.startsWith("http://")) {
                    host = "http://" + host;
                }
                String tag = UUID.randomUUID().toString();
                SliceBeam.EVENT_BUS.fireEvent(new NeedSnackbarEvent(SnackbarsLayout.Type.LOADING, R.string.MenuSliceSendToPrinterLoading).tag(tag));
                Header[] headers = TextUtils.isEmpty(apiKey) ? new Header[0] : new Header[] {new BasicHeader("X-Api-Key", apiKey)};
                RequestParams params = new RequestParams();
                try {
                    params.put("file", new FileInputStream(BedFragment.getTempGCodePath()), name, ContentType.TEXT_PLAIN.getMimeType());
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
                params.put("select", String.valueOf(print));
                params.put("print", String.valueOf(print));

                client.post(SliceBeam.INSTANCE, host + "/api/files/local", headers, params, null, new AsyncHttpResponseHandler() {
                    @Override
                    public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                        try {
                            JSONObject obj = new JSONObject(new String(responseBody));
                            if (!obj.has("action") && !obj.has("files")) {
                                throw new JSONException(obj.toString());
                            }
                            SliceBeam.EVENT_BUS.fireEvent(new NeedDismissSnackbarEvent(tag));
                            SliceBeam.EVENT_BUS.fireEvent(new NeedSnackbarEvent(print ? SnackbarsLayout.Type.INFO : SnackbarsLayout.Type.DONE, print ? R.string.MenuSliceSendToPrinterPrintStarted : R.string.MenuSliceSendToPrinterOK));
                        } catch (JSONException e) {
                            onFailure(statusCode, headers, responseBody, e);
                        }
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                        SliceBeam.EVENT_BUS.fireEvent(new NeedDismissSnackbarEvent(tag));
                        ViewUtils.postOnMainThread(() -> new BeamAlertDialogBuilder(fragment.getContext())
                                .setTitle(R.string.MenuSliceSendToPrinterFailed)
                                .setMessage(error.toString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show());
                    }
                });
                break;
            case "flashforge":
                uploadFlashforge(host, apiKey, print, name);
                break;
        }
    }

    private void uploadFlashforge(String host, String apiKey, boolean print, String name) {
        final ConfigObject printer = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        // Same configuration keys as desktop OrcaSlicer: the printer serial lives in
        // flashforge_serial_number and the check code in printhost_apikey.
        final String serialNumber = printer != null ? printer.get("flashforge_serial_number") : "";
        final String checkCode = printer != null ? printer.get("printhost_apikey") : "";

        if (TextUtils.isEmpty(serialNumber) || TextUtils.isEmpty(checkCode)) {
            ViewUtils.postOnMainThread(() -> new BeamAlertDialogBuilder(fragment.getContext())
                    .setTitle(R.string.MenuSliceSendToPrinterFailed)
                    .setMessage(R.string.PrinterTestFlashforgeMissingCreds)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            return;
        }

        final String tag = UUID.randomUUID().toString();
        SliceBeam.EVENT_BUS.fireEvent(new NeedSnackbarEvent(SnackbarsLayout.Type.LOADING, R.string.MenuSliceSendToPrinterLoading).tag(tag));

        // Flashforge local API listens on port 8898.
        String base = host.startsWith("http://") ? host : "http://" + host;
        if (!base.contains(":")) {
            base += ":8898";
        }
        final String uploadUrl = base + "/uploadGcode";

        final boolean printNow = print;
        final String uploadName = name;
        final GCodeProcessorResult sliceResult = fragment.getGlView().getRenderer().getGcodeResult();

        // Building the package parses the whole G-code: never do it on the UI thread.
        new Thread(() -> {
            File gcodeFile = BedFragment.getTempGCodePath();
            File upload = null;
            try {
                File packageFile = new File(SliceBeam.INSTANCE.getCacheDir(), gcode3mfName(uploadName));
                GCode3mfPackager.create(gcodeFile, packageFile, null, printer, sliceResult);
                upload = packageFile;
            } catch (Exception e) {
                failHostAction(tag, SliceBeam.INSTANCE.getString(R.string.MenuSliceExport3mfFailed, String.valueOf(e.getMessage())));
                return;
            }

            final RequestParams params = new RequestParams();
            try {
                params.put("gcodeFile", new FileInputStream(upload), upload.getName(), ContentType.APPLICATION_OCTET_STREAM.getMimeType());
            } catch (FileNotFoundException e) {
                failHostAction(tag, String.valueOf(e.getMessage()));
                return;
            }

            final long fileSize = upload.length();
            final Header[] headers = new Header[]{
                    new BasicHeader("serialNumber", serialNumber),
                    new BasicHeader("checkCode", checkCode),
                    new BasicHeader("fileSize", String.valueOf(fileSize)),
                    new BasicHeader("printNow", printNow ? "true" : "false"),
                    new BasicHeader("levelingBeforePrint", "false"),
                    new BasicHeader("flowCalibration", "false"),
                    new BasicHeader("firstLayerInspection", "false"),
                    new BasicHeader("timeLapseVideo", "false"),
                    new BasicHeader("useMatlStation", "false"),
                    new BasicHeader("gcodeToolCnt", "1"),
                    new BasicHeader("materialMappings", "W1td") // base64 of "[]"
            };

            client.post(SliceBeam.INSTANCE, uploadUrl, headers, params, null, new AsyncHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, Header[] responseHeaders, byte[] responseBody) {
                    try {
                        JSONObject obj = new JSONObject(new String(responseBody));
                        int code = obj.optInt("code", obj.optInt("err", -1));
                        if (code != 0) {
                            String msg = obj.optString("message", obj.optString("msg", ""));
                            failHostAction(tag, SliceBeam.INSTANCE.getString(R.string.PrinterTestFlashforgeError, code, msg));
                            return;
                        }
                        SliceBeam.EVENT_BUS.fireEvent(new NeedDismissSnackbarEvent(tag));
                        SliceBeam.EVENT_BUS.fireEvent(new NeedSnackbarEvent(
                                printNow ? SnackbarsLayout.Type.INFO : SnackbarsLayout.Type.DONE,
                                printNow ? R.string.MenuSliceSendToPrinterPrintStarted : R.string.MenuSliceSendToPrinterOK));
                    } catch (JSONException e) {
                        failHostAction(tag, "HTTP " + statusCode + ": " + new String(responseBody));
                    }
                }

                @Override
                public void onFailure(int statusCode, Header[] responseHeaders, byte[] responseBody, Throwable error) {
                    String body = responseBody == null ? "" : new String(responseBody);
                    failHostAction(tag, "HTTP " + statusCode + ": " + error + (body.isEmpty() ? "" : " — " + body));
                }
            });
        }, "flashforge-upload").start();
    }

    private void failHostAction(String tag, String message) {
        SliceBeam.EVENT_BUS.fireEvent(new NeedDismissSnackbarEvent(tag));
        ViewUtils.postOnMainThread(() -> new BeamAlertDialogBuilder(fragment.getContext())
                .setTitle(R.string.MenuSliceSendToPrinterFailed)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }


    private final static class InfoMenu extends UnfoldMenu implements IThemeView {
        private final static String[] SCHEME_NAME_KEYS = {
                "LineType",
                "LayerHeight",
                "LineWidth",
                "Speed",
                "ActualSpeed",
                "FanSpeed",
                "Temperature",
                "Flow",
                "ActualFlow",
                "LayerTime",
                "LayerTimeLog",
                "Filament"
        };
        // Must match libvgcode EViewType: FeatureType=0, Height, Width, Speed, ActualSpeed,
        // FanSpeed, Temperature, VolumetricFlowRate, ActualVolumetricFlowRate, LayerTimeLinear,
        // LayerTimeLogarithmic, Tool.
        private final static int[] SCHEME_VALUES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, GCodeViewer.VIEW_TYPE_TOOL};

        private static GCodeStats cachedStats;
        private static String cachedStatsKey;

        private TextView totalView;
        private SegmentsView segmentsView;
        private ExtrusionRoleView[] roleViews = new ExtrusionRoleView[GCodeViewer.EXTRUSION_ROLES_COUNT];
        private static DecimalFormat format = new DecimalFormat("0.##");

        private final SliceMenu parent;

        private LinearLayout statsContainer;
        private TextView legendTitle;
        private LinearLayout legendBody;
        private GCodeStats stats;

        public InfoMenu(SliceMenu parent) {
            this.parent = parent;
        }

        private String[] getSchemeNames(Context ctx) {
            String[] names = new String[SCHEME_NAME_KEYS.length];
            for (int i = 0; i < SCHEME_NAME_KEYS.length; i++) {
                int resId = ctx.getResources().getIdentifier(SCHEME_NAME_KEYS[i], "string", ctx.getPackageName());
                names[i] = resId != 0 ? ctx.getString(resId) : SCHEME_NAME_KEYS[i];
            }
            return names;
        }

        private GCodeViewer getViewer() {
            return fragment.getGlView().getRenderer().getViewer();
        }

        private GCodeProcessorResult getResult() {
            return fragment.getGlView().getRenderer().getGcodeResult();
        }

        /** Metric shown by a given scheme index, or null when the scheme colors by feature/tool. */
        private GCodeStats.Metric metricForScheme(int index) {
            if (stats == null) return null;
            switch (index) {
                case 1: return stats.height;
                case 2: return stats.width;
                case 3:
                case 4: return stats.speed;
                case 5: return stats.fan;
                case 6: return stats.temperature;
                case 7:
                case 8: return stats.flow;
                case 9:
                case 10: return stats.layerTime;
                default: return null;
            }
        }

        private String unitForScheme(int index) {
            switch (index) {
                case 1:
                case 2: return "mm";
                case 3:
                case 4: return "mm/s";
                case 5: return "%";
                case 6: return "°C";
                case 7:
                case 8: return "mm³/s";
                case 9:
                case 10: return "s";
                default: return "";
            }
        }

        private int schemeIndexOfViewType(int viewType) {
            for (int i = 0; i < SCHEME_VALUES.length; i++) {
                if (SCHEME_VALUES[i] == viewType) return i;
            }
            return 0;
        }

        private void applyLegend(Context ctx) {
            if (legendTitle == null || legendBody == null) return;
            GCodeViewer viewer = getViewer();
            int index = schemeIndexOfViewType(viewer != null ? viewer.getViewType() : GCodeViewer.VIEW_TYPE_FEATURE);
            int viewType = SCHEME_VALUES[index];
            legendTitle.setText(getSchemeNames(ctx)[index]);

            legendBody.removeAllViews();
            int secondary = ThemesRepo.getColor(android.R.attr.textColorSecondary);

            if (!ViewLegend.hasValueScale(viewType)) {
                // Feature / tool views: the colors are categorical, name them instead of a scale.
                addNote(ctx, ctx.getString(viewType == GCodeViewer.VIEW_TYPE_TOOL
                        ? R.string.InfoLegendToolNote
                        : R.string.InfoLegendFeatureNote));
                if (viewType == GCodeViewer.VIEW_TYPE_TOOL) {
                    addToolSwatches(ctx);
                }
                return;
            }

            if (stats == null) {
                addNote(ctx, ctx.getString(R.string.InfoStatsComputing));
                return;
            }

            GCodeStats.Metric metric = metricForScheme(index);
            String unit = unitForScheme(index);
            boolean approximate = viewType == ViewLegend.VIEW_ACTUAL_SPEED || viewType == ViewLegend.VIEW_ACTUAL_FLOW;
            boolean logarithmic = ViewLegend.isLogarithmic(viewType);

            boolean hasData = metric != null && metric.hasData();
            boolean singleValue = hasData && metric.max - metric.min < 1e-4f;

            View bar = new View(ctx);
            GradientDrawable background;
            if (!hasData || singleValue) {
                // libvgcode paints a uniform range with the first palette color.
                background = new GradientDrawable();
                background.setColor(ViewLegend.PALETTE[0]);
            } else {
                background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, ViewLegend.gradientColors());
            }
            background.setCornerRadius(ViewUtils.dp(4));
            background.setStroke(ViewUtils.dp(1), ThemesRepo.getColor(android.R.attr.colorControlHighlight));
            bar.setBackground(background);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(16));
            barParams.leftMargin = ViewUtils.dp(16);
            barParams.rightMargin = ViewUtils.dp(16);
            barParams.topMargin = ViewUtils.dp(2);
            legendBody.addView(bar, barParams);

            String mark = approximate ? "≈ " : "";
            LinearLayout labels = new LinearLayout(ctx);
            labels.setOrientation(LinearLayout.HORIZONTAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setPadding(ViewUtils.dp(16), ViewUtils.dp(4), ViewUtils.dp(16), 0);

            if (!hasData) {
                labels.addView(legendLabel(ctx, ctx.getString(R.string.InfoStatsNoValues), secondary, Gravity.START, 1f));
            } else if (singleValue) {
                labels.addView(legendLabel(ctx, mark + GCodeStats.format(metric.min, unit), secondary, Gravity.START, 1f));
            } else {
                labels.addView(legendLabel(ctx, mark + GCodeStats.format(metric.min, unit), secondary, Gravity.START, 1f));
                labels.addView(legendLabel(ctx, ctx.getString(R.string.InfoStatsAverageShort,
                        GCodeStats.format(metric.avg, unit)), secondary, Gravity.CENTER, 1f));
                labels.addView(legendLabel(ctx, mark + GCodeStats.format(metric.max, unit), secondary, Gravity.END, 1f));
            }
            legendBody.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (logarithmic) {
                addNote(ctx, ctx.getString(R.string.InfoLegendLog));
            }
            if (approximate) {
                addNote(ctx, ctx.getString(R.string.InfoLegendApprox));
            }
        }

        private TextView legendLabel(Context ctx, String text, int color, int gravity, float weight) {
            TextView view = new TextView(ctx);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            view.setTextColor(color);
            view.setGravity(gravity | Gravity.CENTER_VERTICAL);
            view.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
            return view;
        }

        private void addNote(Context ctx, String text) {
            TextView note = new TextView(ctx);
            note.setText(text);
            note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            note.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            note.setPadding(ViewUtils.dp(16), ViewUtils.dp(2), ViewUtils.dp(16), ViewUtils.dp(2));
            legendBody.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void addToolSwatches(Context ctx) {
            int[] palette = Prefs.getFilamentPalette();
            int tools = Math.max(1, Math.min(stats != null ? stats.tools : 1, palette.length));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(ViewUtils.dp(16), ViewUtils.dp(4), ViewUtils.dp(16), 0);
            for (int i = 0; i < tools; i++) {
                View swatch = new View(ctx);
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(palette[i]);
                dot.setStroke(ViewUtils.dp(1), ThemesRepo.getColor(android.R.attr.colorControlHighlight));
                swatch.setBackground(dot);
                LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ViewUtils.dp(14), ViewUtils.dp(14));
                dotParams.rightMargin = ViewUtils.dp(6);
                row.addView(swatch, dotParams);

                TextView label = new TextView(ctx);
                label.setText(ctx.getString(R.string.InfoLegendExtruder, i + 1));
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                label.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                labelParams.rightMargin = ViewUtils.dp(14);
                row.addView(label, labelParams);
            }
            legendBody.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void styleStatsRows(Context ctx, int activeIndex) {
            if (statsContainer == null) return;
            int accent = ThemesRepo.getColor(android.R.attr.colorAccent);
            int primary = ThemesRepo.getColor(android.R.attr.textColorPrimary);
            int secondary = ThemesRepo.getColor(android.R.attr.textColorSecondary);
            for (int i = 0; i < statsContainer.getChildCount(); i++) {
                View row = statsContainer.getChildAt(i);
                if (!(row instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) row;
                Integer rowIndex = (Integer) ll.getTag();
                if (rowIndex == null) continue;
                boolean active = rowIndex == activeIndex;
                View name = ll.getChildAt(0);
                View value = ll.getChildAt(1);
                if (name instanceof TextView) {
                    ((TextView) name).setTextColor(active ? accent : primary);
                    ((TextView) name).setTypeface(active
                            ? ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM)
                            : null);
                }
                if (value instanceof TextView) {
                    ((TextView) value).setTextColor(active ? accent : secondary);
                }
            }
        }

        private LinearLayout metricRow(Context ctx, int schemeIndex, int labelRes, GCodeStats.Metric metric, String unit) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ViewUtils.dp(16), ViewUtils.dp(6), ViewUtils.dp(16), ViewUtils.dp(6));
            row.setTag(schemeIndex);
            row.setOnClickListener(v -> {
                GCodeViewer viewer = getViewer();
                if (viewer == null) return;
                fragment.getGlView().queueEvent(() -> {
                    GCodeViewer glViewer = getViewer();
                    if (glViewer == null) return;
                    int type = SCHEME_VALUES[schemeIndex];
                    if (type == GCodeViewer.VIEW_TYPE_TOOL) {
                        int[] pal = Prefs.getFilamentPalette();
                        int[] rgb = new int[pal.length];
                        for (int i = 0; i < pal.length; i++) rgb[i] = pal[i] & 0xFFFFFF;
                        glViewer.setToolColors(rgb);
                    }
                    glViewer.setViewType(type);
                    fragment.getGlView().requestRender();
                    ViewUtils.postOnMainThread(() -> {
                        applyLegend(ctx);
                        parent.adapter.notifyDataSetChanged();
                    });
                });
            });

            TextView name = new TextView(ctx);
            name.setText(labelRes);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView value = new TextView(ctx);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            value.setGravity(Gravity.END);
            if (metric == null || !metric.hasData()) {
                value.setText(R.string.InfoStatsNoValues);
            } else {
                value.setText(ctx.getString(R.string.InfoStatsRow,
                        GCodeStats.format(metric.min, unit),
                        GCodeStats.format(metric.max, unit),
                        GCodeStats.format(metric.avg, unit)));
            }
            row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }

        private void loadStatsAsync(Context ctx) {
            final File gcode = BedFragment.getTempGCodePath();
            if (gcode == null || !gcode.isFile()) {
                applyLegend(ctx);
                return;
            }
            final String key = gcode.getAbsolutePath() + ":" + gcode.length() + ":" + gcode.lastModified();
            if (key.equals(cachedStatsKey) && cachedStats != null) {
                stats = cachedStats;
                populateStatsRows(ctx);
                applyLegend(ctx);
                return;
            }
            new Thread(() -> {
                GCodeStats result = null;
                try {
                    result = GCodeStats.analyze(gcode);
                } catch (Exception ignored) {
                }
                final GCodeStats analyzed = result;
                ViewUtils.postOnMainThread(() -> {
                    if (analyzed == null) return;
                    cachedStats = analyzed;
                    cachedStatsKey = key;
                    stats = analyzed;
                    populateStatsRows(ctx);
                    applyLegend(ctx);
                });
            }, "gcode-stats").start();
        }

        private void populateStatsRows(Context ctx) {
            if (statsContainer == null) return;
            statsContainer.removeAllViews();
            if (stats == null) return;
            statsContainer.addView(metricRow(ctx, 1, R.string.LayerHeight, stats.height, "mm"));
            statsContainer.addView(metricRow(ctx, 2, R.string.LineWidth, stats.width, "mm"));
            statsContainer.addView(metricRow(ctx, 3, R.string.Speed, stats.speed, "mm/s"));
            statsContainer.addView(metricRow(ctx, 7, R.string.Flow, stats.flow, "mm³/s"));
            statsContainer.addView(metricRow(ctx, 6, R.string.Temperature, stats.temperature, "°C"));
            statsContainer.addView(metricRow(ctx, 5, R.string.FanSpeed, stats.fan, "%"));
            statsContainer.addView(metricRow(ctx, 9, R.string.LayerTime, stats.layerTime, "s"));
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);

            LinearLayout schemeRow = new LinearLayout(ctx);
            schemeRow.setOrientation(LinearLayout.HORIZONTAL);
            schemeRow.setGravity(Gravity.CENTER_VERTICAL);
            schemeRow.setPadding(ViewUtils.dp(16), ViewUtils.dp(12), ViewUtils.dp(16), ViewUtils.dp(12));
            schemeRow.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));

            TextView schemeLabel = new TextView(ctx);
            schemeLabel.setText(ctx.getString(R.string.ColorScheme));
            schemeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            schemeLabel.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            schemeRow.addView(schemeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView schemeValue = new TextView(ctx);
            schemeValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            schemeValue.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            schemeValue.setGravity(Gravity.END);

            GCodeViewer viewer = getViewer();
            int currentType = viewer != null ? viewer.getViewType() : GCodeViewer.VIEW_TYPE_FEATURE;
            String[] schemeNames = getSchemeNames(ctx);
            schemeValue.setText(schemeNames[schemeIndexOfViewType(currentType)]);
            schemeRow.addView(schemeValue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            schemeRow.setOnClickListener(v -> {
                GCodeViewer vViewer = getViewer();
                if (vViewer == null) return;

                int cType = vViewer.getViewType();
                int selectedIndex = schemeIndexOfViewType(cType);
                String[] currentNames = getSchemeNames(ctx);

                new BeamAlertDialogBuilder(ctx)
                        .setTitle(ctx.getString(R.string.ColorScheme))
                        .setSingleChoiceItems(currentNames, selectedIndex, (dialog, which) -> {
                            int type = SCHEME_VALUES[which];
                            schemeValue.setText(currentNames[which]);
                            fragment.getGlView().queueEvent(() -> {
                                GCodeViewer glViewer = getViewer();
                                if (glViewer != null) {
                                    if (type == GCodeViewer.VIEW_TYPE_TOOL) {
                                        int[] pal = Prefs.getFilamentPalette();
                                        int[] rgb = new int[pal.length];
                                        for (int i = 0; i < pal.length; i++) rgb[i] = pal[i] & 0xFFFFFF;
                                        glViewer.setToolColors(rgb);
                                    }
                                    glViewer.setViewType(type);
                                    fragment.getGlView().requestRender();
                                    ViewUtils.postOnMainThread(() -> {
                                        applyLegend(ctx);
                                        parent.adapter.notifyDataSetChanged();
                                    });
                                }
                            });
                            dialog.dismiss();
                        })
                        .show();
            });

            ll.addView(schemeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

            // Values of the active view: the numbers behind the colors.
            LinearLayout legendBlock = new LinearLayout(ctx);
            legendBlock.setOrientation(LinearLayout.VERTICAL);
            legendBlock.setPadding(0, ViewUtils.dp(6), 0, ViewUtils.dp(6));

            legendTitle = new TextView(ctx);
            legendTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            legendTitle.setPadding(ViewUtils.dp(16), ViewUtils.dp(4), ViewUtils.dp(16), 0);
            legendTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            legendBlock.addView(legendTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            legendBody = new LinearLayout(ctx);
            legendBody.setOrientation(LinearLayout.VERTICAL);
            legendBlock.addView(legendBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            statsContainer = new LinearLayout(ctx);
            statsContainer.setOrientation(LinearLayout.VERTICAL);
            legendBlock.addView(statsContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ll.addView(legendBlock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

            LinearLayout seamRow = new LinearLayout(ctx);
            seamRow.setOrientation(LinearLayout.HORIZONTAL);
            seamRow.setGravity(Gravity.CENTER_VERTICAL);
            seamRow.setPadding(ViewUtils.dp(16), ViewUtils.dp(12), ViewUtils.dp(16), ViewUtils.dp(12));
            seamRow.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));

            TextView seamLabel = new TextView(ctx);
            seamLabel.setText(ctx.getString(R.string.ShowSeams));
            seamLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            seamLabel.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            seamRow.addView(seamLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            MaterialCheckBox seamCheckBox = new MaterialCheckBox(ctx);
            seamCheckBox.setChecked(viewer != null && viewer.isOptionVisible(GCodeViewer.OPTION_TYPE_SEAMS));
            seamRow.addView(seamCheckBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            seamRow.setOnClickListener(v -> {
                GCodeViewer vViewer = getViewer();
                if (vViewer != null) {
                    vViewer.toggleOptionVisibility(GCodeViewer.OPTION_TYPE_SEAMS);
                    seamCheckBox.setChecked(!seamCheckBox.isChecked());
                    fragment.getGlView().requestRender();
                }
            });
            seamCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                GCodeViewer vViewer = getViewer();
                if (vViewer != null && vViewer.isOptionVisible(GCodeViewer.OPTION_TYPE_SEAMS) != isChecked) {
                    vViewer.toggleOptionVisibility(GCodeViewer.OPTION_TYPE_SEAMS);
                    fragment.getGlView().requestRender();
                }
            });

            ll.addView(seamRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

            for (int i = 0; i < GCodeViewer.EXTRUSION_ROLES_COUNT; i++) {
                ll.addView(roleViews[i] = new ExtrusionRoleView(ctx));
            }

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

            totalView = new TextView(ctx);
            totalView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            totalView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(18));
            totalParams.topMargin = ViewUtils.dp(8);
            ll.addView(totalView, totalParams);

            segmentsView = new SegmentsView(ctx);
            LinearLayout.LayoutParams segmentsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(12));
            segmentsParams.leftMargin = ViewUtils.dp(12);
            segmentsParams.rightMargin = ViewUtils.dp(12);
            segmentsParams.topMargin = ViewUtils.dp(8);
            segmentsParams.bottomMargin = ViewUtils.dp(8);
            ll.addView(segmentsView, segmentsParams);

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));
            LinearLayout toolbar = new LinearLayout(ctx);
            toolbar.setPadding(ViewUtils.dp(12), 0, ViewUtils.dp(12), 0);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));
            toolbar.setOnClickListener(v -> dismiss());

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.arrow_left_outline_28);
            icon.setColorFilter(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            toolbar.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)));

            TextView title = new TextView(ctx);
            title.setText(ctx.getString(R.string.MenuOrientationPositionBack));
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleParams.leftMargin = ViewUtils.dp(12);
            toolbar.addView(title, titleParams);

            ll.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)));
            onApplyTheme();

            // The panel must not cover the whole model, and its content must scroll:
            // otherwise changing the color scheme looks like it does nothing.
            ScrollView scroll = new ScrollView(ctx);
            scroll.setFillViewport(false);
            scroll.addView(ll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }

        @Override
        protected void onCreate() {
            super.onCreate();

            GCodeViewer viewer = getViewer();
            GCodeProcessorResult result = getResult();
            if (viewer != null) {
                for (int i = 0; i < GCodeViewer.EXTRUSION_ROLES_COUNT; i++) {
                    boolean visible = viewer.getEstimatedTime(i) != 0;
                    roleViews[i].setVisibility(visible ? View.VISIBLE : View.GONE);
                    if (visible) {
                        roleViews[i].bind(viewer, result, i);
                    }
                }
            }
            updateValues();
            ViewUtils.postOnMainThread(() -> segmentsView.startAnimation(), 50);
            Context ctx = fragment.getContext();
            if (ctx != null) {
                applyLegend(ctx);
                loadStatsAsync(ctx);
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            segmentsView.setNotVisible();
        }

        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            int cap = super.getRequestedSize(into, portrait);
            if (portrait) {
                GCodeViewer viewer = getViewer();
                if (viewer != null) {
                    int visibleCount = 0;
                    for (int i = 0; i < GCodeViewer.EXTRUSION_ROLES_COUNT; i++) {
                        if (viewer.getEstimatedTime(i) != 0) {
                            visibleCount++;
                        }
                    }
                    int needed = ViewUtils.dp(42) * visibleCount + ViewUtils.dp(28) + ViewUtils.dp(52) + ViewUtils.dp(18 + 8) + ViewUtils.dp(44) + ViewUtils.dp(46);
                    return Math.min(needed, cap);
                }
            }
            return cap;
        }

        private float getTotalEstimatedTime() {
            GCodeViewer viewer = getViewer();
            if (viewer == null) return 0;
            float total = 0;
            for (int i = 0; i < GCodeViewer.EXTRUSION_ROLES_COUNT; i++) {
                if (viewer.isExtrusionRoleVisible(i)) {
                    total += viewer.getEstimatedTime(i);
                }
            }
            return total;
        }

        @SuppressLint("SetTextI18n")
        private void updateValues() {
            GCodeViewer viewer = getViewer();
            GCodeProcessorResult result = getResult();
            if (viewer == null) return;

            float[] values = new float[2 + GCodeViewer.EXTRUSION_ROLES_COUNT];
            double totalWeight = 0;
            double totalLength = 0;
            values[0] = 0;
            values[values.length - 1] = 1;
            float prev = 0;
            int lastVisible = 0;
            float totalTime = getTotalEstimatedTime();
            for (int i = 0; i < GCodeViewer.EXTRUSION_ROLES_COUNT; i++) {
                if (viewer.isExtrusionRoleVisible(i)) {
                    float percent = totalTime > 0 ? viewer.getEstimatedTime(i) / totalTime : 0;
                    values[i + 1] = prev + percent;
                    lastVisible = i;
                    prev = values[i + 1];
                    totalLength += result.getUsedFilamentMM(i);
                    totalWeight += result.getUsedFilamentG(i);
                } else {
                    values[i + 1] = prev;
                }
            }

            values[lastVisible] = 1;

            segmentsView.setValues(values);
            totalView.setText(formatComplex(totalWeight, totalLength, totalTime));
        }

        private static String formatComplex(double weight, double length, float time) {
            StringBuilder sb = new StringBuilder();
            if (weight > 0) {
                sb.append(format.format(weight)).append(" ").append(SliceBeam.INSTANCE.getString(R.string.MenuSliceInfoWeight)).append(" | ");
            }
            sb.append(format.format(length)).append(" ").append(SliceBeam.INSTANCE.getString(R.string.MenuSliceInfoLength)).append(" | ");
            sb.append(formatTime(time));
            return sb.toString();
        }

        private static String formatTime(float time) {
            int secondsTotal = (int) Math.round(Math.ceil(time));
            int seconds = secondsTotal % 60;
            int minutes = ((secondsTotal - seconds) / 60) % 60;
            int hours = ((secondsTotal - seconds) / 60) / 60;

            StringBuilder sb = new StringBuilder();
            if (hours > 0) {
                sb.append(hours).append(" ").append(SliceBeam.INSTANCE.getString(R.string.MenuSliceInfoHour));
            }
            if (minutes > 0) {
                if (sb.length() > 0) sb.append(" ");

                sb.append(minutes).append(" ").append(SliceBeam.INSTANCE.getString(R.string.MenuSliceInfoMinute));
            }
            if (seconds > 0 || sb.length() == 0) {
                if (sb.length() > 0) sb.append(" ");

                sb.append(seconds).append(" ").append(SliceBeam.INSTANCE.getString(R.string.MenuSliceInfoSecond));
            }

            return sb.toString();
        }

        @Override
        public void onApplyTheme() {
            totalView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            if (legendTitle != null) {
                legendTitle.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            }
        }

        private final class ExtrusionRoleView extends LinearLayout implements IThemeView {
            private MaterialCheckBox checkBox;
            private View colorSwatch;
            private TextView titleView;
            private TextView timeView;

            private Runnable invalidateGl;

            public ExtrusionRoleView(Context context) {
                super(context);
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                setPadding(ViewUtils.dp(12), 0, ViewUtils.dp(16), 0);

                checkBox = new MaterialCheckBox(getContext()) {
                    @Override
                    public boolean dispatchTouchEvent(MotionEvent event) {
                        return false;
                    }
                };
                addView(checkBox, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)));

                colorSwatch = new View(context);
                LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(ViewUtils.dp(12), ViewUtils.dp(12));
                swatchParams.rightMargin = ViewUtils.dp(8);
                addView(colorSwatch, swatchParams);

                titleView = new TextView(context);
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                timeView = new TextView(context);
                timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                addView(timeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(42)));
                onApplyTheme();
            }

            public void bind(GCodeViewer viewer, GCodeProcessorResult result, @GCodeViewer.ExtrusionRole int role) {
                switch (role) {
                    case GCodeViewer.EXTRUSION_ROLE_NONE:
                        titleView.setText(Slic3rLocalization.getString("Unknown"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_PERIMETER:
                        titleView.setText(Slic3rLocalization.getString("Perimeter"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_EXTERNAL_PERIMETER:
                        titleView.setText(Slic3rLocalization.getString("External perimeter"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_OVERHANG_PERIMETER:
                        titleView.setText(Slic3rLocalization.getString("Overhang perimeter"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_INTERNAL_INFILL:
                        titleView.setText(Slic3rLocalization.getString("Internal infill"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_SOLID_INFILL:
                        titleView.setText(Slic3rLocalization.getString("Solid infill"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_TOP_SOLID_INFILL:
                        titleView.setText(Slic3rLocalization.getString("Top solid infill"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_IRONING:
                        titleView.setText(Slic3rLocalization.getString("Ironing"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_BRIDGE_INFILL:
                        titleView.setText(Slic3rLocalization.getString("Bridge infill"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_GAP_FILL:
                        titleView.setText(Slic3rLocalization.getString("Gap fill"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_SKIRT:
                        titleView.setText(Slic3rLocalization.getString("Skirt/Brim"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_SUPPORT_MATERIAL:
                        titleView.setText(Slic3rLocalization.getString("Support material"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_SUPPORT_MATERIAL_INTERFACE:
                        titleView.setText(Slic3rLocalization.getString("Support material interface"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_WIPE_TOWER:
                        titleView.setText(Slic3rLocalization.getString("Wipe tower"));
                        break;
                    case GCodeViewer.EXTRUSION_ROLE_CUSTOM:
                        titleView.setText(Slic3rLocalization.getString("Custom"));
                        break;
                }

                timeView.setText(formatComplex(result.getUsedFilamentG(role), result.getUsedFilamentMM(role), viewer.getEstimatedTime(role)));

                checkBox.setChecked(viewer.isExtrusionRoleVisible(role));
                int roleColor = SegmentsView.mapColor(role);
                checkBox.setButtonTintList(ColorStateList.valueOf(roleColor));
                GradientDrawable swatch = new GradientDrawable();
                swatch.setShape(GradientDrawable.OVAL);
                swatch.setColor(roleColor);
                swatch.setStroke(ViewUtils.dp(1), ThemesRepo.getColor(android.R.attr.colorControlHighlight));
                colorSwatch.setBackground(swatch);
                setOnClickListener(v -> {
                    if (getTotalEstimatedTime() == viewer.getEstimatedTime(role)) {
                        return;
                    }

                    viewer.toggleExtrusionRoleVisible(role);
                    checkBox.setChecked(!checkBox.isChecked());
                    updateValues();
                    if (invalidateGl != null) ViewUtils.removeCallbacks(invalidateGl);
                    ViewUtils.postOnMainThread(invalidateGl = () -> {
                        Pair<Long, Long> p = viewer.getLayersViewRange();
                        viewer.setLayersViewRange(p.first, p.second);
                        fragment.getGlView().requestRender();
                    }, 250);
                });
            }

            @Override
            public void onApplyTheme() {
                titleView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
                timeView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
                setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 12));
            }
        }
    }

    private final static class LayersMenu extends UnfoldMenu {
        private PositionScrollView fromTrack, toTrack;
        private TextView title;

        private Runnable applyCallback;

        private GCodeViewer getViewer() {
            return fragment.getGlView().getRenderer().getViewer();
        }

        private void applyView(int from, int to) {
            if (applyCallback != null) ViewUtils.removeCallbacks(applyCallback);

            GCodeViewer viewer = getViewer();
            if (viewer == null) {
                return;
            }
            // Set infill visibility depth to optimize performance
            // Only show infill for 5 layers below the top layer
            if (Prefs.isPerformanceModeEnabled()) {
                viewer.setInfillVisibilityDepth(5);
            } else {
                viewer.setInfillVisibilityDepth(-1);
            }
            viewer.setLayersViewRange(from - 1, to - 1);
            fragment.getGlView().requestRender();

            title.setText(fragment.getContext().getString(R.string.MenuSliceInfoLayers, from, to));
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);

            ll.addView(new Space(ctx), new LinearLayout.LayoutParams(0, 0, 1f));

            title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
            title.setGravity(Gravity.CENTER);
            ll.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            fromTrack = new PositionScrollView(ctx);
            fromTrack.setProgressListener(integer -> {
                GCodeViewer viewer = getViewer();
                if (viewer == null) return;
                if (Prefs.isPerformanceModeEnabled()) viewer.setFastMode(true);
                toTrack.setMinMax(integer, (int) viewer.getLayersCount());
                if (toTrack.getCurrentPosition() < integer) {
                    toTrack.setCurrentPosition(integer);
                }
                title.setText(fragment.getContext().getString(R.string.MenuSliceInfoLayers, fromTrack.getCurrentPosition(), integer));

                ViewUtils.removeCallbacks(applyCallback);
                ViewUtils.postOnMainThread(applyCallback = ()-> applyView(integer, toTrack.getCurrentPosition()), 50);
            });
            fromTrack.setListener(integer -> {
                GCodeViewer viewer = getViewer();
                if (viewer != null) viewer.setFastMode(false);
                applyView(integer, toTrack.getCurrentPosition());
            });
            ll.addView(fromTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            toTrack = new PositionScrollView(ctx);
            toTrack.setProgressListener(integer -> {
                GCodeViewer viewer = getViewer();
                if (viewer != null && Prefs.isPerformanceModeEnabled()) viewer.setFastMode(true);
                title.setText(fragment.getContext().getString(R.string.MenuSliceInfoLayers, fromTrack.getCurrentPosition(), integer));

                ViewUtils.removeCallbacks(applyCallback);
                ViewUtils.postOnMainThread(applyCallback = ()-> applyView(fromTrack.getCurrentPosition(), integer), 50);
            });
            toTrack.setListener(integer -> {
                GCodeViewer viewer = getViewer();
                if (viewer != null) viewer.setFastMode(false);
                applyView(fromTrack.getCurrentPosition(), integer);
            });
            ll.addView(toTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));
            LinearLayout toolbar = new LinearLayout(ctx);
            toolbar.setPadding(ViewUtils.dp(12), 0, ViewUtils.dp(12), 0);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));
            toolbar.setOnClickListener(v -> dismiss());

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.arrow_left_outline_28);
            icon.setColorFilter(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            toolbar.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)));

            TextView title = new TextView(ctx);
            title.setText(R.string.MenuOrientationPositionBack);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
                leftMargin = ViewUtils.dp(12);
            }});

            ll.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)));
            return ll;
        }

        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return portrait ? ViewUtils.dp(80) * 2 + ViewUtils.dp(24) + ViewUtils.dp(52) + ViewUtils.dp(12) : super.getRequestedSize(into, false);
        }

        @Override
        protected void onCreate() {
            super.onCreate();

            GCodeViewer viewer = getViewer();
            if (viewer == null) return;
            long max = viewer.getLayersCount();
            fromTrack.setMinMax(1, (int) max);
            toTrack.setMinMax(1, (int) max);

            Pair<Long, Long> range = viewer.getLayersViewRange();
            // TODO: Support long instead of int in PositionScrollView
            fromTrack.setCurrentPosition(Math.min(range.first.intValue() + 1, range.second.intValue() + 1));
            toTrack.setCurrentPosition(Math.max(range.first.intValue() + 1, range.second.intValue() + 1));

            title.setText(fragment.getContext().getString(R.string.MenuSliceInfoLayers, fromTrack.getCurrentPosition(), toTrack.getCurrentPosition()));
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            fromTrack.stopScroll();
            toTrack.stopScroll();
        }
    }

    private static File pendingExportFile;

    /** Returns the file the user chose to export (a prepared .gcode.3mf) and clears it. */
    public static File consumePendingExportFile() {
        File f = pendingExportFile;
        pendingExportFile = null;
        return f;
    }

    /** "Export" and "Share" both ask which format to use, like desktop OrcaSlicer. */
    private void showFormatDialog(boolean share) {
        Context ctx = fragment.getContext();
        if (!(ctx instanceof Activity)) return;

        String[] labels = {
                ctx.getString(R.string.MenuSliceFormatGcode),
                ctx.getString(R.string.MenuSliceFormatGcode3mf)
        };
        new BeamAlertDialogBuilder(ctx)
                .setTitle(share ? R.string.MenuSliceShare : R.string.MenuSliceExportToFile)
                .setItems(labels, (dialog, which) -> prepareAndDispatch(share, which == 1))
                .show();
    }

    private void prepareAndDispatch(boolean share, final boolean use3mf) {
        Context ctx = fragment.getContext();
        if (!(ctx instanceof Activity)) return;
        final Activity act = (Activity) ctx;

        GCodeProcessorResult result = fragment.getGlView().getRenderer().getGcodeResult();
        String recommended = result != null ? result.getRecommendedName() : "print.gcode";
        if (recommended == null || recommended.isEmpty()) recommended = "print.gcode";
        final String finalRecommended = recommended;

        final File source = BedFragment.getTempGCodePath();
        if (source == null || !source.isFile() || source.length() == 0) {
            new BeamAlertDialogBuilder(ctx)
                    .setTitle(R.string.MenuSliceSendToPrinterFailed)
                    .setMessage(R.string.MenuSliceExportNoGcode)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        if (!use3mf) {
            dispatch(act, source, finalRecommended, share, false);
            return;
        }

        // Packaging reads and parses the whole G-code: keep it off the UI thread.
        final String tag = UUID.randomUUID().toString();
        SliceBeam.EVENT_BUS.fireEvent(new NeedSnackbarEvent(SnackbarsLayout.Type.LOADING, R.string.MenuSliceSendToPrinterLoading).tag(tag));
        new Thread(() -> {
            File packageFile = null;
            String error = null;
            try {
                packageFile = buildGcode3mfPackage(source, finalRecommended);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final File built = packageFile;
            final String failure = error;
            ViewUtils.postOnMainThread(() -> {
                SliceBeam.EVENT_BUS.fireEvent(new NeedDismissSnackbarEvent(tag));
                if (built == null) {
                    new BeamAlertDialogBuilder(ctx)
                            .setTitle(R.string.MenuSliceSendToPrinterFailed)
                            .setMessage(ctx.getString(R.string.MenuSliceExport3mfFailed, String.valueOf(failure)))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                dispatch(act, built, gcode3mfName(finalRecommended), share, true);
            });
        }, "gcode3mf-export").start();
    }

    private void dispatch(Activity act, File file, String fileName, boolean share, boolean use3mf) {
        if (share) {
            shareFile(act, file, fileName, use3mf);
            return;
        }
        pendingExportFile = file;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(use3mf ? "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" : "application/x-gcode");
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        act.startActivityForResult(i, MainActivity.REQUEST_CODE_EXPORT_GCODE);
    }

    private static String gcode3mfName(String recommended) {
        String base = recommended.trim();
        if (base.isEmpty()) base = "print.gcode";
        if (base.toLowerCase(Locale.ROOT).endsWith(".gcode.3mf")) return base;
        if (base.toLowerCase(Locale.ROOT).endsWith(".gcode")) return base + ".3mf";
        return base + ".gcode.3mf";
    }

    /** Builds the .gcode.3mf in cache and returns it. */
    private File buildGcode3mfPackage(File gcode, String recommended) throws Exception {
        File dest = new File(SliceBeam.INSTANCE.getCacheDir(), "export_" + System.currentTimeMillis() + ".gcode.3mf");
        ConfigObject printer = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        GCodeProcessorResult result = fragment.getGlView().getRenderer().getGcodeResult();
        GCode3mfPackager.create(gcode, dest, null, printer, result);
        if (!GCode3mfPackager.verify(dest)) {
            // Keep going: some firmwares only need the archive to be readable, but warn in log.
            Log.w("SliceMenu", "gcode.3mf failed self-check: " + dest.getAbsolutePath());
        }
        return dest;
    }

    private void shareFile(Activity act, File f, String displayName, boolean use3mf) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(use3mf ? "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" : "application/x-gcode");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Custom display name: some OEMs resolve the name from the path instead of the provider.
            i.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(act, BuildConfig.APPLICATION_ID + ".provider", f, displayName));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
        }
        act.startActivity(Intent.createChooser(i, null));
    }
}
