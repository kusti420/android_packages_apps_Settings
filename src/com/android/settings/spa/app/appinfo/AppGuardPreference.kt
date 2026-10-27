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

package com.android.settings.spa.app.appinfo

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.settings.R
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.ui.GuardActivity
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.app.userId

/**
 * IchthysOS: the "Guard" row of App info. Opens the app's page in Guard (network, trackers, data
 * access), which links back here with "Open system app info". Guard holds the policies of the
 * user Settings runs as, so the row is left out for another user's (a work profile's) apps.
 */
@Composable
fun AppGuardPreference(app: ApplicationInfo) {
    val context = LocalContext.current
    if (app.userId != context.userId) return
    // IchthysOS: and Guard runs for the system user only (GuardRepository.isGuardUser).
    if (!GuardRepository.isGuardUser()) return

    val summary = stringResource(R.string.ichthys_guard_app_info_summary)
    Preference(
        object : PreferenceModel {
            override val title = stringResource(R.string.ichthys_guard_title)
            override val summary = { summary }
            override val onClick = {
                context.startActivity(GuardActivity.appIntent(context, app.packageName))
            }
        }
    )
}
