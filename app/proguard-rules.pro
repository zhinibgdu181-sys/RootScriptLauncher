# 1. 保留 MainActivity 和它的所有方法
-keep class com.example.rootlauncher.MainActivity { *; }

# 2. 保留所有 Android 组件类名
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# 3. 保留 R 文件，防止资源找不到
-keep class com.example.rootlauncher.R$* { *; }

# 4. 保留 ProcessBuilder、Runtime 等底层调用
-keep class java.lang.ProcessBuilder { *; }
-keep class java.lang.Runtime { *; }

# 5. 保留 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 6. 保留泛型和注解
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 7. 防止混淆报错
-dontwarn com.example.rootlauncher.**
