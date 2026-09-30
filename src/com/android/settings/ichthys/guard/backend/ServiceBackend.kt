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

package com.android.settings.ichthys.guard.backend

import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import com.android.internal.ichthys.guard.GuardEvent as GuardEventParcel
import com.android.internal.ichthys.guard.IGuardEventListener
import com.android.internal.ichthys.guard.IIchthysGuard
import com.android.settings.ichthys.guard.model.EventType
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.NetworkAccess
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Talks to IchthysGuardService (binder "ichthys_guard"). Settings is a platform app, so it uses the
 * hidden framework classes directly: ServiceManager and the IIchthysGuard AIDL in framework.jar.
 * Calls need org.ichthys.permission.MANAGE_ICHTHYS_GUARD and, under SELinux, `find` on
 * ichthys_guard_service for system_app.
 *
 * The binder is looked up lazily and dropped when it dies, so Guard recovers when the service (or
 * system_server) restarts. While the service is unreachable every call returns its fallback and
 * [isLive] is false; policies are still saved and pushed again on the next boot or change.
 */
class ServiceBackend : GuardBackend {

    @Volatile private var cached: IIchthysGuard? = null

    private val deathRecipient = IBinder.DeathRecipient { cached = null }

    private fun service(): IIchthysGuard? {
        cached?.let { if (it.asBinder().isBinderAlive) return it }
        val binder =
            try {
                ServiceManager.getService(SERVICE_NAME)
            } catch (e: SecurityException) {
                Log.w(TAG, "ichthys_guard lookup denied", e)
                null
            } ?: return null
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            return null
        }
        return IIchthysGuard.Stub.asInterface(binder).also { cached = it }
    }

    override val isLive: Boolean
        get() = service() != null

    override fun setNetworkPolicy(uid: Int, access: NetworkAccess) =
        call(Unit) { it.setNetworkPolicy(uid, access.toWire()) }

    override fun replaceNetworkPolicies(policies: Map<Int, NetworkAccess>) =
        call(Unit) {
            val list = policies.filterValues { v -> v != NetworkAccess.ALLOW }.entries.toList()
            it.replaceNetworkPolicies(
                IntArray(list.size) { i -> list[i].key },
                IntArray(list.size) { i -> list[i].value.toWire() },
            )
        }

    override fun getNetworkPolicies(): Map<Int, NetworkAccess> =
        call(emptyMap()) {
            val flat = it.networkPolicies ?: IntArray(0)
            buildMap {
                for (i in 0 until flat.size / 2) put(
                    flat[2 * i],
                    networkAccessFromWire(flat[2 * i + 1]),
                )
            }
        }

    override fun getLatestSeq(): Long = call(0L) { it.latestSeq }

    override fun getEvents(sinceSeq: Long, max: Int): List<GuardEvent> =
        call(emptyList()) {
            it.getEvents(sinceSeq, max.coerceAtMost(MAX_PAGE)).orEmpty().map { e -> e.toModel() }
        }

    override fun liveEvents(): Flow<List<GuardEvent>> = callbackFlow {
        val listener =
            object : IGuardEventListener.Stub() {
                override fun onEvents(events: MutableList<GuardEventParcel>?) {
                    trySend(events.orEmpty().map { it.toModel() })
                }
            }
        call(Unit) { it.registerListener(listener) }
        awaitClose { call(Unit) { it.unregisterListener(listener) } }
    }

    override fun getScan(packageName: String): List<String>? =
        call(null) { it.getScan(packageName)?.filterNotNull() }

    override fun pauseEnforcement(minutes: Int) =
        call(Unit) { it.pauseEnforcement(if (minutes <= 0) 0 else minutes.coerceAtMost(1440)) }

    override fun getPausedUntil(): Long = call(0L) { it.pausedUntil }

    private inline fun <T> call(fallback: T, block: (IIchthysGuard) -> T): T {
        val s = service() ?: return fallback
        return try {
            block(s)
        } catch (e: RemoteException) {
            Log.w(TAG, "ichthys_guard call failed", e)
            cached = null
            fallback
        } catch (e: SecurityException) {
            Log.w(TAG, "ichthys_guard denied (MANAGE_ICHTHYS_GUARD missing?)", e)
            fallback
        } catch (e: IllegalArgumentException) {
            // setNetworkPolicy refuses non-ALLOW for system-critical uids.
            Log.w(TAG, "ichthys_guard rejected the call", e)
            fallback
        }
    }

    companion object {
        private const val TAG = "IchthysGuard"
        const val SERVICE_NAME = "ichthys_guard"
        private const val MAX_PAGE = 2_000
    }
}

internal fun GuardEventParcel.toModel() =
    GuardEvent(
        seq = seq,
        timeMs = timeMs,
        uid = uid,
        type = if (type == GuardEventParcel.TYPE_CONNECT) EventType.CONNECT else EventType.DNS,
        host = host,
        ips = ips.orEmpty().filterNotNull(),
        ip = ip,
        port = port,
        blocked = blocked,
        returnCode = returnCode,
    )
