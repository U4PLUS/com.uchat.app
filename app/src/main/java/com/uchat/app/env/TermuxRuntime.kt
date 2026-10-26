package com.uchat.app.env

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Termux 运行时：下载官方 Termux bootstrap（完整 Linux 用户空间：bash/apt/coreutils…），
 * 提取并解压到应用私有目录，供 run_shell 工具与终端执行命令（老设备用 apt-android-5 变体，新设备用 apt-android-7）。
 *
 * bootstrap 打包在 termux-app APK 的 lib/<abi>/libtermux-bootstrap.so 里（ELF 内嵌 zip 数据，
 * java.util.zip 可直接按 zip 打开），解压后为标准 $PREFIX（usr/bin、usr/lib）+ SYMLINKS.txt 符号链接清单。
 * 二进制 interpreter 指向设备自带 /system/bin/linker(64)，真机可直接运行。
 */
class TermuxRuntime(private val context: Context) {

    /** 安装状态 */
    sealed class State {
        object NotInstalled : State()
        data class Downloading(val percent: Int) : State()
        object Extracting : State()
        data class Ready(val abi: String, val sizeMb: Int) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state

    private val runtimeRoot: File get() = File(context.filesDir, "termux")
    val prefix: File get() = File(runtimeRoot, "files/usr")
    val home: File get() = File(runtimeRoot, "files/home")

    private val variant: String get() = if (Build.VERSION.SDK_INT < 26) "apt-android-5" else "apt-android-7"

    fun abiOf(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
        Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
        else -> "x86"
    }

    /** bootstrap 是否已就绪（bash 可执行） */
    fun isReady(): Boolean {
        val bash = File(prefix, "bin/bash")
        return bash.isFile && bash.canExecute()
    }

    /** 重建状态（App 启动时调用）：若已安装则标记就绪 */
    fun refresh() {
        if (isReady()) {
            val size = try { prefix.listFiles()?.sumOf { it.length() } ?: 0L } catch (_: Exception) { 0L }
            _state.value = State.Ready(abiOf(), (size / 1024 / 1024).toInt())
        }
    }

