package dev.hram.androsaur.camera

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * How another Androsaur app on this phone learns where the service is and how to talk to it: `call("link")` returns the port
 * and the access code. The provider is guarded by the signature permission CONTROL, so only apps signed with the same key
 * can ask. The code is a credential: the caller keeps it in memory and never logs it.
 */
class LinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "link") return null
        val context = context ?: return null
        return Bundle().apply {
            putString("module", "androsaur-camera"); putInt("port", 8088); putString("token", MainActivity.token(context))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
