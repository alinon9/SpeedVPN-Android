package com.speedvpn.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class StartupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(5, 9, 20))
        }

        val title = TextView(this).apply {
            text = "SpeedVPN — Startup Diagnostic"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "تم تشغيل طبقة الإقلاع بنجاح. اضغط لفتح الواجهة الرئيسية."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val button = Button(this).apply {
            text = "فتح الواجهة الرئيسية"
            setOnClickListener {
                try {
                    startActivity(Intent(this@StartupActivity, MainActivity::class.java))
                } catch (t: Throwable) {
                    status.text = "فشل تشغيل MainActivity:\n\n" + t.stackTraceToString()
                }
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(button)
        setContentView(root)
    }
}
