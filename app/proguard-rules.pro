# Gson 反射序列化的数据模型（字段名不可混淆）
-keep class com.uchat.app.data.** { *; }

# 工具定义（按名称匹配执行）
-keep class com.uchat.app.tools.** { *; }

# ViewModel / UI 保持组件名（日志诊断友好）
-keepnames class com.uchat.app.viewmodel.** { *; }
