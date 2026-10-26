package com.auditlab.control

import android.net.Uri

/** Accept only a document provider URI, before requesting a report or opening any destination. */
internal object ExportDestination {
    fun isAllowed(uri: Uri): Boolean = uri.scheme == "content" && !uri.authority.isNullOrBlank()
}
