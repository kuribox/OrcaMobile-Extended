package ru.ytkab0bp.slicebeam.view;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.slic3r.Model;
import ru.ytkab0bp.slicebeam.theme.IThemeView;
import ru.ytkab0bp.slicebeam.theme.ThemesRepo;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;
import ru.ytkab0bp.slicebeam.utils.Vec3d;

/**
 * Scaling on its own screen, built with the same {@link PositionScrollView} tracks and the same
 * transform maths as the tools menu, so it feels and behaves like the control the app already has.
 *
 * <p>The difference with a naive track is bookkeeping: the tracks show the factor applied by this
 * screen and every commit sends {@code target / already applied}, so the numbers mean what they say
 * and 100 % is always the size the part had when the screen opened.
 */
public class ScaleOverlay extends FrameLayout implements IThemeView {

    private static final int MAX_PERCENT = 400;

    private final BedFragment bed;
    private final Context ctx;
    private final LinearLayout panel;
    private final LinearLayout content;

    private android.widget.SeekBar xTrack, yTrack, zTrack;
    private boolean updatingBars;
    private TextView xTitle, yTitle, zTitle;

    private int objectIndex = -1;
    private boolean locked = true;
    private double baseX = 1, baseY = 1, baseZ = 1;
    private double appliedX = 1, appliedY = 1, appliedZ = 1;
    private boolean syncing;

    private final double[] tempMatrix = new double[16];
    private final double[] tempVecArr = new double[4];
    private final Vec3d tempVec = new Vec3d();
    private final Vec3d tempVec2 = new Vec3d();

    public ScaleOverlay(BedFragment bed) {
        super(bed.getContext());
        this.bed = bed;
        this.ctx = bed.getContext();
        setClickable(false);
        setFocusable(false);

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setPadding(ViewUtils.dp(16), ViewUtils.dp(12), ViewUtils.dp(16), ViewUtils.dp(12));
        panel.setBackground(panelBackground());

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(R.string.ScaleTitle);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        close.setOnClickListener(v -> bed.dismissScale());
        header.addView(close, new LinearLayout.LayoutParams(ViewUtils.dp(32), ViewUtils.dp(32)));

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        panelParams.leftMargin = ViewUtils.dp(8);
        panelParams.rightMargin = ViewUtils.dp(8);
        panelParams.bottomMargin = ViewUtils.dp(8);
        addView(panel, panelParams);

        build();
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(ViewUtils.dp(18));
        drawable.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        drawable.setStroke(ViewUtils.dp(1), ThemesRepo.getColor(android.R.attr.colorControlHighlight));
        return drawable;
    }

    private Model model() {
        return bed.getCurrentModel();
    }

    private void build() {
        content.removeAllViews();
        objectIndex = bed.getSelectedObjectIndex();
        Model model = model();

        if (model == null || objectIndex < 0) {
            TextView hint = new TextView(ctx);
            hint.setText(R.string.ScaleSelectPart);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hint.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            hint.setPadding(0, ViewUtils.dp(12), 0, ViewUtils.dp(12));
            content.addView(hint);
            content.addView(primaryButton(R.string.ScaleRetry, v -> build()));
            return;
        }

        // Base size measured on the GL thread, like every other model read in this app.
        readGeometry(model);
        appliedX = 1;
        appliedY = 1;
        appliedZ = 1;

        xTitle = addAxis(R.attr.xTrackColor, "X");
        xTrack = addTrack(R.attr.xTrackColor, 0);
        yTitle = addAxis(R.attr.yTrackColor, "Y");
        yTrack = addTrack(R.attr.yTrackColor, 1);
        zTitle = addAxis(R.attr.zTrackColor, "Z");
        zTrack = addTrack(R.attr.zTrackColor, 2);
        updateTitles();

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = ViewUtils.dp(6);

        final ImageView lock = new ImageView(ctx);
        lock.setImageResource(locked ? R.drawable.ic_padlock_locked : R.drawable.ic_padlock_open);
        lock.setColorFilter(ThemesRepo.getColor(locked
                ? android.R.attr.colorAccent : android.R.attr.textColorSecondary));
        lock.setPadding(ViewUtils.dp(10), ViewUtils.dp(10), ViewUtils.dp(10), ViewUtils.dp(10));
        lock.setOnClickListener(v -> {
            locked = !locked;
            lock.setImageResource(locked ? R.drawable.ic_padlock_locked : R.drawable.ic_padlock_open);
            lock.setColorFilter(ThemesRepo.getColor(locked
                    ? android.R.attr.colorAccent : android.R.attr.textColorSecondary));
        });
        actions.addView(lock, new LinearLayout.LayoutParams(ViewUtils.dp(48), ViewUtils.dp(52)));
        actions.addView(primaryButton(R.string.ScaleReset, v -> reset()), weighted(1f));
        actions.addView(primaryButton(R.string.ScaleDone, v -> bed.dismissScale()), weighted(1f));
        content.addView(actions, actionsParams);
    }

