package com.avalon.cwm

import android.content.Context

/**
 * 需要在本地服务启动前读取的设置，存 SharedPreferences。
 * 目前只有「局域网访问」：它决定 NanoHTTPD 绑 0.0.0.0 还是 127.0.0.1，
 * 必须在服务起来之前就知道，所以不能放 WebView 的 localStorage。
 */
object AppSettings {
    private const val PREFS = "cwm_settings"
    private const val KEY_LAN = "lan_access"

    fun lanAccess(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAN, true)

    fun setLanAccess(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LAN, enabled)
            .apply()
    }
}
