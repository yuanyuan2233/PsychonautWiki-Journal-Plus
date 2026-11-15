/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SubstancePkCustomization(
    val substanceName: String,
    val customHalfLifeHours: Double? = null,
    val customTMaxHours: Double? = null,
    val customWashoutHours: Double? = null,
    val customPkRole: String? = null,
    val customBioavailabilityFraction: Double? = null,
)

/**
 * 个体化药代动力学 (PK) 覆盖参数的轻量持久化存储。
 * 采用独立 SharedPreferences + StateFlow 隔离存储，并支持备份导出与恢复。
 */
@Singleton
class PkCustomizationStore @VisibleForTesting internal constructor(
    private val prefs: SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _customizationsFlow = MutableStateFlow(loadAllCustomizations())
    val customizationsFlow: StateFlow<Map<String, SubstancePkCustomization>> =
        _customizationsFlow.asStateFlow()

    fun getCustomization(substanceName: String): SubstancePkCustomization? {
        val trimmed = substanceName.trim()
        if (trimmed.isEmpty()) return null
        val snapshot = _customizationsFlow.value
        snapshot[substanceName]?.let { return it }
        snapshot[trimmed]?.let { return it }
        return snapshot.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value
    }

    @Synchronized
    fun saveCustomization(customization: SubstancePkCustomization) {
        val sanitized = sanitize(customization)
        if (sanitized == null) {
            val cleanName = customization.substanceName.trim()
            if (cleanName.isNotEmpty()) {
                resetToDefault(cleanName)
            }
            return
        }
        val currentMap = _customizationsFlow.value.toMutableMap()
        val editor = prefs.edit()
        val existingKeys = currentMap.keys.filter { it.equals(sanitized.substanceName, ignoreCase = true) }
        for (oldKey in existingKeys) {
            currentMap.remove(oldKey)
            if (oldKey != sanitized.substanceName) {
                editor.remove(getKey(oldKey))
            }
        }
        currentMap[sanitized.substanceName] = sanitized
        editor.putString(getKey(sanitized.substanceName), serializeCustomization(sanitized))
        editor.apply()
        _customizationsFlow.value = currentMap.toMap()
    }

    @Synchronized
    fun resetToDefault(substanceName: String) {
        val trimmed = substanceName.trim()
        if (trimmed.isEmpty()) return
        val currentMap = _customizationsFlow.value.toMutableMap()
        val editor = prefs.edit()
        val matchingKeys = currentMap.keys.filter { it.equals(trimmed, ignoreCase = true) }
        for (key in matchingKeys) {
            currentMap.remove(key)
            editor.remove(getKey(key))
        }
        editor.remove(getKey(trimmed))
        editor.apply()
        _customizationsFlow.value = currentMap.toMap()
    }

    fun getAllCustomizations(): Map<String, SubstancePkCustomization> {
        return _customizationsFlow.value
    }

    fun getAllCustomizationsForExport(): List<SubstancePkCustomization> {
        return _customizationsFlow.value.values.sortedBy { it.substanceName }
    }

    @Synchronized
    fun replaceAllCustomizations(customizations: List<SubstancePkCustomization>): Boolean {
        val newMap = linkedMapOf<String, SubstancePkCustomization>()
        customizations.forEach { item ->
            val sanitized = sanitize(item) ?: return@forEach
            val existingKey = newMap.keys.firstOrNull { it.equals(sanitized.substanceName, ignoreCase = true) }
            if (existingKey != null && existingKey != sanitized.substanceName) {
                newMap.remove(existingKey)
            }
            newMap[sanitized.substanceName] = sanitized
        }

        val editor = prefs.edit()
        editor.clear()
        newMap.values.forEach { sanitized ->
            editor.putString(getKey(sanitized.substanceName), serializeCustomization(sanitized))
        }
        val committed = editor.commit()
        if (committed) {
            _customizationsFlow.value = newMap.toMap()
        }
        return committed
    }

    @Synchronized
    fun clearAllCustomizations(): Boolean {
        val committed = prefs.edit().clear().commit()
        if (committed) {
            _customizationsFlow.value = emptyMap()
        }
        return committed
    }

    private fun loadAllCustomizations(): Map<String, SubstancePkCustomization> {
        val result = linkedMapOf<String, SubstancePkCustomization>()
        val allEntries = try {
            prefs.all
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        for ((key, value) in allEntries) {
            if (key.startsWith(PREFIX) && value is String) {
                val fallbackName = key.removePrefix(PREFIX)
                val parsed = parseCustomization(fallbackName, value) ?: continue
                result[parsed.substanceName] = parsed
            }
        }
        return result.toMap()
    }

    private fun parseCustomization(fallbackName: String, raw: String): SubstancePkCustomization? {
        return try {
            val json = JSONObject(raw)
            val name = getNonBlankString(json, "substanceName") ?: fallbackName.trim()
            if (name.isEmpty()) return null
            val candidate = SubstancePkCustomization(
                substanceName = name,
                customHalfLifeHours = getPositiveDouble(json, "halfLifeHours"),
                customTMaxHours = getPositiveDouble(json, "tMaxHours"),
                customWashoutHours = getPositiveDouble(json, "washoutHours"),
                customPkRole = getNonBlankString(json, "pkRole"),
                customBioavailabilityFraction = getValidFraction(json, "bioavailabilityFraction"),
            )
            sanitize(candidate)
        } catch (_: Exception) {
            null
        }
    }

    private fun serializeCustomization(customization: SubstancePkCustomization): String {
        val json = JSONObject().apply {
            put("substanceName", customization.substanceName)
            customization.customHalfLifeHours?.let { put("halfLifeHours", it) }
            customization.customTMaxHours?.let { put("tMaxHours", it) }
            customization.customWashoutHours?.let { put("washoutHours", it) }
            customization.customPkRole?.let { put("pkRole", it) }
            customization.customBioavailabilityFraction?.let { put("bioavailabilityFraction", it) }
        }
        return json.toString()
    }

    private fun sanitize(customization: SubstancePkCustomization): SubstancePkCustomization? {
        val cleanName = customization.substanceName.trim()
        if (cleanName.isEmpty()) return null
        val hl = customization.customHalfLifeHours?.takeIf { it.isFinite() && it > 0.0 }
        val tMax = customization.customTMaxHours?.takeIf { it.isFinite() && it > 0.0 }
        val washout = customization.customWashoutHours?.takeIf { it.isFinite() && it > 0.0 }
        val pkRole = customization.customPkRole?.trim()?.takeIf { it.isNotEmpty() }
        val bioavail = customization.customBioavailabilityFraction?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
        if (hl == null && tMax == null && washout == null && pkRole == null && bioavail == null) {
            return null
        }
        return SubstancePkCustomization(
            substanceName = cleanName,
            customHalfLifeHours = hl,
            customTMaxHours = tMax,
            customWashoutHours = washout,
            customPkRole = pkRole,
            customBioavailabilityFraction = bioavail,
        )
    }

    private fun getPositiveDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun getValidFraction(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
    }

    private fun getNonBlankString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key, "").trim().takeIf { it.isNotEmpty() }
    }

    private fun getKey(substanceName: String): String = "$PREFIX$substanceName"

    companion object {
        private const val PREFS_NAME = "pk_customization_prefs"
        private const val PREFIX = "custom_pk_"
    }
}