    /** Measures the part (and its current scale) inside a GL event so it cannot race the renderer. */
    private void readGeometry(final Model model) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final double[] sizes = new double[]{10, 10, 10};
        final double[] factors = new double[]{1, 1, 1};
        try {
            if (bed.getGlView() != null) {
                bed.getGlView().queueEvent(() -> {
                    try {
                        model.resetBoundingBox();
                        Vec3d min = new Vec3d();
                        Vec3d max = new Vec3d();
                        model.getBoundingBoxExact(objectIndex, min, max);
                        model.getScale(objectIndex, tempVec);
                        sizes[0] = max.x - min.x;
                        sizes[1] = max.y - min.y;
                        sizes[2] = max.z - min.z;
                        factors[0] = tempVec.x;
                        factors[1] = tempVec.y;
                        factors[2] = tempVec.z;
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
        }
        baseX = Math.max(0.1, sizes[0] / safeFactor(factors[0]));
        baseY = Math.max(0.1, sizes[1] / safeFactor(factors[1]));
        baseZ = Math.max(0.1, sizes[2] / safeFactor(factors[2]));
    }

    private TextView addAxis(int colorAttr, String label) {
        TextView title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        title.setTextColor(ThemesRepo.getColor(colorAttr));
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)));
        return title;
    }

