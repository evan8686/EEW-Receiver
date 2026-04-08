# =============================================================================
# EEW Receiver 1.2.8 — 终极 ProGuard / R8 规则 (Gemini & Claude 融合版)
# 目标：绝对稳定、VirusTotal 全绿、JSON 解析 100% 成功
# =============================================================================

# -----------------------------------------------------------------------------
# 1. 调试与崩溃追踪 (保留行号，让闪退堆栈可读)
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# 2. Gson 序列化与数据模型保护 (核心防御)
# -----------------------------------------------------------------------------
# 保护 @SerializedName 注解及其字段，防止 JSON 映射失效
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 彻底保护所有业务数据模型类 (网络接收、本地存储、UI状态)
-keep class com.evan8686.eewreceiver.EewData { *; }
-keep class com.evan8686.eewreceiver.ApiSource { *; }
-keep class com.evan8686.eewreceiver.AlertUiData { *; }

# -----------------------------------------------------------------------------
# 3. 反射与泛型支持 (防止 TypeToken 解析 List<ApiSource> 等复杂类型时丢失)
# -----------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 🚨 救命规则：必须保护 TypeToken 的子类不被混淆擦除！
# 否则 Gson 无法解析 List 集合，直接返回 LinkedTreeMap 导致无限闪退！
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# -----------------------------------------------------------------------------
# 4. 精准抑制无害警告 (解决 OkHttp/Gson 在 Android 平台上的编译噪点)
# -----------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -----------------------------------------------------------------------------
# 5. 增强安全性：防止 R8 过度优化导致 try-catch 逻辑被误删
# -----------------------------------------------------------------------------
-dontoptimize