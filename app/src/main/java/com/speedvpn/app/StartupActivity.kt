package com.speedvpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class StartupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash_recovery", MODE_PRIVATE)
        val previousCrash = prefs.getString("last_crash", null)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(5, 9, 20))
        }
        val title = TextView(this).apply {
            text = if (previousCrash.isNullOrBlank()) "SpeedVPN — Startup Diagnostic" else "SpeedVPN — Crash Detected"
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = if (previousCrash.isNullOrBlank()) "تم تشغيل طبقة الإقلاع بنجاح. اضغط لفتح الواجهة الرئيسية." else "تم تسجيل انهيار سابق. التفاصيل:"
            textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val details = TextView(this).apply {
            text = previousCrash.orEmpty(); textSize = 11f
            setTextColor(Color.rgb(255, 190, 190)); setPadding(12, 12, 12, 12); setTextIsSelectable(true)
        }
        val open = Button(this).apply {
            text = "فتح الواجهة الرئيسية"
            setOnClickListener {
                try { startActivity(Intent(this@StartupActivity, MainActivity::class.java)) }
                catch (t: Throwable) { status.text = "فشل تشغيل MainActivity:"; details.text = t.stackTraceToString() }
            }
        }
        val clear = Button(this).apply {
            text = "مسح سجل الانهيار"
            setOnClickListener { prefs.edit().clear().commit(); recreate() }
        }
        root.addView(title); root.addView(status)
        if (!previousCrash.isNullOrBlank()) {
            root.addView(ScrollView(this).apply { addView(details) }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(clear)
        } else { root.addView(open) }
        setContentView(root)
    }
}