    /**
     * Plain SeekBar in percent of the size this screen started with. Its position is deterministic —
     * no fling, no rebound — so the number on screen is always the size of the part. The old
     * PositionScrollView control bounced and its position ended up being taken as a measurement.
     */
    private android.widget.SeekBar addTrack(int colorAttr, int axis) {
        android.widget.SeekBar bar = new android.widget.SeekBar(ctx);
        bar.setMax(MAX_PERCENT - 1);
        bar.setProgress(percentOf(axis) - 1);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingBars) return;
                double target = (progress + 1) / 100.0;
                updateTitles(targetFor(axis, target));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                if (updatingBars) return;
                commit(axis, (seekBar.getProgress() + 1) / 100.0);
            }
        });
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(44)));
        return bar;
    }

    private int percentOf(int axis) {
        return (int) Math.max(1, Math.min(MAX_PERCENT, Math.round(applied(axis) * 100)));
    }

    private double[] targetFor(int axis, double target) {
        double[] values = new double[]{appliedX, appliedY, appliedZ};
        if (locked) {
            values[0] = target;
            values[1] = target;
            values[2] = target;
        } else {
            values[axis] = target;
        }
        return values;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewUtils.dp(52), weight);
        params.rightMargin = ViewUtils.dp(6);
        return params;
    }

    private View primaryButton(int textRes, View.OnClickListener listener) {
        TextView button = new TextView(ctx);
        button.setText(textRes);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        button.setBackground(ViewUtils.createRipple(
                ThemesRepo.getColor(android.R.attr.colorControlHighlight),
                ThemesRepo.getColor(android.R.attr.colorControlHighlight), ViewUtils.dp(8)));
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)));
        return button;
    }


    private static double safeFactor(double value) {
        double factor = Math.abs(value);
        // A broken read-back must not turn into a huge "100 %" size.
        if (!Double.isFinite(factor) || factor < 0.01 || factor > 100) return 1;
        return factor;
    }

    private double base(int axis) {
        return axis == 0 ? baseX : axis == 1 ? baseY : baseZ;
    }

    private double factor(int axis) {
        return base(axis) * applied(axis);
    }

    private void updateTitles() {
        updateTitles(new double[]{appliedX, appliedY, appliedZ});
    }

    private void updateTitles(double[] values) {
        if (xTitle == null) return;
        xTitle.setText(describe(R.string.ScaleAxisX, values[0], baseX));
        yTitle.setText(describe(R.string.ScaleAxisY, values[1], baseY));
        zTitle.setText(describe(R.string.ScaleAxisZ, values[2], baseZ));
    }

    private String describe(int labelRes, double factor, double base) {
        return String.format(Locale.getDefault(), "%s  %.0f %%  ·  %.1f mm",
                ctx.getString(labelRes), factor * 100, base * factor);
    }

    private double applied(int axis) {
        return axis == 0 ? appliedX : axis == 1 ? appliedY : appliedZ;
    }

    /** Applies the absolute target of that bar (and the other two when the padlock is closed). */
    private void commit(int axis, double target) {
        if (updatingBars) return;
        Model model = model();
        if (model == null || objectIndex < 0) return;

        double[] targets = targetFor(axis, target);
        double rx = targets[0] / Math.max(0.001, appliedX);
        double ry = targets[1] / Math.max(0.001, appliedY);
        double rz = targets[2] / Math.max(0.001, appliedZ);
        if (Math.abs(rx - 1) < 1e-4 && Math.abs(ry - 1) < 1e-4 && Math.abs(rz - 1) < 1e-4) return;

        updatingBars = true;
        try {
            applyFactors(model, rx, ry, rz);
            appliedX = targets[0];
            appliedY = targets[1];
            appliedZ = targets[2];
            syncBars();
            updateTitles(targets);
        } catch (Throwable ignored) {
        } finally {
            updatingBars = false;
        }
    }

    /**
     * Applies the transform on the GL thread, exactly like TransformMenu: mutating the model from the
     * UI thread raced with the renderer, which is what made the first commit land and the second land
     * on a stale state (or move the part somewhere else).
     *
     * <p>Rotation and mirror are compensated, and the part is dropped onto the bed by hand on Z
     * instead of using the native "ensure on bed" (which decides with its own cached box and moved
     * parts sideways when the cache was stale).
     */
    private void applyFactors(final Model model, final double dx, final double dy, final double dz) {
        if (bed.getGlView() == null) return;
        bed.getGlView().queueEvent(() -> {
            try {
                model.resetBoundingBox();
                model.getRotation(objectIndex, tempVec);
                ru.ytkab0bp.slicebeam.utils.DoubleMatrix.setIdentityM(tempMatrix, 0);
                ru.ytkab0bp.slicebeam.utils.DoubleMatrix.rotateM(tempMatrix, 0, Math.toDegrees(tempVec.x), 1, 0, 0);
                ru.ytkab0bp.slicebeam.utils.DoubleMatrix.rotateM(tempMatrix, 0, Math.toDegrees(tempVec.y), 0, 1, 0);
                ru.ytkab0bp.slicebeam.utils.DoubleMatrix.rotateM(tempMatrix, 0, Math.toDegrees(tempVec.z), 0, 0, 1);
                tempVecArr[0] = dx;
                tempVecArr[1] = dy;
                tempVecArr[2] = dz;
                tempVecArr[3] = 1;
                ru.ytkab0bp.slicebeam.utils.DoubleMatrix.multiplyMV(tempVecArr, 0, tempMatrix, 0, tempVecArr, 0);
                double rx = Math.abs(tempVecArr[0] / tempVecArr[3]);
                double ry = Math.abs(tempVecArr[1] / tempVecArr[3]);
                double rz = Math.abs(tempVecArr[2] / tempVecArr[3]);

                model.getMirror(objectIndex, tempVec);
                model.scale(objectIndex, rx * tempVec.x, ry * tempVec.y, rz * tempVec.z);
                model.resetBoundingBox();
                dropOnBed(model);

                bed.getGlView().getRenderer().invalidateSelectionObject();
                bed.getGlView().getRenderer().setSelectionScale(1, 1, 1);
                bed.getGlView().getRenderer().invalidateGlModel(objectIndex);
                bed.getGlView().requestRender();
            } catch (Throwable ignored) {
            }
        });
        bed.getGlView().requestRender();
    }

    /** Puts the part back on the bed surface without moving it sideways. */
    private void dropOnBed(Model model) {
        try {
            Vec3d min = new Vec3d();
            Vec3d max = new Vec3d();
            model.getBoundingBoxExact(objectIndex, min, max);
            if (min.z < -0.01 || min.z > 0.01) {
                model.translate(objectIndex, 0, 0, -min.z);
                model.resetBoundingBox();
            }
        } catch (Throwable ignored) {
        }
    }

    private void syncBars() {
        updatingBars = true;
        try {
            if (xTrack != null) xTrack.setProgress(percentOf(0) - 1);
            if (yTrack != null) yTrack.setProgress(percentOf(1) - 1);
            if (zTrack != null) zTrack.setProgress(percentOf(2) - 1);
        } finally {
            updatingBars = false;
        }
    }

    private void resetTrackPositions() {
        appliedX = 1;
        appliedY = 1;
        appliedZ = 1;
        syncBars();
    }

    private void reset() {
        syncing = true;
        try {
            double dx = 1 / Math.max(0.001, appliedX);
            double dy = 1 / Math.max(0.001, appliedY);
            double dz = 1 / Math.max(0.001, appliedZ);
            Model model = model();
            if (model != null && objectIndex >= 0) {
                applyFactors(model, dx, dy, dz);
            }
            appliedX = 1;
            appliedY = 1;
            appliedZ = 1;
            resetTrackPositions();
            updateTitles();
        } catch (Throwable ignored) {
        } finally {
            syncing = false;
        }
    }

    @Override
    public void onApplyTheme() {
        if (panel != null) panel.setBackground(panelBackground());
    }
}
