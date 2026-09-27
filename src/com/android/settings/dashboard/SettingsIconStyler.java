/*
 * Copyright (C) 2026 IchthysOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.dashboard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;

import com.android.settings.R;
import com.android.settingslib.Utils;
import com.android.settingslib.widget.AdaptiveIconShapeDrawable;

/**
 * IchthysOS: renders the Settings homepage tile icons in the style chosen by the crDroid
 * "Settings style" screen ({@code settings_icons.xml}).
 *
 * <p>Deliberately self-contained in the Settings app: it builds its own {@link LayerDrawable}
 * rather than extending {@code SettingsLib}'s {@code AdaptiveIcon}, so none of this needs a
 * framework rebuild. The geometry mirrors {@code AdaptiveIcon} exactly (foreground sized to
 * {@code dashboard_tile_image_size}, inset by {@code dashboard_tile_foreground_image_inset}) so
 * switching styles never changes the row height on the homepage.
 *
 * <p>The settings are read per icon build, which happens when the homepage binds its tiles, so a
 * change applies the next time Settings is opened rather than live under the user's finger.
 */
public final class SettingsIconStyler {

    /** {@link Settings.System} keys written by crDroidSettings' "Settings style" screen. */
    static final String KEY_STYLE = "settings_icon_style";
    static final String KEY_RANDOM_COLORS = "settings_icon_random_colors";
    static final String KEY_CORNER_STYLE = "settings_icon_corner_style";

    /** Stock Material-expressive look: pale scheme background, saturated scheme icon. */
    static final int STYLE_EXPRESSIVE = 0;
    static final int STYLE_COLORED_BG = 1;
    static final int STYLE_GRADIENT_BG = 2;
    static final int STYLE_ACCENT_OUTLINE = 3;
    static final int STYLE_COLORED_OUTLINE = 4;
    static final int STYLE_COLORED_ICON = 5;
    static final int STYLE_ACCENT_ICON = 6;

    /** Follows the device icon mask ({@code config_icon_mask}) — circle or squircle. */
    static final int CORNER_MASK = 0;
    static final int CORNER_ROUNDED_SQUARE = 1;

    /**
     * Foreground inset, matching {@code SettingsLibAdaptiveIcon}'s own
     * {@code dashboard_tile_foreground_image_inset} (6dp). Hardcoded rather than read from that
     * library's {@code R}, because Settings' own resource of the same name is a different value
     * (8dp) and picking it up here would make styled icons a different size from the stock ones.
     */
    private static final float FOREGROUND_INSET_DP = 6f;

    /** Corner radius of the rounded-square shape, as a fraction of the icon's full size. */
    private static final float ROUNDED_SQUARE_RADIUS_RATIO = 0.28f;

    /** Outline thickness for the two outline styles, as a fraction of the icon's full size. */
    private static final float OUTLINE_WIDTH_RATIO = 0.05f;

    /** Alpha applied to a random colour when it stands in for a pale scheme background. */
    private static final int PALE_ALPHA = 0x3D;

    private SettingsIconStyler() {}

