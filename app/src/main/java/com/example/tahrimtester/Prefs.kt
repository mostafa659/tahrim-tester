package com.example.tahrimtester

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tahrim", Context.MODE_PRIVATE)
    var startAyah: Int
        get() = sp.getInt("startAyah", 1)
        set(v) { sp.edit().putInt("startAyah", v).apply() }
    var preset: String
        get() = sp.getString("preset", "") ?: ""
        set(v) { sp.edit().putString("preset", v).apply() }
    var textSp: Float
        get() = sp.getFloat("textSp", 30f)
        set(v) { sp.edit().putFloat("textSp", v).apply() }
    var seenIntro: Boolean
        get() = sp.getBoolean("seenIntro", false)
        set(v) { sp.edit().putBoolean("seenIntro", v).apply() }
}
