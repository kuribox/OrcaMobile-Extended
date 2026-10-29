package ru.ytkab0bp.slicebeam.view;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.ytkab0bp.slicebeam.MainActivity;
import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.components.BeamAlertDialogBuilder;
import ru.ytkab0bp.slicebeam.components.bed_menu.SliceMenu;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.slic3r.Model;
import ru.ytkab0bp.slicebeam.theme.IThemeView;
import ru.ytkab0bp.slicebeam.theme.ThemesRepo;
import ru.ytkab0bp.slicebeam.utils.GCodeStats;
import ru.ytkab0bp.slicebeam.utils.GCodeThumbnail;
import ru.ytkab0bp.slicebeam.utils.HostTransfer;
import ru.ytkab0bp.slicebeam.utils.IOUtils;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;

/**
 * Guided mode as a panel over the real bed screen, so the 3D view the user drags, the auto-arrange,
 * the scale gestures and the sliced preview are exactly the ones the app always uses.
 *
 * <p>The panel only covers the bottom of the screen; the transparent part lets touches reach the
 * plate. Closing it leaves everything applied: models stay on the bed, answers live in
 * {@code SliceBeam.LIVE_DIFF_PRINT} (unsaved edits of the active profile) and a slice already loaded
 * stays in the viewer.
 */
public class WizardOverlay extends FrameLayout implements IThemeView {

    private static final int STEP_FILES = 0;
    private static final int STEP_BED = 1;
    private static final int STEP_PRINTER = 2;
    private static final int STEP_OPTIONS = 3;
    private static final int STEP_FILAMENT = 4;
    private static final int STEP_SLICE = 5;
    private static final int STEP_COUNT = 6;

