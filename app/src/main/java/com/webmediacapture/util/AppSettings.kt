package com.webmediacapture.util

import android.content.Context

object AppSettings {
    private const val PREFS = "web_media_capture"
    private const val AUTO_YT_DLP = "auto_yt_dlp"
    private const val LIBRARY_SORT_NAME = "library_sort_name"

    fun autoYtDlp(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_YT_DLP, true)

    fun setAutoYtDlp(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_YT_DLP, enabled).apply()
    }

    fun librarySortByName(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LIBRARY_SORT_NAME, false)

    fun setLibrarySortByName(context: Context, byName: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LIBRARY_SORT_NAME, byName).apply()
    }
}
