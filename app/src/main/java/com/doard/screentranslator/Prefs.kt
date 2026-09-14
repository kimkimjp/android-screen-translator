package com.doard.screentranslator

import android.content.Context

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun showFloatingButton(context: Context) = prefs(context).getBoolean("show_button", true)
    fun setShowFloatingButton(context: Context, show: Boolean) =
        prefs(context).edit().putBoolean("show_button", show).apply()

    /** フローティングボタンの位置。未保存なら null */
    fun buttonPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains("button_x")) return null
        return p.getInt("button_x", 0) to p.getInt("button_y", 0)
    }

    fun setButtonPosition(context: Context, x: Int, y: Int) =
        prefs(context).edit().putInt("button_x", x).putInt("button_y", y).apply()
}
