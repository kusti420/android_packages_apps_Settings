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

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * IchthysOS "Shoal" Settings icon badge: an outlined squircle with a faint fill, the category glyph
 * centred and tinted, and the three-dot lattice notch at its top-right corner (the status bar's
 * zig-zag motif).
 *
 * <p>Colour style: fill / outline / glyph / dots all derive from the category colour. Mono style:
 * neutral fill (#f2f4f1 at ~8 %), #3a4246 outline, #f2f4f1 glyph, #8b938f dots.
 *
 * <p>Geometry (in px, from dp by the caller): the squircle is {@code size} square with corner
 * {@code radius}; the drawable's intrinsic size adds {@code notchOverhang} on the top and end so
 * the notch can sit over the corner without being clipped.
 */
public final class ShoalBadgeDrawable extends Drawable {

    /** Adaptive-icon foregrounds are 108 units with the art inside the middle ~48; scale up. */
    private static final float ADAPTIVE_SCALE = 108f / 48f;

    private final Drawable mGlyph;
    private final boolean mAdaptiveGlyph;
    private final int mColor;
    private final float mSize;
    private final float mRadius;
    private final float mStroke;
    private final float mGlyphSize;
    private final float mDot;
    private final float mOverhang;
    private final int mFillAlpha;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mClip = new Path();

    /**
     * @param glyph the preference icon; its glyph is extracted (see {@link #extractGlyph})
     * @param color identity colour of the badge (category colour, or the glyph colour in mono)
     */
    ShoalBadgeDrawable(@Nullable Drawable glyph, int color, int fillColor, int lineColor,
            int glyphColor, int dotColor, float sizePx, float radiusPx, float strokePx,
            float glyphPx, float dotPx, float overhangPx) {
        final Drawable g = extractGlyph(glyph);
        mAdaptiveGlyph = glyph instanceof AdaptiveIconDrawable
                || (glyph instanceof ShoalBadgeDrawable
                        && ((ShoalBadgeDrawable) glyph).mAdaptiveGlyph);
        mGlyph = g == null ? null : g.mutate();
        mColor = color;
        mSize = sizePx;
        mRadius = radiusPx;
        mStroke = strokePx;
        mGlyphSize = glyphPx;
        mDot = dotPx;
        mOverhang = overhangPx;
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(fillColor);
        mFillAlpha = Color.alpha(fillColor);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(strokePx);
        mLine.setColor(lineColor);
        mDots.setStyle(Paint.Style.FILL);
        mDots.setColor(dotColor);
        if (mGlyph != null) {
            // A colour filter wins over any tint the glyph (or a TintDrawable) carries.
            mGlyph.setTintList(null);
            mGlyph.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
        }
    }

    /**
     * The bare glyph of a Settings icon. Homepage icons are layer-lists of a filled background
     * shape (AdaptiveIconShapeDrawable) plus the inset glyph; tinting the whole list paints one
     * solid square. Take the top-most layer instead (recursively), the foreground of an adaptive
     * icon, and the glyph of an existing badge.
     */
    @Nullable
    static Drawable extractGlyph(@Nullable Drawable d) {
        for (int depth = 0; d != null && depth < 4; depth++) {
            if (d instanceof ShoalBadgeDrawable) {
                return ((ShoalBadgeDrawable) d).mGlyph;
            } else if (d instanceof AdaptiveIconDrawable) {
                final Drawable fg = ((AdaptiveIconDrawable) d).getForeground();
                return fg != null ? fg : d;
            } else if (d instanceof LayerDrawable) {
                final LayerDrawable ld = (LayerDrawable) d;
                final int n = ld.getNumberOfLayers();
                if (n == 0) return null;
                if (n == 1) {
                    d = ld.getDrawable(0);
                    continue;
                }
                // Top-most layer is the glyph; the lower ones are background plates.
                return ld.getDrawable(n - 1);
            } else {
                return d;
            }
        }
        return d;
    }


    int getColor() {
        return mColor;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final float left = b.left;
        final float top = b.top + mOverhang;
        final float half = mStroke / 2f;
        mRect.set(left + half, top + half, left + mSize - half, top + mSize - half);
        canvas.drawRoundRect(mRect, mRadius, mRadius, mFill);
        if (mStroke > 0f) canvas.drawRoundRect(mRect, mRadius, mRadius, mLine);

        if (mGlyph != null) {
            final float gs = mAdaptiveGlyph ? mGlyphSize * ADAPTIVE_SCALE : mGlyphSize;
            final int gl = Math.round(left + (mSize - gs) / 2f);
            final int gt = Math.round(top + (mSize - gs) / 2f);
            mGlyph.setBounds(gl, gt, gl + Math.round(gs), gt + Math.round(gs));
            final int save = canvas.save();
            mClip.reset();
            mClip.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
            canvas.clipPath(mClip);
            mGlyph.draw(canvas);
            canvas.restoreToCount(save);
        }

        if (mDot <= 0f) return;
        // Lattice notch: two dots on the top row, one nested half a step in below.
        final float r = mDot / 2f;
        final float gap = mDot * 0.25f;
        final float x0 = left + mSize + mOverhang - (2 * mDot + gap);
        final float y0 = b.top;
        canvas.drawCircle(x0 + r, y0 + r, r, mDots);
        canvas.drawCircle(x0 + mDot + gap + r, y0 + r, r, mDots);
        canvas.drawCircle(x0 + mDot / 2f + gap / 2f + r, y0 + mDot + gap + r, r, mDots);
    }

    /**
     * The plain icon this badge wraps: so a badge is never wrapped twice, and for re-styling
     * when the icon options change.
     */
    @Nullable
    public Drawable getGlyph() {
        return mGlyph;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(mSize + mOverhang);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(mSize + mOverhang);
    }

    @Override
    public void setAlpha(int alpha) {
        mFill.setAlpha(Math.round(mFillAlpha * alpha / 255f));
        mLine.setAlpha(alpha);
        mDots.setAlpha(alpha);
        if (mGlyph != null) mGlyph.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The badge carries its own colours; ignore tints applied by the host view.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
