package ru.ytkab0bp.slicebeam.fragment;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.ytkab0bp.slicebeam.MainActivity;
import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.components.BeamAlertDialogBuilder;
import ru.ytkab0bp.slicebeam.components.bed_menu.SliceMenu;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.navigation.Fragment;
import ru.ytkab0bp.slicebeam.slic3r.Model;
import ru.ytkab0bp.slicebeam.theme.IThemeView;
import ru.ytkab0bp.slicebeam.theme.ThemesRepo;
import ru.ytkab0bp.slicebeam.utils.GCodeStats;
import ru.ytkab0bp.slicebeam.utils.GCodeThumbnail;
import ru.ytkab0bp.slicebeam.utils.HostTransfer;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;

/**
 * Guided mode: five screens that take someone who has never sliced anything from a file to a print.
 *
 * <p>Model → printer → a handful of plain-language questions → filament → slice, and then the file
 * can be sent, downloaded or shared. The answers are written to {@code SliceBeam.LIVE_DIFF_PRINT},
 * the app's unsaved-edit layer, so the user's saved profiles are never modified behind their back.
 */
public class WizardFragment extends Fragment implements IThemeView {

    private static final int STEP_MODEL = 0;
    private static final int STEP_PRINTER = 1;
    private static final int STEP_OPTIONS = 2;
    private static final int STEP_FILAMENT = 3;
    private static final int STEP_SLICE = 4;
    private static final int STEP_COUNT = 5;

