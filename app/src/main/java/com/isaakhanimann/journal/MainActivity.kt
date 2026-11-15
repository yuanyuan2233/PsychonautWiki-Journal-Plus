/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
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

package com.isaakhanimann.journal

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.isaakhanimann.journal.data.routine.RoutineNotificationHelper
import com.isaakhanimann.journal.data.routine.RoutineSchedulerManager
import com.isaakhanimann.journal.ui.main.MainScreen
import com.isaakhanimann.journal.ui.tabs.settings.AppLanguage
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import com.isaakhanimann.journal.ui.theme.JournalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class RoutineDeepLinkRequest(
    val openRoutineRecords: Boolean = false,
    val experienceId: Int? = null
)

class LocalizedActivityContext(
    base: Context,
    private val localizedContext: Context
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedContext.resources
    override fun getAssets(): AssetManager = localizedContext.assets
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var routineSchedulerManager: RoutineSchedulerManager

    private var routineDeepLinkState by mutableStateOf<RoutineDeepLinkRequest?>(null)
    private var isLanguageLoaded = false
    private var currentAppLanguage by mutableStateOf(AppLanguage.FOLLOW_SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isLanguageLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            userPreferences.appLanguageFlow.collect { lang ->
                currentAppLanguage = lang
                isLanguageLoaded = true
            }
        }
        if (savedInstanceState == null) {
            routineDeepLinkState = extractRoutineDeepLink(intent)
        }
        setContent {
            val appLanguage = currentAppLanguage
            val currentContext = LocalContext.current

            val targetLocale = remember(appLanguage) {
                appLanguage.toLocale() ?: run {
                    val systemLocales = Resources.getSystem().configuration.locales
                    if (!systemLocales.isEmpty) systemLocales[0] else Locale.getDefault()
                }
            }

            LaunchedEffect(targetLocale) {
                Locale.setDefault(targetLocale)
            }

            val localizedConfiguration = remember(targetLocale, currentContext) {
                Configuration(currentContext.resources.configuration).apply {
                    setLocale(targetLocale)
                }
            }

            val wrappedLocalizedContext = remember(localizedConfiguration, currentContext) {
                val baseContext = (currentContext as? LocalizedActivityContext)?.baseContext ?: currentContext
                val configContext = baseContext.createConfigurationContext(localizedConfiguration)
                LocalizedActivityContext(base = baseContext, localizedContext = configContext)
            }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfiguration,
                LocalContext provides wrappedLocalizedContext
            ) {
                JournalTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(
                            routineDeepLink = routineDeepLinkState,
                            onConsumeRoutineDeepLink = { routineDeepLinkState = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routineDeepLinkState = extractRoutineDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            routineSchedulerManager.reconcileAndRescheduleAll()
        }
    }

    private fun extractRoutineDeepLink(intent: Intent?): RoutineDeepLinkRequest? {
        if (intent == null) return null
        val openRecords = intent.getBooleanExtra(RoutineNotificationHelper.EXTRA_OPEN_ROUTINE_RECORDS, false)
        val expId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_OPEN_EXPERIENCE_ID, -1)
            .takeIf { it > 0 }
        intent.removeExtra(RoutineNotificationHelper.EXTRA_OPEN_ROUTINE_RECORDS)
        intent.removeExtra(RoutineNotificationHelper.EXTRA_OPEN_EXPERIENCE_ID)
        return if (openRecords || expId != null) {
            RoutineDeepLinkRequest(openRoutineRecords = openRecords, experienceId = expId)
        } else {
            null
        }
    }
}
