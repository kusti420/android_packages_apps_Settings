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

package com.android.settings.ichthys.guard.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.util.Log
import com.android.settings.ichthys.guard.ui.ContactsPickerActivity

/**
 * IchthysOS: the contacts picked for an app in "picked" mode (`pkg:picked` in the contacts
 * list). ContactsProvider keeps the set (lookup keys, per user) and enforces it; Guard reads and
 * writes it through the provider's `ichthys_get_picked` / `ichthys_set_picked` calls, which only
 * the system uid may make. Settings reads the real address book: it runs as the system uid,
 * which the contacts shield never covers.
 */
object PickedContacts {
    private const val TAG = "GuardPickedContacts"

    /** The action the permission dialog's "Allow with picked contacts" opens the picker with. */
    const val ACTION_PICK = "com.android.settings.ichthys.guard.action.PICK_CONTACTS"

    private const val METHOD_GET = "ichthys_get_picked"
    private const val METHOD_SET = "ichthys_set_picked"
    private const val KEY_LOOKUPS = "lookups"

    /** One contact of the real address book, as the picker lists it. */
    data class Entry(
        val lookup: String,
        val name: String,
        val number: String?,
        val starred: Boolean,
        val thumbnail: Uri?,
    )

    /** The lookup keys picked for [pkg]; empty when none or when the provider can't be asked. */
    fun get(context: Context, pkg: String): List<String> =
        try {
            context.contentResolver
                .call(ContactsContract.AUTHORITY_URI, METHOD_GET, pkg, null)
                ?.getStringArrayList(KEY_LOOKUPS)
                .orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read the picked contacts of $pkg", e)
            emptyList()
        }

    /**
     * Stores [lookups] as [pkg]'s picked contacts and puts the app in picked mode (a bare, real
     * contacts entry goes). False when the provider refused.
     */
    fun set(context: Context, pkg: String, lookups: Collection<String>): Boolean =
        try {
            val extras = Bundle().apply { putStringArrayList(KEY_LOOKUPS, ArrayList(lookups)) }
            context.contentResolver.call(ContactsContract.AUTHORITY_URI, METHOD_SET, pkg, extras)
            ShieldTable.setContactsPicked(context, pkg, true)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot store the picked contacts of $pkg", e)
            false
        }

    /** Guard's picker for [pkg]. */
    fun pickerIntent(context: Context, pkg: String): Intent =
        Intent(context, ContactsPickerActivity::class.java).putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)

    /** Whether [pkg] asks for READ_CONTACTS at all (else picking is pointless). */
    fun requestsContacts(context: Context, pkg: String): Boolean =
        try {
            context.packageManager
                .getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                .requestedPermissions
                ?.contains(Manifest.permission.READ_CONTACTS) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * After a preset put [pkg] in picked mode (Messenger): opens the picker right away, so the app
     * doesn't sit with nobody picked. Nothing when the app doesn't ask for contacts.
     */
    fun openPickerAfterPreset(context: Context, pkg: String) {
        if (!ShieldTable.isContactsPicked(context, pkg) || !requestsContacts(context, pkg)) return
        try {
            context.startActivity(pickerIntent(context, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot open the contacts picker for $pkg", e)
        }
    }

    /** The real address book, sorted by name, with each contact's primary (or first) number. */
    fun loadBook(context: Context): List<Entry> {
        val cr = context.contentResolver
        val numbers = HashMap<Long, String>()
        val primary = HashSet<Long>()
        cr.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.IS_SUPER_PRIMARY),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1) ?: continue
                val isPrimary = c.getInt(2) != 0
                if (id !in numbers || (isPrimary && id !in primary)) {
                    numbers[id] = number
                    if (isPrimary) primary += id
                }
            }
        }
        val out = ArrayList<Entry>()
        cr.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED,
                Contacts.PHOTO_THUMBNAIL_URI),
            null,
            null,
            Contacts.SORT_KEY_PRIMARY,
        )?.use { c ->
            while (c.moveToNext()) {
                val lookup = c.getString(1) ?: continue
                val number = numbers[c.getLong(0)]
                out +=
                    Entry(
                        lookup = lookup,
                        name = c.getString(2)?.takeIf { it.isNotBlank() } ?: number ?: "(no name)",
                        number = number,
                        starred = c.getInt(3) != 0,
                        thumbnail = c.getString(4)?.let(Uri::parse),
                    )
            }
        }
        return out
    }

    /**
     * [stored] lookup keys mapped onto the keys [book] uses now: a key changes when contacts are
     * joined or split, and the provider still resolves the old one. Keys of contacts that are gone
     * are dropped.
     */
    fun currentKeys(context: Context, stored: Collection<String>, book: List<Entry>): Set<String> {
        val known = book.mapTo(HashSet()) { it.lookup }
        val out = HashSet<String>()
        for (key in stored) {
            if (key in known) {
                out += key
                continue
            }
            try {
                context.contentResolver
                    .query(Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, Uri.encode(key)),
                        arrayOf(Contacts.LOOKUP_KEY), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { out += it } }
            } catch (e: RuntimeException) {
                // Gone, or not a lookup key: not picked any more.
            }
        }
        return out
    }
}
