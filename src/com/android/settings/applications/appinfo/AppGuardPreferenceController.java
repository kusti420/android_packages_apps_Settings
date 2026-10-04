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

package com.android.settings.applications.appinfo;

import android.content.Context;
import android.text.TextUtils;

import androidx.preference.Preference;

import com.android.settings.ichthys.guard.ui.GuardActivity;

/**
 * IchthysOS: the "Guard" row of App info. Opens the app's page in Guard (network, trackers, data
 * access), which links back here with "Open system app info". The same row as AppGuardPreference
 * on the SPA App info page.
 */
public class AppGuardPreferenceController extends AppInfoPreferenceControllerBase {

    private String mPackageName;

    public AppGuardPreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    public AppGuardPreferenceController setPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    @Override
    public int getAvailabilityStatus() {
        return TextUtils.isEmpty(mPackageName) ? CONDITIONALLY_UNAVAILABLE : AVAILABLE;
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (!TextUtils.equals(preference.getKey(), getPreferenceKey())
                || TextUtils.isEmpty(mPackageName)) {
            return false;
        }
        mContext.startActivity(GuardActivity.appIntent(mContext, mPackageName));
        return true;
    }
}
