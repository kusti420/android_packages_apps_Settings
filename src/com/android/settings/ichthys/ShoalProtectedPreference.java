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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;
import com.android.settingslib.widget.ShoalSurfaces;

import com.google.android.material.chip.ChipGroup;

import java.util.List;

/**
 * IchthysOS Shoal: the homepage "Protected" card. Summarises the IchthysOS protections and opens
 * the IchthysOS page. Hairline surface: accent outline on the ground; tonal: a filled surface.
 */
public class ShoalProtectedPreference extends Preference {

    public ShoalProtectedPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.ichthys_protected_card);
        setSelectable(true);
    }

    public ShoalProtectedPreference(Context context) {
        this(context, null);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getContext().getResources().getDisplayMetrics());
    }

    private static int blend(int base, int over, float amount) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int a = (base >> shift) & 0xff;
            final int b = (over >> shift) & 0xff;
            out |= Math.min(255, Math.max(0, Math.round(a + (b - a) * amount))) << shift;
        }
        return out;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        // Card spacing replaces the group background the list would otherwise draw.
        holder.itemView.setBackground(null);
        super.onBindViewHolder(holder);
        final Context c = getContext();
        final int accent = ShoalStyle.accent(c);
        final List<ShoalStyle.Protection> all = ShoalStyle.protections(c);
        int on = 0;
        for (ShoalStyle.Protection p : all) {
            if (p.on) on++;
        }

        final boolean hairline = !"tonal".equals(Settings.Secure.getString(
                c.getContentResolver(), "ichthys_theme_surface"));
        final GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(24));
        if (hairline) {
            // Shoal: the ink card (#0c1013) with a 1dp accent outline.
            card.setColor(ShoalSurfaces.cardColor(c));
            card.setStroke(Math.max(1, Math.round(dp(1))), accent);
        } else {
            card.setColor((accent & 0x00ffffff) | 0x26000000);
        }
        final View frame = holder.findViewById(R.id.ichthys_protected_frame);
        frame.setBackground(card);
        frame.setClipToOutline(true);

        final ImageView icon = (ImageView) holder.findViewById(R.id.ichthys_protected_icon);
        final GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(accent);
        icon.setBackground(disc);
        // Dark ink shield-check on the accent disc (#07090b).
        icon.setImageTintList(ColorStateList.valueOf(0xFF07090B));

        final TextView title = (TextView) holder.findViewById(android.R.id.title);
        final TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        // The preference has no android:title/summary of its own, so Preference's binding just
        // hid both views (GONE). They are computed here instead; show them again.
        title.setVisibility(View.VISIBLE);
        summary.setVisibility(View.VISIBLE);
        title.setSingleLine(false);
        summary.setMaxLines(3);
        title.setText(on == all.size() ? c.getString(R.string.ichthys_protected_title)
                : c.getString(R.string.ichthys_protected_partial, on, all.size()));
        summary.setText(on == all.size() ? c.getString(R.string.ichthys_protected_summary)
                : c.getString(R.string.ichthys_protected_summary_partial));
        // Title 15sp semibold, summary 13sp (#c9ceca on ink), both in the body font.
        title.setTypeface(ShoalStyle.text(c, 600));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        summary.setTypeface(ShoalStyle.text(c, 400));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        if (ShoalSurfaces.isNight(c)) {
            title.setTextColor(0xFFF2F4F1);
            summary.setTextColor(0xFFC9CECA);
        }

        final ChipGroup chips = (ChipGroup) holder.findViewById(R.id.ichthys_protected_chips);
        chips.removeAllViews();
        chips.setChipSpacing(Math.round(dp(6)));
        for (ShoalStyle.Protection p : all) {
            // Plain TextView pills: Material Chip needs a MaterialComponents theme, which the
            // Settings theme is not, and throws on inflate.
            final TextView chip = new TextView(c);
            chip.setText(p.label);
            chip.setClickable(false);
            chip.setFocusable(false);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setMinHeight(Math.round(dp(26)));
            chip.setPadding(Math.round(dp(10)), 0, Math.round(dp(10)), 0);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setTypeface(ShoalStyle.text(c, 500));
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(13));
            final int off = c.getColor(android.R.color.system_neutral2_500);
            if (p.on) {
                // Shoal: filled accent-tinted chip (#1f1511 for the Koi accent on ink), no line.
                pill.setColor(blend(ShoalSurfaces.isNight(c) ? 0xFF07090B : ShoalSurfaces.cardColor(c),
                        accent, 0.10f));
                chip.setTextColor(ShoalSurfaces.isNight(c) ? 0xFFF2F4F1
                        : c.getColor(android.R.color.system_neutral1_900));
            } else {
                // An inactive protection stays visible as an outlined, muted chip.
                pill.setColor(Color.TRANSPARENT);
                pill.setStroke(Math.round(dp(1)), off);
                chip.setTextColor(off);
            }
            chip.setBackground(pill);
            chips.addView(chip);
        }
    }
}
