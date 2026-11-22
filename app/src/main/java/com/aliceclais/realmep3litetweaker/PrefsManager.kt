package com.aliceclais.realmep3litetweaker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

object PrefsManager {
    const val PREFS_NAME = "tweak_p3_lite_prefs"
    const val KEY_ENABLE_ANIMATIONS = "enable_smooth_animations"
    const val KEY_FORCE_FLOATING_ICON = "force_floating_icon"
    const val KEY_HOOK_SYSTEM_SERVER = "hook_system_server"
    const val KEY_ANIM_SPEED_SCALE = "anim_speed_scale"
    const val KEY_SAFE_MODE = "safe_crash_guard"

    @SuppressLint("WorldReadableFiles")
    @Suppress("DEPRECATION")
    fun getWorldReadablePrefs(context: Context): SharedPreferences? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            null
        }
    }
}
