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

package com.android.settings.notification;

import static android.media.audio.Flags.streamAssistantPublic;

import android.content.Context;
import android.media.AudioManager;
import android.text.TextUtils;

// LINT.IfChange
public class AssistantVolumePreferenceController extends VolumeSliderPreferenceController {

    private static final String KEY_ASSISTANT_VOLUME = "assistant_volume";

    /** IchthysOS: no assistant on this ROM. Flip to false to restore the slider. */
    private static final boolean ASSIST_DISABLED = true;

    public AssistantVolumePreferenceController(Context context) {
        super(context, KEY_ASSISTANT_VOLUME);
    }

    @Override
    public int getAvailabilityStatus() {
        // IchthysOS: this ROM ships no assistant and no voice-interaction service, so the
        // "Assistant volume" slider in Settings > Sound & vibration controls a stream nothing
        // ever plays on. Reported UNSUPPORTED_ON_DEVICE rather than removed from
        // sound_settings.xml because VolumeSliderPreferenceController.setupVolPreference()
        // dereferences findPreference() without a null check whenever isAvailable() is true -
        // deleting the XML entry alone would NPE. Doing it here also drops the row from the
        // Settings search index and from its slice. Keep in sync with
        // AssistantVolumePreference.isAvailable() (the Catalyst equivalent).
        if (ASSIST_DISABLED) {
            return UNSUPPORTED_ON_DEVICE;
        }
        return streamAssistantPublic()
                && !mHelper.isSingleVolume()
                && !AssistantVolumePreference.Companion.hasFeatureWatch(mContext)
                ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public boolean isSliceable() {
        return TextUtils.equals(getPreferenceKey(), KEY_ASSISTANT_VOLUME);
    }

    @Override
    public boolean isPublicSlice() {
        return true;
    }

    @Override
    public boolean useDynamicSliceSummary() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_ASSISTANT_VOLUME;
    }

    @Override
    public int getAudioStream() {
        return AudioManager.STREAM_ASSISTANT;
    }

    @Override
    public int getMuteIcon() {
        return com.android.internal.R.drawable.ic_volume_voice_chat_mute;
    }
}
// LINT.ThenChange(AssistantVolumePreference.kt)
