/*
 * Copyright (c) 2022. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class FileSystemConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Throws(IOException::class, SecurityException::class)
    fun getTextFromUri(uri: Uri): String {
        return try {
            readTextFromUriOnce(uri)
        } catch (e: IOException) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            readTextFromUriOnce(uri)
        }
    }

    private fun readTextFromUriOnce(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Unable to open input stream for URI: $uri")
        return input.bufferedReader().use { it.readText() }
    }

    @Throws(IOException::class, SecurityException::class)
    fun saveTextInUri(uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Unable to open output stream for URI: $uri")
        output.bufferedWriter().use { writer ->
            writer.write(text)
            writer.flush()
        }
    }
}