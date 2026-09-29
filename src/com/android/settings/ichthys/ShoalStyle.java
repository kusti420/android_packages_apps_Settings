/*
 * Copyright (C) 2026 IchthysOS
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

package com.android.settings.ichthys;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.settingslib.widget.ShoalSurfaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IchthysOS "Shoal" styling for Settings, driven by the ichthys.nix theme options:
 * <ul>
 *   <li>{@code theme.iconBadges} (Settings.Secure {@code ichthys_settings_icon_badges}, default on):
 *       category icons become outlined, color-coded {@link ShoalBadgeDrawable}s.</li>
 *   <li>{@code theme.font.display / text / mono} ({@code ichthys_theme_font_*}): named font
 *       families from /product/etc/fonts_customization.xml; defaults are the Koi preset's.</li>
 *   <li>{@code theme.surface} is applied to list groups by SettingsLib's group adapter.</li>
 * </ul>
 * Everything here reads settings at bind time, so a change shows the next time a screen binds.
 */
public final class ShoalStyle {

    private ShoalStyle() {}

    public static final String KEY_BADGES = "ichthys_settings_icon_badges";
    /** theme.iconStyle: "mono" (default) or "colour". */
    public static final String KEY_ICON_STYLE = "ichthys_settings_icon_style";
    public static final String KEY_FONT_DISPLAY = "ichthys_theme_font_display";
    public static final String KEY_FONT_TEXT = "ichthys_theme_font_text";
    public static final String KEY_FONT_MONO = "ichthys_theme_font_mono";

    private static final String DEFAULT_DISPLAY = "syne";
    private static final String DEFAULT_TEXT = "instrument-sans";
    private static final String DEFAULT_MONO = "jetbrains-mono";

    /** Category colors: distinct hues that don't follow Material's palette. */
    private static final Map<String, Integer> CATEGORY = Map.ofEntries(
            Map.entry("top_level_network", 0xFF2DE2E6),
            Map.entry("top_level_connected_devices", 0xFFD27BFF),
            Map.entry("top_level_apps", 0xFFC6FF3D),
            Map.entry("top_level_notifications", 0xFFFFB000),
            Map.entry("top_level_sound", 0xFF7AA2FF),
            Map.entry("top_level_priority_modes", 0xFFB9A7FF),
            Map.entry("top_level_communal", 0xFF5EEAD4),
            Map.entry("top_level_display", 0xFFFF5FA2),
            Map.entry("top_level_wallpaper", 0xFFFF9ECF),
            Map.entry("top_level_storage", 0xFF8AE1FF),
            Map.entry("top_level_battery", 0xFFF5E663),
            Map.entry("top_level_system", 0xFF9AA6FF),
            Map.entry("top_level_about_device", 0xFFCFD6D2),
            Map.entry("top_level_safety_center", 0xFFFF4D6D),
            Map.entry("top_level_security", 0xFFFF4D6D),
            Map.entry("top_level_privacy", 0xFFFF7A90),
            Map.entry("top_level_location", 0xFF5FD4FF),
            Map.entry("top_level_accounts", 0xFFFFC98A),
            Map.entry("top_level_accounts_and_backup", 0xFFFFC98A),
            Map.entry("top_level_supervision", 0xFFFFD166),
            Map.entry("top_level_emergency", 0xFFFF3B3B),
            Map.entry("top_level_accessibility", 0xFFFFA3F1),
            Map.entry("top_level_support", 0xFFA0F0FF));

    /** Keys that are IchthysOS's own; they take the accent. */
    private static final String[] ACCENT_KEYS = {"top_level_crdroid", "top_level_ichthys"};

    /** Fallback hues for keys we don't know (crDroid pages, injected tiles), picked by hash. */
    private static final int[] FALLBACK = {
            0xFF2DE2E6, 0xFFD27BFF, 0xFFC6FF3D, 0xFFFFB000, 0xFFFF5FA2, 0xFF7AA2FF,
            0xFFF5E663, 0xFF9AA6FF, 0xFFFF9ECF, 0xFF8AE1FF};

    /** Mono badges (the default): off-white glyph on a quiet tile, accent only for accent items. */
    public static boolean monoBadges(Context context) {
        return !"colour".equals(Settings.Secure.getString(context.getContentResolver(),
                KEY_ICON_STYLE));
    }

