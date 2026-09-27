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

import com.google.android.material.chip.Chip;
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
            card.setColor(Color.TRANSPARENT);
            card.setStroke(Math.round(dp(1)), accent);
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
        icon.setImageTintList(ColorStateList.valueOf(
                c.getColor(android.R.color.system_neutral1_900)));

        final TextView title = (TextView) holder.findViewById(android.R.id.title);
        final TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        title.setText(on == all.size() ? c.getString(R.string.ichthys_protected_title)
                : c.getString(R.string.ichthys_protected_partial, on, all.size()));
        summary.setText(on == all.size() ? c.getString(R.string.ichthys_protected_summary)
                : c.getString(R.string.ichthys_protected_summary_partial));
        title.setTypeface(ShoalStyle.display(c, 700));
        summary.setTypeface(ShoalStyle.text(c, 400));

        final ChipGroup chips = (ChipGroup) holder.findViewById(R.id.ichthys_protected_chips);
        chips.removeAllViews();
        chips.setChipSpacing(Math.round(dp(6)));
        for (ShoalStyle.Protection p : all) {
            final Chip chip = new Chip(c);
            chip.setText(p.label);
            chip.setClickable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipMinHeight(dp(26));
            chip.setTextSize(12);
            chip.setTypeface(ShoalStyle.text(c, 500));
            chip.setChipBackgroundColor(ColorStateList.valueOf(Color.TRANSPARENT));
            chip.setChipStrokeWidth(dp(1));
            final int tone = p.on ? accent : c.getColor(android.R.color.system_neutral2_500);
            chip.setChipStrokeColor(ColorStateList.valueOf(tone));
            chip.setTextColor(p.on ? c.getColor(android.R.color.system_neutral1_50) : tone);
            chips.addView(chip);
        }
    }
}
