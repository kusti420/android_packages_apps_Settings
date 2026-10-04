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

package com.android.settings.ichthys.protect

import com.android.settings.SubSettings

/*
 * Entry points for the protect pages. Each is declared in AndroidManifest.xml with the
 * com.android.settings.FRAGMENT_CLASS meta-data naming its fragment; SubSettings accepts any
 * fragment, so nothing needs to go into SettingsGateway.
 */

/** [IchthysProtect.ACTION_A11Y_SCOPE] → [A11yScopeSettings]. */
class A11yScopeActivity : SubSettings()

/** [IchthysProtect.ACTION_NO_LOCATION] → [LocationNoFixSettings]. */
class NoLocationActivity : SubSettings()
