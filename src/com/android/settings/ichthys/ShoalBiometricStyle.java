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

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import com.android.settings.R;
import com.android.settingslib.widget.ShoalSurfaces;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButtonStyleUtils;
import com.google.android.setupcompat.template.IFooterActionButton;
import com.google.android.setupdesign.GlifLayout;

/**
 * IchthysOS: Shoal styling of the biometric enrolment flow (intro, find sensor, enrolling, finish;
 * the Settings and the Setup Wizard variants alike).
 *
 * The pages are SetupDesign {@link GlifLayout}s whose theme (GlifTheme) carries SetupWizard's own
 * blue accent, fonts and surfaces, so they looked like a foreign app inside Settings.
 * {@link com.android.settings.biometrics.BiometricEnrollBase} calls {@link #apply} once the
 * content view is set. Everything here is null-safe: not every page has every view, and the
 * Setup Wizard variants must keep working whatever the partner overlay inflated.
 *
 * Colours are the framework's Shoal tokens ({@code ichthys_*}, see
 * frameworks/base/core/res/res/values/ichthys_tokens.xml), not the Glif theme's
 * {@code colorAccent}: the enrol activities never get the dynamic-colour overlay unless the Setup
 * Wizard partner config asks for it, so their colorAccent is SetupWizard's fixed Google blue.
 */
public final class ShoalBiometricStyle {

    private ShoalBiometricStyle() {}

    /** Lottie layer names the stock illustrations colour in blue; they take the accent. */
    private static final String[] ACCENT_LAYERS = {".blue100", ".blue200", ".blue400", ".primary"};