    private static final Pattern TIME_COMMENT =
            Pattern.compile("(?i)^;\\s*estimated printing time[^=]*=\\s*(.*)$");
    private static final Pattern WEIGHT_COMMENT =
            Pattern.compile("(?i)^;\\s*total filament weight \\[g\\]\\s*=\\s*(.*)$");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])", Pattern.CASE_INSENSITIVE);

    private final List<String> printerTitles = new ArrayList<>();
    private final List<String> filamentTitles = new ArrayList<>();

    private int step = STEP_MODEL;
    private LinearLayout root;
    private LinearLayout body;
    private TextView stepLabel;
    private TextView title;
    private TextView explanation;
    private LinearLayout footer;

    private File modelFile;
    private String modelName = "";
    private boolean slicing;

    // Answers of the plain-language questions.
    private int supports = 1;      // 0 none, 1 tree, 2 normal
    private int brim = 1;          // 0 none, 1 auto, 2 outer only
    private int skirt = 1;         // 0 no, 1 yes
    private int quality = 1;       // 0 rough, 1 normal, 2 fine
    private int infill = 1;        // 0..3 → 10/15/25/40 %

    private File gcode;
    private byte[] previewPng;
    private int seconds;
    private double grams;
    private int layers;
    private float averageSpeed;

    @Override
    public View onCreateView(Context ctx) {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));

        // Header: step counter, title and the explanation of what this screen does.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ViewUtils.dp(20), ViewUtils.dp(18), ViewUtils.dp(20), ViewUtils.dp(6));

        stepLabel = new TextView(ctx);
        stepLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        stepLabel.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        header.addView(stepLabel);

        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = ViewUtils.dp(4);
        header.addView(title, titleParams);

        explanation = new TextView(ctx);
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        explanation.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams explainParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explainParams.topMargin = ViewUtils.dp(6);
        header.addView(explanation, explainParams);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ViewUtils.dp(16), ViewUtils.dp(4), ViewUtils.dp(16), ViewUtils.dp(8));

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(ViewUtils.dp(12), ViewUtils.dp(8), ViewUtils.dp(12), ViewUtils.dp(14));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        collectProfiles();
        renderStep();
        return root;
    }

    private Activity activity() {
        Context ctx = getContext();
        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    /** The bed screen underneath: it owns the model and the slicing engine. */
    private BedFragment bed() {
        Activity act = activity();
        if (!(act instanceof MainActivity)) return null;
        Fragment fragment = ((MainActivity) act).getNavigationDelegate().getFragmentAt(0, 0);
        return fragment instanceof BedFragment ? (BedFragment) fragment : null;
    }

    private void collectProfiles() {
        printerTitles.clear();
        if (SliceBeam.CONFIG != null) {
            for (ConfigObject printer : SliceBeam.CONFIG.printerConfigs) {
                printerTitles.add(printer.getTitle());
            }
        }
        filamentTitles.clear();
        if (SliceBeam.CONFIG != null) {
            for (ConfigObject filament : SliceBeam.CONFIG.filamentConfigs) {
                filamentTitles.add(filament.getTitle());
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private void renderStep() {
        body.removeAllViews();
        footer.removeAllViews();
        stepLabel.setText(getContext().getString(R.string.WizardStep, step + 1, STEP_COUNT));

        switch (step) {
            case STEP_MODEL: renderModel(); break;
            case STEP_PRINTER: renderPrinter(); break;
            case STEP_OPTIONS: renderOptions(); break;
            case STEP_FILAMENT: renderFilament(); break;
            default: renderSlice(); break;
        }
        renderFooter();
    }

    private void renderModel() {
        title.setText(R.string.WizardModelTitle);
        explanation.setText(R.string.WizardModelExplain);

        if (modelFile != null) {
            addCard(modelName, getContext().getString(R.string.WizardModelLoaded), false, null);
        }
        addButton(getContext().getString(R.string.WizardModelChoose), v -> pickModel());
        addNote(getContext().getString(R.string.WizardModelFormats));
    }

    private void renderPrinter() {
        title.setText(R.string.WizardPrinterTitle);
        explanation.setText(R.string.WizardPrinterExplain);

        String selected = SliceBeam.CONFIG.presets.get("printer");
        for (String printerTitle : printerTitles) {
            addCard(printerTitle, null, TextUtils.equals(printerTitle, selected),
                    v -> selectPrinter(printerTitle));
        }
    }

    private void renderOptions() {
        title.setText(R.string.WizardOptionsTitle);
        explanation.setText(R.string.WizardOptionsExplain);

        addSectionTitle(R.string.WizardSupports);
        addCard(getContext().getString(R.string.WizardSupportsNone), getContext().getString(R.string.WizardSupportsNoneHint), supports == 0, v -> { supports = 0; renderStep(); });
        addCard(getContext().getString(R.string.WizardSupportsTree), getContext().getString(R.string.WizardSupportsTreeHint), supports == 1, v -> { supports = 1; renderStep(); });
        addCard(getContext().getString(R.string.WizardSupportsNormal), getContext().getString(R.string.WizardSupportsNormalHint), supports == 2, v -> { supports = 2; renderStep(); });

        addSectionTitle(R.string.WizardBrim);
        addCard(getContext().getString(R.string.WizardBrimNone), getContext().getString(R.string.WizardBrimNoneHint), brim == 0, v -> { brim = 0; renderStep(); });
        addCard(getContext().getString(R.string.WizardBrimAuto), getContext().getString(R.string.WizardBrimAutoHint), brim == 1, v -> { brim = 1; renderStep(); });
        addCard(getContext().getString(R.string.WizardBrimOuter), getContext().getString(R.string.WizardBrimOuterHint), brim == 2, v -> { brim = 2; renderStep(); });

        addSectionTitle(R.string.WizardSkirt);
        addCard(getContext().getString(R.string.WizardSkirtYes), getContext().getString(R.string.WizardSkirtYesHint), skirt == 1, v -> { skirt = 1; renderStep(); });
        addCard(getContext().getString(R.string.WizardSkirtNo), getContext().getString(R.string.WizardSkirtNoHint), skirt == 0, v -> { skirt = 0; renderStep(); });

        addSectionTitle(R.string.WizardQuality);
        addCard(getContext().getString(R.string.WizardQualityRough), getContext().getString(R.string.WizardQualityRoughHint), quality == 0, v -> { quality = 0; renderStep(); });
        addCard(getContext().getString(R.string.WizardQualityNormal), getContext().getString(R.string.WizardQualityNormalHint), quality == 1, v -> { quality = 1; renderStep(); });
        addCard(getContext().getString(R.string.WizardQualityFine), getContext().getString(R.string.WizardQualityFineHint), quality == 2, v -> { quality = 2; renderStep(); });

        addSectionTitle(R.string.WizardInfill);
        addCard(getContext().getString(R.string.WizardInfill10), null, infill == 0, v -> { infill = 0; renderStep(); });
        addCard(getContext().getString(R.string.WizardInfill25), null, infill == 1, v -> { infill = 1; renderStep(); });
        addCard(getContext().getString(R.string.WizardInfill40), null, infill == 2, v -> { infill = 2; renderStep(); });
    }

    private void renderFilament() {
        title.setText(R.string.WizardFilamentTitle);
        explanation.setText(R.string.WizardFilamentExplain);

        String selected = SliceBeam.CONFIG.presets.get("filament");
        for (String filamentTitle : filamentTitles) {
            addCard(filamentTitle, null, TextUtils.equals(filamentTitle, selected),
                    v -> selectFilament(filamentTitle));
        }
    }

    private void renderSlice() {
        title.setText(R.string.WizardSliceTitle);

        if (gcode == null) {
            explanation.setText(R.string.WizardSliceExplain);
            addButton(slicing ? getContext().getString(R.string.WizardSlicing) : getContext().getString(R.string.WizardSliceNow),
                    v -> startSlice());
            return;
        }

        explanation.setText(R.string.WizardSliceDone);

        byte[] preview = previewPng;
        if (preview != null) {
            ImageView image = new ImageView(getContext());
            image.setImageBitmap(BitmapFactory.decodeByteArray(preview, 0, preview.length));
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(220));
            imageParams.bottomMargin = ViewUtils.dp(10);
            body.addView(image, imageParams);
        }

        LinearLayout statsCard = new LinearLayout(getContext());
        statsCard.setOrientation(LinearLayout.VERTICAL);
        statsCard.setPadding(ViewUtils.dp(14), ViewUtils.dp(12), ViewUtils.dp(14), ViewUtils.dp(12));
        statsCard.setBackground(ViewUtils.createRipple(
                ThemesRepo.getColor(android.R.attr.colorControlHighlight), ViewUtils.dp(12)));
        statsCard.addView(statRow(R.string.WizardStatTime, formatDuration(seconds)));
        statsCard.addView(statRow(R.string.WizardStatWeight, String.format(java.util.Locale.getDefault(), "%.1f g", grams)));
        statsCard.addView(statRow(R.string.WizardStatLayers, String.valueOf(layers)));
        if (averageSpeed > 0) {
            statsCard.addView(statRow(R.string.WizardStatSpeed,
                    String.format(java.util.Locale.getDefault(), "%.0f mm/s", averageSpeed)));
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = ViewUtils.dp(14);
        body.addView(statsCard, cardParams);

        addSectionTitle(R.string.WizardActionsTitle);
        addButton(getContext().getString(R.string.WizardSend), v -> askFormat(false, false));
        addButton(getContext().getString(R.string.WizardSendPrint), v -> askFormat(true, false));
        addButton(getContext().getString(R.string.WizardDownload), v -> askFormat(false, true));
        addButton(getContext().getString(R.string.WizardShare), v -> askFormat(true, true));
        addNote(getContext().getString(R.string.WizardActionsNote));
    }

    private void renderFooter() {
        if (step > 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.rightMargin = ViewUtils.dp(8);
            footer.addView(button(getContext().getString(R.string.WizardBack), v -> {
                step--;
                renderStep();
            }), params);
        }
        if (step < STEP_SLICE) {
            boolean enabled = true;
            if (step == STEP_MODEL) enabled = modelFile != null;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            View next = button(getContext().getString(R.string.WizardNext), v -> {
                if (step == STEP_OPTIONS) applyAnswers();
                step++;
                renderStep();
            });
            next.setAlpha(enabled ? 1f : 0.4f);
            next.setEnabled(enabled);
            footer.addView(next, params);
        } else if (step > 0) {
            footer.addView(button(getContext().getString(R.string.WizardFinish), v -> close()), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    // ------------------------------------------------------------------ actions

    private void pickModel() {
        Activity act = activity();
        if (act == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        act.startActivityForResult(intent, MainActivity.REQUEST_CODE_WIZARD_FILE);
    }

    /** Called by MainActivity when the file picker returns while this screen is on top. */
    public void onModelPicked(Uri uri) {
        Activity act = activity();
        if (act == null || uri == null) return;
        try {
            ContentResolver resolver = act.getContentResolver();
            String name = ru.ytkab0bp.slicebeam.utils.IOUtils.getDisplayName(uri);
            if (name == null) name = "modelo.stl";
            File dir = new File(SliceBeam.INSTANCE.getCacheDir(), "wizard");
            dir.mkdirs();
            File target = new File(dir, name);
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            }

            final File picked = target;
            final String pickedName = name;
            BedFragment bed = bed();
            if (bed == null) {
                toast(getContext().getString(R.string.WizardNoBed));
                return;
            }
            toast(getContext().getString(R.string.WizardLoadingModel));
            new Thread(() -> {
                String failure = null;
                try {
                    bed.loadModel(picked);
                    Model model = bed.getCurrentModel();
                    if (model != null) {
                        for (int i = 0; i < model.getObjectsCount(); i++) {
                            model.autoOrient(i);
                        }
                    }
                } catch (Exception e) {
                    failure = String.valueOf(e.getMessage());
                }
                final String error = failure;
                ViewUtils.postOnMainThread(() -> {
                    if (error != null) {
                        new BeamAlertDialogBuilder(getContext())
                                .setTitle(R.string.MenuFileOpenFileFailed)
                                .setMessage(error)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        return;
                    }
                    modelFile = picked;
                    modelName = pickedName;
                    renderStep();
                });
            }, "wizard-import").start();
        } catch (Exception e) {
            new BeamAlertDialogBuilder(getContext())
                    .setTitle(R.string.MenuFileOpenFileFailed)
                    .setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void selectPrinter(String printerTitle) {
        SliceBeam.CONFIG.presets.put("printer", printerTitle);
        try {
            SliceBeam.ensureCompatibleSelection();
            SliceBeam.saveConfig();
        } catch (Exception ignored) {
        }
        collectProfiles();
        reloadModelOnNewBed();
        renderStep();
    }

    /** A different printer means a different bed: place the model again on the new one. */
    private void reloadModelOnNewBed() {
        if (modelFile == null) return;
        BedFragment bed = bed();
        if (bed == null) return;
        try {
            bed.loadModel(modelFile);
            Model model = bed.getCurrentModel();
            if (model != null) {
                for (int i = 0; i < model.getObjectsCount(); i++) model.autoOrient(i);
            }
        } catch (Exception ignored) {
        }
    }

    private void selectFilament(String filamentTitle) {
        SliceBeam.CONFIG.presets.put("filament", filamentTitle);
        try {
            SliceBeam.saveConfig();
        } catch (Exception ignored) {
        }
        renderStep();
    }

    /** Writes the plain-language answers into the unsaved-edit layer, so profiles stay untouched. */
    private void applyAnswers() {
        Map<String, String> diff = SliceBeam.LIVE_DIFF_PRINT.values;

        switch (supports) {
            case 0:
                diff.put("enable_support", "0");
                break;
            case 1:
                diff.put("enable_support", "1");
                diff.put("support_type", "tree(auto)");
                break;
            default:
                diff.put("enable_support", "1");
                diff.put("support_type", "normal(auto)");
                break;
        }

        switch (brim) {
            case 0: diff.put("brim_type", "no_brim"); break;
            case 1: diff.put("brim_type", "auto_brim"); break;
            default: diff.put("brim_type", "outer_only"); break;
        }

        diff.put("skirt_loops", skirt == 1 ? "1" : "0");
        diff.put("layer_height", quality == 0 ? "0.28" : quality == 1 ? "0.2" : "0.12");
        diff.put("sparse_infill_density", infill == 0 ? "10%" : infill == 1 ? "25%" : "40%");
    }

    private void startSlice() {
        if (slicing) return;
        final BedFragment bed = bed();
        if (bed == null) {
            toast(getContext().getString(R.string.WizardNoBed));
            return;
        }
        final Model model = bed.getCurrentModel();
        if (model == null || model.getObjectsCount() == 0) {
            toast(getContext().getString(R.string.WizardNoModel));
            return;
        }

        applyAnswers();
        slicing = true;
        renderStep();

        new Thread(() -> {
            String error = null;
            try {
                SliceBeam.ensureCompatibleSelection();
                SliceBeam.genCurrentConfig();
                File cfg = SliceBeam.getCurrentConfigFile();
                File out = BedFragment.getTempGCodePath();
                model.slice(cfg.getAbsolutePath(), out.getAbsolutePath(), (progress, text) -> { });
                readStats(out);
                previewPng = GCodeThumbnail.render(out, 320);
                gcode = out;
                bed.loadGCode(out);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String failure = error;
            ViewUtils.postOnMainThread(() -> {
                slicing = false;
                if (failure != null) {
                    new BeamAlertDialogBuilder(getContext())
                            .setTitle(R.string.SliceFailed)
                            .setMessage(failure)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                renderStep();
            });
        }, "wizard-slice").start();
    }

    private void readStats(File gcodeFile) {
        seconds = 0;
        grams = 0;
        layers = 0;
        averageSpeed = 0;
        if (gcodeFile == null || !gcodeFile.isFile()) return;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(gcodeFile),
                        java.nio.charset.StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(";")) continue;
                Matcher time = TIME_COMMENT.matcher(line);
                if (time.matches() && seconds == 0) seconds = parseDuration(time.group(1));
                Matcher weight = WEIGHT_COMMENT.matcher(line);
                if (weight.matches() && grams == 0) {
                    grams = parseDouble(weight.group(1));
                }
            }
        } catch (Exception ignored) {
        }

        try {
            GCodeStats stats = GCodeStats.analyze(gcodeFile);
            layers = stats.layers;
            averageSpeed = stats.speed.hasData() ? stats.speed.avg : 0;
        } catch (Exception ignored) {
        }
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim().replace("\"", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseDuration(String text) {
        if (text == null) return 0;
        Matcher matcher = DURATION.matcher(text);
        int total = 0;
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                case "d": total += value * 86400; break;
                case "h": total += value * 3600; break;
                case "m": total += value * 60; break;
                default: total += value; break;
            }
        }
        return total;
    }

    private String formatDuration(int totalSeconds) {
        if (totalSeconds <= 0) return "—";
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) return getContext().getString(R.string.WizardDurationHours, hours, minutes);
        return getContext().getString(R.string.WizardDurationMinutes, minutes);
    }

    /** Asks G-code vs 3MF package, then sends, downloads or shares. */
    private void askFormat(boolean print, boolean notSend) {
        String[] options = {
                getContext().getString(R.string.MenuSliceFormatGcode),
                getContext().getString(R.string.MenuSliceFormatGcode3mf)
        };
        new BeamAlertDialogBuilder(getContext())
                .setTitle(notSend ? (print ? R.string.WizardShare : R.string.WizardDownload) : R.string.WizardSendTitle)
                .setItems(options, (dialog, which) -> {
                    boolean use3mf = which == 1;
                    if (notSend) {
                        handleLocal(print, use3mf);
                    } else {
                        handleSend(print, use3mf);
                    }
                })
                .show();
    }

    private void handleLocal(boolean share, boolean use3mf) {
        final Activity act = activity();
        final BedFragment bed = bed();
        if (act == null || bed == null) return;
        try {
            String recommended = SliceBeam.INSTANCE.getString(R.string.WizardPrintName);
            File payload = HostTransfer.preparePayload(bed, recommended, use3mf);
            String name = HostTransfer.payloadName(recommended, use3mf);
            if (share) {
                act.startActivity(HostTransfer.shareIntent(act, payload, name, use3mf));
            } else {
                SliceMenu.setPendingExportFile(payload);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(use3mf ? "application/octet-stream" : "text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, name);
                act.startActivityForResult(intent, MainActivity.REQUEST_CODE_EXPORT_GCODE);
            }
        } catch (Exception e) {
            new BeamAlertDialogBuilder(getContext())
                    .setTitle(R.string.WizardActionFailed)
                    .setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void handleSend(boolean print, boolean use3mf) {
        final BedFragment bed = bed();
        if (bed == null) return;
        toast(getContext().getString(R.string.WizardSending));
        new Thread(() -> HostTransfer.send(bed, print, use3mf, (ok, message) -> ViewUtils.postOnMainThread(() ->
                new BeamAlertDialogBuilder(getContext())
                        .setTitle(ok ? R.string.MenuPrinterTestOKTitle : R.string.MenuSliceSendToPrinterFailed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show())), "wizard-send").start();
    }

    private void close() {
        Activity act = activity();
        if (act instanceof MainActivity) {
            ((MainActivity) act).getNavigationDelegate().destroyCurrent();
        }
    }

    private android.graphics.drawable.GradientDrawable rounded(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setCornerRadius(ViewUtils.dp(12));
        drawable.setColor(color);
        return drawable;
    }

    // ------------------------------------------------------------------ view helpers

    private void addSectionTitle(int textRes) {
        TextView view = new TextView(getContext());
        view.setText(textRes);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        view.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ViewUtils.dp(14);
        params.bottomMargin = ViewUtils.dp(6);
        body.addView(view, params);
    }

    private void addNote(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ViewUtils.dp(10);
        body.addView(view, params);
    }

    /** Card with an optional subtitle; selected = 1 marks the chosen answer. */
    private void addCard(String heading, String subtitle, boolean selected, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ViewUtils.dp(14), ViewUtils.dp(12), ViewUtils.dp(14), ViewUtils.dp(12));
        card.setBackground(ViewUtils.createRipple(
                ThemesRepo.getColor(android.R.attr.colorControlHighlight), ViewUtils.dp(12)));
        if (selected) {
            card.setBackground(rounded(ThemesRepo.getColor(android.R.attr.colorControlHighlight)));
        }

        TextView headingView = new TextView(getContext());
        headingView.setText(heading);
        headingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        headingView.setTextColor(ThemesRepo.getColor(
                selected ? android.R.attr.colorAccent : android.R.attr.textColorPrimary));
        card.addView(headingView);

        if (subtitle != null) {
            TextView sub = new TextView(getContext());
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            sub.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            card.addView(sub);
        }

        if (listener != null) card.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ViewUtils.dp(8);
        body.addView(card, params);
    }

    private void addButton(String text, View.OnClickListener listener) {
        body.addView(button(text, listener), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView button(String text, View.OnClickListener listener) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        view.setTextColor(ThemesRepo.getColor(R.attr.textColorOnAccent));
        view.setPadding(ViewUtils.dp(16), ViewUtils.dp(14), ViewUtils.dp(16), ViewUtils.dp(14));
        view.setBackground(rounded(ThemesRepo.getColor(android.R.attr.colorAccent)));
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ViewUtils.dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private View statRow(int labelRes, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ViewUtils.dp(3), 0, ViewUtils.dp(3));

        TextView label = new TextView(getContext());
        label.setText(labelRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView number = new TextView(getContext());
        number.setText(value);
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        number.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        number.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        row.addView(number);
        return row;
    }

    private void toast(String message) {
        ViewUtils.postOnMainThread(() -> android.widget.Toast.makeText(
                getContext(), message, android.widget.Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onApplyTheme() {
        if (root != null) {
            root.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));
            renderStep();
        }
    }
}
