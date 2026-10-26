package com.uchat.app.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.uchat.app.AIChatApplication
import com.uchat.app.env.TermuxRuntime
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.Button
import kotlinx.coroutines.launch

/**
 * Termux 终端面板：TerminalView（pty bash 会话） + 顶部栏。
 * 在布局完成后自动 attach 终端会话（从 TermuxRuntime 的 bash 启动）。
 */
@Composable
fun TerminalPanel(onClose: () -> Unit, termuxRuntime: TermuxRuntime) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(ComposeColor(0xFF101010))
    ) {
        // 顶部栏：标题 + 键盘 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(ComposeColor(0xFF1B1B1B))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ComposeColor(0xFF4CAF50))
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Termux 终端",
                color = ComposeColor.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                try {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                } catch (_: Exception) {}
            }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Keyboard, contentDescription = "键盘", tint = ComposeColor.White)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭终端", tint = ComposeColor.White)
            }
        }
        // 终端视图（环境未就绪时提示安装）
        if (!termuxRuntime.isReady()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Termux 环境未就绪", color = ComposeColor.White, fontSize = 16.sp)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "开启后即可使用完整 Linux 终端（bash/apt 等），\n安装解压约需 1 分钟",
                        color = ComposeColor(0xFF999999), fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { scope.launch { termuxRuntime.install() } }) { Text("安装 Termux 环境") }
                }
            }
        } else {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    val client = TermuxTerminalClient(ctx, this) {}
                    setTerminalViewClient(client)
                    // 必须先用 setTextSize 初始化渲染器（字体度量），否则 attachSession→updateSize 会因 mRenderer=null 崩溃
                    setTextSize(12)
                    // 首次布局完成后 attach 会话（attachSession 内部会启动 pty bash 子进程）
                    var attached = false
                    addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
                        if (!attached && (l != ol || t != ot || r != or || b != ob)) {
                            attached = true
                            val bash = termuxRuntime.prefix.absolutePath + "/bin/bash"
                            val env = termuxRuntime.envArray()
                            val session = TerminalSession(
                                bash, termuxRuntime.home.absolutePath,
                                arrayOf(bash, "-l"), env, null, client
                            )
                            attachSession(session)
                            requestFocus()
                        }
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        }
    }
}

/** 获取 AIChatApplication 的单例 TermuxRuntime */
@Composable
fun rememberTermuxRuntime(): TermuxRuntime? {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as? AIChatApplication)?.termuxRuntime
    }
}