    private static final Pattern TIME_COMMENT = Pattern.compile("(?i)^;\\s*estimated printing time[^=]*=\\s*(.*)$");
    private static final Pattern WEIGHT_COMMENT = Pattern.compile("(?i)^;\\s*total filament weight \\[g\\]\\s*=\\s*(.*)$");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])", Pattern.CASE_INSENSITIVE);

    private final BedFragment bed;
    private final Context ctx;
    private final List<String> loadedNames = new ArrayList<>();
    private final List<String> printerTitles = new ArrayList<>();
    private final List<String> filamentTitles = new ArrayList<>();

    private final LinearLayout panel;
    private final LinearLayout content;
    private final TextView stepLabel;
    private final TextView titleView;
    private final TextView explainView;
    private final LinearLayout footer;
    private final LinearLayout actions;

    private int step = STEP_FILES;
    private int supports = 1;
    private int brim = 1;
    private int skirt = 1;
    private int quality = 1;
    private int infill = 1;
    private float scaleX = 100f;
    private float scaleY = 100f;
    private float scaleZ = 100f;
    private float appliedX = 100f;
    private float appliedY = 100f;
    private float appliedZ = 100f;
    private boolean scaleLocked = true;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean slicing;
    private boolean sliceFailed;
    private File gcode;
    private byte[] previewPng;
    private int seconds;
    private double grams;
    private int layers;
    private float averageSpeed;

    public WizardOverlay(BedFragment bed) {
        super(bed.getContext());
        this.bed = bed;
        this.ctx = bed.getContext();

        // Transparent wrapper: only the panel consumes touches, the rest goes to the bed.
        setClickable(false);
        setFocusable(false);

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setPadding(ViewUtils.dp(14), ViewUtils.dp(12), ViewUtils.dp(14), ViewUtils.dp(12));
        panel.setBackground(panelBackground());

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView badge = new ImageView(ctx);
        badge.setImageResource(R.drawable.magic_wand_outline_28);
        badge.setColorFilter(ThemesRepo.getColor(android.R.attr.colorAccent));
        header.addView(badge, new LinearLayout.LayoutParams(ViewUtils.dp(22), ViewUtils.dp(22)));

        titleView = new TextView(ctx);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        titleView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = ViewUtils.dp(8);
        header.addView(titleView, titleParams);

        stepLabel = new TextView(ctx);
        stepLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        stepLabel.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        header.addView(stepLabel);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        close.setOnClickListener(v -> bed.dismissWizard());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(ViewUtils.dp(32), ViewUtils.dp(32));
        closeParams.leftMargin = ViewUtils.dp(8);
        header.addView(close, closeParams);

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        explainView = new TextView(ctx);
        explainView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        explainView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams explainParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explainParams.topMargin = ViewUtils.dp(4);
        panel.addView(explainView, explainParams);

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = ViewUtils.dp(8);
        panel.addView(scroll, scrollParams);

        actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = ViewUtils.dp(8);
        panel.addView(footer, footerParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        panelParams.leftMargin = ViewUtils.dp(8);
        panelParams.rightMargin = ViewUtils.dp(8);
        panelParams.bottomMargin = ViewUtils.dp(8);
        panelParams.height = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.46f);
        addView(panel, panelParams);

        collectProfiles();
        render();
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(ViewUtils.dp(18));
        drawable.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        drawable.setStroke(ViewUtils.dp(1), ThemesRepo.getColor(android.R.attr.colorControlHighlight));
        return drawable;
    }

    private Activity activity() {
        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    // ------------------------------------------------------------------ picker

    public void startPicker() {
        Activity act = activity();
        if (act == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        act.startActivityForResult(intent, MainActivity.REQUEST_CODE_WIZARD_FILE);
    }

    /** Receives the document picker result (single or several files). */
    public void onPickerResult(Intent data) {
        if (data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        toast(ctx.getString(R.string.WizardLoadingModel));
        new Thread(() -> {
            String error = null;
            for (Uri uri : uris) {
                try {
                    File copied = copyToCache(uri);
                    if (copied == null) continue;
                    bed.loadModel(copied);
                    loadedNames.add(copied.getName().replaceFirst("^\\d+_", ""));
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                }
            }
            Model model = bed.getCurrentModel();
            if (model != null) {
                for (int i = 0; i < model.getObjectsCount(); i++) {
                    try {
                        model.autoOrient(i);
                    } catch (Exception ignored) {
                    }
                }
                arrange();
            }
            final String failure = error;
            ViewUtils.postOnMainThread(() -> {
                if (failure != null) {
                    new BeamAlertDialogBuilder(ctx)
                            .setTitle(R.string.MenuFileOpenFileFailed)
                            .setMessage(failure)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                render();
            });
        }, "wizard-import").start();
    }

    private File copyToCache(Uri uri) {
        Activity act = activity();
        if (act == null) return null;
        try {
            ContentResolver resolver = act.getContentResolver();
            String name = IOUtils.getDisplayName(uri);
            if (name == null) name = "pieza.stl";
            File dir = new File(SliceBeam.INSTANCE.getCacheDir(), "wizard");
            dir.mkdirs();
            File target = new File(dir, System.currentTimeMillis() + "_" + name);
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ steps

    private void collectProfiles() {
        printerTitles.clear();
        filamentTitles.clear();
        if (SliceBeam.CONFIG == null) return;
        for (ConfigObject printer : SliceBeam.CONFIG.printerConfigs) printerTitles.add(printer.getTitle());
        for (ConfigObject filament : SliceBeam.CONFIG.filamentConfigs) filamentTitles.add(filament.getTitle());
    }

    private void render() {
        content.removeAllViews();
        footer.removeAllViews();
        actions.removeAllViews();
        stepLabel.setText(ctx.getString(R.string.WizardStep, step + 1, STEP_COUNT));

        switch (step) {
            case STEP_FILES: renderFiles(); break;
            case STEP_BED: renderBed(); break;
            case STEP_PRINTER: renderPrinter(); break;
            case STEP_OPTIONS: renderOptions(); break;
            case STEP_FILAMENT: renderFilament(); break;
            default: renderSlice(); break;
        }
        renderFooter();
    }

    private void renderFiles() {
        titleView.setText(R.string.WizardFilesTitle);
        explainView.setText(R.string.WizardFilesExplain);

        addIconButton(R.drawable.folder_simple_plus_outline_28, ctx.getString(R.string.WizardFilesChoose),
                v -> startPicker());

        if (loadedNames.isEmpty()) {
            Model onBed = bed.getCurrentModel();
            if (onBed != null && onBed.getObjectsCount() > 0) {
                for (int i = 0; i < onBed.getObjectsCount(); i++) {
                    loadedNames.add(ctx.getString(R.string.WizardBedPiece, i + 1));
                }
            }
        }
        if (loadedNames.isEmpty()) {
            addNote(ctx.getString(R.string.WizardFilesEmpty));
        } else {
            for (String name : loadedNames) {
                addRow(R.drawable.picture_stack_outline_28, name, null, null, false);
            }
        }
    }

    private void renderBed() {
        titleView.setText(R.string.WizardBedTitle);
        explainView.setText(R.string.WizardBedExplain);
        Model model = bed.getCurrentModel();
        int count = model == null ? 0 : model.getObjectsCount();

        addRow(R.drawable.square_stack_up_outline_28,
                ctx.getString(R.string.WizardBedCount, count), null, null, false);

        LinearLayout tools = new LinearLayout(ctx);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(iconButton(R.drawable.grid_layout_outline_28,
                ctx.getString(R.string.WizardBedArrange), v -> arrange()), weighted(1f));
        ImageView lock = new ImageView(ctx);
        lock.setImageResource(R.drawable.done_outline_28);
        lock.setColorFilter(ThemesRepo.getColor(scaleLocked
                ? android.R.attr.colorAccent : android.R.attr.textColorSecondary));
        lock.setPadding(ViewUtils.dp(10), ViewUtils.dp(16), ViewUtils.dp(10), ViewUtils.dp(16));
        lock.setOnClickListener(v -> {
            scaleLocked = !scaleLocked;
            render();
        });
        tools.addView(lock, weighted(0.6f));
        tools.addView(scaleField("X", scaleX, value -> {
            scaleX = value;
            if (scaleLocked) {
                scaleY = value;
                scaleZ = value;
            }
            applyScale();
        }), weighted(1f));
        tools.addView(scaleField("Y", scaleY, value -> {
            scaleY = value;
            if (scaleLocked) {
                scaleX = value;
                scaleZ = value;
            }
            applyScale();
        }), weighted(1f));
        tools.addView(scaleField("Z", scaleZ, value -> {
            scaleZ = value;
            if (scaleLocked) {
                scaleX = value;
                scaleY = value;
            }
            applyScale();
        }), weighted(1f));
        content.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addNote(ctx.getString(R.string.WizardScaleLockHint));
        addNote(ctx.getString(R.string.WizardBedGestureHint));
    }

    private void renderPrinter() {
        titleView.setText(R.string.WizardPrinterTitle);
        explainView.setText(R.string.WizardPrinterExplain);
        String selected = SliceBeam.CONFIG.presets.get("printer");
        for (String printer : printerTitles) {
            addRow(R.drawable.printer_outline_28, printer, null,
                    v -> {
                        SliceBeam.CONFIG.presets.put("printer", printer);
                        try {
                            SliceBeam.ensureCompatibleSelection();
                            SliceBeam.saveConfig();
                        } catch (Exception ignored) {
                        }
                        step = STEP_PRINTER;
                        render();
                    }, TextUtils.equals(printer, selected));
        }
    }

    private void renderOptions() {
        titleView.setText(R.string.WizardOptionsTitle);
        explainView.setText(R.string.WizardOptionsExplain);

        addChips(R.string.WizardSupports, new String[]{
                ctx.getString(R.string.WizardSupportsNone),
                ctx.getString(R.string.WizardSupportsTree),
                ctx.getString(R.string.WizardSupportsNormal)}, supports, i -> supports = i);

        addChips(R.string.WizardBrim, new String[]{
                ctx.getString(R.string.WizardBrimNoneShort),
                ctx.getString(R.string.WizardBrimAutoShort),
                ctx.getString(R.string.WizardBrimOuterShort)}, brim, i -> brim = i);

        addChips(R.string.WizardSkirt, new String[]{
                ctx.getString(R.string.WizardSkirtYesShort),
                ctx.getString(R.string.WizardSkirtNoShort)}, skirt == 1 ? 0 : 1,
                i -> skirt = i == 0 ? 1 : 0);

        addChips(R.string.WizardQuality, new String[]{"0.28", "0.20", "0.16", "0.12"}, quality, i -> quality = i);
        addNote(ctx.getString(R.string.WizardQualityNote));

        addChips(R.string.WizardInfill, new String[]{"10 %", "25 %", "40 %"}, infill, i -> infill = i);
    }

    private void renderFilament() {
        titleView.setText(R.string.WizardFilamentTitle);
        explainView.setText(R.string.WizardFilamentExplain);
        String selected = SliceBeam.CONFIG.presets.get("filament");
        for (String filament : filamentTitles) {
            addRow(R.drawable.slot_filament_28, filament, null,
                    v -> {
                        SliceBeam.CONFIG.presets.put("filament", filament);
                        try {
                            SliceBeam.saveConfig();
                        } catch (Exception ignored) {
                        }
                        step = STEP_FILAMENT;
                        render();
                    }, TextUtils.equals(filament, selected));
        }
    }

    /** Entering this step slices: the result appears in the 3D viewer behind the panel. */
    private void renderSlice() {
        titleView.setText(R.string.WizardSliceTitle);
        explainView.setText(slicing ? ctx.getString(R.string.WizardSlicing) : ctx.getString(R.string.WizardSliceExplain));

        if (gcode == null) {
            if (sliceFailed) {
                addIconButton(R.drawable.refresh_outline_28,
                        ctx.getString(R.string.WizardRetrySlice), v -> {
                            sliceFailed = false;
                            render();
                        });
            } else if (!slicing) {
                startSlice();
            }
            return;
        }

        if (previewPng != null) {
            ImageView image = new ImageView(ctx);
            image.setImageBitmap(BitmapFactory.decodeByteArray(previewPng, 0, previewPng.length));
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(110));
            params.bottomMargin = ViewUtils.dp(6);
            content.addView(image, params);
        }

        addRow(R.drawable.clock_circle_dashed_outline_24,
                ctx.getString(R.string.WizardStatTime), formatDuration(seconds), null, false);
        addRow(R.drawable.slot_filament_28,
                ctx.getString(R.string.WizardStatWeight),
                String.format(java.util.Locale.getDefault(), "%.1f g", grams), null, false);
        addRow(R.drawable.square_stack_up_outline_28,
                ctx.getString(R.string.WizardStatLayers), String.valueOf(layers), null, false);
        addRow(R.drawable.magic_wand_outline_28,
                ctx.getString(R.string.WizardStatSpeed),
                String.format(java.util.Locale.getDefault(), "%.0f mm/s", averageSpeed), null, false);
    }

    private void renderFooter() {
        if (step > STEP_FILES) {
            footer.addView(iconButton(R.drawable.arrow_left_outline_28,
                    ctx.getString(R.string.WizardBack), v -> {
                        if (step == STEP_SLICE) bed.exitSlicedView();
                        step--;
                        render();
                    }), weighted(1f));
        }
        if (step == STEP_FILAMENT) {
            footer.addView(iconButton(R.drawable.done_outline_28,
                    ctx.getString(R.string.WizardFinishNoSlice), v -> bed.dismissWizard()), weighted(1.6f));
        }
        if (step == STEP_SLICE) {
            if (gcode != null) {
                actions.addView(iconButton(R.drawable.send_outline_28,
                        ctx.getString(R.string.WizardSend), v -> askFormat(false, false)), weighted(1f));
                actions.addView(iconButton(R.drawable.send_28,
                        ctx.getString(R.string.WizardSendPrint), v -> askFormat(true, false)), weighted(1f));
                actions.addView(iconButton(R.drawable.folder_simple_arrow_right_outline_28,
                        ctx.getString(R.string.WizardDownload), v -> askFormat(false, true)), weighted(1f));
                actions.addView(iconButton(R.drawable.share_external_28,
                        ctx.getString(R.string.WizardShare), v -> askFormat(true, true)), weighted(1f));
            }
            footer.addView(new android.view.View(ctx), weighted(1f));
            footer.addView(iconButton(R.drawable.done_outline_28,
                    ctx.getString(R.string.WizardFinish), v -> bed.dismissWizard()), weighted(1f));
            return;
        }
        View next = iconButton(R.drawable.arrow_left_outline_28,
                ctx.getString(R.string.WizardNext), true, v -> {
                    if (step == STEP_OPTIONS) applyAnswers();
                    step = Math.min(STEP_SLICE, step + 1);
                    render();
                });
        footer.addView(next, weighted(1.4f));
    }

    // ------------------------------------------------------------------ actions

    private void arrange() {
        Model model = bed.getCurrentModel();
        if (model == null) return;
        try {
            if (bed.getGlView() != null && bed.getGlView().getRenderer().getBed() != null) {
                bed.getGlView().getRenderer().getBed().arrange(model);
            }
            model.resetBoundingBox();
        } catch (Exception ignored) {
        }
        refreshBed();
    }

    /**
     * The renderer keeps uploaded meshes per object: without invalidating them the plate only
     * repaints after the next gesture, which looked like arrange/scale did nothing.
     */
    private void refreshBed() {
        bed.invalidateModel();
    }

    /** Applies the fields as a delta over what is already applied, per axis. */
    private void applyScale() {
        Model model = bed.getCurrentModel();
        if (model == null) return;
        float factorX = appliedX == 0 ? 1f : scaleX / appliedX;
        float factorY = appliedY == 0 ? 1f : scaleY / appliedY;
        float factorZ = appliedZ == 0 ? 1f : scaleZ / appliedZ;
        appliedX = scaleX;
        appliedY = scaleY;
        appliedZ = scaleZ;
        try {
            for (int i = 0; i < model.getObjectsCount(); i++) {
                model.scale(i, factorX, factorY, factorZ);
                model.ensureOnBed(i);
            }
            model.resetBoundingBox();
        } catch (Exception ignored) {
        }
        refreshBed();
    }

    /** Small numeric field (% of original) for one axis. */
    private View scaleField(String axis, float value, FloatConsumer onChange) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        TextView label = new TextView(ctx);
        label.setText(axis);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        column.addView(label);

        android.widget.EditText field = new android.widget.EditText(ctx);
        field.setText(String.valueOf(Math.round(value)));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> {
                    try {
                        float parsed = Float.parseFloat(text.toString().replace(',', '.'));
                        if (parsed > 0) onChange.accept(parsed);
                    } catch (Exception ignored) {
                    }
                }, 250);
            }
        });
        column.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(34)));
        return column;
    }

    private void applyAnswers() {
        java.util.Map<String, String> diff = SliceBeam.LIVE_DIFF_PRINT.values;
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
        diff.put("brim_type", brim == 0 ? "no_brim" : brim == 1 ? "auto_brim" : "outer_only");
        diff.put("skirt_loops", skirt == 1 ? "1" : "0");
        diff.put("layer_height", quality == 0 ? "0.28" : quality == 1 ? "0.20"
                : quality == 2 ? "0.16" : "0.12");
        diff.put("sparse_infill_density", infill == 0 ? "10%" : infill == 1 ? "25%" : "40%");
    }

    private void startSlice() {
        final Model model = bed.getCurrentModel();
        if (model == null || model.getObjectsCount() == 0) {
            explainView.setText(R.string.WizardNoModel);
            return;
        }
        applyAnswers();
        slicing = true;
        explainView.setText(R.string.WizardSlicing);

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
                // Same viewer and same information panel the View tab uses.
                ViewUtils.postOnMainThread(() -> {
                    bed.loadGCode(out);
                    ViewUtils.postOnMainThread(() ->
                            SliceMenu.showInfoPanel(bed, actions), 700);
                });
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String failure = error;
            ViewUtils.postOnMainThread(() -> {
                slicing = false;
                if (failure != null) {
                    sliceFailed = true;
                    explainView.setText(ctx.getString(R.string.WizardSliceFailed, failure));
                }
                render();
            });
        }, "wizard-slice").start();
    }

    private void readStats(File file) {
        seconds = 0;
        grams = 0;
        if (file == null || !file.isFile()) return;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(";")) continue;
                Matcher time = TIME_COMMENT.matcher(line);
                if (time.matches() && seconds == 0) seconds = parseDuration(time.group(1));
                Matcher weight = WEIGHT_COMMENT.matcher(line);
                if (weight.matches() && grams == 0) {
                    try {
                        grams = Double.parseDouble(weight.group(1).trim().replace("\"", ""));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            GCodeStats stats = GCodeStats.analyze(file);
            layers = stats.layers;
            averageSpeed = stats.speed.hasData() ? stats.speed.avg : 0;
        } catch (Exception ignored) {
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

    private String formatDuration(int total) {
        if (total <= 0) return "—";
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        return hours > 0 ? ctx.getString(R.string.WizardDurationHours, hours, minutes)
                : ctx.getString(R.string.WizardDurationMinutes, minutes);
    }

    private void askFormat(boolean print, boolean notSend) {
        String[] options = {
                ctx.getString(R.string.MenuSliceFormatGcode),
                ctx.getString(R.string.MenuSliceFormatGcode3mf)
        };
        new BeamAlertDialogBuilder(ctx)
                .setTitle(notSend ? (print ? R.string.WizardShare : R.string.WizardDownload) : R.string.WizardSendTitle)
                .setItems(options, (dialog, which) -> {
                    if (notSend) {
                        handleLocal(print, which == 1);
                    } else {
                        handleSend(print, which == 1);
                    }
                })
                .show();
    }

    private void handleLocal(boolean share, boolean use3mf) {
        Activity act = activity();
        if (act == null) return;
        try {
            String name = HostTransfer.payloadName(ctx.getString(R.string.WizardPrintName), use3mf);
            File payload = HostTransfer.preparePayload(bed, ctx.getString(R.string.WizardPrintName), use3mf);
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
            new BeamAlertDialogBuilder(ctx)
                    .setTitle(R.string.WizardActionFailed)
                    .setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void handleSend(boolean print, boolean use3mf) {
        toast(ctx.getString(R.string.WizardSending));
        new Thread(() -> HostTransfer.send(bed, print, use3mf, (ok, message) -> ViewUtils.postOnMainThread(() ->
                new BeamAlertDialogBuilder(ctx)
                        .setTitle(ok ? R.string.MenuPrinterTestOKTitle : R.string.MenuSliceSendToPrinterFailed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show())), "wizard-send").start();
    }

    // ------------------------------------------------------------------ widgets

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewUtils.dp(54), weight);
        params.rightMargin = ViewUtils.dp(6);
        return params;
    }

    /** Icon + label button with a fixed height, so every row lines up. */
    private View iconButton(int iconRes, String label, View.OnClickListener listener) {
        return iconButton(iconRes, label, false, listener);
    }

    private View iconButton(int iconRes, String label, boolean mirrorIcon, View.OnClickListener listener) {
        LinearLayout view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(ViewUtils.dp(8), ViewUtils.dp(10), ViewUtils.dp(8), ViewUtils.dp(10));
        view.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight),
                ThemesRepo.getColor(android.R.attr.colorControlHighlight), ViewUtils.dp(4)));
        view.setOnClickListener(listener);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        if (mirrorIcon) icon.setScaleX(-1f);
        view.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(20), ViewUtils.dp(20)));

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.addView(text);

        view.setMinimumHeight(ViewUtils.dp(54));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewUtils.dp(54)));
        return view;
    }

    private void addIconButton(int iconRes, String label, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ViewUtils.dp(12), ViewUtils.dp(10), ViewUtils.dp(12), ViewUtils.dp(10));
        row.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight),
                ThemesRepo.getColor(android.R.attr.colorControlHighlight), ViewUtils.dp(4)));
        row.setOnClickListener(listener);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(ThemesRepo.getColor(android.R.attr.colorAccent));
        row.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(22), ViewUtils.dp(22)));

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = ViewUtils.dp(10);
        row.addView(text, params);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = ViewUtils.dp(6);
        content.addView(row, rowParams);
    }

    private void addRow(int iconRes, String heading, String value, View.OnClickListener listener, boolean selected) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ViewUtils.dp(10), ViewUtils.dp(8), ViewUtils.dp(10), ViewUtils.dp(8));
        row.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight),
                ThemesRepo.getColor(selected ? android.R.attr.colorControlHighlight : android.R.attr.windowBackground),
                ViewUtils.dp(4)));
        if (listener != null) row.setOnClickListener(listener);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(ThemesRepo.getColor(selected ? android.R.attr.colorAccent : android.R.attr.textColorSecondary));
        row.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(20), ViewUtils.dp(20)));

        TextView text = new TextView(ctx);
        text.setText(heading);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setTextColor(ThemesRepo.getColor(selected ? android.R.attr.colorAccent : android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = ViewUtils.dp(10);
        row.addView(text, params);

        if (value != null) {
            TextView valueView = new TextView(ctx);
            valueView.setText(value);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            valueView.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            row.addView(valueView);
        }

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = ViewUtils.dp(4);
        content.addView(row, rowParams);
    }

    private void addChips(int titleRes, String[] options, int selected, IntConsumer onPick) {
        TextView title = new TextView(ctx);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = ViewUtils.dp(6);
        content.addView(title, titleParams);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView chip = new TextView(ctx);
            chip.setText(options[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(ViewUtils.dp(8), ViewUtils.dp(8), ViewUtils.dp(8), ViewUtils.dp(8));
            boolean active = i == selected;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(ViewUtils.dp(4));
            background.setColor(ThemesRepo.getColor(active
                    ? android.R.attr.colorAccent : android.R.attr.colorControlHighlight));
            chip.setBackground(background);
            chip.setTextColor(ThemesRepo.getColor(active
                    ? R.attr.textColorOnAccent : android.R.attr.textColorPrimary));
            chip.setOnClickListener(v -> {
                onPick.accept(index);
                render();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.rightMargin = ViewUtils.dp(6);
            row.addView(chip, params);
        }
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addNote(String text) {
        TextView view = new TextView(ctx);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        view.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ViewUtils.dp(8);
        content.addView(view, params);
    }

    private void toast(String message) {
        ViewUtils.postOnMainThread(() -> android.widget.Toast.makeText(
                ctx, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    private interface FloatConsumer {
        void accept(float value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    @Override
    public void onApplyTheme() {
        if (panel != null) {
            panel.setBackground(panelBackground());
        }
    }
}
