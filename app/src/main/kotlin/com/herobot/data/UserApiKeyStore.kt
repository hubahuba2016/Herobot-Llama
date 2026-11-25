package com.herobot.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores each signed-in user's optional personal Gemini API key privately on-device,
 * scoped by Firebase Auth UID so multiple Google accounts on one device stay isolated
 * and keys are never uploaded to Firestore.
 */
class UserApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getPersonalApiKey(userId: String): String? {
        if (userId.isBlank()) return null
        return prefs.getString(keyForUser(userId), null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setPersonalApiKey(userId: String, apiKey: String) {
        if (userId.isBlank()) return
        val cleaned = apiKey.trim()
        if (cleaned.isEmpty()) {
            clearPersonalApiKey(userId)
        } else {
            prefs.edit().putString(keyForUser(userId), cleaned).apply()
        }
    }

    fun clearPersonalApiKey(userId: String) {
        if (userId.isBlank()) return
        prefs.edit().remove(keyForUser(userId)).apply()
    }

    private fun keyForUser(userId: String): String = "gemini_api_key_$userId"

    companion object {
        private const val PREFS_NAME = "herobot_user_api_keys"
    }
}
