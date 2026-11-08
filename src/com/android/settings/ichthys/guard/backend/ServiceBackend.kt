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
import android.os.UserHandle
import android.util.Log
import com.android.internal.ichthys.guard.GuardEvent as GuardEventParcel
import com.android.internal.ichthys.guard.IGuardEventListener
import com.android.internal.ichthys.guard.IIchthysGuard
import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.EventType
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.JournalKind
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
 *
 * IchthysOS: only the system user's Settings may change the service's tables. They are device-wide
 * and the replace calls overwrite all of them, so a second user's or profile's Settings (Private
 * Space, a work profile) would wipe the owner's rules with its own app list. Writes from any other
 * user are dropped here ([write]); reads stay allowed. The AIDL is unchanged.
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

    override fun setNetworkPolicy(uid: Int, rule: UidRule) =
        write { it.setNetworkPolicy(uid, rule.toWire()) }

    override fun replaceNetworkPolicies(policies: Map<Int, UidRule>) =
        write {
            val list = policies.filterValues { v -> v.access != NetworkAccess.ALLOW }.entries.toList()
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

    override fun getScanInfo(packageName: String): ScanInfo? =
        call(null) { it.getScan(packageName)?.filterNotNull() }?.let { ScanInfo.parse(it) }

    override fun pauseEnforcement(minutes: Int) =
        write { it.pauseEnforcement(if (minutes <= 0) 0 else minutes.coerceAtMost(1440)) }

    override fun getPausedUntil(): Long = call(0L) { it.pausedUntil }

    override fun replaceBehaviours(flags: Map<Int, Int>) =
        write {
            val list = flags.filterValues { v -> v != 0 }.entries.toList()
            it.replaceBehaviours(
                IntArray(list.size) { i -> list[i].key },
                IntArray(list.size) { i -> list[i].value },
            )
        }

    override fun isLanDenied(uid: Int): Boolean = call(false) { it.isLanDenied(uid) }

    override fun isRestrictable(uid: Int): Boolean = call(true) { it.isRestrictable(uid) }

    override fun setAlertMask(mask: Int) = write { it.setAlertMask(mask) }

    override fun addJournal(uid: Int, kind: JournalKind, detail: String?) =
        write { it.addJournal(uid, kind.code, detail) }

    override fun getBlockedCounters(): Map<Int, BlockedCounter> =
        call(emptyMap()) {
            val flat = it.blockedCounters ?: LongArray(0)
            buildMap {
                for (i in 0 until flat.size / 3) put(
                    flat[3 * i].toInt(),
                    BlockedCounter(flat[3 * i + 1].toInt(), flat[3 * i + 2]),
                )
            }
        }

    override fun getHealth(): Int = call(Health.UNREACHABLE) { it.health }

    override fun getDenyNow(): Map<Int, BlockReason> =
        call(emptyMap()) {
            val flat = it.denyNow ?: IntArray(0)
            buildMap {
                for (i in 0 until flat.size / 2) put(
                    flat[2 * i],
                    BlockReason.fromCode(flat[2 * i + 1]),
                )
            }
        }

    /** A call that changes the service's device-wide state: system user only (see above). */
    private inline fun write(block: (IIchthysGuard) -> Unit) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            Log.w(TAG, "ichthys_guard write skipped: Guard runs for the system user only")
            return
        }
        call(Unit, block)
    }

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

internal fun GuardEventParcel.toModel(): GuardEvent {
    val kind =
        when (type) {
            GuardEventParcel.TYPE_CONNECT -> EventType.CONNECT
            GuardEventParcel.TYPE_JOURNAL -> EventType.JOURNAL
            GuardEventParcel.TYPE_SHIELD -> EventType.SHIELD
            else -> EventType.DNS
        }
    val answers = ips.orEmpty().filterNotNull()
    // IchthysOS: the firewall's deny chain (with its reason), or the system hosts file's sinkhole
    // answer, which has a reason of its own.
    val sinkholed = GuardEvent.isSinkholed(kind, answers, ip)
    val code =
        when {
            kind != EventType.DNS && kind != EventType.CONNECT -> code
            blocked -> if (code == 0) BlockReason.POLICY.code else code
            sinkholed -> BlockReason.BLOCKLIST.code
            else -> 0
        }
    return GuardEvent(
        seq = seq,
        timeMs = timeMs,
        uid = uid,
        type = kind,
        host = host,
        ips = answers,
        ip = ip,
        port = port,
        blocked = blocked || sinkholed,
        returnCode = returnCode,
        code = code,
        detail = detail,
    )
}
