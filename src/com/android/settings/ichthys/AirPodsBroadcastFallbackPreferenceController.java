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
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;

/**
 * The switch that lets AirPods battery levels come from nearby Bluetooth broadcasts while the
 * earbuds' own channel is unavailable (Bluetooth settings, under the AirPods battery switch).
 * Read by SystemUI's IchthysPodBatteryController; unset counts as off. The preference depends on
 * the main switch in the XML, which is what greys it out.
 */
public class AirPodsBroadcastFallbackPreferenceController extends TogglePreferenceController {

    /** {@code Settings.Secure}, per user. Also {@code bluetooth.airpodsBroadcastFallback}. */
    private static final String KEY_ENABLED = "ichthys_airpods_battery_broadcast_fallback";

    public AirPodsBroadcastFallbackPreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public boolean isChecked() {
        return Settings.Secure.getInt(mContext.getContentResolver(), KEY_ENABLED, 0) != 0;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        return Settings.Secure.putInt(mContext.getContentResolver(), KEY_ENABLED,
                isChecked ? 1 : 0);
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_connected_devices;
    }
}
