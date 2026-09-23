# 私有相册 App —— ProGuard 规则（当前 release 未开启混淆，保留备用）
-keep class com.privatealbum.app.model.** { *; }
-keep class com.privatealbum.app.net.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** { **[] $VALUES; public *; }

# 关闭日志
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
