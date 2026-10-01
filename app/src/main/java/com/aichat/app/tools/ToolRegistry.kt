package com.aichat.app.tools

import android.content.Context
import android.os.Build
import com.aichat.app.data.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工具注册表：内置工具（计算器 / 时间 / busybox 沙箱 shell）。
 *
 * shell 执行基于随包内置的静态 busybox（armeabi-v7a / arm64-v8a / x86 / x86_64 全兼容），
 * 不依赖系统 sh/toybox（Android 6 等旧系统 toybox 命令不全、且 Process 超时 API 缺失会导致崩溃）。
 * 后续可扩展：脚本工具（QuickJS / JS）、Alpine proot 执行器。
 */
class ToolRegistry(private val context: Context) {

    /** 工具定义列表（随每个请求发给模型的 tools 数组） */
    val definitions: List<ToolDef> = listOf(
        ToolDef(
            id = "calculator",
            name = "calculator",
            description = "精确计算数学表达式。支持 + - * / ^ %、括号、小数、科学计数法、常量 pi/e，以及函数 sin cos tan sqrt log ln abs round floor ceil。适合任何数值计算，不要心算。",
            parameters = """{"type":"object","properties":{"expression":{"type":"string","description":"数学表达式，如 (2+3)*4^2 或 sqrt(2)+pi"}},"required":["expression"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "current_time",
            name = "current_time",
            description = "获取当前本地日期与时间（含星期）。不知道今天是几号/几点时先调用它。",
            parameters = """{"type":"object","properties":{},"required":[]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "run_shell",
            name = "run_shell",
            description = "在沙箱 shell 中执行命令（内置 busybox，全架构兼容）。白名单命令：echo cat grep egrep sed awk sort head tail wc date cut tr uniq base64 printf ls find stat bc expr seq fold pwd id whoami uname uptime df du free touch mkdir tee cp xargs yes wget tar gzip gunzip md5sum sha1sum sha256sum od xxd which env time。仅 wget 可以联网（HTTP/HTTPS，用于下载/抓取）。禁止绝对路径、禁止 '..'、禁止重定向，禁止 rm/chmod/chown/mv/su/curl 等破坏性或提权命令。工作目录为 App 私有沙箱目录。",
            parameters = """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令，如：echo hello 或 grep -n error notes.txt 或 wget -qO page.html https://example.com"}},"required":["command"]}""",
            dangerLevel = 2
        )
    )

    private val workDir: File = File(context.filesDir, "toolbox").apply { mkdirs() }

    private val ALLOWED_COMMANDS = setOf(
        "echo", "cat", "grep", "egrep", "sed", "awk", "sort", "head", "tail", "wc",
        "date", "cut", "tr", "uniq", "base64", "printf", "ls", "find", "stat", "bc",
        "expr", "seq", "fold", "pwd", "id", "whoami", "uname", "uptime", "df", "du",
        "free", "touch", "mkdir", "tee", "cp", "xargs", "yes",
        "wget", "tar", "gzip", "gunzip", "md5sum", "sha1sum", "sha256sum",
        "od", "xxd", "which", "env", "time"
    )
    private val DANGEROUS_TOKENS = listOf(
        "rm ", "rm -", " del", "mv ", "dd ", "mkfs", "chmod", "chown", "su ",
        "sudo", "shutdown", "reboot", "mount", "mknod", "kill", "curl", "nc ",
        "python", "perl", "sh -c", "bash", "zsh", "chroot"
    )

    /** 执行工具（ViewModel 在授权通过后调用）。最外层兜底，任何异常/Error 都转成结果文本，绝不崩溃 */
    suspend fun execute(name: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        try {
            doExecute(name, argumentsJson)
        } catch (t: Throwable) {
            "错误：工具执行异常 —— ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun doExecute(name: String, argumentsJson: String): String = when (name) {
        "calculator" -> {
            val expr = parseArg(argumentsJson, "expression")
            if (expr.isBlank()) "错误：缺少参数 expression"
            else try {
                val v = ExpressionEvaluator.eval(expr)
                "= $v"
            } catch (e: Exception) {
                "错误：表达式无法计算 —— ${e.message ?: "语法错误"}"
            }
        }
        "current_time" -> {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.getDefault())
            "当前本地时间：${fmt.format(Date())}"
        }
        "run_shell" -> runSafeShell(parseArg(argumentsJson, "command"))
        else -> "错误：未知工具 $name"
    }

    // ── busybox 位置：jniLibs 打包为 libbusybox.so / libssl_helper.so ──
    // 系统按 ABI 自动解压到 nativeLibraryDir（/data/app/<pkg>/lib/<abi>，可执行分区，只读即可用）。
    // 关键约束：busybox 靠 argv[0] 的 basename 判断模式——必须是 "busybox"（libbusybox.so → applet not found）。
    // 解法（全 API 兼容，无写入、无符号链接）：
    //   /system/bin/sh -c 'exec -a busybox <nativeDir>/libbusybox.so sh -c <命令>'
    //  mksh 的 exec -a 显式指定 argv[0]=busybox → busybox 进入通用模式 ✓
    //  （此前符号链接方案依赖 java.nio.file.Files（API 26+），Android 7 以下静默回退导致 applet not found）

    private val linkDir: File get() = File(context.filesDir, "bin")
    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    private val busyboxBin: File by lazy {
        File(nativeDir, "libbusybox.so").also {
            // ssl_helper 符号链接（尽力而为，wget HTTPS 用；低版本 API 或受限环境跳过）
            try {
                linkDir.mkdirs()
                val real = File(nativeDir, "libssl_helper.so")
                val link = File(linkDir, "ssl_helper")
                if (link.exists()) link.delete()
                if (real.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
                    } catch (_: Throwable) {
                        // 忽略：wget https 将不可用，HTTP 不受影响
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /** mksh 单引号引用（防 wrapper 逃逸） */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** busybox 沙箱：白名单命令 + 目录/路径限制 + 兼容低版本 API 的超时 + 输出截断 */
    private fun runSafeShell(command: String): String {
        if (command.isBlank()) return "错误：缺少参数 command"
        val first = command.trim().split(Regex("\\s+"), limit = 2)[0]
        if (first !in ALLOWED_COMMANDS) {
            return "错误：命令 $first 不在白名单内。可用：${ALLOWED_COMMANDS.joinToString(" ")}"
        }
        val lower = command.lowercase(Locale.ROOT)
        DANGEROUS_TOKENS.forEach { token ->
            if (lower.contains(token)) return "错误：命令包含被禁止的内容（$token）"
        }
        if (command.contains("..")) return "错误：不允许使用 '..' 逃逸工作目录"
        if (command.trim().startsWith("/")) return "错误：不允许绝对路径，请在 App 沙箱目录内操作"
        if (command.contains(">")) return "错误：不允许重定向，请使用 tee 写文件"
        return try {
            val bb = busyboxBin
            // mksh exec -a 修正 argv[0]，busybox 进入通用模式；命令经单引号引用防逃逸
            val cmdLine = "exec -a busybox " + shQuote(bb.absolutePath) + " sh -c " + shQuote(command)
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmdLine)
                .directory(workDir)
                .redirectErrorStream(true)
            pb.environment()["PATH"] = linkDir.absolutePath + ":" + nativeDir.absolutePath +
                ":" + (pb.environment()["PATH"] ?: "/system/bin:/vendor/bin")
            val proc = pb.start()

            // 读线程排空输出（避免管道写满导致子进程阻塞）
            val out = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (out.length < 16000) out.append(line).append('\n')
                    }
                } catch (_: Exception) {}
            }
            reader.start()

            // 兼容低版本 API：isAlive()/waitFor(long,TimeUnit)/destroyForcibly 都是 API 26+，
            // Android 6(API 23) 上会抛 NoSuchMethodError。用 exitValue() 抛 IllegalThreadStateException 判断存活。
            val deadline = System.currentTimeMillis() + 15_000
            var running = true
            while (System.currentTimeMillis() < deadline && running) {
                running = try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
                if (running) { try { Thread.sleep(100) } catch (_: InterruptedException) { break } }
            }
            val timedOut = running
            if (timedOut) proc.destroy()
            try { reader.join(2000) } catch (_: InterruptedException) {}
            val exit = if (timedOut) -1 else proc.exitValue()
            val text = out.toString()
            val capped = if (text.length > 6000) text.take(6000) + "\n…(输出过长已截断)" else text
            if (timedOut) "错误：命令执行超过 15 秒已中止"
            else if (exit == 0) capped.ifBlank { "（无输出，成功）" }
            else "exit=$exit\n${capped.ifBlank { "（无输出）" }}"
        } catch (t: Throwable) {
            "错误：命令执行失败 —— ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun parseArg(json: String, key: String): String = try {
        val map = com.google.gson.Gson().fromJson(json, Map::class.java) ?: emptyMap<String, Any?>()
        (map[key] as? String) ?: ""
    } catch (_: Exception) { "" }

    private companion object {
        val SUPPORTED_ABIS_SET = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
}

/** 单文件数学表达式求值器（无依赖，支持 + - * / ^ % 括号 小数 常量与常用函数） */
internal object ExpressionEvaluator {
    fun eval(expr: String): Double {
        val p = Parser(expr.replace(" ", ""))
        return p.parse()
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double {
            val v = expr()
            if (i < s.length) throw IllegalArgumentException("意外字符: ${s[i]}")
            return v
        }
        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }
        private fun term(): Double {
            var v = factor()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= factor() }
                    '/' -> {
                        i++; val d = factor()
                        if (d == 0.0) throw IllegalArgumentException("除零")
                        v /= d
                    }
                    '%' -> {
                        i++; val d = factor()
                        if (d == 0.0) throw IllegalArgumentException("除零")
                        v %= d
                    }
                    else -> return v
                }
            }
        }
        private fun factor(): Double {
            val base = power()
            if (peek() == '^') { i++; return Math.pow(base, factor()) }
            return base
        }
        private fun power(): Double {
            if (peek() == '-') { i++; return -power() }
            if (peek() == '+') { i++; return power() }
            if (peek() == '(') {
                i++; val v = expr()
                if (peek() != ')') throw IllegalArgumentException("缺右括号")
                i++; return v
            }
            val name = ident()
            if (name.isNotEmpty()) {
                val lower = name.lowercase(Locale.ROOT)
                if (peek() == '(') {
                    i++
                    val v = expr()
                    if (peek() != ')') throw IllegalArgumentException("缺右括号")
                    i++
                    return when (lower) {
                        "sin" -> Math.sin(v); "cos" -> Math.cos(v); "tan" -> Math.tan(v)
                        "sqrt" -> Math.sqrt(v); "log" -> Math.log10(v); "ln" -> Math.log(v)
                        "abs" -> Math.abs(v); "round" -> Math.round(v).toDouble()
                        "floor" -> Math.floor(v); "ceil" -> Math.ceil(v)
                        else -> throw IllegalArgumentException("未知函数 $lower")
                    }
                }
                return when (lower) {
                    "pi" -> Math.PI; "e" -> Math.E
                    else -> throw IllegalArgumentException("未知常量 $name")
                }
            }
            return number()
        }
        private fun ident(): String {
            val sb = StringBuilder()
            while (i < s.length && (s[i].isLetter() || (sb.isNotEmpty() && s[i].isDigit()))) {
                sb.append(s[i]); i++
            }
            return sb.toString()
        }
        private fun number(): Double {
            val start = i
            var dot = false
            while (i < s.length && (s[i].isDigit() || (!dot && s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) {
                if (s[i] == '.') dot = true
                i++
            }
            if (start == i) throw IllegalArgumentException("需要数字")
            val chunk = s.substring(start, i)
            if (i + 1 < s.length && (s[i] == 'e' || s[i] == 'E') && (s[i + 1] == '+' || s[i + 1] == '-' || s[i + 1].isDigit())) {
                val eStart = i; i++
                if (s[i] == '+' || s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
                return chunk.toDouble() * Math.pow(10.0, s.substring(eStart + 1, i).toDouble())
            }
            return chunk.toDouble()
        }
        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    }
}