    public static boolean badgesEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_BADGES, 1) != 0;
    }

    /** The theme accent (from Monet / the ichthys.nix accent). */
    public static int accent(Context context) {
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        try {
            return a.getColor(0, 0xFFFF6B3D);
        } finally {
            a.recycle();
        }
    }

    /** Category color for a preference key. */
    public static int colorFor(Context context, @Nullable String key) {
        if (key == null) {
            return accent(context);
        }
        for (String k : ACCENT_KEYS) {
            if (k.equals(key)) return accent(context);
        }
        final Integer c = CATEGORY.get(key);
        if (c != null) {
            return c;
        }
        return FALLBACK[Math.floorMod(key.hashCode(), FALLBACK.length)];
    }

    private static float dp(Context context, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }

    /** Wraps {@code icon}'s glyph in a badge of {@code color}; returns an existing badge unchanged. */
    public static Drawable badge(Context context, @Nullable Drawable icon, int color) {
        if (icon instanceof ShoalBadgeDrawable) {
            return icon;
        }
        final float size = dp(context, 46);
        final float radius = dp(context, 15);
        final float stroke = dp(context, 1.5f);
        final float glyph = dp(context, 22);
        final float dot = dp(context, 4);
        final float overhang = dp(context, 3);
        if (monoBadges(context)) {
            // Mono: neutral squircle (#f2f4f1 at 0x14 on ink), #3a4246 hairline, off-white glyph,
            // muted #8b938f lattice. IchthysOS's own items keep the accent on glyph and outline.
            // In day mode the same roles flip to ink on paper.
            final boolean night = ShoalSurfaces.isNight(context);
            final boolean isAccent = color == accent(context);
            final int neutral = night ? 0xFFF2F4F1 : 0xFF12171A;
            final int ink = isAccent ? color : neutral;
            final int line = isAccent ? color : (night ? 0xFF3A4246 : 0xFFC4CAC7);
            final int dots = isAccent ? color : (night ? 0xFF8B938F : 0xFF6B7370);
            return new ShoalBadgeDrawable(icon, ink, (neutral & 0x00ffffff) | 0x14000000,
                    line, ink, dots, size, radius, stroke, glyph, dot, overhang);
        }
        // Colour: ~12 % category tint fill, 1.5dp category outline, category glyph and lattice.
        return new ShoalBadgeDrawable(icon, color, (color & 0x00ffffff) | 0x1f000000,
                color, color, color, size, radius, stroke, glyph, dot, overhang);
    }

    /**
     * Shows {@code icon} as a badge in {@code view} (sized to the badge) when badges are on;
     * otherwise leaves the view to the normal preference binding.
     */
    public static void bindIcon(ImageView view, @Nullable Drawable icon, @Nullable String key) {
        if (view == null || icon == null || !badgesEnabled(view.getContext())) {
            return;
        }
        final Context c = view.getContext();
        final Drawable badge = badge(c, icon, colorFor(c, key));
        view.setImageDrawable(badge);
        view.setImageTintList(null);
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = badge.getIntrinsicWidth();
            lp.height = badge.getIntrinsicHeight();
            view.setLayoutParams(lp);
        }
    }

    /**
     * Badges every icon under {@code group} (recursively) in {@code color}. Preferences keep their
     * badge across rebinds because the icon itself is replaced; already-badged icons are skipped.
     */
    public static void badgeAll(androidx.preference.PreferenceGroup group, int color) {
        if (group == null || !badgesEnabled(group.getContext())) {
            return;
        }
        final Context c = group.getContext();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final androidx.preference.Preference p = group.getPreference(i);
            final Drawable icon = p.getIcon();
            if (icon != null && !(icon instanceof ShoalBadgeDrawable)) {
                p.setIcon(badge(c, icon, color));
            }
            if (p instanceof androidx.preference.PreferenceGroup) {
                badgeAll((androidx.preference.PreferenceGroup) p, color);
            }
        }
    }

    /** The n-th distinct Shoal hue (for pages or tabs that have no category of their own). */
    public static int hue(int n) {
        return FALLBACK[Math.floorMod(n, FALLBACK.length)];
    }

    private static Typeface family(Context context, String key, String def, int weight) {
        String name = Settings.Secure.getString(context.getContentResolver(), key);
        if (TextUtils.isEmpty(name)) name = def;
        return Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false);
    }

    public static Typeface display(Context context, int weight) {
        return family(context, KEY_FONT_DISPLAY, DEFAULT_DISPLAY, weight);
    }

    public static Typeface text(Context context, int weight) {
        return family(context, KEY_FONT_TEXT, DEFAULT_TEXT, weight);
    }

    public static Typeface mono(Context context, int weight) {
        return family(context, KEY_FONT_MONO, DEFAULT_MONO, weight);
    }

    /**
     * Body font on a list row's title (medium) and summary (regular). With hairline surfaces the
     * Shoal sizes apply too: title 15sp, summary 12.5sp in the muted ink tone.
     */
    public static void bindRowText(@Nullable TextView title, @Nullable TextView summary) {
        if (title != null) {
            title.setTypeface(text(title.getContext(), 500));
            if (ShoalSurfaces.isHairline(title.getContext())) {
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            }
        }
        if (summary != null) {
            final Context c = summary.getContext();
            summary.setTypeface(text(c, 400));
            if (ShoalSurfaces.isHairline(c)) {
                summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                if (ShoalSurfaces.isNight(c)) {
                    final int muted = ShoalSurfaces.mutedTextColor(c);
                    summary.setTextColor(new android.content.res.ColorStateList(
                            new int[][] {
                                    new int[] {-android.R.attr.state_enabled},
                                    new int[] {}},
                            new int[] {(muted & 0x00ffffff) | 0x61000000, muted}));
                }
            }
        }
    }

    /**
     * Homepage header: the display-font title and the search card as a hairline (or tonal) pill.
     */
    public static void styleHomepageHeader(android.app.Activity activity) {
        final TextView title = activity.findViewById(com.android.settings.R.id.ichthys_homepage_title);
        if (title != null) {
            title.setTypeface(display(activity, 600));
            title.setLetterSpacing(-0.0125f);
        }
        final android.view.View bar = activity.findViewById(
                com.android.settings.R.id.search_action_bar);
        if (bar != null && bar.getParent()
                instanceof com.google.android.material.card.MaterialCardView) {
            final com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) bar.getParent();
            final boolean hairline = ShoalSurfaces.isHairline(activity);
            card.setRadius(dp(activity, 24));
            card.setCardElevation(0);
            card.setStrokeWidth(hairline ? Math.max(1, Math.round(dp(activity, 1))) : 0);
            card.setStrokeColor(hairline ? ShoalSurfaces.hairlineColor(activity)
                    : android.graphics.Color.TRANSPARENT);
            if (hairline) {
                // Search pill: the ink card (#0c1013) with the #20282d hairline.
                card.setCardBackgroundColor(ShoalSurfaces.cardColor(activity));
            }
            bar.setMinimumHeight(Math.round(dp(activity, 48)));
            bar.setBackground(null);
            final TextView hint = bar.findViewById(com.android.settings.R.id.search_bar_title);
            if (hint != null) {
                hint.setTypeface(text(activity, 400));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // "Protected" summary

    /** One protection shown on the homepage card. */
    public static final class Protection {
        public final String label;
        public final boolean on;

        Protection(String label, boolean on) {
            this.label = label;
            this.on = on;
        }
    }

    /**
     * The IchthysOS protections, read from their settings (unset counts as on, matching each
     * consumer's default) and the device's hardware-backed keystore feature.
     */
    public static List<Protection> protections(Context context) {
        final List<Protection> out = new ArrayList<>();
        final PackageManager pm = context.getPackageManager();
        out.add(new Protection(context.getString(
                com.android.settings.R.string.ichthys_protect_keystore),
                pm.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE)));
        out.add(new Protection(context.getString(
                com.android.settings.R.string.ichthys_protect_contacts),
                globalOn(context, "ichthys_contacts_lockdown")));
        out.add(new Protection(context.getString(
                com.android.settings.R.string.ichthys_protect_clipboard),
                globalOn(context, "ichthys_clipboard_lockdown")));
        out.add(new Protection(context.getString(
                com.android.settings.R.string.ichthys_protect_sensors),
                globalOn(context, "ichthys_sensor_fingerprint_protection")));
        out.add(new Protection(context.getString(
                com.android.settings.R.string.ichthys_protect_adblock), adBlockActive()));
        return out;
    }

    /**
     * The system-wide blocklist ships as /system/etc/hosts (HaGeZi, tens of thousands of entries).
     * A stock hosts file is a few lines, so size tells whether blocking is in place. Nothing can
     * turn it off at runtime; this catches a build or overlay that shipped without it.
     */
    private static boolean adBlockActive() {
        return new java.io.File("/system/etc/hosts").length() > 64 * 1024;
    }

    private static boolean globalOn(Context context, String key) {
        return Settings.Global.getInt(context.getContentResolver(), key, 1) != 0;
    }
}
