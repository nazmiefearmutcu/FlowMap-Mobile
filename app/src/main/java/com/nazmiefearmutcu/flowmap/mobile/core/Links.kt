package com.nazmiefearmutcu.flowmap.mobile.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens [url] in the user's browser; shows a short message instead of crashing when none can. */
fun Context.openUrl(url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    if (uri == null || (uri.scheme != "https" && uri.scheme != "http")) {
        Toast.makeText(this, "This link cannot be opened.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, "This link cannot be opened.", Toast.LENGTH_SHORT).show()
    }
}
