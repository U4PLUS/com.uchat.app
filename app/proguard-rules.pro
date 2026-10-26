# Gson 反射序列化的数据模型（字段名不可混淆）
-keep class com.uchat.app.data.** { *; }

# 工具定义（按名称匹配执行）
-keep class com.uchat.app.tools.** { *; }

# ViewModel / UI 保持组件名（日志诊断友好）
-keepnames class com.uchat.app.viewmodel.** { *; }

# BouncyCastle：以 JCE provider / ASN.1 动态加载方式使用，需完整保留（老设备 SSH 的 EC 兜底实现）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JSch：大量实现类（jce/jcraft 包 + 主包的 DH/ECDH/DH2 等密钥交换类）通过字符串类名反射加载
# （setConfig("random"/"cipher"/"kex"/"keypairgen"/"signature" 等），R8 无法感知，必须整包保留且不混淆。
# 整包保留会连带 Windows 专用的 PageantConnector（引用 Android 没有的 com.sun.jna），用 -dontwarn 抑制缺失类
# （Android 上该分支永不执行）。
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.sun.jna.**
-dontwarn com.sun.jna.platform.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.security.auth.**
-dontwarn org.apache.commons.logging.**

# ── Termux 终端模块 ──
# TerminalView/TerminalSession 与 Android View 系统交互，保持类名与方法不被混淆
-keep class com.termux.view.** { *; }
-keep class com.termux.terminal.** { *; }
