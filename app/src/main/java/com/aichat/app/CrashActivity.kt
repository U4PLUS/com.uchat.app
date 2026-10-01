package com.aichat.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 崩溃页：全局未捕获异常处理器写入 crash.log 后启动本页，
 * 展示堆栈、可复制日志、一键重启（避免直接闪退无提示）。
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logText = runCatching { File(filesDir, "crash.log").readText() }
            .getOrNull() ?: "（无日志）"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(16))
            setBackgroundColor(Color.rgb(30, 30, 34))
        }

        root.addView(TextView(this).apply {
            text = "AI Chat 遇到问题"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "崩溃已记录，可复制日志反馈，或重新启动继续使用。"
            setTextColor(Color.rgb(180, 180, 185))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        val tv = TextView(this).apply {
            text = logText
            setTextColor(Color.rgb(220, 220, 225))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        btnRow.addView(Button(this).apply {
            text = "复制日志"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", logText))
            }
        })
        btnRow.addView(Button(this).apply {
            text = "重新启动"
            setOnClickListener {
                val intent = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
            }
        })
        root.addView(btnRow)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