    /**
     * 安装 Termux 环境：
     * - Android 8+：从内置 assets（termux-bootstrap/<abi>.bin，apt-android-7 变体）直接解包，离线可用；
     * - Android 5-7：老设备兼容变体（apt-android-5，interpreter=/system/bin/linker）运行时下载后解包。
     */
    suspend fun install() {
        if (isReady()) return
        _state.value = State.Downloading(0)
        val apkCache = File(context.cacheDir, "termux_${variant}.apk")
        val soTmp = File(context.cacheDir, "libtermux-bootstrap_${abiOf()}.so")
        try {
            // 1) 获取本机 ABI 的 bootstrap：Android 8+ 从内置 assets 离线解包；老设备下载 apt-android-5 变体
            val embedded = Build.VERSION.SDK_INT >= 26 && extractFromAssets(soTmp)
            if (!embedded) {
                val apkUrl = "https://gh-proxy.org/https://github.com/termux/termux-app/releases/download/v0.119.0-beta.1/" +
                    "termux-app_v0.119.0-beta.1%2B${variant}-github-debug_universal.apk"
                val directUrl = "https://github.com/termux/termux-app/releases/download/v0.119.0-beta.1/" +
                    "termux-app_v0.119.0-beta.1%2B${variant}-github-debug_universal.apk"
                downloadFile(apkUrl, apkCache) { _state.value = State.Downloading(it) } ||
                    downloadFile(directUrl, apkCache) { _state.value = State.Downloading(it) } ||
                    throw Exception("下载 Termux 环境失败（两个源均不可达）")
                _state.value = State.Extracting
                ZipFile(apkCache).use { apk ->
                    val entry = apk.getEntry("lib/${abiOf()}/libtermux-bootstrap.so")
                        ?: throw Exception("APK 中缺少 ${abiOf()} 的 bootstrap")
                    apk.getInputStream(entry).use { input ->
                        soTmp.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }

            // 2) 解压 bootstrap（so 内嵌 zip）到 prefix
            val target = prefix
            if (target.exists()) target.deleteRecursively()
            target.parentFile?.mkdirs()
            ZipFile(soTmp).use { z ->
                z.entries().asSequence().forEach { e ->
                    val outFile = File(target, e.name)
                    if (e.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        z.getInputStream(e).use { input -> outFile.outputStream().use { out -> input.copyTo(out) } }
                    }
                }
            }

            // 4) 修复权限：bin / libexec 下的程序需可执行
            File(target, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
            File(target, "libexec").listFiles()?.forEach { it.setExecutable(true, false) }

            // 5) 按 SYMLINKS.txt 重建符号链接（格式：链接名←目标，相对 prefix 根）
            val symlinksFile = File(target, "SYMLINKS.txt")
            if (symlinksFile.exists()) {
                symlinksFile.readLines().forEach { line ->
                    if (line.isBlank() || !line.contains("←")) return@forEach
                    val parts = line.split("←", limit = 2)
                    val link = File(target, parts[0])
                    val tgt = parts[1]
                    try {
                        link.parentFile?.mkdirs()
                        if (link.exists()) link.delete()
                        java.lang.Runtime.getRuntime().exec(arrayOf("ln", "-s", tgt, link.absolutePath)).waitFor()
                    } catch (_: Exception) {
                        // 个别链接失败不影响整体
                    }
                }
            }

            // 6) 校验
            val bash = File(target, "bin/bash")
            if (!bash.isFile || !bash.canExecute()) throw Exception("Termux 环境校验失败（bash 不可执行）")

            // 清理缓存
            apkCache.delete(); soTmp.delete()

            val size = try { target.listFiles()?.sumOf { it.length() } ?: 0L } catch (_: Exception) { 0L }
            _state.value = State.Ready(abiOf(), (size / 1024 / 1024).toInt())
        } catch (e: Exception) {
            apkCache.delete(); soTmp.delete()
            _state.value = State.Failed(e.message ?: "安装失败")
        }
    }

    /** 卸载：删除整个运行时 */
    fun uninstall() {
        try { runtimeRoot.deleteRecursively() } catch (_: Exception) {}
        _state.value = State.NotInstalled
    }

    /**
     * 在 Termux 环境执行一条命令，返回 stdout+stderr 合并输出（截断 8000 字符）。
     * @param command 要执行的命令
     * @param cwd 工作目录（默认 termux home）
     * @param timeoutMs 超时（默认 30s）
     */
    suspend fun execBash(command: String, cwd: File? = null, timeoutMs: Long = 30_000L): String =
        withContext(Dispatchers.IO) {
            if (!isReady()) return@withContext "错误：Termux 环境未就绪"
            val bash = File(prefix, "bin/bash")
            home.mkdirs()
            File(runtimeRoot, "files/tmp").mkdirs()
            val pb = ProcessBuilder(bash.absolutePath, "-c", command)
            pb.environment().putAll(
                mapOf(
                    "PREFIX" to prefix.absolutePath,
                    "PATH" to "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets",
                    "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
                    "HOME" to home.absolutePath,
                    "TMPDIR" to File(runtimeRoot, "files/tmp").absolutePath,
                    "TERMUX_VERSION" to "0.119.0",
                    "TERMUX_IS_DEBUGGABLE_BUILD" to "0"
                )
            )
            pb.directory(cwd ?: home)
            pb.redirectErrorStream(true)
            val proc = try { pb.start() } catch (e: Exception) { return@withContext "错误：无法启动 Termux bash —— ${e.message}" }
            val out = StringBuilder()
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                val readerThread = Thread {
                    try {
                        val buf = CharArray(4096)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            out.append(buf, 0, n)
                            if (out.length > 16_000) { proc.destroy(); break }
                        }
                    } catch (_: Exception) {}
                }
                readerThread.start()
                val started = System.currentTimeMillis()
                while (readerThread.isAlive && System.currentTimeMillis() - started < timeoutMs) {
                    Thread.sleep(100)
                }
                if (readerThread.isAlive) {
                    proc.destroy()
                    readerThread.join(2000)
                    return@withContext ("错误：Termux 命令执行超时（${timeoutMs / 1000} 秒）\n" + out.take(2000))
                }
                proc.waitFor()
            } catch (e: Exception) {
                proc.destroy()
                return@withContext "错误：Termux 执行失败 —— ${e.message}"
            } finally {
                try { proc.destroy() } catch (_: Exception) {}
            }
            val result = out.toString().trim()
            (if (result.isBlank()) "（命令执行完成，无输出）" else result).take(8000)
        }

    /** Termux 环境变量数组（用于终端会话 pty 子进程环境） */
    fun envArray(): Array<String> = arrayOf(
        "PREFIX=${prefix.absolutePath}",
        "PATH=${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets",
        "LD_LIBRARY_PATH=${prefix.absolutePath}/lib",
        "HOME=${home.absolutePath}",
        "TMPDIR=${File(runtimeRoot, "files/tmp").absolutePath}",
        "TERM=xterm-256color",
        "TERMUX_VERSION=0.119.0",
        "TERMUX_IS_DEBUGGABLE_BUILD=0"
    )

    /** 从内置 assets 复制本机 ABI 的 bootstrap 到缓存文件（返回是否成功） */
    private fun extractFromAssets(target: File): Boolean {
        return try {
            context.assets.open("termux-bootstrap/${abiOf()}.bin").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 带进度下载（返回是否成功） */
    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "uchat-termux-runtime/1.0")
            conn.connect()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return false }
            val total = conn.contentLengthLong
            val input = conn.inputStream
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPercent = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val p = (read * 100 / total).toInt()
                        if (p != lastPercent) { lastPercent = p; onProgress(p.coerceIn(0, 99)) }
                    }
                }
            }
            input.close(); conn.disconnect()
            target.length() > 0
        } catch (_: Exception) {
            false
        }
    }
}
