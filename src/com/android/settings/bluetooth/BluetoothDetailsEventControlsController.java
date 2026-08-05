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

import android.content.Context;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.core.lifecycle.Lifecycle;

/**
 * Lets the user decide which events <em>this</em> Bluetooth device is allowed to trigger on the
 * phone, from the device's own details screen.
 *
 * <p>Scoped to one device by design. Every event these switches govern reaches the system at a
 * point that already knows which {@link android.bluetooth.BluetoothDevice} sent it —
 * {@code AvrcpTargetService#sendMediaKeyEvent}, {@code AvrcpVolumeManager#setVolume} and
 * {@code HeadsetService#startVoiceRecognitionByHeadset} — so the enforcement is per address and a
 * second headset of the same model is unaffected. The phone's own hardware keys reach the system
 * through {@code PhoneWindowManager} and never touch those paths, so nothing here can disable a
 * physical button on the phone.
 *
 * <p>Everything defaults to allowed, so an untouched device behaves exactly as stock and stores
 * nothing at all.
 */
public class BluetoothDetailsEventControlsController extends BluetoothDetailsController {

    public static final String KEY_EVENT_CONTROLS_GROUP = "bluetooth_event_controls";

    private static final String PREF_KEY_VOLUME = "bt_event_volume";
    private static final String PREF_KEY_ASSISTANT = "bt_event_assistant";
    private static final String PREF_KEY_PLAY_PAUSE = "bt_event_play_pause";
    private static final String PREF_KEY_TRACKS = "bt_event_tracks";
    private static final String PREF_KEY_SWAP_TRACKS = "bt_event_swap_tracks";

    private PreferenceCategory mCategory;
    private SwitchPreferenceCompat mVolume;
    private SwitchPreferenceCompat mAssistant;
    private SwitchPreferenceCompat mPlayPause;
    private SwitchPreferenceCompat mTracks;
    private SwitchPreferenceCompat mSwapTracks;

    public BluetoothDetailsEventControlsController(
            Context context,
            PreferenceFragmentCompat fragment,
            CachedBluetoothDevice device,
            Lifecycle lifecycle) {
        super(context, fragment, device, lifecycle);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_EVENT_CONTROLS_GROUP;
    }

    @Override
    protected void init(PreferenceScreen screen) {
        mCategory = screen.findPreference(KEY_EVENT_CONTROLS_GROUP);
        if (mCategory == null) {
            return;
        }
        mVolume =
                addSwitch(
                        PREF_KEY_VOLUME,
                        R.string.bluetooth_event_volume_title,
                        R.string.bluetooth_event_volume_summary,
                        IchthysBluetoothEventPrefs.KEY_VOLUME,
                        true);
        mAssistant =
                addSwitch(
                        PREF_KEY_ASSISTANT,
                        R.string.bluetooth_event_assistant_title,
                        R.string.bluetooth_event_assistant_summary,
                        IchthysBluetoothEventPrefs.KEY_ASSISTANT,
                        true);
        mPlayPause =
                addSwitch(
                        PREF_KEY_PLAY_PAUSE,
                        R.string.bluetooth_event_play_pause_title,
                        R.string.bluetooth_event_play_pause_summary,
                        IchthysBluetoothEventPrefs.KEY_PLAY_PAUSE,
                        true);
        mTracks =
                addSwitch(
                        PREF_KEY_TRACKS,
                        R.string.bluetooth_event_tracks_title,
                        R.string.bluetooth_event_tracks_summary,
                        IchthysBluetoothEventPrefs.KEY_TRACKS,
                        true);
        mSwapTracks =
                addSwitch(
                        PREF_KEY_SWAP_TRACKS,
                        R.string.bluetooth_event_swap_tracks_title,
                        R.string.bluetooth_event_swap_tracks_summary,
                        IchthysBluetoothEventPrefs.KEY_SWAP_TRACKS,
                        false);
    }

    private SwitchPreferenceCompat addSwitch(
            String prefKey, int titleRes, int summaryRes, String storageKey, boolean defaultOn) {
        final SwitchPreferenceCompat pref = new SwitchPreferenceCompat(mCategory.getContext());
        pref.setKey(prefKey);
        pref.setTitle(titleRes);
        pref.setSummary(summaryRes);
        // Backed by the shared Settings.Global blob, not by Preference's own SharedPreferences.
        pref.setPersistent(false);
        pref.setChecked(read(storageKey, defaultOn));
        pref.setOnPreferenceChangeListener(
                (p, newValue) -> {
                    final boolean enabled = (Boolean) newValue;
                    if (!write(storageKey, enabled)) {
                        return false;
                    }
                    if (IchthysBluetoothEventPrefs.KEY_TRACKS.equals(storageKey)) {
                        // Swapping is meaningless while the events are suppressed entirely.
                        updateSwapEnabledState(enabled);
                    }
                    return true;
                });
        mCategory.addPreference(pref);
        return pref;
    }

    private boolean read(String storageKey, boolean defaultValue) {
        return IchthysBluetoothEventPrefs.get(
                mContext.getContentResolver(), getAddress(), storageKey, defaultValue);
    }

    private boolean write(String storageKey, boolean value) {
        return IchthysBluetoothEventPrefs.set(
                mContext.getContentResolver(), getAddress(), storageKey, value);
    }

    private String getAddress() {
        return mCachedDevice == null ? null : mCachedDevice.getAddress();
    }

    private void updateSwapEnabledState(boolean tracksAllowed) {
        if (mSwapTracks != null) {
            mSwapTracks.setEnabled(tracksAllowed);
        }
    }

    @Override
    protected void refresh() {
        if (mCategory == null) {
            return;
        }
        final boolean tracksAllowed = read(IchthysBluetoothEventPrefs.KEY_TRACKS, true);
        mVolume.setChecked(read(IchthysBluetoothEventPrefs.KEY_VOLUME, true));
        mAssistant.setChecked(read(IchthysBluetoothEventPrefs.KEY_ASSISTANT, true));
        mPlayPause.setChecked(read(IchthysBluetoothEventPrefs.KEY_PLAY_PAUSE, true));
        mTracks.setChecked(tracksAllowed);
        mSwapTracks.setChecked(read(IchthysBluetoothEventPrefs.KEY_SWAP_TRACKS, false));
        updateSwapEnabledState(tracksAllowed);
    }

    @Override
    public boolean isAvailable() {
        // Needs an address to key the overrides on; everything else is profile-independent.
        return getAddress() != null;
    }
}
