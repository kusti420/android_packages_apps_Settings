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

package com.android.settings.bluetooth;

import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings-side accessor for the per-Bluetooth-device event overrides enforced by the Bluetooth
 * stack.
 *
 * <p>The authoritative copy of this logic lives in {@code
 * com.android.bluetooth.ichthys.PerDeviceEventPrefs}, inside the Bluetooth APK. The two cannot
 * share a class — they are separate APKs and the Bluetooth stack is a module — so the storage
 * format is the contract between them: a single JSON object in {@link Settings.Global}, keyed by
 * device address.
 *
 * <p><b>Keep {@link #SETTINGS_KEY} and the key names below in sync with that class.</b> A mismatch
 * fails silently as "the toggle does nothing", because an unknown key simply reads as its default.
 *
 * <p>{@link Settings.Global} rather than Bluetooth's own per-device storage on purpose: that store
 * is erased when a device is unpaired ({@code BluetoothStorageManager#onBondStateChanged} removes
 * the device on {@code BOND_NONE}), and these preferences are meant to survive a forget/re-pair.
 */
public final class IchthysBluetoothEventPrefs {

    private static final String TAG = "IchthysBtPrefs";

    /** Must match {@code PerDeviceEventPrefs.SETTINGS_KEY}. */
    public static final String SETTINGS_KEY = "ichthys_bluetooth_device_events";

    public static final String KEY_VOLUME = "volume";
    public static final String KEY_ASSISTANT = "assistant";
    public static final String KEY_PLAY_PAUSE = "play_pause";
    public static final String KEY_TRACKS = "tracks";
    public static final String KEY_SWAP_TRACKS = "swap_tracks";

    private IchthysBluetoothEventPrefs() {}

    /** @return the stored flag, or {@code defaultValue} when this device has no override for it. */
    public static boolean get(
            ContentResolver resolver, String address, String key, boolean defaultValue) {
        if (resolver == null || TextUtils.isEmpty(address)) {
            return defaultValue;
        }
        try {
            final String raw = Settings.Global.getString(resolver, SETTINGS_KEY);
            if (TextUtils.isEmpty(raw)) {
                return defaultValue;
            }
            final JSONObject device = new JSONObject(raw).optJSONObject(address);
            return device == null ? defaultValue : device.optBoolean(key, defaultValue);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Ignoring unreadable " + SETTINGS_KEY, e);
            return defaultValue;
        }
    }

    /** Sets one flag for one device, leaving every other device and key untouched. */
    public static boolean set(
            ContentResolver resolver, String address, String key, boolean value) {
        if (resolver == null || TextUtils.isEmpty(address)) {
            return false;
        }
        try {
            final String raw = Settings.Global.getString(resolver, SETTINGS_KEY);
            final JSONObject all = TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
            JSONObject device = all.optJSONObject(address);
            if (device == null) {
                device = new JSONObject();
                all.put(address, device);
            }
            device.put(key, value);
            return Settings.Global.putString(resolver, SETTINGS_KEY, all.toString());
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Failed to store " + key, e);
            return false;
        }
    }
}
