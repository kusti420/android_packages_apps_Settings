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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * IchthysOS "Shoal" Settings icon badge: an outlined squircle with a faint fill of the category
 * color, the original glyph tinted in that color, and the three-dot lattice notch at its top-right
 * corner (the status bar's zig-zag motif).
 *
 * <p>Geometry (in px, from dp by the caller): the squircle is {@code size} square with corner
 * {@code radius}; the drawable's intrinsic size adds {@code notchOverhang} on the top and end so
 * the notch can sit over the corner without being clipped.
 */
public final class ShoalBadgeDrawable extends Drawable {

    private final Drawable mGlyph;
    private final int mColor;
    private final float mSize;
    private final float mRadius;
    private final float mStroke;
    private final float mGlyphSize;
    private final float mDot;
    private final float mOverhang;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    ShoalBadgeDrawable(@Nullable Drawable glyph, int color, float sizePx, float radiusPx,
            float strokePx, float glyphPx, float dotPx, float overhangPx) {
        mGlyph = glyph == null ? null : glyph.mutate();
        mColor = color;
        mSize = sizePx;
        mRadius = radiusPx;
        mStroke = strokePx;
        mGlyphSize = glyphPx;
        mDot = dotPx;
        mOverhang = overhangPx;
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor((color & 0x00ffffff) | 0x1f000000); // ~12 % tint
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(strokePx);
        mLine.setColor(color);
        mDots.setStyle(Paint.Style.FILL);
        mDots.setColor(color);
        if (mGlyph != null) {
            mGlyph.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
    }

    /** The wrapped glyph, so a badge is never wrapped twice. */
    @Nullable
    Drawable getGlyph() {
        return mGlyph;
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
        canvas.drawRoundRect(mRect, mRadius, mRadius, mLine);

        if (mGlyph != null) {
            final int gl = Math.round(left + (mSize - mGlyphSize) / 2f);
            final int gt = Math.round(top + (mSize - mGlyphSize) / 2f);
            mGlyph.setBounds(gl, gt, gl + Math.round(mGlyphSize), gt + Math.round(mGlyphSize));
            mGlyph.draw(canvas);
        }

        // Lattice notch: two dots on the top row, one nested half a step in below.
        final float r = mDot / 2f;
        final float gap = mDot * 0.25f;
        final float x0 = left + mSize + mOverhang - (2 * mDot + gap);
        final float y0 = b.top;
        canvas.drawCircle(x0 + r, y0 + r, r, mDots);
        canvas.drawCircle(x0 + mDot + gap + r, y0 + r, r, mDots);
        canvas.drawCircle(x0 + mDot / 2f + gap / 2f + r, y0 + mDot + gap + r, r, mDots);
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
        mFill.setAlpha(Math.round(0x1f * alpha / 255f));
        mLine.setAlpha(alpha);
        mDots.setAlpha(alpha);
        if (mGlyph != null) mGlyph.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The badge carries its own category color; ignore tints applied by the host view.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
