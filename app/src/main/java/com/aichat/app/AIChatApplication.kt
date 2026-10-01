package com.aichat.app

import android.app.Application
import android.content.Intent
import com.aichat.app.data.AppRepository
import com.aichat.app.tools.ToolRegistry
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

class AIChatApplication : Application() {
    lateinit var repository: AppRepository
        private set
    lateinit var toolRegistry: ToolRegistry
        private set

    private var prevHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        toolRegistry = ToolRegistry(this)
        // 崩溃处理：写 crash.log → 启动应用内崩溃页（显示堆栈/复制/重启），不弹系统闪退窗
        // 防循环：60 秒内崩溃超过 3 次则交给系统默认处理（避免崩溃页反复崩）
        prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()) + " ===")
                pw.println("thread: " + thread.toString())
                throwable.printStackTrace(pw)
                pw.flush()
                File(filesDir, "crash.log").writeText(sw.toString())
            } catch (_: Exception) {}

            val sp = getSharedPreferences("crash_guard", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = sp.getLong("last", 0L)
            val count = if (now - last < 60_000) sp.getInt("count", 0) + 1 else 1
            sp.edit().putLong("last", now).putInt("count", count).apply()
            if (count > 3) {
                prevHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (_: Exception) {
                prevHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
