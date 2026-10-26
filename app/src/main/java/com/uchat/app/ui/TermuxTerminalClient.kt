package com.uchat.app.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Termux 终端回调实现：TerminalView 与 TerminalSession 的事件分发。
 * 大部分回调采用安全默认值，日志输出到 Logcat。
 */
class TermuxTerminalClient(
    private val context: Context,
    private val view: TerminalView,
    private val onSessionFinished: () -> Unit = {}
) : TerminalViewClient, TerminalSessionClient {

    // ── TerminalSessionClient ──
    override fun onTextChanged(session: TerminalSession) {}
    override fun onTitleChanged(session: TerminalSession) {}
    override fun onSessionFinished(session: TerminalSession) { onSessionFinished() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        } catch (_: Exception) {}
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ── TerminalViewClient ──
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e(TAG, "$tag: $message") }
    override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: $message") }
    override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: $message") }
    override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: $message") }
    override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: $message") }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(TAG, "$tag: $message", e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, tag, e) }

    private companion object { const val TAG = "UchatTerminal" }
}
