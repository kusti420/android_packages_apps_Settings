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

package com.android.settings.ichthys.guard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settings.R
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.guard.ui.theme.GuardTheme

/**
 * IchthysOS: Shared values (home city, trips, units, the fake place's coordinates) as its own
 * page under Settings › IchthysOS, out of Guard. The screen itself is unchanged
 * ([SharedValuesScreen]); only the entry moved: the hub lists it, the Weather page's Location row
 * opens it, and Guard's gear links here from "Spoofed values" (which this page links back to).
 */
class SharedValuesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GuardTheme {
                val t = Guard.tokens
                Scaffold(
                    containerColor = t.ink,
                    contentColor = t.text,
                    topBar = { TopBar(stringResource(R.string.ichthys_shared_values_title)) { finish() } },
                ) { padding ->
                    SharedValuesScreen(
                        onBack = { finish() },
                        modifier = Modifier.padding(padding),
                        onOpenSpoofed = { startActivity(GuardActivity.spoofedValuesIntent(this)) },
                    )
                }
            }
        }
    }

    @Composable
    private fun TopBar(title: String, onBack: () -> Unit) {
        val t = Guard.tokens
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.ichthys_guard_navigate_up),
                    tint = t.text,
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = t.text)
        }
    }

    companion object {
        @JvmStatic
        fun intent(context: Context): Intent = Intent(context, SharedValuesActivity::class.java)
    }
}
