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

package com.android.settings.ichthys.gallery

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies gallery clean-up after images change (content-triggered, see [GalleryCleanup]). */
class GalleryCleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
                GalleryCleanup.apply(applicationContext)
                // Content-triggered jobs fire once; arm the next one.
                GalleryCleanup.schedule(applicationContext)
                jobFinished(params, false)
            }
            .start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Content-triggered jobs don't survive a reboot; re-arm after boot. */
class GalleryCleanupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
                try {
                    GalleryCleanup.apply(context)
                    GalleryCleanup.schedule(context)
                } finally {
                    pending.finish()
                }
            }
            .start()
    }
}
