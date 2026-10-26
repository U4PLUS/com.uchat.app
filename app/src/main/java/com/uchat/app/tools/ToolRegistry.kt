package com.uchat.app.tools

import android.content.Context
import android.os.Build
import com.uchat.app.data.ToolDef
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

    /** 记忆系统回调（由上层注入，与存储解耦） */
    var memoryOps: com.uchat.app.data.MemoryOps? = null

    /** 联网能力回调（web_search / fetch_page 由上层注入，复用内置证书信任） */
    var netOps: com.uchat.app.data.NetOps? = null

    /** SSH 能力回调（ssh_execute 由上层注入，复用设置页配置的连接） */
    var sshOps: com.uchat.app.data.SSHOps? = null

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
        ),
        ToolDef(
            id = "web_search",
            name = "web_search",
            description = "联网搜索互联网（免费额度，无需 API Key）。查询最新资讯、实时事件、用户提到的陌生名词、验证事实时调用。返回若干条排名结果（标题/链接/摘要），需要详细内容时再用 fetch_page 打开链接。",
            parameters = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，尽量具体，如：2026年世界杯赛程"}},"required":["query"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "fetch_page",
            name = "fetch_page",
            description = "抓取指定网页的正文内容（Markdown，自动截断到约 8000 字符）。用于阅读 web_search 结果中的链接、获取完整文章内容。仅限公开页面，不支持登录/私有页面。",
            parameters = """{"type":"object","properties":{"url":{"type":"string","description":"要抓取的完整网址，如 https://example.com/article"}},"required":["url"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "remember_memory",
            name = "remember_memory",
            description = "把关于用户的重要信息（偏好、身份、习惯、长期事实）保存进长期记忆，跨会话有效。仅在出现值得长期记住的新信息时调用（用户明确说「记住…」、表达偏好、透露个人事实）；琐碎/一次性内容不要保存。",
            parameters = """{"type":"object","properties":{"content":{"type":"string","description":"要保存的记忆内容，如：用户喜欢简洁回答"},"importance":{"type":"string","enum":["low","normal","high"],"description":"重要程度，默认 normal"}},"required":["content"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "recall_memories",
            name = "recall_memories",
            description = "检索长期记忆中关于用户的已知信息（偏好、身份、习惯、历史事实）。注意：系统已在上下文中自动注入最近记忆摘要，通常无需调用。仅在用户明确询问过去发生的事、或需要更早/更具体的跨会话信息时才调用；日常对话、简单问题直接回答，禁止先查记忆。确需查询时一次查清（用较宽泛的关键词），同一轮不要多次重复查询。",
            parameters = """{"type":"object","properties":{"query":{"type":"string","description":"检索关键词，如：偏好 或 工作"}},"required":["query"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "send_file",
            name = "send_file",
            description = "向用户发送一个文件，聊天中会展示为文件卡片供用户点击下载。用于把找到/生成的文件交付给用户：给出文件名（含扩展名）与可下载的 http(s) 地址（如 run_shell 中 wget 下载的文件，或你已知的直链）。文件类型由扩展名决定展示图标。",
            parameters = """{"type":"object","properties":{"name":{"type":"string","description":"文件名，需含扩展名，如 report.pdf 或 app.apk"},"url":{"type":"string","description":"文件的 http(s) 下载直链"}},"required":["name","url"]}""",
            dangerLevel = 1
        ),
        ToolDef(
            id = "ssh_execute",
            name = "ssh_execute",
            description = "通过 SSH 连接用户在设置页配置的远程服务器，执行一条命令并返回输出（stdout/stderr）。用于查看远程服务器状态、管理文件、执行远程脚本等。连接参数（主机/端口/用户名/密码或私钥）在设置 → SSH 中配置；未配置或未启用时返回错误。命令不要包含交互式输入需求。",
            parameters = """{"type":"object","properties":{"command":{"type":"string","description":"在远程服务器上执行的命令，如：uname -a 或 df -h 或 tail -n 20 /var/log/syslog"}},"required":["command"]}""",
            dangerLevel = 3
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

    private suspend fun doExecute(name: String, argumentsJson: String): String = when (name) {
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
        "web_search" -> {
            val query = parseArg(argumentsJson, "query")
            if (query.isBlank()) "错误：缺少参数 query"
            else netOps?.webSearch(query, 5) ?: "错误：联网搜索未就绪"
        }
        "fetch_page" -> {
            val url = parseArg(argumentsJson, "url")
            if (url.isBlank()) "错误：缺少参数 url"
            else netOps?.fetchPage(url) ?: "错误：联网抓取未就绪"
        }
        "remember_memory" -> {
            val content = parseArg(argumentsJson, "content")
            val importance = parseArg(argumentsJson, "importance").ifBlank { "normal" }
            if (content.isBlank()) "错误：缺少参数 content"
            else memoryOps?.saveMemory(content, importance) ?: "错误：记忆系统未就绪"
        }
        "recall_memories" -> {
            val query = parseArg(argumentsJson, "query")
            if (query.isBlank()) "错误：缺少参数 query"
            else memoryOps?.recallMemories(query) ?: "错误：记忆系统未就绪"
        }
        "send_file" -> {
            val name = parseArg(argumentsJson, "name").trim()
            val url = parseArg(argumentsJson, "url").trim()
            when {
                name.isBlank() -> "错误：缺少参数 name（文件名，含扩展名）"
                !url.startsWith("http://") && !url.startsWith("https://") -> "错误：url 必须是 http(s) 下载直链"
                else -> "【文件卡片】$name|$url"
            }
        }
        "ssh_execute" -> {
            val command = parseArg(argumentsJson, "command").trim()
            if (command.isBlank()) "错误：缺少参数 command"
            else sshOps?.sshExecute(command) ?: "错误：SSH 工具未就绪"
        }
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