    /** The Shoal accent (system primary). */
    @ColorInt
    public static int accent(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_accent);
    }

    /** Text on the accent. */
    @ColorInt
    public static int onAccent(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_on_accent);
    }

    /** Accent-tinted fill (primary container): discs and targets that carry an accent glyph. */
    @ColorInt
    public static int accentFill(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_accent_fill);
    }

    /** The ink ground every Shoal page sits on. */
    @ColorInt
    public static int background(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_ink);
    }

    /** {@code color} with its alpha replaced by {@code alpha} (0..255). */
    @ColorInt
    public static int withAlpha(@ColorInt int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    /**
     * Styles one enrol page: ink ground, display-face title, body-face description, accent header
     * icon and illustrations, Shoal card and footer buttons.
     */
    public static void apply(Activity activity, @Nullable GlifLayout layout,
            @Nullable FooterBarMixin footer) {
        final int bg = background(activity);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        if (layout == null) {
            return;
        }
        final Context c = layout.getContext();
        // The status bar strip GlifLayout paints itself, and the content behind a partner overlay.
        layout.setBackgroundPatterned(false);
        layout.setBackgroundBaseColor(ColorStateList.valueOf(bg));
        layout.setBackgroundColor(bg);

        final TextView title = layout.getHeaderTextView();
        if (title != null) {
            title.setTypeface(ShoalStyle.display(c, 600));
            title.setLetterSpacing(-0.0125f);
        }
        final TextView description = layout.getDescriptionTextView();
        if (description != null) {
            description.setTypeface(ShoalStyle.text(c, 400));
            description.setTextColor(ShoalSurfaces.mutedTextColor(c));
        }
        final ImageView icon = layout.findViewById(
                com.google.android.setupdesign.R.id.sud_layout_icon);
        if (icon != null) {
            icon.setImageTintList(ColorStateList.valueOf(accent(c)));
        }
        tintLottie(layout.findViewById(
                com.google.android.setupdesign.R.id.sud_layout_animation_icon));

        styleTree(layout.findViewById(com.google.android.setupdesign.R.id.sud_layout_content));

        final View card = layout.findViewById(R.id.ichthys_enroll_card);
        if (card != null) {
            card.setBackground(card(c));
        }

        styleFooter(footer);
    }

    /**
     * Recolours the blue layers of a stock enrolment lottie with the accent. Lottie forgets value
     * callbacks when a new composition is set, so callers that swap compositions (the enrolling
     * stage hints) call this again after each {@code setComposition}.
     */
    public static void tintLottie(@Nullable LottieAnimationView view) {
        if (view == null) {
            return;
        }
        final int accent = accent(view.getContext());
        for (String layer : ACCENT_LAYERS) {
            view.addValueCallback(new KeyPath("**", layer, "**"), LottieProperty.COLOR_FILTER,
                    frameInfo -> new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_ATOP));
        }
    }

    /** A Shoal card: 24dp radius, card fill, 1dp hairline when the hairline surface is on. */
    public static Drawable card(Context context) {
        final GradientDrawable card = new GradientDrawable();
        card.setColor(ShoalSurfaces.cardColor(context));
        card.setCornerRadius(dp(context, 24));
        if (ShoalSurfaces.isHairline(context)) {
            card.setStroke(Math.max(1, Math.round(dp(context, 1))),
                    ShoalSurfaces.hairlineColor(context));
        }
        return card;
    }

    /** Body face on every text in the content area; illustrations take the accent. */
    private static void styleTree(@Nullable View view) {
        if (view == null) {
            return;
        }
        if (view instanceof LottieAnimationView) {
            tintLottie((LottieAnimationView) view);
        } else if (view instanceof TextView && !(view instanceof Button)) {
            final TextView text = (TextView) view;
            final Context c = text.getContext();
            // Sub-heads (the intro's 20sp titles) in the medium weight, everything else regular.
            final boolean subhead = text.getTextSize() >= TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 19f, c.getResources().getDisplayMetrics());
            text.setTypeface(ShoalStyle.text(c, subhead ? 600 : 400));
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleTree(group.getChildAt(i));
            }
        }
    }

    /**
     * Footer buttons: the primary as an accent pill, a filled secondary (the intro's "No thanks")
     * as a card pill, a borderless secondary as accent text. Buttons the page adds later (find
     * sensor adds "Start" after the sensor check) are styled as they arrive.
     */
    private static void styleFooter(@Nullable FooterBarMixin footer) {
        if (footer == null) {
            return;
        }
        final LinearLayout container = footer.getButtonContainer();
        if (container == null) {
            return;
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            styleButton(footer, container.getChildAt(i));
        }
        container.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                styleButton(footer, child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });
    }

    private static void styleButton(FooterBarMixin footer, View view) {
        if (!(view instanceof Button)) {
            return;
        }
        final Button button = (Button) view;
        final Context c = button.getContext();
        final int accent = accent(c);
        final int[][] states = {{-android.R.attr.state_enabled}, {}};
        button.setTypeface(ShoalStyle.text(c, 600));
        button.setAllCaps(false);

        final boolean filled = view instanceof IFooterActionButton
                && ((IFooterActionButton) view).isPrimaryButtonStyle();
        if (!filled) {
            button.setTextColor(new ColorStateList(states,
                    new int[] {withAlpha(accent, 0x61), accent}));
            return;
        }
        // The primary action is the one accent per page; a filled secondary is a tonal pill.
        final boolean primary = view == footer.getPrimaryButtonView();
        final int fill = primary ? accent
                : c.getColor(com.android.settingslib.widget.theme.R.color
                        .settingslib_materialColorSurfaceContainerHigh);
        final int text = primary ? onAccent(c)
                : c.getColor(com.android.internal.R.color.ichthys_text);
        final Drawable background = button.getBackground();
        if (background != null) {
            // FooterBarMixin may have colour-filtered the stock blue in; the tint below replaces it.
            background.mutate().setColorFilter(null);
        }
        button.setBackgroundTintMode(PorterDuff.Mode.SRC_IN);
        button.setBackgroundTintList(new ColorStateList(states,
                new int[] {withAlpha(fill, 0x33), fill}));
        button.setTextColor(new ColorStateList(states,
                new int[] {withAlpha(text, 0x61), text}));
        try {
            final GradientDrawable shape = FooterButtonStyleUtils.getGradientDrawable(button);
            if (shape != null) {
                shape.setCornerRadius(dp(c, 24));
            }
        } catch (RuntimeException ignored) {
            // A partner-themed button with another background structure keeps its own corners.
        }
    }

    private static float dp(Context context, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
