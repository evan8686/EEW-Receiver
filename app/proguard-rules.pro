# =============================================================================
# EEW Receiver 2.0 — 终极 ProGuard / R8 规则 (Gemini & Claude 融合版)
# 目标：绝对稳定、包体积最优、容灾逻辑 100% 生效
# =============================================================================

# -----------------------------------------------------------------------------
# 1. 调试与崩溃追踪（保留行号，让 Logcat / Bugly 堆栈可读）
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# 2. Gson 序列化与数据模型保护（核心业务数据防御）
# -----------------------------------------------------------------------------
# 保护所有带 @SerializedName 注解的字段，防止 JSON 字段映射失效
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 彻底保护业务数据类（网络接收 / 本地存储 / Intent 传递 / UI 状态）
-keep class com.evan8686.eewreceiver.EewData { *; }
-keep class com.evan8686.eewreceiver.ApiSource { *; }
-keep class com.evan8686.eewreceiver.AlertUiData { *; }

# -----------------------------------------------------------------------------
# 3. 反射与泛型支持（防止 TypeToken 泛型擦除导致 List 解析崩溃）
# -----------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 防止未来代码变动或 Gson 内部反射被 R8 破坏
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# -----------------------------------------------------------------------------
# 4. OSMDroid 地图引擎保护（2.0 新增）
# -----------------------------------------------------------------------------
# 保护 OSMDroid 所有核心类、配置类、瓦片引擎、覆盖物和接口
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }

# 保护高德地图自定义的匿名内部类 (OnlineTileSourceBase)
-keep class * extends org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase { *; }

# 压制 OSMDroid 依赖链上的无害编译警告
-dontwarn org.osmdroid.**
-dontwarn android.graphics.Picture

# -----------------------------------------------------------------------------
# 5. Jetpack Compose 保护（剔除冗余，保留核心）
# -----------------------------------------------------------------------------
# 保护所有带 @Composable 注解的函数，防止 R8 将其内联消除或重命名
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable *;
}
# 注：未显式保留 material3.**，因为 Google 官方 AAR 已自带最优混淆规则，显式保留会导致 APK 严重膨胀。

# -----------------------------------------------------------------------------
# 6. Android TTS 语音播报保护（保护 Ducking 压低音量功能）
# -----------------------------------------------------------------------------
# 防止系统回调因为 R8 重命名匿名类而断联
-keep class * extends android.speech.tts.UtteranceProgressListener {
    public void onStart(java.lang.String);
    public void onDone(java.lang.String);
    public void onError(java.lang.String);
}

# -----------------------------------------------------------------------------
# 7. 精准抑制无害编译警告（OkHttp / Gson 底层依赖噪点）
# -----------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -----------------------------------------------------------------------------
# 8. 精准容灾逻辑保护（替代 -dontoptimize）
# -----------------------------------------------------------------------------
# 仅防止核心管理类中含有 try-catch 的方法被 R8 错误内联或删除，同时允许其他代码瘦身
-keep class com.evan8686.eewreceiver.DataManager { *; }
-keep class com.evan8686.eewreceiver.EarthquakeCalculator { *; }
-keep class com.evan8686.eewreceiver.AlertManager { *; }