    /**
     * Builds the styled homepage icon.
     *
     * @param icon the tile's icon, already normalized to {@code dashboard_tile_image_size}
     * @param fgColorRes the scheme's saturated colour
     * @param bgColorRes the scheme's pale colour
     * @param stableKey a per-tile identifier, used to keep a tile's random colour stable across
     *     rebinds; may be null, in which case random colours fall back to the scheme colour
     * @return the styled drawable, or {@code null} when the user is on the stock look and the
     *     caller should keep its own {@code AdaptiveIcon} path
     */
    @Nullable
    public static Drawable style(Context context, Drawable icon, @ColorRes int fgColorRes,
            @ColorRes int bgColorRes, @Nullable String stableKey) {
        final int style = systemInt(context, KEY_STYLE, STYLE_EXPRESSIVE);
        final boolean randomColors = systemInt(context, KEY_RANDOM_COLORS, 0) != 0;
        final int corner = systemInt(context, KEY_CORNER_STYLE, CORNER_MASK);

        // Nothing to do: stock style, stock shape, stock colours. Let the caller use AdaptiveIcon
        // so the default look stays bit-for-bit what it was.
        if (style == STYLE_EXPRESSIVE && corner == CORNER_MASK && !randomColors) {
            return null;
        }

        @ColorInt int accent = Utils.getColorAttrDefaultColor(context,
                android.R.attr.colorAccent);
        @ColorInt int saturated;
        @ColorInt int pale;
        if (randomColors && !TextUtils.isEmpty(stableKey)) {
            saturated = randomColorFor(stableKey);
            pale = withAlpha(saturated, PALE_ALPHA);
        } else {
            saturated = context.getColor(fgColorRes);
            pale = context.getColor(bgColorRes);
        }

        final int size = context.getResources()
                .getDimensionPixelSize(R.dimen.dashboard_tile_image_size);
        final int inset = Math.round(
                FOREGROUND_INSET_DP * context.getResources().getDisplayMetrics().density);
        final int fullSize = size + inset * 2;

        @ColorInt final int iconTint;
        final Drawable background;
        switch (style) {
            case STYLE_COLORED_BG:
                iconTint = Color.WHITE;
                background = solid(context, corner, fullSize, saturated);
                break;
            case STYLE_GRADIENT_BG:
                iconTint = Color.WHITE;
                background = gradient(context, corner, fullSize, saturated);
                break;
            case STYLE_ACCENT_OUTLINE:
                iconTint = accent;
                background = outline(context, corner, fullSize, accent);
                break;
            case STYLE_COLORED_OUTLINE:
                iconTint = saturated;
                background = outline(context, corner, fullSize, saturated);
                break;
            case STYLE_COLORED_ICON:
                iconTint = saturated;
                background = null;
                break;
            case STYLE_ACCENT_ICON:
                iconTint = accent;
                background = null;
                break;
            case STYLE_EXPRESSIVE:
            default:
                // Reached when only the corner style or random colours were changed.
                iconTint = saturated;
                background = solid(context, corner, fullSize, pale);
                break;
        }

        icon.setTint(iconTint);

        // Keep the footprint identical whether or not there is a background, so switching to an
        // icon-only style does not shift the homepage rows.
        final Drawable backdrop = background != null ? background : transparent(fullSize);
        final LayerDrawable layers = new LayerDrawable(new Drawable[] {backdrop, icon});
        layers.setLayerSize(0, fullSize, fullSize);
        layers.setLayerSize(1, size, size);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /**
     * The tile's identity for random colouring. Uses the resolved tile key, which is stable across
     * rebinds and reboots, so a given entry keeps its colour instead of flickering on every scroll.
     */
    @Nullable
    public static String stableKeyFor(Context context, com.android.settingslib.drawer.Tile tile) {
        String key = null;
        try {
            key = tile.getKey(context);
        } catch (RuntimeException e) {
            // Falls through to the package name below.
        }
        // getKey() is null for tiles that declare no key hint.
        return TextUtils.isEmpty(key) ? tile.getPackageName() : key;
    }

    private static Drawable solid(Context context, int corner, int fullSize, @ColorInt int color) {
        if (corner == CORNER_ROUNDED_SQUARE) {
            final GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(fullSize * ROUNDED_SQUARE_RADIUS_RATIO);
            d.setColor(color);
            return d;
        }
        final ShapeDrawable d = maskShape(context);
        d.getPaint().setColor(color);
        return d;
    }

    private static Drawable gradient(Context context, int corner, int fullSize,
            @ColorInt int color) {
        final int start = color;
        final int end = shift(color, 0.65f);
        if (corner == CORNER_ROUNDED_SQUARE) {
            final GradientDrawable d = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[] {start, end});
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(fullSize * ROUNDED_SQUARE_RADIUS_RATIO);
            return d;
        }
        final ShapeDrawable d = maskShape(context);
        // PathShape draws with the paint as-is, so the shader has to be sized here rather than
        // left to the drawable's bounds.
        d.getPaint().setShader(new LinearGradient(
                0, 0, fullSize, fullSize, start, end, Shader.TileMode.CLAMP));
        return d;
    }

    private static Drawable outline(Context context, int corner, int fullSize,
            @ColorInt int color) {
        final int width = Math.max(1, Math.round(fullSize * OUTLINE_WIDTH_RATIO));
        if (corner == CORNER_ROUNDED_SQUARE) {
            final GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(fullSize * ROUNDED_SQUARE_RADIUS_RATIO);
            d.setColor(Color.TRANSPARENT);
            d.setStroke(width, color);
            return d;
        }
        final ShapeDrawable d = maskShape(context);
        final Paint paint = d.getPaint();
        paint.setStyle(Paint.Style.STROKE);
        // A centred stroke would be clipped in half at the bounds, so halve it back.
        paint.setStrokeWidth(width);
        paint.setColor(color);
        return d;
    }

    private static ShapeDrawable maskShape(Context context) {
        return new AdaptiveIconShapeDrawable(context.getResources());
    }

    private static Drawable transparent(int fullSize) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.TRANSPARENT);
        d.setSize(fullSize, fullSize);
        return d;
    }

    /**
     * A saturated colour derived from {@code key}. Deterministic, so a tile keeps its colour; the
     * hue is spread over the full wheel while saturation and value stay fixed, which keeps every
     * generated colour legible against both the light and dark homepage.
     */
    @ColorInt
    private static int randomColorFor(String key) {
        final int hash = key.hashCode();
        final float hue = Math.floorMod(hash, 360);
        return Color.HSVToColor(new float[] {hue, 0.55f, 0.80f});
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Scales a colour's brightness, used for the second stop of the gradient style. */
    @ColorInt
    private static int shift(@ColorInt int color, float valueFactor) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * valueFactor);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int systemInt(Context context, String key, int def) {
        return Settings.System.getInt(context.getContentResolver(), key, def);
    }
}
