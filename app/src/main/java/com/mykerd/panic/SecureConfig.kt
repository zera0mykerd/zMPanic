package com.mykerd.panic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureConfig {
    private const val TAG = "SecureConfig"
    private const val PREFS_FILE = "secure_zmpanic_prefs"
    private const val STEALTH_PREFS_FILE = "secure_stealth_prefs"
    @Volatile
    private var prefsInstance: SharedPreferences? = null
    @Volatile
    private var stealthPrefsInstance: SharedPreferences? = null
    fun getPrefs(context: Context): SharedPreferences {
        prefsInstance?.let { return it }
        return synchronized(this) {
            prefsInstance ?: createPrefs(context, PREFS_FILE, "secure_zmpanic_fallback").also { prefsInstance = it }
        }
    }
    fun getStealthPrefs(context: Context): SharedPreferences {
        stealthPrefsInstance?.let { return it }
        return synchronized(this) {
            stealthPrefsInstance ?: createPrefs(context, STEALTH_PREFS_FILE, "secure_stealth_fallback").also { stealthPrefsInstance = it }
        }
    }
    private fun createPrefs(context: Context, fileName: String, fallbackName: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted prefs for $fileName", e)
            context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
        }
    }
}