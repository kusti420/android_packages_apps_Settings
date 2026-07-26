/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settings.applications.appinfo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * IchthysOS: the App-info version footer, extended so its summary can show MULTIPLE lines. The
 * default footer summary is single-line, which clipped the second line (the package name) that
 * {@link AppVersionPreferenceController} appends. Forcing the summary TextView to multi-line here
 * (in onBindViewHolder) lets "version X" + package name render as two footer lines.
 */
public class AppVersionFooterPreference extends Preference {

    public AppVersionFooterPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AppVersionFooterPreference(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View summaryView = holder.findViewById(android.R.id.summary);
        if (summaryView instanceof TextView) {
            final TextView summary = (TextView) summaryView;
            summary.setSingleLine(false);
            summary.setMaxLines(4);
        }
    }
}
