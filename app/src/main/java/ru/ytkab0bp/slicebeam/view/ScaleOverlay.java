package ru.ytkab0bp.slicebeam.view;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    private PositionScrollView xTrack, yTrack, zTrack;
    private TextView xTitle, yTitle, zTitle;

    private int objectIndex = -1;
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

        // Base size: what the part measures now, which is what "100 %" means on this screen.
        try {
            Vec3d min = new Vec3d();
            Vec3d max = new Vec3d();
            model.getBoundingBoxExact(objectIndex, min, max);
            model.getScale(objectIndex, tempVec);
            baseX = Math.max(0.1, (max.x - min.x) / Math.max(0.001, Math.abs(tempVec.x)));
            baseY = Math.max(0.1, (max.y - min.y) / Math.max(0.001, Math.abs(tempVec.y)));
            baseZ = Math.max(0.1, (max.z - min.z) / Math.max(0.001, Math.abs(tempVec.z)));
        } catch (Throwable ignored) {
        }
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
        actions.addView(primaryButton(R.string.ScaleReset, v -> reset()), weighted(1f));
        actions.addView(primaryButton(R.string.ScaleDone, v -> bed.dismissScale()), weighted(1f));
        content.addView(actions, actionsParams);
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

    private PositionScrollView addTrack(int colorAttr, int axis) {
        PositionScrollView track = new PositionScrollView(ctx);
        track.setActiveColor(colorAttr);
        track.setMinMax(1, MAX_PERCENT);
        track.setCurrentPosition(100, false);
        track.setProgressListener(integer -> preview(axis, integer / 100.0));
        track.setListener(integer -> commit(axis, integer / 100.0));
        content.addView(track, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(78)));
        return track;
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

    private double applied(int axis) {
        return axis == 0 ? appliedX : axis == 1 ? appliedY : appliedZ;
    }

    private double base(int axis) {
        return axis == 0 ? baseX : axis == 1 ? baseY : baseZ;
    }

    private double factor(int axis) {
        return base(axis) * applied(axis);
    }

    private void updateTitles() {
        if (xTitle == null) return;
        xTitle.setText(describe(0, R.string.ScaleAxisX));
        yTitle.setText(describe(1, R.string.ScaleAxisY));
        zTitle.setText(describe(2, R.string.ScaleAxisZ));
    }

    private String describe(int axis, int labelRes) {
        return String.format(Locale.getDefault(), "%s  %.0f %%  ·  %.1f mm",
                ctx.getString(labelRes), applied(axis) * 100, factor(axis));
    }

    /** Live feedback only: the real transform happens on release, like the tools menu does. */
    private void preview(int axis, double target) {
        if (syncing) return;
        try {
            double delta = target / Math.max(0.001, applied(axis));
            float dx = axis == 0 ? (float) delta : 1f;
            float dy = axis == 1 ? (float) delta : 1f;
            float dz = axis == 2 ? (float) delta : 1f;
            bed.getGlView().getRenderer().setSelectionScale(dx, dy, dz);
            bed.getGlView().requestRender();
        } catch (Throwable ignored) {
        }
    }

    private void commit(int axis, double target) {
        if (syncing) return;
        Model model = model();
        if (model == null || objectIndex < 0) return;
        double delta = target / Math.max(0.001, applied(axis));
        if (Math.abs(delta - 1) < 1e-4) return;

        final double[] factors = new double[]{1, 1, 1};
        factors[axis] = delta;
        double dx = factors[0], dy = factors[1], dz = factors[2];

        try {
            // Rotation and mirror must be accounted for, exactly like TransformMenu.scale().
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
            dx = Math.abs(tempVecArr[0] / tempVecArr[3]);
            dy = Math.abs(tempVecArr[1] / tempVecArr[3]);
            dz = Math.abs(tempVecArr[2] / tempVecArr[3]);

            model.getMirror(objectIndex, tempVec);
            dx *= tempVec.x;
            dy *= tempVec.y;
            dz *= tempVec.z;

            model.scale(objectIndex, dx, dy, dz);
            model.ensureOnBed(objectIndex);
            model.resetBoundingBox();
        } catch (Throwable ignored) {
        }

        if (axis == 0) appliedX = target;
        if (axis == 1) appliedY = target;
        if (axis == 2) appliedZ = target;

        bed.invalidateModel();
        updateTitles();
    }

    private void reset() {
        syncing = true;
        try {
            double dx = 1 / Math.max(0.001, appliedX);
            double dy = 1 / Math.max(0.001, appliedY);
            double dz = 1 / Math.max(0.001, appliedZ);
            Model model = model();
            if (model != null && objectIndex >= 0) {
                model.scale(objectIndex, dx, dy, dz);
                model.ensureOnBed(objectIndex);
                model.resetBoundingBox();
            }
            appliedX = 1;
            appliedY = 1;
            appliedZ = 1;
            if (xTrack != null) xTrack.setCurrentPosition(100, false);
            if (yTrack != null) yTrack.setCurrentPosition(100, false);
            if (zTrack != null) zTrack.setCurrentPosition(100, false);
            bed.invalidateModel();
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
