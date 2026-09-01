package com.webmediacapture.util

import android.content.Context

object AppSettings {
    private const val PREFS = "web_media_capture"
    private const val AUTO_YT_DLP = "auto_yt_dlp"

    fun autoYtDlp(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_YT_DLP, true)

    fun setAutoYtDlp(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_YT_DLP, enabled).apply()
    }
}
