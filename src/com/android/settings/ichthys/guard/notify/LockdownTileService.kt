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

package com.android.settings.ichthys.guard.notify

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.settings.R
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.ui.formatClock

/**
 * Quick Settings panic tile: one tap toggles Lockdown (network off for every app except the
 * allowlist) without opening Guard. Long-press opens Guard: Settings' QS_TILE_PREFERENCES gateway
 * (QSTileLongPressGatewayActivity) routes both Guard tiles to GuardActivity.
 *
 * Declared in the Settings manifest for now; a later step replaces both with native SystemUI tiles.
 */
class LockdownTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val repo = GuardRepository.get(this)
        repo.setLockdown(!repo.rules.value.lockdown.enabled)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = GuardRepository.get(this).rules.value.lockdown.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.ichthys_guard_tile_lockdown)
        tile.subtitle =
            getString(
                if (on) R.string.ichthys_guard_tile_lockdown_on else R.string.ichthys_guard_tile_off
            )
        tile.updateTile()
    }
}

/** Quick Settings tile: pause protection for 5 minutes, tap again to resume. */
class PauseTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val repo = GuardRepository.get(this)
        repo.pause(if (repo.isPaused()) 0 else 5)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val repo = GuardRepository.get(this)
        val paused = repo.isPaused()
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label =
            getString(
                if (paused) R.string.ichthys_guard_tile_paused
                else R.string.ichthys_guard_tile_pause
            )
        tile.subtitle =
            if (paused) {
                getString(
                    R.string.ichthys_guard_tile_paused_until,
                    formatClock(repo.pausedUntil.value).dropLast(3),
                )
            } else {
                getString(R.string.ichthys_guard_tile_pause_duration)
            }
        tile.updateTile()
    }
}
