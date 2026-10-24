package ru.ytkab0bp.slicebeam.utils;

/**
 * Java port of libvgcode's color range mapping ({@code src/ColorRange.cpp}), used to draw the color
 * legend of the G-code preview.
 *
 * <p>The prebuilt native library does not expose the palette or the ranges through JNI, so the exact
 * table and interpolation are replicated here. Keep them in sync with
 * {@code app/src/main/jni/libvgcode/include/ColorRange.hpp} and {@code src/ColorRange.cpp}.
 */
public final class ViewLegend {

    /** DEFAULT_RANGES_COLORS from ColorRange.hpp, in order (cold → hot). */
    public static final int[] PALETTE = {
            0xFF0B2C7A, //  11,  44, 122  bluish
            0xFF135985, //  19,  89, 133
            0xFF1C8891, //  28, 136, 145
            0xFF04D60F, //   4, 214,  15
            0xFFAAF200, // 170, 242,   0
            0xFFFCF903, // 252, 249,   3
            0xFFF5CE0A, // 245, 206,  10
            0xFFE38820, // 227, 136,  32
            0xFFD16830, // 209, 104,  48
            0xFFC2523C, // 194,  82,  60
            0xFF942616  // 148,  38,  22  reddish
    };

    /** libvgcode EViewType values, as exposed by GCodeViewer. */
    public static final int VIEW_FEATURE = 0;
    public static final int VIEW_HEIGHT = 1;
    public static final int VIEW_WIDTH = 2;
    public static final int VIEW_SPEED = 3;
    public static final int VIEW_ACTUAL_SPEED = 4;
    public static final int VIEW_FAN_SPEED = 5;
    public static final int VIEW_TEMPERATURE = 6;
    public static final int VIEW_FLOW = 7;
    public static final int VIEW_ACTUAL_FLOW = 8;
    public static final int VIEW_LAYER_TIME_LINEAR = 9;
    public static final int VIEW_LAYER_TIME_LOG = 10;

    private ViewLegend() {}

    /** True when the palette carries a continuous value scale (i.e. a gradient legend makes sense). */
    public static boolean hasValueScale(int viewType) {
        switch (viewType) {
            case VIEW_HEIGHT:
            case VIEW_WIDTH:
            case VIEW_SPEED:
            case VIEW_ACTUAL_SPEED:
            case VIEW_FAN_SPEED:
            case VIEW_TEMPERATURE:
            case VIEW_FLOW:
            case VIEW_ACTUAL_FLOW:
            case VIEW_LAYER_TIME_LINEAR:
            case VIEW_LAYER_TIME_LOG:
                return true;
            default:
                return false;
        }
    }

    /** ColorRange::EColorRangeType — only the layer time logarithmic view uses a log axis. */
    public static boolean isLogarithmic(int viewType) {
        return viewType == VIEW_LAYER_TIME_LOG;
    }

    /** Colors for a GradientDrawable, cold → hot. */
    public static int[] gradientColors() {
        return PALETTE.clone();
    }

    private static float stepSize(int viewType, float min, float max) {
        if (isLogarithmic(viewType)) {
            return min > 0 ? (float) (Math.log(max / min) / (PALETTE.length - 1.0)) : 0f;
        }
        return (max - min) / (PALETTE.length - 1.0f);
    }

    /** Values at each palette stop, as ColorRange::get_values() returns them. */
    public static float[] binValues(int viewType, float min, float max) {
        float step = stepSize(viewType, min, max);
        float[] out = new float[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) {
            if (isLogarithmic(viewType)) {
                out[i] = step > 0 && min > 0
                        ? (float) Math.exp(Math.log(min) + i * step)
                        : min;
            } else {
                out[i] = min + i * step;
            }
        }
        return out;
    }

    /** ColorRange::get_color_at() — clamped, piecewise-linear in sRGB. */
    public static int colorAt(int viewType, float value, float min, float max) {
        float v = Math.max(min, Math.min(max, value));
        float step = stepSize(viewType, min, max);
        float t = 0f;
        if (step > 0f) {
            if (isLogarithmic(viewType)) {
                if (min > 0f) t = (float) (Math.log(v / min) / step);
            } else {
                t = (v - min) / step;
            }
        }
        int maxIdx = PALETTE.length - 1;
        int low = Math.max(0, Math.min(maxIdx, (int) t));
        int high = Math.max(0, Math.min(maxIdx, low + 1));
        return lerp(PALETTE[low], PALETTE[high], t - low);
    }

    private static int lerp(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
