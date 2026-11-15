package com.isaakhanimann.journal.ui.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import com.isaakhanimann.journal.R

/**
 * Opens an external URI safely without crashing when no browser/handler exists or the URI is invalid.
 */
fun openUriSafely(
    context: Context,
    uriHandler: UriHandler,
    url: String,
) {
    val trimmedUrl = url.trim()
    if (trimmedUrl.isEmpty()) {
        showCannotOpenLinkToast(context)
        return
    }
    try {
        uriHandler.openUri(trimmedUrl)
    } catch (_: ActivityNotFoundException) {
        showCannotOpenLinkToast(context)
    } catch (_: IllegalArgumentException) {
        showCannotOpenLinkToast(context)
    } catch (_: SecurityException) {
        showCannotOpenLinkToast(context)
    }
}

private fun showCannotOpenLinkToast(context: Context) {
    Toast.makeText(
        context,
        R.string.audit_journal_cannot_open_link,
        Toast.LENGTH_SHORT
    ).show()
}
